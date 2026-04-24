package com.yokhan.vpn.shared.domain

data class DashboardState(
    val connectionStatus: ConnectionStatus,
    val activeProfileName: String,
    val activeProfileProtocol: VpnProtocol,
    val activeProfileSubtitle: String,
    val uploadRate: RateDisplay,
    val downloadRate: RateDisplay,
    val latency: LatencyDisplay,
    val uploadLabel: String,
    val downloadLabel: String,
    val latencyLabel: String,
    val timerLabel: String,
    val shouldAnimateSpace: Boolean,
    val canRunConnectionTest: Boolean,
    val errorMessage: String?,
)

object DashboardStateMapper {
    fun from(snapshot: ConnectionSnapshot): DashboardState {
        val profile = snapshot.activeProfile
        return DashboardState(
            connectionStatus = snapshot.status,
            activeProfileName = profile?.name ?: "Нет конфигурации",
            activeProfileProtocol = profile?.protocol ?: VpnProtocol.Unknown,
            activeProfileSubtitle = profile?.subtitle() ?: "Выберите профиль",
            uploadRate = RateDisplay(snapshot.traffic.uploadBytesPerSecond),
            downloadRate = RateDisplay(snapshot.traffic.downloadBytesPerSecond),
            latency = LatencyDisplay(snapshot.traffic.latencyMs),
            uploadLabel = snapshot.traffic.uploadBytesPerSecond.toRateLabel(),
            downloadLabel = snapshot.traffic.downloadBytesPerSecond.toRateLabel(),
            latencyLabel = snapshot.traffic.latencyMs?.let { "${it} мс" } ?: "--",
            timerLabel = snapshot.connectedForMillis.toTimerLabel(),
            shouldAnimateSpace = snapshot.isRunning,
            canRunConnectionTest = snapshot.status == ConnectionStatus.Connected,
            errorMessage = snapshot.errorMessage,
        )
    }

    private fun ProfileSummary.subtitle(): String {
        return subscriptionLabel?.let { "$protocolLabel · $it" } ?: protocolLabel
    }

    private fun Long.toRateLabel(): String {
        require(this >= 0) { "rate must be non-negative" }
        return when {
            this < 1024 -> "$this B/s"
            this < 1024 * 1024 -> "${formatOneDecimal(this / 1024.0)} KB/s"
            else -> "${formatOneDecimal(this / (1024.0 * 1024.0))} MB/s"
        }
    }

    private fun Long.toTimerLabel(): String {
        val totalSeconds = this / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "${hours.pad2()}:${minutes.pad2()}:${seconds.pad2()}"
    }

    private fun Long.pad2(): String = if (this < 10) "0$this" else toString()

    private fun formatOneDecimal(value: Double): String {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        val text = rounded.toString()
        return text.removeSuffix(".0")
    }
}
