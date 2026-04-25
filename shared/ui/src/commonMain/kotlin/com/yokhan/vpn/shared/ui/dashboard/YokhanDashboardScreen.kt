package com.yokhan.vpn.shared.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yokhan.vpn.shared.domain.ConnectionStatus
import com.yokhan.vpn.shared.domain.DashboardState
import nb4a.shared_ui.generated.resources.Res
import nb4a.shared_ui.generated.resources.golos_text
import nb4a.shared_ui.generated.resources.ic_dashboard_spaceship
import nb4a.shared_ui.generated.resources.ic_dashboard_spaceship_flame
import nb4a.shared_ui.generated.resources.jetbrains_mono_regular
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.painterResource

@Immutable
data class DashboardProfileUiItem(
    val id: Long,
    val name: String,
    val protocol: String,
    val isActive: Boolean,
)

@Immutable
data class DashboardUiActions(
    val onMenuClick: () -> Unit = {},
    val onToggleConnection: () -> Unit = {},
    val onRunConnectionTest: () -> Unit = {},
    val onAddProfile: () -> Unit = {},
    val onSelectProfile: (Long) -> Unit = {},
    val onEditProfile: (Long) -> Unit = {},
    val onOpenProfileMenu: (Long) -> Unit = {},
)

@Immutable
data class DashboardUiLabels(
    val title: String,
    val system: String,
    val currentConfig: String,
    val upload: String,
    val download: String,
    val orbIdle: String,
    val connectAction: String,
    val connected: String,
    val disconnected: String,
    val connecting: String,
    val disconnecting: String,
    val tapConnect: String,
    val checkConnection: String,
    val configurations: String,
    val add: String,
    val latency: String,
    val status: String,
    val protocol: String,
) {
    companion object {
        val Russian = DashboardUiLabels(
            title = "Панель управления",
            system = "СИСТЕМА · 01",
            currentConfig = "ТЕКУЩАЯ КОНФИГУРАЦИЯ",
            upload = "ОТПРАВКА",
            download = "ПОЛУЧЕНИЕ",
            orbIdle = "ПУСК",
            connectAction = "ПОДКЛЮЧИТЬСЯ",
            connected = "ПОДКЛЮЧЕНО",
            disconnected = "ОТКЛЮЧЕНО",
            connecting = "ПОДКЛЮЧЕНИЕ",
            disconnecting = "ОСТАНОВКА",
            tapConnect = "Нажмите для подключения",
            checkConnection = "ПРОВЕРИТЬ СОЕДИНЕНИЕ",
            configurations = "КОНФИГУРАЦИИ",
            add = "+ ДОБАВИТЬ",
            latency = "ЗАДЕРЖКА",
            status = "СТАТУС",
            protocol = "ПРОТОКОЛ",
        )
    }
}

@Composable
fun YokhanDashboardScreen(
    state: DashboardState,
    profiles: List<DashboardProfileUiItem>,
    labels: DashboardUiLabels,
    actions: DashboardUiActions,
    modifier: Modifier = Modifier,
) {
    val connected = state.connectionStatus == ConnectionStatus.Connected
    val running = state.shouldAnimateSpace
    val colors = if (running) DashboardSpaceColors else DashboardLightColors
    val typography = dashboardTypography()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        if (running) {
            Starfield(modifier = Modifier.fillMaxSize())
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 104.dp),
        ) {
            DashboardToolbar(colors, labels, typography, actions)
            CurrentConfigCard(state, colors, labels, typography)
            LaunchPanel(state, connected, running, colors, labels, typography, actions)
            ConnectionStatusRow(state, connected, colors, labels, typography)
            ConnectionTestButton(state, colors, labels, typography, actions)
            ProfilesCard(profiles, colors, labels, typography, actions)
            Spacer(Modifier.height(160.dp))
        }

        DashboardStatsBar(
            state = state,
            connected = connected,
            colors = colors,
            labels = labels,
            typography = typography,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Immutable
private data class DashboardTypography(
    val text: FontFamily,
    val mono: FontFamily,
)

@Composable
private fun dashboardTypography(): DashboardTypography {
    return DashboardTypography(
        text = FontFamily(Font(Res.font.golos_text)),
        mono = FontFamily(Font(Res.font.jetbrains_mono_regular)),
    )
}

@Composable
private fun DashboardToolbar(
    colors: DashboardColors,
    labels: DashboardUiLabels,
    typography: DashboardTypography,
    actions: DashboardUiActions,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .width(40.dp)
                .clickable(onClick = actions.onMenuClick),
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
            text = labels.title,
            color = colors.primary,
            fontFamily = typography.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = labels.system,
                color = colors.secondary,
                fontFamily = typography.mono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
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
private fun CurrentConfigCard(
    state: DashboardState,
    colors: DashboardColors,
    labels: DashboardUiLabels,
    typography: DashboardTypography,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            SectionLabel(
                text = labels.currentConfig,
                colors = colors,
                typography = typography,
                fontSize = 10,
            )
            Text(
                text = state.activeProfileName,
                color = colors.primary,
                fontFamily = typography.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = state.activeProfileSubtitle,
                color = colors.secondary,
                fontFamily = typography.mono,
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
private fun LaunchPanel(
    state: DashboardState,
    connected: Boolean,
    running: Boolean,
    colors: DashboardColors,
    labels: DashboardUiLabels,
    typography: DashboardTypography,
    actions: DashboardUiActions,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SpeedPanel(labels.upload, state.uploadLabel, "↑", colors, typography, false)
        OrbScene(connected, running, colors, labels, typography, actions.onToggleConnection)
        SpeedPanel(labels.download, state.downloadLabel, "↓", colors, typography, true)
    }
}

@Composable
private fun SpeedPanel(
    label: String,
    value: String,
    arrow: String,
    colors: DashboardColors,
    typography: DashboardTypography,
    alignEnd: Boolean,
) {
    Column(
        modifier = Modifier.width(80.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = label,
            color = colors.tertiary,
            fontFamily = typography.mono,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                color = colors.primary,
                fontFamily = typography.mono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = arrow,
                color = colors.accent,
                fontFamily = typography.mono,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        WaveLine(
            colors = colors,
            modifier = Modifier
                .padding(top = 4.dp)
                .width(56.dp)
                .height(8.dp),
        )
    }
}

@Composable
private fun OrbScene(
    connected: Boolean,
    running: Boolean,
    colors: DashboardColors,
    labels: DashboardUiLabels,
    typography: DashboardTypography,
    onClick: () -> Unit,
) {
    val infinite = rememberInfiniteTransition(label = "dashboard-orb")
    val ringScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (connected) 1.04f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ring-scale",
    )
    val flameScale by infinite.animateFloat(
        initialValue = 0.96f,
        targetValue = if (connected) 1.12f else 0.96f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "flame-scale",
    )

    Box(
        modifier = Modifier
            .size(192.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(colors.orbCenter, colors.background)))
        )
        Canvas(Modifier.size(176.dp).scale(ringScale)) {
            drawCircle(
                color = colors.accent.copy(alpha = if (connected) 0.35f else 0.18f),
                style = Stroke(width = 1.dp.toPx())
            )
        }
        OrbMarks(connected = connected, colors = colors, modifier = Modifier.size(192.dp))
        if (!running) {
            Text(
                text = labels.orbIdle,
                color = colors.secondary,
                fontFamily = typography.mono,
                fontSize = 12.sp,
                letterSpacing = 1.2.sp,
            )
        }
        if (running) {
            DashboardRocket(flameScale)
        }
    }
}

@Composable
private fun ConnectionStatusRow(
    state: DashboardState,
    connected: Boolean,
    colors: DashboardColors,
    labels: DashboardUiLabels,
    typography: DashboardTypography,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when (state.connectionStatus) {
                ConnectionStatus.Connected -> labels.connected
                ConnectionStatus.Connecting -> labels.connecting
                ConnectionStatus.Disconnecting -> labels.disconnecting
                else -> labels.tapConnect
            },
            color = if (connected) colors.primary else colors.tertiary,
            fontFamily = typography.mono,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        if (connected) {
            Text(
                text = state.timerLabel,
                color = colors.primary,
                fontFamily = typography.mono,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ConnectionTestButton(
    state: DashboardState,
    colors: DashboardColors,
    labels: DashboardUiLabels,
    typography: DashboardTypography,
    actions: DashboardUiActions,
) {
    Row(
        modifier = Modifier
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, colors.border, RoundedCornerShape(20.dp))
            .clickable(enabled = state.canRunConnectionTest, onClick = actions.onRunConnectionTest)
            .alpha(if (state.canRunConnectionTest) 1f else 0.55f)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .alignCenterHorizontally(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⊙", color = colors.secondary, fontFamily = typography.mono, fontSize = 14.sp)
        Text(
            text = labels.checkConnection,
            color = colors.secondary,
            fontFamily = typography.text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ProfilesCard(
    profiles: List<DashboardProfileUiItem>,
    colors: DashboardColors,
    labels: DashboardUiLabels,
    typography: DashboardTypography,
    actions: DashboardUiActions,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(
                text = labels.configurations,
                colors = colors,
                typography = typography,
                fontSize = 12,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = labels.add,
                color = DashboardAccent,
                fontFamily = typography.mono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = actions.onAddProfile),
            )
        }
        profiles.forEachIndexed { index, item ->
            ProfileRow(item, index != 0, colors, typography, actions)
        }
    }
}

@Composable
private fun ProfileRow(
    item: DashboardProfileUiItem,
    showDivider: Boolean,
    colors: DashboardColors,
    typography: DashboardTypography,
    actions: DashboardUiActions,
) {
    Column {
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.divider)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { actions.onSelectProfile(item.id) }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(2.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (item.isActive) colors.accent else Color.Transparent)
            )
            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f),
            ) {
                Text(
                    text = item.name,
                    color = colors.primary,
                    fontFamily = typography.mono,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.protocol,
                    color = colors.tertiary,
                    fontFamily = typography.mono,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                text = "✎",
                color = colors.secondary,
                fontSize = 22.sp,
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .clickable { actions.onEditProfile(item.id) },
            )
            Text(
                text = "⋮",
                color = colors.secondary,
                fontSize = 24.sp,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clickable { actions.onOpenProfileMenu(item.id) },
            )
        }
    }
}

@Composable
private fun DashboardStatsBar(
    state: DashboardState,
    connected: Boolean,
    colors: DashboardColors,
    labels: DashboardUiLabels,
    typography: DashboardTypography,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.border.copy(alpha = 0.8f))
            .background(Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatsSegment("◷", state.latencyLabel, labels.latency, colors, typography)
        StatsDivider(colors)
        StatsSegment(
            "●",
            if (connected) labels.connected else labels.disconnected,
            labels.status,
            colors.copy(primary = if (connected) DashboardGreen else DashboardRed),
            typography,
        )
        StatsDivider(colors)
        StatsSegment("◇", state.activeProfileProtocol.name.uppercase(), labels.protocol, colors, typography)
    }
}

@Composable
private fun RowScope.StatsSegment(
    icon: String,
    value: String,
    label: String,
    colors: DashboardColors,
    typography: DashboardTypography,
) {
    Column(
        modifier = Modifier
            .height(42.dp)
            .padding(horizontal = 6.dp)
            .weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = colors.tertiary, fontFamily = typography.mono, fontSize = 12.sp)
            Text(
                text = value,
                color = colors.primary,
                fontFamily = typography.mono,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Text(
            text = label,
            color = colors.tertiary,
            fontFamily = typography.mono,
            fontSize = 10.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun StatsDivider(colors: DashboardColors) {
    Box(Modifier.width(1.dp).height(34.dp).background(colors.divider))
}

@Composable
private fun SectionLabel(
    text: String,
    colors: DashboardColors,
    typography: DashboardTypography,
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
            fontFamily = typography.mono,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun SignalBox(colors: DashboardColors) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
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
private fun WaveLine(colors: DashboardColors, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val segment = size.width / 8f
        repeat(8) { index ->
            val x = index * segment
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
private fun OrbMarks(connected: Boolean, colors: DashboardColors, modifier: Modifier = Modifier) {
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
    val infinite = rememberInfiniteTransition(label = "starfield")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "stars",
    )
    Canvas(modifier) {
        val fixedStars = listOf(
            Triple(0.03f, 0.08f, 0.5f),
            Triple(0.19f, 0.68f, 0.4f),
            Triple(0.32f, 0.44f, 0.35f),
            Triple(0.54f, 0.58f, 0.45f),
            Triple(0.76f, 0.72f, 0.35f),
            Triple(0.91f, 0.48f, 0.4f),
        )
        fixedStars.forEach { (x, y, alpha) ->
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

@Composable
private fun DashboardRocket(flameScale: Float) {
    Image(
        painter = painterResource(Res.drawable.ic_dashboard_spaceship_flame),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(width = 22.dp, height = 98.dp)
            .offset(x = (-10).dp, y = 116.dp)
            .scale(scaleX = 1f, scaleY = flameScale),
    )
    Image(
        painter = painterResource(Res.drawable.ic_dashboard_spaceship_flame),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(width = 22.dp, height = 98.dp)
            .offset(x = 8.dp, y = 116.dp)
            .scale(scaleX = 1f, scaleY = flameScale),
    )
    Image(
        painter = painterResource(Res.drawable.ic_dashboard_spaceship),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(width = 132.dp, height = 228.dp),
    )
}

@Immutable
private data class DashboardColors(
    val background: Color,
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val border: Color,
    val divider: Color,
    val accent: Color,
    val orbCenter: Color,
)

private val DashboardAccent = Color(0xFFE55A1B)
private val DashboardGreen = Color(0xFF28B44F)
private val DashboardRed = Color(0xFFE53E3E)

private val DashboardLightColors = DashboardColors(
    background = Color(0xFFF3F3F7),
    primary = Color(0xFF111117),
    secondary = Color(0xFF6C6C74),
    tertiary = Color(0xFFA3A3AB),
    border = Color(0x14000000),
    divider = Color(0x0D000000),
    accent = DashboardAccent,
    orbCenter = Color.White,
)

private val DashboardSpaceColors = DashboardColors(
    background = Color(0xFF0A0A18),
    primary = Color(0xFFE8E8F0),
    secondary = Color(0xFF8888A0),
    tertiary = Color(0xFF8888A0),
    border = Color(0x1AFFFFFF),
    divider = Color(0x1AFFFFFF),
    accent = DashboardGreen,
    orbCenter = Color(0xFF1C1C34),
)

private fun Modifier.alignCenterHorizontally(): Modifier = this.then(Modifier.fillMaxWidth(0.6f))
