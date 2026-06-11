package com.yokhan.gates.app.ui.shell

import io.nekohasekai.sagernet.database.ProxyEntity

sealed interface YokhanShellActivityRoute {
    object Scanner : YokhanShellActivityRoute
    object GroupCreate : YokhanShellActivityRoute
    data class GroupEdit(val groupId: Long) : YokhanShellActivityRoute
    object RouteCreate : YokhanShellActivityRoute
    data class RouteEdit(val routeId: Long) : YokhanShellActivityRoute
    object RouteAssets : YokhanShellActivityRoute
    object AppManager : YokhanShellActivityRoute
    object RuCompatibility : YokhanShellActivityRoute
    data class AddManualProfile(val protocol: YokhanManualProfileProtocol) : YokhanShellActivityRoute
    data class EditProfile(
        val profile: ProxyEntity,
        val isSubscription: Boolean,
    ) : YokhanShellActivityRoute
}

enum class YokhanManualProfileProtocol {
    Socks,
    Http,
    Shadowsocks,
    VMess,
    VLESS,
    Trojan,
    TrojanGo,
    Mieru,
    Naive,
    Hysteria,
    Tuic,
    ShadowTLS,
    AnyTLS,
    SSH,
    WireGuard,
    Config,
    Chain,
}
