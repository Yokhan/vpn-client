package com.yokhan.vpn.shared.domain

enum class NetworkProviderState {
    CurrentMonolith,
    ExternalReady,
    Missing,
    Untrusted,
    Outdated,
}

data class NetworkProviderSummary(
    val state: NetworkProviderState,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val certificateSha256: String?,
    val checkedPackages: List<String> = emptyList(),
) {
    val canUseProvider: Boolean
        get() = state == NetworkProviderState.CurrentMonolith ||
            state == NetworkProviderState.ExternalReady
}
