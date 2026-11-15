package com.yokhan.gates.app.ui.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.yokhan.gates.app.auth.YokhanKeycloakAuth
import com.yokhan.gates.shared.domain.ConnectionStatus
import com.yokhan.gates.shared.domain.DashboardState
import com.yokhan.gates.shared.domain.LatencyDisplay
import com.yokhan.gates.shared.domain.RateDisplay
import com.yokhan.gates.shared.domain.VpnProtocol
import com.yokhan.gates.shared.ui.dashboard.DashboardUiActions
import com.yokhan.gates.shared.ui.dashboard.DashboardUiLabels
import com.yokhan.gates.shared.ui.dashboard.DashboardUiTuning
import com.yokhan.gates.shared.ui.dashboard.DashboardUiVisibility
import com.yokhan.gates.shared.ui.dashboard.YokhanDashboardScreen
import io.nekohasekai.sagernet.R

typealias DashboardProfileUiItem = com.yokhan.gates.shared.ui.dashboard.DashboardProfileUiItem
typealias DashboardComposeActions = DashboardUiActions

@Composable
fun DashboardComposeScreen(
    state: DashboardState,
    profiles: List<DashboardProfileUiItem>,
    actions: DashboardComposeActions,
    modifier: Modifier = Modifier,
    scrollToTopSignal: Int = 0,
) {
    val authConfigured = YokhanKeycloakAuth.isConfigured()
    val signedIn by YokhanKeycloakAuth.sessionAvailable.collectAsState()
    val authBusy by YokhanKeycloakAuth.busy.collectAsState()
    YokhanDashboardScreen(
        state = state,
        profiles = profiles,
        labels = dashboardLabels(authConfigured = authConfigured, signedIn = signedIn, authBusy = authBusy),
        actions = actions,
        tuning = DashboardUiTuning(contentBottomPadding = 112),
        visibility = DashboardUiVisibility(addProfileEnabled = !authBusy),
        modifier = modifier,
        scrollToTopSignal = scrollToTopSignal,
        drawBackgroundEffects = false,
    )
}

@Composable
private fun dashboardLabels(authConfigured: Boolean, signedIn: Boolean, authBusy: Boolean): DashboardUiLabels {
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
        add = stringResource(
            if (authBusy) R.string.yokhan_auth_connecting_short
            else if (signedIn) R.string.yokhan_auth_refresh_short
            else if (authConfigured) R.string.dashboard_auth_add else R.string.dashboard_add,
        ),
        firstRunTitle = stringResource(
            if (authConfigured) {
                R.string.dashboard_auth_first_run_title
            } else {
                R.string.dashboard_first_run_title
            },
        ),
        firstRunBody = stringResource(
            if (authConfigured) {
                R.string.dashboard_auth_first_run_body
            } else {
                R.string.dashboard_first_run_body
            },
        ),
        firstRunAction = stringResource(
            if (authBusy) {
                R.string.yokhan_auth_connecting
            } else if (authConfigured) {
                R.string.yokhan_auth_login_action
            } else {
                R.string.dashboard_first_run_action
            },
        ),
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
        activeProfileProtocolLabel = "VLESS",
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
