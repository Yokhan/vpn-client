package com.yokhan.vpn.app.provider

import io.nekohasekai.sagernet.BuildConfig
import java.util.Locale

object YokhanNetworkProviderContract {
    const val ROLE = "com.yokhan.vpn.provider.NETWORK_PROVIDER"
    const val ACTION_STATUS = "com.yokhan.vpn.provider.action.STATUS"
    const val ACTION_CONNECT = "com.yokhan.vpn.provider.action.CONNECT"
    const val ACTION_DISCONNECT = "com.yokhan.vpn.provider.action.DISCONNECT"
    const val EXTRA_PROFILE_ID = "com.yokhan.vpn.provider.extra.PROFILE_ID"

    val externalPackage: String = BuildConfig.YOKHAN_PROVIDER_PACKAGE
    val monolithPackage: String = BuildConfig.APPLICATION_ID
    val acceptedExternalCertificateSha256: Set<String> =
        BuildConfig.YOKHAN_PROVIDER_CERT_SHA256
            .split(',')
            .mapNotNull { it.normalizedSha256OrNull() }
            .toSet()

    fun normalizeFingerprint(value: String): String? = value.normalizedSha256OrNull()

    private fun String.normalizedSha256OrNull(): String? {
        val normalized = trim()
            .replace(":", "")
            .replace(" ", "")
            .lowercase(Locale.US)
        return normalized.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
    }
}

enum class YokhanNetworkProviderSource {
    CurrentMonolith,
    ExternalPackage
}

data class YokhanNetworkProviderInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val certificateSha256: List<String>,
    val source: YokhanNetworkProviderSource,
    val trusted: Boolean
)

sealed class YokhanNetworkProviderStatus {
    data class Ready(val provider: YokhanNetworkProviderInfo) : YokhanNetworkProviderStatus()
    data class Untrusted(val provider: YokhanNetworkProviderInfo) : YokhanNetworkProviderStatus()
    data class Missing(val checkedPackages: List<String>) : YokhanNetworkProviderStatus()
}
