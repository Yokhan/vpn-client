package com.yokhan.vpn.app.provider

import com.yokhan.vpn.shared.domain.NetworkProviderState
import com.yokhan.vpn.shared.domain.NetworkProviderSummary

object YokhanNetworkProviderStatusMapper {
    fun toSummary(status: YokhanNetworkProviderStatus): NetworkProviderSummary {
        return when (status) {
            is YokhanNetworkProviderStatus.Ready -> {
                val state = when (status.provider.source) {
                    YokhanNetworkProviderSource.CurrentMonolith -> NetworkProviderState.CurrentMonolith
                    YokhanNetworkProviderSource.ExternalPackage -> NetworkProviderState.ExternalReady
                }
                status.provider.toSummary(state)
            }
            is YokhanNetworkProviderStatus.Untrusted -> {
                status.provider.toSummary(NetworkProviderState.Untrusted)
            }
            is YokhanNetworkProviderStatus.Missing -> {
                NetworkProviderSummary(
                    state = NetworkProviderState.Missing,
                    packageName = null,
                    versionName = null,
                    versionCode = null,
                    certificateSha256 = null,
                    checkedPackages = status.checkedPackages,
                )
            }
        }
    }

    private fun YokhanNetworkProviderInfo.toSummary(
        state: NetworkProviderState,
    ): NetworkProviderSummary {
        return NetworkProviderSummary(
            state = state,
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            certificateSha256 = certificateSha256.firstOrNull(),
        )
    }
}
