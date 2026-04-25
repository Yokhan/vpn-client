package com.yokhan.vpn.preview.desktop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.yokhan.vpn.shared.domain.ConnectionStatus
import com.yokhan.vpn.shared.domain.DashboardState
import com.yokhan.vpn.shared.domain.LatencyDisplay
import com.yokhan.vpn.shared.domain.RateDisplay
import com.yokhan.vpn.shared.domain.VpnProtocol

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
            PreviewProfile(1L, "Bridge-igor", "VLESS", true),
            PreviewProfile(2L, "Relay-igor", "VLESS", false),
            PreviewProfile(3L, "VLESS-Reality-igor", "VLESS", false),
        )
    }

    Row(Modifier.fillMaxSize().background(Color(0xFF15151E))) {
        DashboardScreen(
            state = state,
            profiles = profiles,
            onToggle = { connected = !connected },
            modifier = Modifier
                .width(412.dp)
                .fillMaxHeight()
        )
        PreviewControlPanel(
            connected = connected,
            onToggle = { connected = !connected },
            modifier = Modifier.weight(1f).fillMaxHeight()
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
            text = "This is a Windows-runnable Compose preview. It does not start VPN services.",
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

@Composable
private fun DashboardScreen(
    state: DashboardState,
    profiles: List<PreviewProfile>,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = state.shouldAnimateSpace
    val connected = state.connectionStatus == ConnectionStatus.Connected
    val colors = if (running) SpaceColors else LightColors

    Box(modifier.background(colors.background)) {
        if (running) Starfield(Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 96.dp),
        ) {
            Toolbar(colors)
            CurrentConfig(state, colors)
            LaunchArea(state, connected, running, colors, onToggle)
            StatusLine(state, connected, colors)
            TestButton(state, colors)
            Profiles(profiles, colors)
            Spacer(Modifier.height(140.dp))
        }

        StatsBar(
            state = state,
            connected = connected,
            colors = colors,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun Toolbar(colors: PreviewColors) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.width(40.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            repeat(3) {
                Box(
                    Modifier
                        .width(22.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(colors.primary.copy(alpha = 0.58f))
                )
            }
        }
        Text(
            text = "Панель управления",
            color = colors.primary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "СИСТЕМА · 01",
                color = colors.secondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Box(
                Modifier
                    .padding(start = 6.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
            )
        }
    }
}

@Composable
private fun CurrentConfig(state: DashboardState, colors: PreviewColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            SectionLabel("ТЕКУЩАЯ КОНФИГУРАЦИЯ", colors, 10)
            Text(
                text = state.activeProfileName,
                color = colors.primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = state.activeProfileSubtitle,
                color = colors.secondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        SignalBox(colors)
    }
}

@Composable
private fun LaunchArea(
    state: DashboardState,
    connected: Boolean,
    running: Boolean,
    colors: PreviewColors,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SpeedPanel("ОТПРАВКА", state.uploadLabel, "^", colors, false)
        OrbScene(connected, running, colors, onToggle)
        SpeedPanel("ПОЛУЧЕНИЕ", state.downloadLabel, "v", colors, true)
    }
}

@Composable
private fun SpeedPanel(
    label: String,
    value: String,
    arrow: String,
    colors: PreviewColors,
    alignEnd: Boolean,
) {
    Column(
        modifier = Modifier.width(80.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = label,
            color = colors.tertiary,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                color = colors.primary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = arrow,
                color = colors.accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        WaveLine(colors, Modifier.padding(top = 4.dp).width(56.dp).height(8.dp))
    }
}

@Composable
private fun OrbScene(
    connected: Boolean,
    running: Boolean,
    colors: PreviewColors,
    onToggle: () -> Unit,
) {
    val infinite = rememberInfiniteTransition(label = "orb")
    val ringScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (connected) 1.04f else 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "ring",
    )
    val flameScale by infinite.animateFloat(
        initialValue = 0.96f,
        targetValue = if (connected) 1.12f else 0.96f,
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse),
        label = "flame",
    )

    Box(
        modifier = Modifier.size(192.dp).clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(colors.orbCenter, colors.background)))
        )
        Canvas(Modifier.size(176.dp)) {
            drawCircle(
                color = colors.accent.copy(alpha = if (connected) 0.35f else 0.18f),
                radius = size.minDimension * 0.5f * ringScale,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        OrbMarks(connected, colors, Modifier.size(192.dp))
        if (!running) {
            Text(
                text = "ПУСК",
                color = colors.secondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        } else {
            RocketCanvas(
                flameScale = flameScale,
                modifier = Modifier.size(width = 132.dp, height = 320.dp)
            )
        }
    }
}

@Composable
private fun RocketCanvas(flameScale: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val bodyTop = 46.dp.toPx()
        val bodyBottom = 218.dp.toPx()
        val bodyWidth = 34.dp.toPx()
        val wingWidth = 56.dp.toPx()
        val orange = Color(0xFFE55A1B)

        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFD9D9DE))),
            topLeft = Offset(cx - bodyWidth / 2f, bodyTop),
            size = Size(bodyWidth, bodyBottom - bodyTop),
            cornerRadius = CornerRadius(20.dp.toPx(), 20.dp.toPx()),
        )
        drawPath(
            path = Path().apply {
                moveTo(cx - bodyWidth / 2f, 120.dp.toPx())
                lineTo(cx - wingWidth, 210.dp.toPx())
                lineTo(cx - bodyWidth / 2f, 205.dp.toPx())
                close()
            },
            brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFD0D0D6))),
        )
        drawPath(
            path = Path().apply {
                moveTo(cx + bodyWidth / 2f, 120.dp.toPx())
                lineTo(cx + wingWidth, 210.dp.toPx())
                lineTo(cx + bodyWidth / 2f, 205.dp.toPx())
                close()
            },
            brush = Brush.verticalGradient(listOf(Color.White, Color(0xFFD0D0D6))),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFF3A3A3A), Color(0xFF111111))),
            topLeft = Offset(cx - 22.dp.toPx(), 206.dp.toPx()),
            size = Size(44.dp.toPx(), 32.dp.toPx()),
            cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
        )
        drawOval(
            brush = Brush.verticalGradient(
                listOf(Color.White, Color(0xFFFFC36E), Color(0xFFFF6A00), Color.Transparent)
            ),
            topLeft = Offset(cx - 24.dp.toPx(), 232.dp.toPx()),
            size = Size(18.dp.toPx(), 88.dp.toPx() * flameScale),
        )
        drawOval(
            brush = Brush.verticalGradient(
                listOf(Color.White, Color(0xFFFFC36E), Color(0xFFFF6A00), Color.Transparent)
            ),
            topLeft = Offset(cx + 6.dp.toPx(), 232.dp.toPx()),
            size = Size(18.dp.toPx(), 88.dp.toPx() * flameScale),
        )
        repeat(3) { index ->
            drawRect(
                color = orange,
                topLeft = Offset(cx - 46.dp.toPx() + index * 7.dp.toPx(), 186.dp.toPx()),
                size = Size(4.dp.toPx(), 12.dp.toPx()),
            )
            drawRect(
                color = orange,
                topLeft = Offset(cx + 30.dp.toPx() + index * 7.dp.toPx(), 186.dp.toPx()),
                size = Size(4.dp.toPx(), 12.dp.toPx()),
            )
        }
    }
}

@Composable
private fun StatusLine(state: DashboardState, connected: Boolean, colors: PreviewColors) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (connected) "ПОДКЛЮЧЕНО" else "НАЖМИТЕ ДЛЯ ПОДКЛЮЧЕНИЯ",
            color = if (connected) colors.primary else colors.tertiary,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        if (connected) {
            Text(
                text = state.timerLabel,
                color = colors.primary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun TestButton(state: DashboardState, colors: PreviewColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⊙", color = colors.secondary, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Text(
                text = "ПРОВЕРИТЬ СОЕДИНЕНИЕ",
                color = colors.secondary.copy(alpha = if (state.canRunConnectionTest) 1f else 0.55f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun Profiles(profiles: List<PreviewProfile>, colors: PreviewColors) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("КОНФИГУРАЦИИ", colors, 12, Modifier.weight(1f))
            Text(
                text = "+ ДОБАВИТЬ",
                color = Color(0xFFE55A1B),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        profiles.forEachIndexed { index, profile ->
            ProfileRow(profile, index != 0, colors)
        }
    }
}

@Composable
private fun ProfileRow(profile: PreviewProfile, showDivider: Boolean, colors: PreviewColors) {
    Column {
        if (showDivider) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (profile.active) colors.accent else Color.Transparent)
            )
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    text = profile.name,
                    color = colors.primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = profile.protocol,
                    color = colors.tertiary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text("✎", color = colors.secondary, fontSize = 22.sp, modifier = Modifier.padding(10.dp))
            Text("⋮", color = colors.secondary, fontSize = 24.sp, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun StatsBar(
    state: DashboardState,
    connected: Boolean,
    colors: PreviewColors,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.border)
            .background(Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatsSegment("◷", state.latencyLabel, "ЗАДЕРЖКА", colors, Modifier.weight(1f))
        StatsDivider(colors)
        StatsSegment(
            "●",
            if (connected) "ПОДКЛЮЧЕНО" else "ОТКЛЮЧЕНО",
            "СТАТУС",
            colors.copy(primary = if (connected) Color(0xFF28B44F) else Color(0xFFE53E3E)),
            Modifier.weight(1f),
        )
        StatsDivider(colors)
        StatsSegment("◇", state.activeProfileProtocol.name.uppercase(), "ПРОТОКОЛ", colors, Modifier.weight(1f))
    }
}

@Composable
private fun StatsSegment(
    icon: String,
    value: String,
    label: String,
    colors: PreviewColors,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.height(42.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = colors.tertiary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text(
                text = value,
                color = colors.primary,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Text(
            text = label,
            color = colors.tertiary,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun StatsDivider(colors: PreviewColors) {
    Box(Modifier.width(1.dp).height(34.dp).background(colors.divider))
}

@Composable
private fun SectionLabel(
    text: String,
    colors: PreviewColors,
    fontSize: Int,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(colors.tertiary)
        )
        Text(
            text = text,
            color = colors.tertiary,
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun SignalBox(colors: PreviewColors) {
    Box(
        modifier = Modifier.size(44.dp).border(1.dp, colors.border, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(7.dp, 11.dp, 16.dp, 8.dp).forEachIndexed { index, height ->
                Box(
                    Modifier
                        .width(4.dp)
                        .height(height)
                        .background(colors.primary.copy(alpha = if (index == 3) 0.16f else 1f))
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 5.dp, end = 5.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(colors.accent)
        )
    }
}

@Composable
private fun WaveLine(colors: PreviewColors, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val segment = size.width / 8f
        repeat(8) { i ->
            val x = i * segment
            drawLine(
                color = colors.accent.copy(alpha = 0.25f),
                start = Offset(x, size.height / 2f),
                end = Offset(x + segment / 2f, size.height / 4f),
                strokeWidth = 1.1.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = colors.accent.copy(alpha = 0.25f),
                start = Offset(x + segment / 2f, size.height / 4f),
                end = Offset(x + segment, size.height / 2f),
                strokeWidth = 1.1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun OrbMarks(connected: Boolean, colors: PreviewColors, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val radius = size.minDimension * 0.47f
        val center = Offset(size.width / 2f, size.height / 2f)
        repeat(12) { index ->
            val angle = Math.toRadians((index * 30 - 90).toDouble())
            drawCircle(
                color = (if (connected) colors.accent else colors.tertiary).copy(alpha = 0.45f),
                radius = if (index % 2 == 0) 1.5.dp.toPx() else 1.25.dp.toPx(),
                center = Offset(
                    center.x + kotlin.math.cos(angle).toFloat() * radius,
                    center.y + kotlin.math.sin(angle).toFloat() * radius,
                )
            )
        }
    }
}

@Composable
private fun Starfield(modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "stars")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier) {
        listOf(
            Triple(0.03f, 0.08f, 0.5f),
            Triple(0.19f, 0.68f, 0.4f),
            Triple(0.32f, 0.44f, 0.35f),
            Triple(0.54f, 0.58f, 0.45f),
            Triple(0.76f, 0.72f, 0.35f),
            Triple(0.91f, 0.48f, 0.4f),
        ).forEach { (x, y, alpha) ->
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = 0.9.dp.toPx(),
                center = Offset(size.width * x, size.height * y),
            )
        }
        repeat(8) { index ->
            val x = size.width * ((index * 0.13f + 0.11f) % 1f)
            val y = size.height * ((phase + index * 0.17f) % 1f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.35f),
                topLeft = Offset(x, y),
                size = Size(1.2.dp.toPx(), 14.dp.toPx()),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

@Immutable
private data class PreviewProfile(
    val id: Long,
    val name: String,
    val protocol: String,
    val active: Boolean,
)

private data class PreviewColors(
    val background: Color,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val border: Color,
    val divider: Color,
    val accent: Color,
    val orbCenter: Color,
)

private val LightColors = PreviewColors(
    background = Color(0xFFF3F3F7),
    primary = Color(0xFF111117),
    secondary = Color(0xFF6C6C74),
    tertiary = Color(0xFFA3A3AB),
    border = Color(0x14000000),
    divider = Color(0x0D000000),
    accent = Color(0xFFE55A1B),
    orbCenter = Color.White,
)

private val SpaceColors = PreviewColors(
    background = Color(0xFF0A0A18),
    primary = Color(0xFFE8E8F0),
    secondary = Color(0xFF8888A0),
    tertiary = Color(0xFF8888A0),
    border = Color(0x1AFFFFFF),
    divider = Color(0x1AFFFFFF),
    accent = Color(0xFF28B44F),
    orbCenter = Color(0xFF1C1C34),
)

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
