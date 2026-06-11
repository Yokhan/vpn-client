package com.yokhan.gates.app.provider

import com.yokhan.gates.shared.domain.CertificateFingerprint
import io.nekohasekai.sagernet.BuildConfig

object YokhanNetworkProviderContract {
    const val ROLE = "com.yokhan.gates.provider.NETWORK_PROVIDER"
    const val ACTION_STATUS = "com.yokhan.gates.provider.action.STATUS"
    const val ACTION_CONNECT = "com.yokhan.gates.provider.action.CONNECT"
    const val ACTION_DISCONNECT = "com.yokhan.gates.provider.action.DISCONNECT"
    const val EXTRA_PROFILE_ID = "com.yokhan.gates.provider.extra.PROFILE_ID"

    val externalPackage: String = BuildConfig.YOKHAN_PROVIDER_PACKAGE
    val monolithPackage: String = BuildConfig.APPLICATION_ID
    val acceptedExternalCertificateSha256: Set<String> =
        BuildConfig.YOKHAN_PROVIDER_CERT_SHA256
            .split(',')
            .mapNotNull(CertificateFingerprint::normalizeSha256)
            .toSet()

    fun normalizeFingerprint(value: String): String? = CertificateFingerprint.normalizeSha256(value)
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
