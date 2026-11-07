package com.yokhan.gates.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.filterIsInstance
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

internal fun isTrustedApkIdentity(
    expectedPackage: String,
    actualPackage: String,
    expectedSignerSha256: String,
    actualSignerSha256: Collection<String>,
): Boolean {
    val expectedSigner = expectedSignerSha256.normalizeFingerprint()
    return actualPackage == expectedPackage &&
        expectedSigner.isNotBlank() &&
        actualSignerSha256.any { it.normalizeFingerprint() == expectedSigner }
}

private fun String.normalizeFingerprint(): String =
    replace(":", "").trim().lowercase()

internal data class YokhanReleaseAsset(
    val name: String,
    val url: String,
)

internal data class YokhanReleaseInfo(
    val latestVersion: String,
    val channel: String,
    val releaseUrl: String,
    val mandatory: Boolean,
    val minSupportedVersion: String,
    val updateAvailable: Boolean?,
    val apkAsset: YokhanReleaseAsset?,
)

internal fun buildReleaseApiUrl(
    baseUrl: String,
    channel: String,
    version: String,
    packageId: String,
): String {
    val base = baseUrl.trim().trimEnd('/')
    require(base.isNotBlank()) { "YOKHAN_APP_API_BASE_URL is not configured." }
    val effectiveChannel = channel.trim().ifBlank { "stable" }
    val encodedVersion = URLEncoder.encode(version, "UTF-8")
    val encodedPackage = URLEncoder.encode(packageId, "UTF-8")
    val encodedChannel = URLEncoder.encode(effectiveChannel, "UTF-8")
    return "$base/api/app/release?platform=android&version=$encodedVersion" +
        "&package_id=$encodedPackage&channel=$encodedChannel&protocol=2"
}

internal fun resolveReleaseDownloadUrl(
    apiReleaseUrl: String?,
    canonicalDownloadUrl: String,
): String = apiReleaseUrl?.trim()?.takeIf { it.isNotBlank() } ?: canonicalDownloadUrl.trim()

object YokhanAppUpdater {

    fun resumePendingInstall(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) return
        val preferences = SagerNet.bootstrapPreferences()
        val path = preferences.getString(PENDING_INSTALL_PATH_KEY, null) ?: return
        preferences.edit().remove(PENDING_INSTALL_PATH_KEY).apply()
        val file = File(path).takeIf(File::isFile) ?: return
        runCatching {
            requireTrustedDownloadedApk(context, file)
            installDownloadedApk(context, file)
        }.onFailure { error ->
            Logs.w(error)
            Toast.makeText(context, error.readableMessage, Toast.LENGTH_LONG).show()
        }
    }

    fun checkManually(context: Context, checkPreview: Boolean) {
        checkUpdate(
            context = context,
            checkPreview = checkPreview,
            notifyNoUpdate = true,
            notifyErrors = true,
        )
    }

    fun checkOnLaunch(context: Context) {
        val preferences = SagerNet.bootstrapPreferences()
        val now = System.currentTimeMillis()
        val lastCheck = preferences.getLong(LAST_AUTO_CHECK_KEY, 0L)
        if (now - lastCheck < AUTO_CHECK_INTERVAL_MS) return

        preferences.edit().putLong(LAST_AUTO_CHECK_KEY, now).apply()
        checkUpdate(
            context = context,
            checkPreview = isPreview,
            notifyNoUpdate = false,
            notifyErrors = false,
        )
    }

    private fun checkUpdate(
        context: Context,
        checkPreview: Boolean,
        notifyNoUpdate: Boolean,
        notifyErrors: Boolean,
    ) {
        runOnIoDispatcher {
            val client = Libcore.newHttpClient().apply {
                modernTLS()
                trySocks5(DataStore.mixedPort)
            }
            try {
                val response = client.newRequest().apply {
                    setURL(releaseApiUrl(checkPreview))
                }.execute()
                val release = parseReleaseInfo(
                    JSONObject(Util.getStringBox(response.contentString))
                )
                val releaseName = release.latestVersion
                val releaseUrl = resolveReleaseDownloadUrl(
                    release.releaseUrl,
                    BuildConfig.YOKHAN_APP_DOWNLOAD_URL,
                )
                val haveUpdate = hasUpdate(release, checkPreview)

                runOnMainDispatcher {
                    if (haveUpdate) {
                        showUpdateDialog(
                            context = context,
                            releaseName = releaseName,
                            releaseUrl = releaseUrl,
                            apkAsset = release.apkAsset,
                            mandatory = release.mandatory,
                        )
                    } else if (notifyNoUpdate) {
                        Toast.makeText(context, R.string.check_update_no, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
                if (notifyErrors) {
                    runOnMainDispatcher {
                        Toast.makeText(context, e.readableMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                client.close()
            }
        }
    }

    private fun parseReleaseInfo(json: JSONObject): YokhanReleaseInfo {
        val latestVersion = json.optString("latest_version")
            .ifBlank { json.optString("name") }
            .ifBlank { json.optString("release_name") }
        val channel = json.optString("channel")
        val releaseUrl = json.optString("release_url")
            .ifBlank { json.optString("html_url") }
        val mandatory = json.optBoolean("mandatory", false)
        val minSupportedVersion = json.optString("min_supported_version")
        val updateAvailable = if (json.has("update_available") && !json.isNull("update_available")) {
            json.optBoolean("update_available", false)
        } else {
            null
        }
        return YokhanReleaseInfo(
            latestVersion = latestVersion,
            channel = channel,
            releaseUrl = releaseUrl,
            mandatory = mandatory,
            minSupportedVersion = minSupportedVersion,
            updateAvailable = updateAvailable,
            apkAsset = json.findPreferredApkAsset(),
        )
    }

    private fun hasUpdate(release: YokhanReleaseInfo, checkPreview: Boolean): Boolean {
        release.updateAvailable?.let { return it }
        val releaseName = release.latestVersion
        if (releaseName.isBlank()) return false
        return if (isPreview) {
            if (checkPreview) {
                releaseName != BuildConfig.PRE_VERSION_NAME
            } else {
                releaseName != BuildConfig.VERSION_NAME
            }
        } else {
            !releaseName.contains(BuildConfig.VERSION_NAME)
        }
    }

    private fun showUpdateDialog(
        context: Context,
        releaseName: String,
        releaseUrl: String,
        apkAsset: YokhanReleaseAsset?,
        mandatory: Boolean,
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_dialog_title)
            .setMessage(
                context.getString(
                    R.string.update_dialog_message,
                    SagerNet.appVersionNameForDisplay,
                    releaseName,
                )
            )
            .setCancelable(!mandatory)
            .setPositiveButton(R.string.download) { _, _ ->
                if (apkAsset != null) {
                    downloadAndInstallUpdate(context, apkAsset)
                } else {
                    openReleasePage(context, releaseUrl)
                }
            }
            .apply {
                if (!mandatory) setNegativeButton(R.string.no, null)
            }
            .show()
    }

    private fun JSONObject.findPreferredApkAsset(): YokhanReleaseAsset? {
        optJSONObject("asset")?.let { asset ->
            val name = asset.optString("name")
            val url = asset.optString("download_url")
                .ifBlank { asset.optString("browser_download_url") }
            if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                return YokhanReleaseAsset(name = name, url = url)
            }
        }

        val apkAssets = optJSONArray("assets")
            ?.filterIsInstance<JSONObject>()
            ?.mapNotNull { asset ->
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    YokhanReleaseAsset(name = name, url = url)
                } else {
                    null
                }
            }
            .orEmpty()

        if (apkAssets.isEmpty()) return null

        apkAssets.firstOrNull { it.name.contains("universal", ignoreCase = true) }?.let { return it }
        Build.SUPPORTED_ABIS.forEach { abi ->
            apkAssets.firstOrNull { it.name.contains(abi, ignoreCase = true) }?.let { return it }
        }
        return apkAssets.first()
    }

    private fun downloadAndInstallUpdate(context: Context, asset: YokhanReleaseAsset) {
        val appContext = context.applicationContext
        runOnIoDispatcher {
            val client = Libcore.newHttpClient().apply {
                modernTLS()
                keepAlive()
                trySocks5(DataStore.mixedPort)
            }
            try {
                val response = client.newRequest().apply {
                    setURL(asset.url)
                }.execute()

                val updateDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
                updateDir.listFiles()
                    ?.filter { it.extension.equals("apk", ignoreCase = true) }
                    ?.forEach { it.delete() }

                val outputFile = File(updateDir, asset.safeFileName())
                val tempFile = File(updateDir, "${outputFile.name}.tmp")
                if (tempFile.exists()) tempFile.delete()

                response.writeTo(tempFile.canonicalPath)
                if (outputFile.exists()) outputFile.delete()
                if (!tempFile.renameTo(outputFile)) {
                    tempFile.copyTo(outputFile, overwrite = true)
                    tempFile.delete()
                }

                requireTrustedDownloadedApk(appContext, outputFile)

                runOnMainDispatcher {
                    installDownloadedApk(context, outputFile)
                }
            } catch (e: Exception) {
                Logs.w(e)
                runOnMainDispatcher {
                    Toast.makeText(context, e.readableMessage, Toast.LENGTH_LONG).show()
                }
            } finally {
                client.close()
            }
        }
    }

    private fun installDownloadedApk(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            SagerNet.bootstrapPreferences().edit()
                .putString(PENDING_INSTALL_PATH_KEY, file.absolutePath)
                .apply()
            Toast.makeText(context, R.string.update_unknown_sources_required, Toast.LENGTH_LONG).show()
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri(),
                )
            )
            return
        }

        SagerNet.bootstrapPreferences().edit().remove(PENDING_INSTALL_PATH_KEY).apply()

        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.cache", file)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Logs.w(e)
            Toast.makeText(context, e.readableMessage, Toast.LENGTH_LONG).show()
        }
    }

    private fun requireTrustedDownloadedApk(context: Context, file: File) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("Downloaded update is not a valid Android package.")
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty().toList()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty().toList()
        }
        val signerFingerprints = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
        val isTrusted = isTrustedApkIdentity(
            expectedPackage = context.packageName,
            actualPackage = info.packageName,
            expectedSignerSha256 = BuildConfig.YOKHAN_UPDATE_SIGNER_SHA256,
            actualSignerSha256 = signerFingerprints,
        )
        if (!isTrusted) {
            file.delete()
            error("Downloaded update has an unexpected package or signing certificate.")
        }
    }

    private fun openReleasePage(context: Context, releaseUrl: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, releaseUrl.toUri()))
    }

    private fun YokhanReleaseAsset.safeFileName(): String {
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun releaseApiUrl(checkPreview: Boolean): String {
        val version = if (checkPreview) BuildConfig.PRE_VERSION_NAME else BuildConfig.VERSION_NAME
        return buildReleaseApiUrl(
            baseUrl = BuildConfig.YOKHAN_APP_API_BASE_URL,
            channel = BuildConfig.YOKHAN_UPDATE_CHANNEL,
            version = version,
            packageId = BuildConfig.APPLICATION_ID,
        )
    }

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val LAST_AUTO_CHECK_KEY = "yokhan_last_auto_update_check"
    private const val PENDING_INSTALL_PATH_KEY = "yokhan_pending_update_apk"
    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
}
