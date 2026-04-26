package com.yokhan.vpn.app.ui

import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateFormat
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.yokhan.vpn.shared.ui.design.YokhanPalette
import com.yokhan.vpn.shared.ui.design.YokhanTypography
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.filterIsInstance
import io.nekohasekai.sagernet.ktx.getStr
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.tryToShow
import com.yokhan.vpn.app.ui.compose.YokhanButtonGrid
import com.yokhan.vpn.app.ui.compose.YokhanCaption
import com.yokhan.vpn.app.ui.compose.YokhanDivider
import com.yokhan.vpn.app.ui.compose.YokhanMonoValue
import com.yokhan.vpn.app.ui.compose.YokhanRouteAction
import com.yokhan.vpn.app.ui.compose.YokhanRouteCard
import com.yokhan.vpn.app.ui.compose.YokhanRouteLazyFrame
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.util.Date

class AssetsActivity : ThemedActivity() {

    private val rows = mutableStateOf<List<AssetRow>>(emptyList())
    private val updating = mutableStateOf<Set<String>>(emptySet())
    private val assetNames = arrayOf("geoip.db", "geosite.db")

    private val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file == null) return@registerForActivityResult

        val fileName = contentResolver.query(file, null, null, null, null)?.use { cursor ->
            cursor.moveToFirst()
            cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
        }?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
            .substringAfterLast('/')
            .substringAfter(':')

        if (!fileName.endsWith(".db")) {
            alert(getString(R.string.route_not_asset, fileName)).show()
            return@registerForActivityResult
        }

        val rootDir = getExternalFilesDir(null) ?: this.filesDir
        val resolver = contentResolver
        runOnDefaultDispatcher {
            val outFile = File(rootDir, fileName).apply {
                parentFile?.mkdirs()
            }
            resolver.openInputStream(file)?.use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            File(outFile.parentFile, outFile.nameWithoutExtension + ".version.txt").apply {
                if (isFile) delete()
                createNewFile()
                FileWriter(this).use { it.write("Custom") }
            }
            reloadAssets()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }
        setContent {
            Content()
        }
        reloadAssets()
    }

    override fun onResume() {
        super.onResume()
        reloadAssets()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG)
    }

    @Composable
    private fun Content() {
        YokhanRouteLazyFrame(
            title = stringResource(R.string.route_assets),
            systemLabel = stringResource(R.string.dashboard_system_label),
            spaceActive = DataStore.serviceState.isSpaceActive(),
            onMenuClick = ::finish,
        ) { colors, typography ->
            item(key = "asset-actions") {
                YokhanRouteCard(
                    title = stringResource(R.string.route_hub_actions_title),
                    colors = colors,
                    typography = typography,
                ) {
                    YokhanButtonGrid(
                        actions = listOf(
                            YokhanRouteAction(
                                title = stringResource(R.string.action_import_file),
                                primary = true,
                                onClick = { startFilesForResult(importFile, "*/*") },
                            ),
                            YokhanRouteAction(
                                title = stringResource(R.string.asset_refresh),
                                onClick = ::reloadAssets,
                            ),
                        ),
                        colors = colors,
                        typography = typography,
                    )
                }
            }

            items(
                items = rows.value,
                key = { it.file.absolutePath },
            ) { row ->
                AssetCard(
                    row = row,
                    updating = row.file.name in updating.value,
                    colors = colors,
                    typography = typography,
                )
            }
        }
    }

    @Composable
    private fun AssetCard(
        row: AssetRow,
        updating: Boolean,
        colors: YokhanPalette,
        typography: YokhanTypography,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, colors.border, RoundedCornerShape(18.dp)),
        ) {
            if (updating) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(colors.accent),
                )
            }
            Column(Modifier.padding(16.dp)) {
                YokhanMonoValue(
                    text = row.file.name,
                    colors = colors,
                    typography = typography,
                    sizeSp = 16,
                )
                YokhanDivider(colors, Modifier.padding(vertical = 14.dp))
                YokhanCaption(
                    text = stringResource(R.string.route_asset_status, row.localVersion),
                    colors = colors,
                    typography = typography,
                )
                YokhanButtonGrid(
                    actions = buildList {
                        if (row.official) {
                            add(
                                YokhanRouteAction(
                                    title = stringResource(R.string.group_update),
                                    primary = true,
                                    enabled = !updating,
                                    onClick = { updateAsset(row) },
                                ),
                            )
                        } else {
                            add(
                                YokhanRouteAction(
                                    title = stringResource(R.string.delete),
                                    enabled = !updating,
                                    onClick = { confirmDeleteAsset(row) },
                                ),
                            )
                        }
                    },
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
    }

    private fun reloadAssets() {
        runOnDefaultDispatcher {
            val loaded = loadAssetRows()
            onMainDispatcher {
                rows.value = loaded
            }
        }
    }

    private fun loadAssetRows(): List<AssetRow> {
        val rootDir = getExternalFilesDir(null) ?: this.filesDir
        val customFiles = rootDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".db") && it.name !in assetNames }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
        return assetNames.map { File(rootDir, it) }
            .plus(customFiles)
            .map { file ->
                AssetRow(
                    file = file,
                    versionFile = File(file.parentFile, "${file.nameWithoutExtension}.version.txt"),
                    localVersion = readLocalVersion(file),
                    official = file.name in assetNames,
                )
            }
    }

    private fun readLocalVersion(file: File): String {
        val versionFile = File(file.parentFile, "${file.nameWithoutExtension}.version.txt")
        return if (file.isFile) {
            if (versionFile.isFile) {
                runCatching { versionFile.readText().trim() }.getOrElse { "<unknown>" }
            } else {
                "Unknown-" + DateFormat.getDateFormat(app).format(Date(file.lastModified()))
            }
        } else {
            "<unknown>"
        }
    }

    private fun updateAsset(row: AssetRow) {
        if (row.file.name in updating.value) return
        updating.value = updating.value + row.file.name
        runOnDefaultDispatcher {
            runCatching {
                updateAssetFile(row.file, row.versionFile, row.localVersion)
            }.onFailure {
                onMainDispatcher {
                    alert(it.readableMessage).tryToShow()
                }
            }
            onMainDispatcher {
                updating.value = updating.value - row.file.name
            }
            reloadAssets()
        }
    }

    private fun confirmDeleteAsset(row: AssetRow) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm)
            .setMessage(row.file.name)
            .setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    row.file.deleteRecursively()
                    row.versionFile.deleteRecursively()
                    reloadAssets()
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private val rulesProviders = listOf(
        RuleAssetsProvider(
            "SagerNet/sing-geoip",
            "SagerNet/sing-geosite",
        ),
        RuleAssetsProvider(
            "soffchen/sing-geoip",
            "soffchen/sing-geosite",
        ),
        RuleAssetsProvider(
            "Chocolate4U/Iran-sing-box-rules",
        ),
        RuleAssetsProvider(
            "L11R/antizapret-sing-box-geo",
        ),
    )

    private suspend fun updateAssetFile(file: File, versionFile: File, localVersion: String) {
        val fileName = file.name
        val ruleProvider = rulesProviders[DataStore.rulesProvider]
        val repo = ruleProvider.repoByFileName[fileName]

        val client = Libcore.newHttpClient().apply {
            modernTLS()
            keepAlive()
            trySocks5(DataStore.mixedPort)
        }

        try {
            var response = client.newRequest().apply {
                setURL("https://api.github.com/repos/$repo/releases/latest")
            }.execute()

            val release = JSONObject(Util.getStringBox(response.contentString))
            val tagName = release.optString("tag_name")

            if (tagName == localVersion) {
                onMainDispatcher {
                    snackbar(R.string.route_asset_no_update).show()
                }
                return
            }

            val releaseAssets = release.getJSONArray("assets").filterIsInstance<JSONObject>()
            val assetToDownload = releaseAssets.find { it.getStr("name") == fileName }
                ?: error("File $fileName not found in release ${release["url"]}")
            val browserDownloadUrl = assetToDownload.getStr("browser_download_url")

            response = client.newRequest().apply {
                setURL(browserDownloadUrl)
            }.execute()

            val cacheFile = File(file.parentFile, file.name + ".tmp")
            cacheFile.parentFile?.mkdirs()
            response.writeTo(cacheFile.canonicalPath)

            if (fileName.endsWith(".xz")) {
                Libcore.unxz(cacheFile.absolutePath, file.absolutePath)
                cacheFile.delete()
            } else {
                cacheFile.renameTo(file)
            }

            versionFile.writeText(tagName)

            onMainDispatcher {
                snackbar(R.string.route_asset_updated).show()
            }
        } finally {
            client.close()
        }
    }

    private fun BaseService.State.isSpaceActive(): Boolean {
        return this == BaseService.State.Connecting ||
            this == BaseService.State.Connected ||
            this == BaseService.State.Stopping
    }

    private data class AssetRow(
        val file: File,
        val versionFile: File,
        val localVersion: String,
        val official: Boolean,
    )

    private data class RuleAssetsProvider(
        val repoByFileName: Map<String, String>,
    ) {
        constructor(
            geoipRepo: String,
            geositeRepo: String = geoipRepo,
        ) : this(
            mapOf(
                "geoip.db" to geoipRepo,
                "geosite.db" to geositeRepo,
            ),
        )
    }
}
