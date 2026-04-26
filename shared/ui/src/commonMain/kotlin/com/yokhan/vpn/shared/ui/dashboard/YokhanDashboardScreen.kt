package com.yokhan.vpn.shared.ui.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yokhan.vpn.shared.domain.ConnectionStatus
import com.yokhan.vpn.shared.domain.DashboardState
import com.yokhan.vpn.shared.ui.design.YokhanAccent
import com.yokhan.vpn.shared.ui.design.YokhanGreen
import com.yokhan.vpn.shared.ui.design.YokhanMotion
import com.yokhan.vpn.shared.ui.design.YokhanPalette
import com.yokhan.vpn.shared.ui.design.YokhanRed
import com.yokhan.vpn.shared.ui.design.YokhanTypography
import com.yokhan.vpn.shared.ui.design.yokhanPalette
import com.yokhan.vpn.shared.ui.design.yokhanTypography
import nb4a.shared_ui.generated.resources.Res
import nb4a.shared_ui.generated.resources.ic_dashboard_spaceship
import nb4a.shared_ui.generated.resources.ic_dashboard_spaceship_flame
import org.jetbrains.compose.resources.painterResource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
data class DashboardUiTuning(
    val contentBottomPadding: Int = 120,
    val testButtonTopPadding: Int = 12,
    val statsBarBottomPadding: Int = 22,
    val rocketWidth: Int = 132,
    val rocketHeight: Int = 228,
    val flameWidth: Int = 22,
    val flameHeight: Int = 75,
    val leftFlameX: Int = -10,
    val rightFlameX: Int = 8,
    val flameY: Int = 117,
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

private typealias DashboardColors = YokhanPalette
private typealias DashboardTypography = YokhanTypography

@Composable
fun YokhanDashboardScreen(
    state: DashboardState,
    profiles: List<DashboardProfileUiItem>,
    labels: DashboardUiLabels,
    actions: DashboardUiActions,
    modifier: Modifier = Modifier,
    tuning: DashboardUiTuning = DashboardUiTuning(),
) {
    val connected = state.connectionStatus == ConnectionStatus.Connected
    var visualSpaceActive by remember { mutableStateOf(state.shouldAnimateSpace) }
    var starfieldMounted by remember { mutableStateOf(state.shouldAnimateSpace) }
    LaunchedEffect(state.connectionStatus, state.shouldAnimateSpace) {
        if (state.shouldAnimateSpace || state.connectionStatus == ConnectionStatus.Disconnecting) {
            visualSpaceActive = true
            starfieldMounted = true
        } else if (visualSpaceActive) {
            delay(1400)
            visualSpaceActive = false
            delay(1200)
            starfieldMounted = false
        } else {
            starfieldMounted = false
        }
    }
    val targetColors = yokhanPalette(visualSpaceActive)
    val colors = DashboardColors(
        background = animateColorAsState(
            targetValue = targetColors.background,
            animationSpec = tween(600, easing = CssEase),
            label = "dashboard-background",
        ).value,
        primary = animateColorAsState(
            targetValue = targetColors.primary,
            animationSpec = tween(400, easing = CssEase),
            label = "dashboard-primary",
        ).value,
        secondary = animateColorAsState(
            targetValue = targetColors.secondary,
            animationSpec = tween(400, easing = CssEase),
            label = "dashboard-secondary",
        ).value,
        tertiary = animateColorAsState(
            targetValue = targetColors.tertiary,
            animationSpec = tween(400, easing = CssEase),
            label = "dashboard-tertiary",
        ).value,
        border = animateColorAsState(
            targetValue = targetColors.border,
            animationSpec = tween(400, easing = CssEase),
            label = "dashboard-border",
        ).value,
        divider = animateColorAsState(
            targetValue = targetColors.divider,
            animationSpec = tween(400, easing = CssEase),
            label = "dashboard-divider",
        ).value,
        accent = animateColorAsState(
            targetValue = targetColors.accent,
            animationSpec = tween(400, easing = CssEase),
            label = "dashboard-accent",
        ).value,
        orbCenter = animateColorAsState(
            targetValue = targetColors.orbCenter,
            animationSpec = tween(400, easing = CssEase),
            label = "dashboard-orb-center",
        ).value,
    )
    val spaceLayerAlpha by animateFloatAsState(
        targetValue = if (visualSpaceActive) 1f else 0f,
        animationSpec = tween(1200, easing = CssEaseIn),
        label = "dashboard-space-layer",
    )
    val typography = dashboardTypography()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        if (starfieldMounted) {
            Starfield(modifier = Modifier.fillMaxSize().alpha(spaceLayerAlpha))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = tuning.contentBottomPadding.dp),
        ) {
            DashboardToolbar(colors, labels, typography, actions)
            CurrentConfigCard(state, colors, labels, typography)
            LaunchPanel(state, connected, visualSpaceActive, colors, labels, typography, actions, tuning)
            ConnectionStatusRow(state, connected, colors, labels, typography)
            ConnectionTestButton(state, colors, labels, typography, actions, tuning)
            ProfilesCard(profiles, colors, labels, typography, actions)
            Spacer(Modifier.height(160.dp))
        }

        DashboardStatsBar(
            state = state,
            connected = connected,
            colors = colors,
            labels = labels,
            typography = typography,
            tuning = tuning,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun dashboardTypography(): DashboardTypography {
    return yokhanTypography()
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
                lineHeight = 12.sp,
            )
            Box(
                Modifier
                    .padding(start = 2.dp)
                    .offset(y = (-1).dp)
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
    tuning: DashboardUiTuning,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SpeedPanel(labels.upload, state.uploadLabel, "↑", colors, typography, false)
        OrbScene(state.connectionStatus, connected, running, colors, labels, typography, actions.onToggleConnection, tuning)
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
    connectionStatus: ConnectionStatus,
    connected: Boolean,
    running: Boolean,
    colors: DashboardColors,
    labels: DashboardUiLabels,
    typography: DashboardTypography,
    onClick: () -> Unit,
    tuning: DashboardUiTuning,
) {
    val infinite = rememberInfiniteTransition(label = "dashboard-orb")
    val ringScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (connected) 1.04f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = CssEaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ring-scale",
    )
    val flameScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (connected) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = CssEaseInOut),
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
        DashboardRocket(connectionStatus, flameScale, tuning)
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
    val statusText = when (state.connectionStatus) {
        ConnectionStatus.Connected -> labels.connected
        ConnectionStatus.Connecting -> labels.connecting
        ConnectionStatus.Disconnecting -> labels.disconnecting
        else -> labels.tapConnect
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (connected) {
            Text(
                text = statusText,
                color = colors.primary,
                fontFamily = typography.mono,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = state.timerLabel,
                color = colors.primary,
                fontFamily = typography.mono,
                fontSize = 12.sp,
            )
        } else {
            Text(
                text = statusText,
                color = colors.tertiary,
                fontFamily = typography.mono,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
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
    tuning: DashboardUiTuning,
) {
    Box(
        modifier = Modifier
            .padding(top = tuning.testButtonTopPadding.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .shadow(2.dp, RoundedCornerShape(20.dp), clip = false)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            colors.primary.copy(alpha = if (state.canRunConnectionTest) 0.055f else 0.028f),
                            colors.background.copy(alpha = 0.08f),
                        )
                    )
                )
                .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                .clickable(onClick = actions.onRunConnectionTest)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .border(1.dp, colors.secondary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(colors.secondary)
                )
            }
            Text(
                text = labels.checkConnection,
                color = colors.secondary,
                fontFamily = typography.text,
                fontSize = 10.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
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
    tuning: DashboardUiTuning,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
    ) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
                .padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = tuning.statsBarBottomPadding.dp),
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
            .padding(horizontal = 6.dp)
            .weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = icon,
                color = colors.tertiary,
                fontFamily = typography.mono,
                fontSize = 12.sp,
                lineHeight = 12.sp,
            )
            Text(
                text = value,
                color = colors.primary,
                fontFamily = typography.mono,
                fontSize = 10.sp,
                lineHeight = 12.sp,
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
            lineHeight = 12.sp,
            maxLines = 1,
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
                .offset(y = (-0.5f).dp)
                .size(6.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(colors.tertiary)
        )
        Text(
            text = text,
            color = colors.tertiary,
            fontFamily = typography.mono,
            fontSize = fontSize.sp,
            lineHeight = fontSize.sp,
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
            animation = tween(16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "stars",
    )
    Canvas(modifier) {
        staticStars.forEach { star ->
            drawCircle(
                color = Color.White.copy(alpha = star.alpha),
                radius = star.radius.dp.toPx(),
                center = Offset(size.width * star.x, size.height * star.y),
            )
        }
        val elapsed = phase * 16f
        movingStars.forEach { star ->
            val progress = ((elapsed + star.initialOffsetSeconds) / star.durationSeconds) % 1f
            val y = size.height * progress * 1.2f
            if (star.streak) {
                drawRoundRect(
                    color = Color.White.copy(alpha = star.alpha),
                    topLeft = Offset(size.width * star.x, y),
                    size = Size(star.width.dp.toPx(), star.height.dp.toPx()),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
            } else {
                drawCircle(
                    color = Color.White.copy(alpha = star.alpha),
                    radius = (star.width / 2f).dp.toPx(),
                    center = Offset(size.width * star.x, y),
                )
            }
        }
    }
}

@Composable
private fun DashboardRocket(
    connectionStatus: ConnectionStatus,
    flameScale: Float,
    tuning: DashboardUiTuning,
) {
    val initiallyVisible = connectionStatus == ConnectionStatus.Connecting ||
        connectionStatus == ConnectionStatus.Connected ||
        connectionStatus == ConnectionStatus.Disconnecting
    var visible by remember { mutableStateOf(initiallyVisible) }
    val yOffset = remember { Animatable(if (initiallyVisible) 0f else 1.15f) }
    val alpha = remember { Animatable(if (initiallyVisible) 1f else 0f) }

    LaunchedEffect(connectionStatus) {
        val shouldDock = connectionStatus == ConnectionStatus.Connecting ||
            connectionStatus == ConnectionStatus.Connected
        if (shouldDock) {
            visible = true
            if (yOffset.value < -1f || alpha.value == 0f) {
                yOffset.snapTo(1.15f)
            }
            coroutineScope {
                launch { alpha.animateTo(1f, tween(400, easing = CssEaseOut)) }
                launch { yOffset.animateTo(0f, tween(1400, easing = RocketLaunchEasing)) }
            }
        } else if (visible) {
            coroutineScope {
                launch { alpha.animateTo(0f, tween(800, easing = CssEaseIn)) }
                launch { yOffset.animateTo(-1.25f, tween(1400, easing = RocketDisconnectEasing)) }
            }
            visible = false
            yOffset.snapTo(1.15f)
        }
    }

    if (!visible) return

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset(y = (tuning.rocketHeight * yOffset.value).dp)
            .alpha(alpha.value),
    ) {
    Image(
        painter = painterResource(Res.drawable.ic_dashboard_spaceship),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(width = tuning.rocketWidth.dp, height = tuning.rocketHeight.dp),
    )
    Image(
        painter = painterResource(Res.drawable.ic_dashboard_spaceship_flame),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(width = tuning.flameWidth.dp, height = tuning.flameHeight.dp)
            .offset(x = tuning.leftFlameX.dp, y = tuning.flameY.dp)
            .graphicsLayer {
                scaleY = flameScale
                scaleX = 1f + (flameScale - 1f) * 0.4f
                transformOrigin = TransformOrigin(0.5f, 0f)
            },
    )
    Image(
        painter = painterResource(Res.drawable.ic_dashboard_spaceship_flame),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(width = tuning.flameWidth.dp, height = tuning.flameHeight.dp)
            .offset(x = tuning.rightFlameX.dp, y = tuning.flameY.dp)
            .graphicsLayer {
                scaleY = flameScale
                scaleX = 1f + (flameScale - 1f) * 0.4f
                transformOrigin = TransformOrigin(0.5f, 0f)
            },
    )
    }
}

@Immutable
private data class StaticStar(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float,
)

@Immutable
private data class MovingStar(
    val x: Float,
    val width: Float,
    val height: Float,
    val alpha: Float,
    val durationSeconds: Float,
    val initialOffsetSeconds: Float,
    val streak: Boolean,
)

private val staticStars = listOf(
    StaticStar(0.03f, 0.08f, 1f, 0.5f),
    StaticStar(0.07f, 0.42f, 1f, 0.4f),
    StaticStar(0.12f, 0.18f, 1.5f, 0.7f),
    StaticStar(0.15f, 0.65f, 1f, 0.3f),
    StaticStar(0.18f, 0.88f, 1f, 0.5f),
    StaticStar(0.22f, 0.05f, 1f, 0.4f),
    StaticStar(0.25f, 0.35f, 1.5f, 0.6f),
    StaticStar(0.28f, 0.72f, 1f, 0.3f),
    StaticStar(0.33f, 0.15f, 1f, 0.5f),
    StaticStar(0.36f, 0.55f, 1f, 0.4f),
    StaticStar(0.40f, 0.82f, 1.5f, 0.6f),
    StaticStar(0.44f, 0.28f, 1f, 0.3f),
    StaticStar(0.48f, 0.48f, 1f, 0.5f),
    StaticStar(0.52f, 0.12f, 1f, 0.4f),
    StaticStar(0.55f, 0.62f, 1.5f, 0.7f),
    StaticStar(0.58f, 0.90f, 1f, 0.3f),
    StaticStar(0.62f, 0.22f, 1f, 0.5f),
    StaticStar(0.65f, 0.50f, 1f, 0.4f),
    StaticStar(0.68f, 0.78f, 1.5f, 0.6f),
    StaticStar(0.72f, 0.06f, 1f, 0.3f),
    StaticStar(0.75f, 0.38f, 1f, 0.5f),
    StaticStar(0.78f, 0.68f, 1f, 0.4f),
    StaticStar(0.82f, 0.92f, 1.5f, 0.7f),
    StaticStar(0.85f, 0.25f, 1f, 0.3f),
    StaticStar(0.88f, 0.52f, 1f, 0.5f),
    StaticStar(0.91f, 0.75f, 1f, 0.4f),
    StaticStar(0.94f, 0.10f, 1.5f, 0.6f),
    StaticStar(0.97f, 0.45f, 1f, 0.3f),
    StaticStar(0.05f, 0.95f, 1f, 0.5f),
    StaticStar(0.45f, 0.03f, 1f, 0.4f),
    StaticStar(0.60f, 0.40f, 1f, 0.3f),
    StaticStar(0.35f, 0.95f, 1.5f, 0.6f),
    StaticStar(0.80f, 0.15f, 1f, 0.5f),
    StaticStar(0.50f, 0.75f, 1f, 0.4f),
    StaticStar(0.10f, 0.30f, 1f, 0.3f),
)

private val movingStars = listOf(
    MovingStar(0.02f, 2f, 2f, 0.5f, 9f, 3f, false),
    MovingStar(0.07f, 1f, 1f, 0.4f, 14f, 11f, false),
    MovingStar(0.12f, 3f, 3f, 0.7f, 8f, 1f, false),
    MovingStar(0.17f, 1f, 1f, 0.3f, 16f, 7f, false),
    MovingStar(0.22f, 2f, 2f, 0.6f, 11f, 9f, false),
    MovingStar(0.28f, 1f, 1f, 0.5f, 13f, 4f, false),
    MovingStar(0.33f, 2f, 2f, 0.4f, 10f, 6f, false),
    MovingStar(0.39f, 1f, 1f, 0.6f, 15f, 12f, false),
    MovingStar(0.45f, 3f, 3f, 0.7f, 9.5f, 2f, false),
    MovingStar(0.51f, 1f, 1f, 0.35f, 12f, 8f, false),
    MovingStar(0.57f, 2f, 2f, 0.5f, 14.5f, 10f, false),
    MovingStar(0.63f, 1f, 1f, 0.4f, 10.5f, 5f, false),
    MovingStar(0.69f, 2f, 2f, 0.6f, 8.5f, 7f, false),
    MovingStar(0.75f, 1f, 1f, 0.3f, 16f, 13f, false),
    MovingStar(0.81f, 3f, 3f, 0.65f, 11.5f, 3f, false),
    MovingStar(0.87f, 1f, 1f, 0.45f, 13.5f, 9f, false),
    MovingStar(0.93f, 2f, 2f, 0.5f, 9f, 6f, false),
    MovingStar(0.11f, 1f, 12f, 0.4f, 2.8f, 1.2f, true),
    MovingStar(0.23f, 2f, 18f, 0.35f, 2.2f, 0.5f, true),
    MovingStar(0.41f, 1f, 10f, 0.5f, 3f, 2.1f, true),
    MovingStar(0.55f, 1f, 15f, 0.3f, 2.5f, 1.8f, true),
    MovingStar(0.68f, 2f, 20f, 0.4f, 2f, 0.3f, true),
    MovingStar(0.82f, 1f, 8f, 0.45f, 3.2f, 2.5f, true),
    MovingStar(0.47f, 1f, 14f, 0.35f, 2.6f, 1.4f, true),
    MovingStar(0.88f, 1f, 10f, 0.3f, 2.4f, 0.8f, true),
)

private val DashboardAccent = YokhanAccent
private val DashboardGreen = YokhanGreen
private val DashboardRed = YokhanRed
private val CssEase = YokhanMotion.CssEase
private val CssEaseIn = YokhanMotion.CssEaseIn
private val CssEaseOut = YokhanMotion.CssEaseOut
private val CssEaseInOut = YokhanMotion.CssEaseInOut
private val RocketLaunchEasing = YokhanMotion.RocketLaunch
private val RocketDisconnectEasing = YokhanMotion.RocketDisconnect
