package com.yokhan.gates.windows

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yokhan.gates.shared.ui.navigation.GatesNavigationItem
import com.yokhan.gates.shared.ui.route.YokhanRouteButton
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanRadius
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.YokhanStroke
import com.yokhan.gates.shared.ui.design.YokhanTypography
import com.yokhan.gates.shared.ui.route.YokhanCaption
import com.yokhan.gates.shared.ui.route.YokhanMonoValue
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanStatusPill
import com.yokhan.gates.shared.ui.route.yokhanOutlinedSurface

@Composable
internal fun GatesVerticalScrollbar(
    scrollState: ScrollState,
    colors: YokhanPalette,
    modifier: Modifier = Modifier,
) {
    val style = LocalScrollbarStyle.current.copy(
        unhoverColor = colors.border.copy(alpha = 0.7f),
        hoverColor = colors.accent.copy(alpha = 0.85f),
        thickness = 6.dp,
    )
    CompositionLocalProvider(LocalScrollbarStyle provides style) {
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = modifier.fillMaxHeight().padding(end = 3.dp, top = 8.dp, bottom = 84.dp),
        )
    }
}

@Composable
internal fun GatesLazyVerticalScrollbar(
    listState: LazyListState,
    colors: YokhanPalette,
    modifier: Modifier = Modifier,
) {
    val style = LocalScrollbarStyle.current.copy(
        unhoverColor = colors.border.copy(alpha = 0.7f),
        hoverColor = colors.accent.copy(alpha = 0.85f),
        thickness = 6.dp,
    )
    CompositionLocalProvider(LocalScrollbarStyle provides style) {
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = modifier.fillMaxHeight().padding(end = 3.dp, top = 8.dp, bottom = 84.dp),
        )
    }
}

@Composable
internal fun GatesGlobalDropOverlay(
    visible: Boolean,
    dragLabel: String,
    proxyHovered: Boolean,
    directHovered: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onProxyBoundsChanged: (Rect?) -> Unit,
    onDirectBoundsChanged: (Rect?) -> Unit,
    onDismiss: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background.copy(alpha = 0.88f))
                .clickable(onClick = onDismiss)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .yokhanOutlinedSurface(colors, radius = YokhanRadius.Card, backgroundColor = colors.surface)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                YokhanMonoValue(
                    text = dragLabel.ifBlank { "ПЕРЕТАЩИТЕ ПРИЛОЖЕНИЕ, ЯРЛЫК ИЛИ ПРОФИЛЬ" },
                    colors = colors,
                    typography = typography,
                    sizeSp = 13,
                )
                YokhanCaption(
                    text = "Отпустите на нужной зоне, чтобы сразу назначить правило; одиночный JSON-профиль импортируется автоматически.",
                    colors = colors,
                    typography = typography,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    GlobalDropTargetZone(
                        title = "В туннель GATES",
                        subtitle = "Весь трафик приложения пойдёт через защищённый маршрут",
                        highlighted = proxyHovered,
                        isAccent = true,
                        colors = colors,
                        typography = typography,
                        onBoundsChanged = onProxyBoundsChanged,
                        modifier = Modifier.weight(1f),
                    )
                    GlobalDropTargetZone(
                        title = "Напрямую мимо GATES",
                        subtitle = "Приложение подключается к сети напрямую (Direct)",
                        highlighted = directHovered,
                        isAccent = false,
                        colors = colors,
                        typography = typography,
                        onBoundsChanged = onDirectBoundsChanged,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun GlobalDropTargetZone(
    title: String,
    subtitle: String,
    highlighted: Boolean,
    isAccent: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onBoundsChanged: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (isAccent) colors.accent else colors.danger
    val border = if (highlighted) tint else colors.border
    val bg = if (highlighted) tint.copy(alpha = 0.16f) else colors.surfaceRaised.copy(alpha = 0.65f)
    Column(
        modifier = modifier
            .heightIn(min = 132.dp)
            .onGloballyPositioned { coords -> onBoundsChanged(coords.boundsInRoot()) }
            .clip(RoundedCornerShape(YokhanRadius.Card))
            .background(bg)
            .border(if (highlighted) 2.dp else YokhanStroke.Thin, border, RoundedCornerShape(YokhanRadius.Card))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = title,
            color = if (highlighted) tint else colors.primary,
            fontFamily = typography.mono,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = subtitle,
            color = colors.secondary,
            fontFamily = typography.text,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

internal data class WindowsParsedProbeRow(
    val tag: String,
    val ok: Boolean,
    val statusLabel: String,
    val server: String,
    val latencyLabel: String,
)

internal fun parseWindowsProbeRows(probeOutput: String): List<WindowsParsedProbeRow> {
    if (probeOutput.isBlank()) return emptyList()
    return probeOutput.lineSequence().mapNotNull { raw ->
        val line = raw.trim()
        if (!line.contains("| status=")) return@mapNotNull null
        val parts = line.split('|').map { it.trim() }
        val tag = parts.firstOrNull().orEmpty()
        if (tag.isBlank()) return@mapNotNull null
        val kv = parts.drop(1).associate { token ->
            val eq = token.indexOf('=')
            if (eq > 0) token.substring(0, eq).trim() to token.substring(eq + 1).trim()
            else token to ""
        }
        val status = kv["status"].orEmpty().uppercase()
        val ok = status == "OK"
        val tcpMs = kv["tcp_ms"]?.toIntOrNull()
        val latencyLabel = when {
            tcpMs != null && tcpMs >= 0 -> "$tcpMs мс"
            ok -> "OK"
            else -> "--"
        }
        val server = kv["server"].orEmpty()
        val statusLabel = when (status) {
            "OK" -> "ДОСТУПЕН"
            "FAIL" -> "НЕДОСТУПЕН"
            else -> "НЕТ ДАННЫХ"
        }
        WindowsParsedProbeRow(
            tag = tag,
            ok = ok,
            statusLabel = statusLabel,
            server = server,
            latencyLabel = latencyLabel,
        )
    }.toList()
}

@Composable
internal fun WindowsRouteProbeSummaryCard(
    probeOutput: String,
    colors: YokhanPalette,
    typography: YokhanTypography,
) {
    if (probeOutput.isBlank()) return
    val rows = remember(probeOutput) { parseWindowsProbeRows(probeOutput) }
    var showRaw by remember(probeOutput) { mutableStateOf(rows.isEmpty()) }

    YokhanRouteCard("Результаты диагностики маршрутов", colors, typography) {
        if (rows.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .yokhanOutlinedSurface(colors, radius = YokhanRadius.Panel)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (row.ok) colors.success else colors.danger),
                        )
                        Column(Modifier.weight(1f)) {
                            YokhanMonoValue(
                                text = activeRouteTitle(row.tag),
                                colors = colors,
                                typography = typography,
                                sizeSp = 12,
                            )
                            if (row.server.isNotBlank()) {
                                YokhanCaption(row.server, colors, typography)
                            }
                        }
                        YokhanStatusPill(
                            text = if (row.ok && row.latencyLabel != "OK") "${row.statusLabel} · ${row.latencyLabel}" else row.statusLabel,
                            active = row.ok,
                            colors = colors,
                            typography = typography,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (showRaw) "Скрыть подробный отчёт" else "Показать подробный отчёт",
                color = colors.accent,
                fontFamily = typography.mono,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(YokhanRadius.Small))
                    .clickable(role = Role.Button) { showRaw = !showRaw }
                    .padding(vertical = 4.dp),
            )
        }
        if (showRaw) {
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    text = probeOutput,
                    color = colors.secondary,
                    fontFamily = typography.mono,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

private fun activeRouteTitle(tag: String): String =
    when (tag.lowercase()) {
        "bridge" -> "BRIDGE (VLESS)"
        "relay" -> "RELAY (VLESS)"
        "naive" -> "NAIVE (HTTPS)"
        "hy2" -> "HY2 (HYSTERIA2)"
        "xhttp" -> "XHTTP (XRAY)"
        else -> tag.uppercase()
    }

@Composable
internal fun WindowsActiveConnectionsCard(
    apps: List<WindowsActiveNetworkApp>,
    busy: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onRefresh: () -> Unit,
    onAddProxy: (String) -> Unit,
    onAddDirect: (String) -> Unit,
    onMoveRule: (target: String, toProxy: Boolean) -> Unit,
) {
    YokhanRouteCard("Кто сейчас в сети (${apps.size})", colors, typography) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            YokhanCaption(
                text = "Активные TCP/UDP-процессы с выходом в сеть.",
                colors = colors,
                typography = typography,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Обновить",
                color = if (busy) colors.tertiary else colors.accent,
                fontFamily = typography.mono,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(YokhanRadius.Small))
                    .clickable(enabled = !busy, role = Role.Button, onClick = onRefresh)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(YokhanSpacing.CompactGap))
        if (apps.isEmpty()) {
            YokhanCaption(
                text = "Нет активных пользовательских подключений.",
                colors = colors,
                typography = typography,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                apps.take(10).forEach { app ->
                    val target = windowsProcessRoutingValue(app.name, app.path)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .yokhanOutlinedSurface(colors, radius = YokhanRadius.Panel)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = app.name,
                                color = colors.primary,
                                fontFamily = typography.mono,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    append("TCP ${app.tcpCount} · UDP ${app.udpCount}")
                                    when (app.status) {
                                        WindowsAppRouteStatus.PROXY -> append(" · Через GATES")
                                        WindowsAppRouteStatus.DIRECT -> append(" · Напрямую")
                                        WindowsAppRouteStatus.UNSET -> if (app.isRuCandidate) append(" · РФ (авто)")
                                    }
                                },
                                color = when (app.status) {
                                    WindowsAppRouteStatus.PROXY -> colors.accent
                                    WindowsAppRouteStatus.DIRECT -> colors.danger
                                    WindowsAppRouteStatus.UNSET -> colors.secondary
                                },
                                fontFamily = typography.mono,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        when (app.status) {
                            WindowsAppRouteStatus.PROXY -> QuickNetChip(
                                label = "↔ Прямо",
                                enabled = !busy,
                                accent = false,
                                colors = colors,
                                typography = typography,
                                onClick = { onMoveRule(target, false) },
                            )
                            WindowsAppRouteStatus.DIRECT -> QuickNetChip(
                                label = "↔ GATES",
                                enabled = !busy,
                                accent = true,
                                colors = colors,
                                typography = typography,
                                onClick = { onMoveRule(target, true) },
                            )
                            WindowsAppRouteStatus.UNSET -> {
                                QuickNetChip(
                                    label = "+ GATES",
                                    enabled = !busy,
                                    accent = true,
                                    colors = colors,
                                    typography = typography,
                                    onClick = { onAddProxy(target) },
                                )
                                QuickNetChip(
                                    label = "+ Прямо",
                                    enabled = !busy,
                                    accent = false,
                                    colors = colors,
                                    typography = typography,
                                    onClick = { onAddDirect(target) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickNetChip(
    label: String,
    enabled: Boolean,
    accent: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(YokhanRadius.Pill))
            .background(if (accent) colors.accent.copy(alpha = 0.14f) else colors.surface)
            .border(
                YokhanStroke.Thin,
                if (accent) colors.accent.copy(alpha = 0.6f) else colors.border,
                RoundedCornerShape(YokhanRadius.Pill),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = if (accent) colors.accent else colors.primary,
            fontFamily = typography.mono,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun GatesDesktopSidebar(
    selectedKey: String,
    items: List<GatesNavigationItem>,
    connected: Boolean,
    busy: Boolean,
    appVersion: String,
    activeRouteLabel: String,
    telemetryLabel: String,
    accountLabel: String,
    updateAvailable: Boolean,
    colors: YokhanPalette,
    typography: YokhanTypography,
    onSelect: (String) -> Unit,
    onToggleConnection: () -> Unit,
    onOpenAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(226.dp)
                .fillMaxHeight()
                .background(colors.surface)
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .yokhanOutlinedSurface(colors, radius = YokhanRadius.Panel, backgroundColor = colors.surfaceRaised)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (connected) colors.accent else colors.secondary.copy(alpha = 0.55f)),
                    )
                    Text(
                        text = "GATES",
                        color = colors.primary,
                        fontFamily = typography.mono,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (appVersion.isNotBlank()) {
                        YokhanCaption(appVersion, colors, typography)
                    }
                }
                Text(
                    text = if (connected) "Подключено · $activeRouteLabel" else "Отключено · $activeRouteLabel",
                    color = if (connected) colors.accent else colors.secondary,
                    fontFamily = typography.text,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (telemetryLabel.isNotBlank()) {
                    YokhanCaption(telemetryLabel, colors, typography)
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEach { item ->
                    val selected = item.key == selectedKey
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("route.nav.${item.key.lowercase()}")
                            .clip(RoundedCornerShape(YokhanRadius.Panel))
                            .background(if (selected) colors.accent.copy(alpha = 0.14f) else Color.Transparent)
                            .border(
                                YokhanStroke.Thin,
                                if (selected) colors.accent.copy(alpha = 0.5f) else Color.Transparent,
                                RoundedCornerShape(YokhanRadius.Panel),
                            )
                            .clickable(enabled = !busy, role = Role.Tab) { onSelect(item.key) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = item.glyph,
                            color = if (selected) colors.accent else colors.secondary,
                            fontFamily = typography.mono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = item.title,
                            color = if (selected) colors.primary else colors.secondary,
                            fontFamily = typography.text,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                YokhanRouteButton(
                    text = if (connected) "Отключить туннель" else "Подключить туннель",
                    primary = !connected,
                    enabled = !busy,
                    colors = colors,
                    typography = typography,
                    onClick = onToggleConnection,
                    modifier = Modifier.fillMaxWidth().testTag("sidebar.toggle_tunnel"),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(YokhanRadius.Small))
                        .clickable(enabled = !busy, role = Role.Button, onClick = onOpenAccess)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = accountLabel.ifBlank { "Аккаунт и подписка" },
                        color = colors.secondary,
                        fontFamily = typography.text,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (updateAvailable) {
                        YokhanStatusPill("ОБНОВА", true, colors, typography)
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .width(YokhanStroke.Thin)
                .fillMaxHeight()
                .background(colors.border),
        )
    }
}
