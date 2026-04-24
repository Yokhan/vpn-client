package com.yokhan.vpn.shared.domain

enum class ConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
    Disconnecting,
    Error,
}

enum class VpnProtocol {
    Vless,
    Vmess,
    Trojan,
    Shadowsocks,
    Socks,
    WireGuard,
    Unknown,
}

data class ProfileSummary(
    val id: Long,
    val name: String,
    val protocol: VpnProtocol,
    val subscriptionLabel: String? = null,
)

data class TrafficSnapshot(
    val uploadBytesPerSecond: Long,
    val downloadBytesPerSecond: Long,
    val latencyMs: Long? = null,
    val capturedAtMillis: Long,
) {
    init {
        require(uploadBytesPerSecond >= 0) { "uploadBytesPerSecond must be non-negative" }
        require(downloadBytesPerSecond >= 0) { "downloadBytesPerSecond must be non-negative" }
        require(latencyMs == null || latencyMs >= 0) { "latencyMs must be null or non-negative" }
        require(capturedAtMillis >= 0) { "capturedAtMillis must be non-negative" }
    }
}

data class ConnectionSnapshot(
    val status: ConnectionStatus,
    val activeProfile: ProfileSummary?,
    val traffic: TrafficSnapshot,
    val connectedForMillis: Long = 0,
    val errorMessage: String? = null,
) {
    init {
        require(connectedForMillis >= 0) { "connectedForMillis must be non-negative" }
        require(status == ConnectionStatus.Error || errorMessage == null) {
            "errorMessage is only valid for Error status"
        }
    }

    val isRunning: Boolean
        get() = status == ConnectionStatus.Connecting || status == ConnectionStatus.Connected
}
