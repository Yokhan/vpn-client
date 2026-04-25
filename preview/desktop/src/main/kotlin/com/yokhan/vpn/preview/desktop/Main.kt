package com.yokhan.vpn.preview.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.yokhan.vpn.shared.domain.ConnectionStatus
import com.yokhan.vpn.shared.domain.DashboardState
import com.yokhan.vpn.shared.domain.LatencyDisplay
import com.yokhan.vpn.shared.domain.RateDisplay
import com.yokhan.vpn.shared.domain.VpnProtocol
import com.yokhan.vpn.shared.ui.dashboard.DashboardProfileUiItem
import com.yokhan.vpn.shared.ui.dashboard.DashboardTypography
import com.yokhan.vpn.shared.ui.dashboard.DashboardUiActions
import com.yokhan.vpn.shared.ui.dashboard.DashboardUiLabels
import com.yokhan.vpn.shared.ui.dashboard.YokhanDashboardScreen

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "YokhanVPN Dashboard Preview",
    ) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                DashboardPreviewApp()
            }
        }
    }
}

@Composable
private fun DashboardPreviewApp() {
    var connected by remember { mutableStateOf(true) }
    val status = if (connected) ConnectionStatus.Connected else ConnectionStatus.Disconnected
    val state = previewDashboardState(status)
    val profiles = remember {
        listOf(
            DashboardProfileUiItem(1L, "Bridge-igor", "VLESS", true),
            DashboardProfileUiItem(2L, "Relay-igor", "VLESS", false),
            DashboardProfileUiItem(3L, "VLESS-Reality-igor", "VLESS", false),
        )
    }

    Row(Modifier.fillMaxSize().background(Color(0xFF15151E))) {
        YokhanDashboardScreen(
            state = state,
            profiles = profiles,
            labels = DashboardUiLabels.Russian,
            actions = DashboardUiActions(onToggleConnection = { connected = !connected }),
            typography = DashboardTypography(
                text = FontFamily.SansSerif,
                mono = FontFamily.Monospace,
            ),
            modifier = Modifier
                .width(412.dp)
                .fillMaxHeight(),
        )
        PreviewControlPanel(
            connected = connected,
            onToggle = { connected = !connected },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun PreviewControlPanel(
    connected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Desktop UI preview",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "This window uses the same shared Compose dashboard layout as Android. The rocket asset still differs until PNG/fonts move to shared resources.",
            color = Color(0xFFB7B7C8),
            fontSize = 14.sp,
        )
        Button(
            onClick = onToggle,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE55A1B)),
        ) {
            Text(if (connected) "Switch to disconnected" else "Switch to connected")
        }
        Text(
            text = "Current state: ${if (connected) "connected" else "disconnected"}",
            color = Color(0xFFB7B7C8),
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun previewDashboardState(status: ConnectionStatus): DashboardState {
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
