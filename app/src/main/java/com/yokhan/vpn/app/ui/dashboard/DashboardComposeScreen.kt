package com.yokhan.vpn.app.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.yokhan.vpn.shared.domain.ConnectionStatus
import com.yokhan.vpn.shared.domain.DashboardState
import com.yokhan.vpn.shared.domain.LatencyDisplay
import com.yokhan.vpn.shared.domain.RateDisplay
import com.yokhan.vpn.shared.domain.VpnProtocol
import com.yokhan.vpn.shared.ui.dashboard.DashboardUiActions
import com.yokhan.vpn.shared.ui.dashboard.DashboardUiLabels
import com.yokhan.vpn.shared.ui.dashboard.YokhanDashboardScreen
import io.nekohasekai.sagernet.R

typealias DashboardProfileUiItem = com.yokhan.vpn.shared.ui.dashboard.DashboardProfileUiItem
typealias DashboardComposeActions = DashboardUiActions

@Composable
fun DashboardComposeScreen(
    state: DashboardState,
    profiles: List<DashboardProfileUiItem>,
    actions: DashboardComposeActions,
    modifier: Modifier = Modifier,
) {
    YokhanDashboardScreen(
        state = state,
        profiles = profiles,
        labels = dashboardLabels(),
        actions = actions,
        modifier = modifier,
    )
}

@Composable
private fun dashboardLabels(): DashboardUiLabels {
    return DashboardUiLabels(
        title = stringResource(R.string.dashboard_title),
        system = stringResource(R.string.dashboard_system_label),
        currentConfig = stringResource(R.string.dashboard_current_config),
        upload = stringResource(R.string.dashboard_upload_label),
        download = stringResource(R.string.dashboard_download_label),
        orbIdle = stringResource(R.string.dashboard_orb_idle),
        connectAction = stringResource(R.string.dashboard_connect_action),
        connected = stringResource(R.string.dashboard_connected_status),
        disconnected = stringResource(R.string.dashboard_disconnected_status),
        connecting = stringResource(R.string.dashboard_state_connecting),
        disconnecting = stringResource(R.string.dashboard_state_stopping),
        tapConnect = stringResource(R.string.dashboard_tap_connect),
        checkConnection = stringResource(R.string.dashboard_check_connection),
        checkingConnection = stringResource(R.string.connection_test_testing).uppercase(),
        configurations = stringResource(R.string.dashboard_configurations),
        add = stringResource(R.string.dashboard_add),
        latency = stringResource(R.string.dashboard_latency_label),
        status = stringResource(R.string.dashboard_status_label),
        protocol = stringResource(R.string.dashboard_protocol_label),
    )
}

@Preview(widthDp = 412, heightDp = 860, showBackground = true)
@Composable
private fun DashboardComposeConnectedPreview() {
    DashboardComposeScreen(
        state = previewState(ConnectionStatus.Connected),
        profiles = previewProfiles,
        actions = DashboardComposeActions(),
    )
}

@Preview(widthDp = 412, heightDp = 860, showBackground = true)
@Composable
private fun DashboardComposeDisconnectedPreview() {
    DashboardComposeScreen(
        state = previewState(ConnectionStatus.Disconnected),
        profiles = previewProfiles,
        actions = DashboardComposeActions(),
    )
}

private val previewProfiles = listOf(
    DashboardProfileUiItem(1L, "Bridge-igor", "VLESS", true),
    DashboardProfileUiItem(2L, "Relay-igor", "VLESS", false),
    DashboardProfileUiItem(3L, "VLESS-Reality-igor", "VLESS", false),
)

private fun previewState(status: ConnectionStatus): DashboardState {
    val connected = status == ConnectionStatus.Connected
    return DashboardState(
        connectionStatus = status,
        activeProfileName = "Bridge-igor",
        activeProfileProtocol = VpnProtocol.Vless,
        activeProfileSubtitle = "VLESS · Subscription #1776115554141",
        uploadRate = RateDisplay(if (connected) 12_400L else 0L),
        downloadRate = RateDisplay(if (connected) 48_700L else 0L),
        latency = LatencyDisplay(if (connected) 103L else null),
        uploadLabel = if (connected) "12.4 KB/s" else "0 B/s",
        downloadLabel = if (connected) "48.7 KB/s" else "0 B/s",
        latencyLabel = if (connected) "103 MS" else "--",
        timerLabel = if (connected) "00:01:24" else "00:00:00",
        shouldAnimateSpace = connected,
        canRunConnectionTest = connected,
        errorMessage = null,
    )
}
