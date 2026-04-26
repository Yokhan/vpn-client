package com.yokhan.vpn.app.ui

import com.yokhan.vpn.shared.domain.ConnectionSnapshot
import com.yokhan.vpn.shared.domain.ConnectionStatus
import com.yokhan.vpn.shared.domain.DashboardState
import com.yokhan.vpn.shared.domain.DashboardStateMapper
import com.yokhan.vpn.shared.domain.ProfileSummary
import com.yokhan.vpn.shared.domain.TrafficSnapshot
import com.yokhan.vpn.shared.domain.VpnProtocol
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.ProxyEntity

internal object DashboardStateAdapter {
    fun from(
        serviceState: BaseService.State,
        profile: ProxyEntity?,
        groupName: String,
        txRate: Long,
        rxRate: Long,
        latencyMs: Long?,
        connectedForMillis: Long,
        nowMillis: Long,
        connectionTestRunning: Boolean = false,
    ): DashboardState {
        return DashboardStateMapper.from(
            ConnectionSnapshot(
                status = serviceState.toSharedStatus(),
                activeProfile = profile?.toProfileSummary(groupName),
                traffic = TrafficSnapshot(
                    uploadBytesPerSecond = txRate.coerceAtLeast(0L),
                    downloadBytesPerSecond = rxRate.coerceAtLeast(0L),
                    latencyMs = latencyMs?.coerceAtLeast(0L),
                    capturedAtMillis = nowMillis.coerceAtLeast(0L),
                ),
                connectedForMillis = connectedForMillis.coerceAtLeast(0L),
            ),
        ).copy(
            isConnectionTestRunning = connectionTestRunning,
        )
    }

    private fun BaseService.State.toSharedStatus(): ConnectionStatus {
        return when (this) {
            BaseService.State.Connected -> ConnectionStatus.Connected
            BaseService.State.Connecting -> ConnectionStatus.Connecting
            BaseService.State.Stopping -> ConnectionStatus.Disconnecting
            BaseService.State.Idle -> ConnectionStatus.Disconnected
            BaseService.State.Stopped -> ConnectionStatus.Disconnected
        }
    }

    private fun ProxyEntity.toProfileSummary(groupName: String): ProfileSummary {
        return ProfileSummary(
            id = id,
            name = displayName().orEmpty(),
            protocol = displayType().toVpnProtocol(),
            protocolLabel = displayType().ifBlank { VpnProtocol.Unknown.name.uppercase() },
            subscriptionLabel = groupName,
        )
    }

    private fun String.toVpnProtocol(): VpnProtocol {
        return when (uppercase()) {
            "VLESS" -> VpnProtocol.Vless
            "VMESS" -> VpnProtocol.Vmess
            "TROJAN" -> VpnProtocol.Trojan
            "SHADOWSOCKS", "SS" -> VpnProtocol.Shadowsocks
            "SOCKS", "SOCKS5" -> VpnProtocol.Socks
            "WIREGUARD", "WG" -> VpnProtocol.WireGuard
            else -> VpnProtocol.Unknown
        }
    }
}
