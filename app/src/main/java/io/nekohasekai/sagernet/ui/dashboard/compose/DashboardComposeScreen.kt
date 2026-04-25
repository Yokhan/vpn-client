package io.nekohasekai.sagernet.ui.dashboard.compose

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yokhan.vpn.shared.domain.ConnectionStatus
import com.yokhan.vpn.shared.domain.DashboardState
import com.yokhan.vpn.shared.domain.LatencyDisplay
import com.yokhan.vpn.shared.domain.RateDisplay
import com.yokhan.vpn.shared.domain.VpnProtocol
import io.nekohasekai.sagernet.R

@Immutable
data class DashboardProfileUiItem(
    val id: Long,
    val name: String,
    val protocol: String,
    val isActive: Boolean,
)

@Immutable
data class DashboardComposeActions(
    val onMenuClick: () -> Unit = {},
    val onToggleConnection: () -> Unit = {},
    val onRunConnectionTest: () -> Unit = {},
    val onAddProfile: () -> Unit = {},
    val onSelectProfile: (Long) -> Unit = {},
    val onEditProfile: (Long) -> Unit = {},
    val onOpenProfileMenu: (Long) -> Unit = {},
)

@Composable
fun DashboardComposeScreen(
    state: DashboardState,
    profiles: List<DashboardProfileUiItem>,
    actions: DashboardComposeActions,
    modifier: Modifier = Modifier,
) {
    val connected = state.connectionStatus == ConnectionStatus.Connected
    val running = state.shouldAnimateSpace
    val colors = if (running) DashboardSpaceColors else DashboardLightColors

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
            DashboardToolbar(colors, actions)
            CurrentConfigCard(state, colors)
            LaunchPanel(state, connected, running, colors, actions)
            ConnectionStatusRow(state, connected, colors)
            ConnectionTestButton(state, colors, actions)
            ProfilesCard(profiles, colors, actions)
            Spacer(Modifier.height(160.dp))
        }

        DashboardStatsBar(
            state = state,
            connected = connected,
            colors = colors,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun DashboardToolbar(
    colors: DashboardColors,
    actions: DashboardComposeActions,
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
            text = stringResource(R.string.dashboard_title),
            color = colors.primary,
            fontFamily = golosFamily(),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.dashboard_system_label),
                color = colors.secondary,
                fontFamily = monoFamily(),
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
                text = stringResource(R.string.dashboard_current_config),
                colors = colors,
                fontSize = 10,
            )
            Text(
                text = state.activeProfileName,
                color = colors.primary,
                fontFamily = golosFamily(),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = state.activeProfileSubtitle,
                color = colors.secondary,
                fontFamily = monoFamily(),
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
    actions: DashboardComposeActions,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SpeedPanel(
            label = stringResource(R.string.dashboard_upload_label),
            value = state.uploadLabel,
            arrow = "^",
            colors = colors,
            alignEnd = false,
        )
        OrbScene(
            connected = connected,
            running = running,
            colors = colors,
            onClick = actions.onToggleConnection,
        )
        SpeedPanel(
            label = stringResource(R.string.dashboard_download_label),
            value = state.downloadLabel,
            arrow = "v",
            colors = colors,
            alignEnd = true,
        )
    }
}

@Composable
private fun SpeedPanel(
    label: String,
    value: String,
    arrow: String,
    colors: DashboardColors,
    alignEnd: Boolean,
) {
    Column(
        modifier = Modifier.width(80.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = label,
            color = colors.tertiary,
            fontFamily = monoFamily(),
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
                fontFamily = monoFamily(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = arrow,
                color = colors.accent,
                fontFamily = monoFamily(),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        WaveLine(
            colors = colors,
            modifier = Modifier
                .padding(top = 4.dp)
                .width(56.dp)
                .height(8.dp)
        )
    }
}

@Composable
private fun OrbScene(
    connected: Boolean,
    running: Boolean,
    colors: DashboardColors,
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
                .background(
                    Brush.radialGradient(
                        listOf(colors.orbCenter, colors.background),
                    )
                )
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
                text = stringResource(R.string.dashboard_orb_idle),
                color = colors.secondary,
                fontFamily = monoFamily(),
                fontSize = 12.sp,
                letterSpacing = 1.2.sp,
            )
        }
        if (running) {
            Image(
                painter = painterResource(R.drawable.ic_dashboard_spaceship_flame),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(width = 22.dp, height = 98.dp)
                    .offset(x = (-10).dp, y = 116.dp)
                    .scale(scaleX = 1f, scaleY = flameScale),
            )
            Image(
                painter = painterResource(R.drawable.ic_dashboard_spaceship_flame),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(width = 22.dp, height = 98.dp)
                    .offset(x = 8.dp, y = 116.dp)
                    .scale(scaleX = 1f, scaleY = flameScale),
            )
            Image(
                painter = painterResource(R.drawable.ic_dashboard_spaceship),
                contentDescription = stringResource(R.string.dashboard_connect_action),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 132.dp, height = 228.dp),
            )
        }
    }
}

@Composable
private fun ConnectionStatusRow(
    state: DashboardState,
    connected: Boolean,
    colors: DashboardColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when (state.connectionStatus) {
                ConnectionStatus.Connected -> stringResource(R.string.dashboard_connected_status)
                ConnectionStatus.Connecting -> stringResource(R.string.dashboard_state_connecting)
                ConnectionStatus.Disconnecting -> stringResource(R.string.dashboard_state_stopping)
                else -> stringResource(R.string.dashboard_tap_connect)
            },
            color = if (connected) colors.primary else colors.tertiary,
            fontFamily = monoFamily(),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        if (connected) {
            Text(
                text = state.timerLabel,
                color = colors.primary,
                fontFamily = monoFamily(),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ConnectionTestButton(
    state: DashboardState,
    colors: DashboardColors,
    actions: DashboardComposeActions,
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
        Text(
            text = "⊙",
            color = colors.secondary,
            fontFamily = monoFamily(),
            fontSize = 14.sp,
        )
        Text(
            text = stringResource(R.string.dashboard_check_connection),
            color = colors.secondary,
            fontFamily = golosFamily(),
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
    actions: DashboardComposeActions,
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
                text = stringResource(R.string.dashboard_configurations),
                colors = colors,
                fontSize = 12,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.dashboard_add),
                color = DashboardAccent,
                fontFamily = monoFamily(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = actions.onAddProfile),
            )
        }
        profiles.forEachIndexed { index, item ->
            ProfileRow(
                item = item,
                showDivider = index != 0,
                colors = colors,
                actions = actions,
            )
        }
    }
}

@Composable
private fun ProfileRow(
    item: DashboardProfileUiItem,
    showDivider: Boolean,
    colors: DashboardColors,
    actions: DashboardComposeActions,
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
                    fontFamily = monoFamily(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.protocol,
                    color = colors.tertiary,
                    fontFamily = monoFamily(),
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
        StatsSegment("◷", state.latencyLabel, stringResource(R.string.dashboard_latency_label), colors)
        StatsDivider(colors)
        StatsSegment(
            "●",
            if (connected) stringResource(R.string.dashboard_connected_status)
            else stringResource(R.string.dashboard_disconnected_status),
            stringResource(R.string.dashboard_status_label),
            colors.copy(primary = if (connected) DashboardGreen else DashboardRed),
        )
        StatsDivider(colors)
        StatsSegment("◇", state.activeProfileProtocol.name.uppercase(), stringResource(R.string.dashboard_protocol_label), colors)
    }
}

@Composable
private fun RowScope.StatsSegment(
    icon: String,
    value: String,
    label: String,
    colors: DashboardColors,
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
            Text(icon, color = colors.tertiary, fontFamily = monoFamily(), fontSize = 12.sp)
            Text(
                text = value,
                color = colors.primary,
                fontFamily = monoFamily(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Text(
            text = label,
            color = colors.tertiary,
            fontFamily = monoFamily(),
            fontSize = 10.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun StatsDivider(colors: DashboardColors) {
    Box(
        Modifier
            .width(1.dp)
            .height(34.dp)
            .background(colors.divider)
    )
}

@Composable
private fun SectionLabel(
    text: String,
    colors: DashboardColors,
    fontSize: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(colors.tertiary)
        )
        Text(
            text = text,
            color = colors.tertiary,
            fontFamily = monoFamily(),
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
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
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
        val pathWidth = size.width / 8f
        for (i in 0 until 8) {
            val startX = i * pathWidth
            drawLine(
                color = colors.accent.copy(alpha = 0.25f),
                start = Offset(startX, size.height / 2f),
                end = Offset(startX + pathWidth / 2f, size.height / 4f),
                strokeWidth = 1.1.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = colors.accent.copy(alpha = 0.25f),
                start = Offset(startX + pathWidth / 2f, size.height / 4f),
                end = Offset(startX + pathWidth, size.height / 2f),
                strokeWidth = 1.1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun OrbMarks(
    connected: Boolean,
    colors: DashboardColors,
    modifier: Modifier = Modifier,
) {
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
        val specs = listOf(
            Triple(0.03f, 0.08f, 0.5f),
            Triple(0.19f, 0.68f, 0.4f),
            Triple(0.32f, 0.44f, 0.35f),
            Triple(0.54f, 0.58f, 0.45f),
            Triple(0.76f, 0.72f, 0.35f),
            Triple(0.91f, 0.48f, 0.4f),
        )
        specs.forEach { (x, y, alpha) ->
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
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
            )
        }
    }
}

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
    orbCenter = Color(0xFFFFFFFF),
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

@Composable
private fun monoFamily(): FontFamily = FontFamily(Font(R.font.jetbrains_mono))

@Composable
private fun golosFamily(): FontFamily = FontFamily(Font(R.font.golos_text))

private fun Modifier.alignCenterHorizontally(): Modifier = this.then(
    Modifier.fillMaxWidth(0.6f)
)

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
