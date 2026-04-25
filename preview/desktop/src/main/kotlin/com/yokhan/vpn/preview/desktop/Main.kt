package com.yokhan.vpn.preview.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.yokhan.vpn.shared.domain.ConnectionStatus
import com.yokhan.vpn.shared.domain.DashboardState
import com.yokhan.vpn.shared.domain.LatencyDisplay
import com.yokhan.vpn.shared.domain.RateDisplay
import com.yokhan.vpn.shared.domain.VpnProtocol
import com.yokhan.vpn.shared.ui.dashboard.DashboardProfileUiItem
import com.yokhan.vpn.shared.ui.dashboard.DashboardUiActions
import com.yokhan.vpn.shared.ui.dashboard.DashboardUiLabels
import com.yokhan.vpn.shared.ui.dashboard.DashboardUiTuning
import com.yokhan.vpn.shared.ui.dashboard.YokhanDashboardScreen
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Window as AwtWindow
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO
import javax.swing.JFrame
import kotlin.math.roundToInt

private val IPhone15ProMaxWidth = 430.dp
private val IPhone15ProMaxHeight = 932.dp

fun main() = application {
    val windowState = rememberWindowState(size = DpSize(960.dp, 1040.dp))
    Window(
        onCloseRequest = ::exitApplication,
        title = "YokhanVPN Dashboard Preview",
        state = windowState,
    ) {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                DashboardPreviewApp(window = window)
            }
        }
    }
}

@Composable
private fun DashboardPreviewApp(window: AwtWindow) {
    var connected by remember { mutableStateOf(true) }
    var phoneBounds by remember { mutableStateOf<Rectangle?>(null) }
    var screenshotStatus by remember { mutableStateOf("No screenshot yet") }
    var tuning by remember { mutableStateOf(DashboardUiTuning()) }
    val status = if (connected) ConnectionStatus.Connected else ConnectionStatus.Disconnected
    val state = previewDashboardState(status)
    val profiles = remember {
        listOf(
            DashboardProfileUiItem(1L, "Bridge-igor", "VLESS", true),
            DashboardProfileUiItem(2L, "Relay-igor", "VLESS", false),
            DashboardProfileUiItem(3L, "VLESS-Reality-igor", "VLESS", false),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF15151E))
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(
            modifier = Modifier
                .width(IPhone15ProMaxWidth)
                .height(IPhone15ProMaxHeight)
                .border(1.dp, Color(0xFF2B2B36), RoundedCornerShape(2.dp))
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    phoneBounds = Rectangle(
                        bounds.left.roundToInt(),
                        bounds.top.roundToInt(),
                        bounds.width.roundToInt(),
                        bounds.height.roundToInt(),
                    )
                },
        ) {
            Column(Modifier.fillMaxSize().background(Color(0xFF0A0A18))) {
                FakeIPhoneStatusBar()
                YokhanDashboardScreen(
                    state = state,
                    profiles = profiles,
                    labels = DashboardUiLabels.Russian,
                    actions = DashboardUiActions(onToggleConnection = { connected = !connected }),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    tuning = tuning,
                )
            }
        }
        PreviewControlPanel(
            connected = connected,
            onToggle = { connected = !connected },
            tuning = tuning,
            onTuningChange = { tuning = it },
            screenshotStatus = screenshotStatus,
            onScreenshot = {
                val bounds = phoneBounds
                screenshotStatus = if (bounds == null) {
                    "Phone viewport is not measured yet"
                } else {
                    runCatching { savePhoneScreenshot(window, bounds) }
                        .fold(
                            onSuccess = { "Saved: ${it.absolutePath}" },
                            onFailure = { "Screenshot failed: ${it.message ?: it::class.simpleName}" },
                        )
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FakeIPhoneStatusBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(Color(0xFF0A0A18))
            .padding(start = 8.dp, end = 8.dp, top = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = "22:29",
            color = Color(0xFFE8E8F0),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "173 K/c   4G LTE   ║⋮",
            color = Color(0xFF9A9AAE),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Box(
            modifier = Modifier
                .padding(start = 8.dp)
                .border(1.dp, Color(0xFF9A9AAE), RoundedCornerShape(2.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(
                text = "86",
                color = Color(0xFF9A9AAE),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PreviewControlPanel(
    connected: Boolean,
    onToggle: () -> Unit,
    tuning: DashboardUiTuning,
    onTuningChange: (DashboardUiTuning) -> Unit,
    screenshotStatus: String,
    onScreenshot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Desktop UI preview",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "This window uses the same shared Compose dashboard layout, rocket PNGs, flame PNG, and fonts as the Android Compose route.",
            color = Color(0xFFB7B7C8),
            fontSize = 14.sp,
        )
        Text(
            text = "Viewport: iPhone 15 Pro Max, 430x932 dp",
            color = Color(0xFFB7B7C8),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
        Button(
            onClick = onToggle,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE55A1B)),
        ) {
            Text(if (connected) "Switch to disconnected" else "Switch to connected")
        }
        Button(
            onClick = onScreenshot,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A7C55)),
        ) {
            Text("Save phone screenshot")
        }
        Button(
            onClick = { onTuningChange(DashboardUiTuning()) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B3B48)),
        ) {
            Text("Reset tuning")
        }
        TuningSlider("Rocket width", tuning.rocketWidth, 96f..170f) {
            onTuningChange(tuning.copy(rocketWidth = it))
        }
        TuningSlider("Rocket height", tuning.rocketHeight, 170f..280f) {
            onTuningChange(tuning.copy(rocketHeight = it))
        }
        TuningSlider("Flame height", tuning.flameHeight, 54f..140f) {
            onTuningChange(tuning.copy(flameHeight = it))
        }
        TuningSlider("Flame Y", tuning.flameY, 72f..158f) {
            onTuningChange(tuning.copy(flameY = it))
        }
        TuningSlider("Left flame X", tuning.leftFlameX, -28f..4f) {
            onTuningChange(tuning.copy(leftFlameX = it))
        }
        TuningSlider("Right flame X", tuning.rightFlameX, -4f..28f) {
            onTuningChange(tuning.copy(rightFlameX = it))
        }
        TuningSlider("Test button width %", (tuning.testButtonWidthFraction * 100).roundToInt(), 38f..72f) {
            onTuningChange(tuning.copy(testButtonWidthFraction = it / 100f))
        }
        TuningSlider("Bottom stats pad", tuning.statsBarBottomPadding, 8f..34f) {
            onTuningChange(tuning.copy(statsBarBottomPadding = it))
        }
        Text(
            text = "Current state: ${if (connected) "connected" else "disconnected"}",
            color = Color(0xFFB7B7C8),
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = screenshotStatus,
            color = Color(0xFFB7B7C8),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun TuningSlider(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "$label: $value",
            color = Color(0xFFB7B7C8),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = range,
        )
    }
}

private fun savePhoneScreenshot(window: AwtWindow, boundsInWindow: Rectangle): File {
    val contentLocation = if (window is JFrame) {
        window.contentPane.locationOnScreen
    } else {
        window.locationOnScreen
    }
    val capture = Rectangle(
        contentLocation.x + boundsInWindow.x,
        contentLocation.y + boundsInWindow.y,
        boundsInWindow.width,
        boundsInWindow.height,
    )
    val image = Robot().createScreenCapture(capture)
    val projectRoot = findProjectRoot()
    val outputDir = File(projectRoot, "preview/desktop/build/screenshots").apply { mkdirs() }
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val output = File(outputDir, "dashboard-iphone15-pro-max-$timestamp.png")
    ImageIO.write(image, "png", output)
    return output
}

private fun findProjectRoot(): File {
    return generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, ".git").isDirectory }
        ?: File(System.getProperty("user.dir")).absoluteFile
}

private fun previewDashboardState(status: ConnectionStatus): DashboardState {
    val connected = status == ConnectionStatus.Connected
    return DashboardState(
        connectionStatus = status,
        activeProfileName = "Bridge-igor",
        activeProfileProtocol = VpnProtocol.Vless,
        activeProfileSubtitle = "VLESS \u00B7 Subscription #1776115554141",
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
