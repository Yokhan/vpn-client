package com.yokhan.gates.app.ui

internal enum class AppManagerProxyMode {
    Off,
    Proxy,
    Bypass,
}

internal data class AppManagerOpenPlan(
    val proxyAppsEnabled: Boolean,
    val bypassEnabled: Boolean,
    val proxyMode: AppManagerProxyMode,
)

internal fun planAppManagerOpen(proxyAppsEnabled: Boolean, bypassEnabled: Boolean) =
    AppManagerOpenPlan(
        proxyAppsEnabled = proxyAppsEnabled,
        bypassEnabled = bypassEnabled,
        proxyMode = when {
            !proxyAppsEnabled -> AppManagerProxyMode.Off
            bypassEnabled -> AppManagerProxyMode.Bypass
            else -> AppManagerProxyMode.Proxy
        },
    )
