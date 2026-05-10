package com.yokhan.vpn.app.provider

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest
import java.util.Locale

class YokhanNetworkProviderDetector(
    private val packageManager: PackageManager,
    private val shellPackageName: String = YokhanNetworkProviderContract.monolithPackage
) {
    fun detect(
        externalPackageName: String = YokhanNetworkProviderContract.externalPackage
    ): YokhanNetworkProviderStatus {
        val checkedPackages = listOf(externalPackageName, shellPackageName).distinct()

        readProviderInfo(
            packageName = externalPackageName,
            source = YokhanNetworkProviderSource.ExternalPackage
        )?.let { provider ->
            return if (provider.trusted) {
                YokhanNetworkProviderStatus.Ready(provider)
            } else {
                YokhanNetworkProviderStatus.Untrusted(provider)
            }
        }

        readProviderInfo(
            packageName = shellPackageName,
            source = YokhanNetworkProviderSource.CurrentMonolith
        )?.let { provider ->
            return YokhanNetworkProviderStatus.Ready(provider)
        }

        return YokhanNetworkProviderStatus.Missing(checkedPackages)
    }

    private fun readProviderInfo(
        packageName: String,
        source: YokhanNetworkProviderSource
    ): YokhanNetworkProviderInfo? {
        val packageInfo = try {
            packageManager.packageInfoCompat(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val fingerprints = packageInfo.signaturesCompat().map { it.sha256() }
        val trusted = when (source) {
            YokhanNetworkProviderSource.CurrentMonolith -> packageName == shellPackageName
            YokhanNetworkProviderSource.ExternalPackage ->
                YokhanNetworkProviderContract.acceptedExternalCertificateSha256.isNotEmpty() &&
                    fingerprints.any { it in YokhanNetworkProviderContract.acceptedExternalCertificateSha256 }
        }

        return YokhanNetworkProviderInfo(
            packageName = packageName,
            versionName = packageInfo.versionName,
            versionCode = packageInfo.versionCodeCompat(),
            certificateSha256 = fingerprints,
            source = source,
            trusted = trusted
        )
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.packageInfoCompat(packageName: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signaturesCompat(): Array<Signature> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = signingInfo ?: return emptyArray()
            if (info.hasMultipleSigners()) {
                info.apkContentsSigners ?: emptyArray()
            } else {
                info.signingCertificateHistory ?: emptyArray()
            }
        } else {
            signatures ?: emptyArray()
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            versionCode.toLong()
        }
    }

    private fun Signature.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString(separator = "") { byte ->
            String.format(Locale.US, "%02x", byte.toInt() and 0xff)
        }
    }
}
