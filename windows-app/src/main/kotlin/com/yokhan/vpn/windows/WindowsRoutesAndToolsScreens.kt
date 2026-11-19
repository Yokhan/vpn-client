package com.yokhan.gates.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yokhan.gates.shared.ui.design.YokhanLayout
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanRadius
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.YokhanStroke
import com.yokhan.gates.shared.ui.route.YokhanAdaptiveActionPair
import com.yokhan.gates.shared.ui.route.YokhanBody
import com.yokhan.gates.shared.ui.route.YokhanButtonGrid
import com.yokhan.gates.shared.ui.route.YokhanCaption
import com.yokhan.gates.shared.ui.route.YokhanMonoValue
import com.yokhan.gates.shared.ui.route.YokhanRouteAction
import com.yokhan.gates.shared.ui.route.YokhanRouteButton
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanRouteFrame
import com.yokhan.gates.shared.ui.route.YokhanStatusPill
import com.yokhan.gates.shared.ui.route.YokhanToggleRow
import com.yokhan.gates.shared.ui.route.yokhanOutlinedSurface

@Composable
internal fun WindowsRoutesScreen(
    activeRoute: String,
    availableRoutes: List<WindowsProfileRoute>,
    routeOrder: List<String>,
    enableRuBypass: Boolean,
    wholeTrafficMode: Boolean,
    bypassLan: Boolean,
    blockQuic: Boolean,
    busy: Boolean,
    spaceActive: Boolean,
    statusMessage: String,
    probeOutput: String,
    onMenuClick: () -> Unit,
    onBack: (() -> Unit)? = null,
    bottomInsetPaddingDp: Int = 64,
    onSelectRoute: (String) -> Unit,
    onUpdateRouteOrder: (List<String>) -> Unit,
    onRuBypassChange: (Boolean) -> Unit,
    onWholeTrafficChange: (Boolean) -> Unit,
    onBypassLanChange: (Boolean) -> Unit,
    onBlockQuicChange: (Boolean) -> Unit,
    onProbeRoutes: () -> Unit,
    onReconnectTunnel: () -> Unit,
    modifier: Modifier = Modifier,
    networkSettingsContent: (@Composable (YokhanPalette, com.yokhan.gates.shared.ui.design.YokhanTypography) -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val wideDesktop = maxWidth >= YokhanLayout.DesktopContentCompanionBreakpoint

        @Composable
        fun TrafficPolicyAndNetworkCards(
            colors: YokhanPalette,
            typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
        ) {
            YokhanRouteCard("Политики трафика", colors, typography) {
                YokhanToggleRow(
                    title = "Умный автообход РФ",
                    summary = "Домены .ru/.рф/.by/.kz, российский DNS и локальные РФ-программы идут напрямую мимо туннеля.",
                    checked = enableRuBypass,
                    colors = colors,
                    typography = typography,
                    onCheckedChange = { if (!busy) onRuBypassChange(it) },
                    modifier = Modifier.testTag("routes.toggle.ru_bypass"),
                )
                Spacer(Modifier.height(YokhanSpacing.CompactGap))
                YokhanToggleRow(
                    title = "Весь трафик через GATES",
                    summary = "Принудительно направлять все подключения и DNS в туннель (взаимоисключающе с автообходом РФ).",
                    checked = wholeTrafficMode,
                    colors = colors,
                    typography = typography,
                    onCheckedChange = { if (!busy) onWholeTrafficChange(it) },
                    modifier = Modifier.testTag("routes.toggle.whole_traffic"),
                )
                Spacer(Modifier.height(YokhanSpacing.CompactGap))
                YokhanToggleRow(
                    title = "Обход локальной сети (LAN)",
                    summary = "Локальные подсети (192.168/16, 10/8) и .local не попадают в туннель; loopback защищён всегда.",
                    checked = bypassLan,
                    colors = colors,
                    typography = typography,
                    onCheckedChange = { if (!busy) onBypassLanChange(it) },
                    modifier = Modifier.testTag("routes.toggle.bypass_lan"),
                )
                Spacer(Modifier.height(YokhanSpacing.CompactGap))
                YokhanToggleRow(
                    title = "Блокировать QUIC (UDP 443)",
                    summary = "Помогает браузерам переключаться на стабильный HTTPS/TCP поверх туннеля.",
                    checked = blockQuic,
                    colors = colors,
                    typography = typography,
                    onCheckedChange = { if (!busy) onBlockQuicChange(it) },
                    modifier = Modifier.testTag("routes.toggle.block_quic"),
                )
            }

            networkSettingsContent?.invoke(colors, typography)

            YokhanRouteCard("Диагностика маршрутов", colors, typography) {
                YokhanCaption(
                    text = "Проверка доступности узлов и пересинхронизация активного туннеля.",
                    colors = colors,
                    typography = typography,
                )
                Spacer(Modifier.height(YokhanSpacing.CompactGap))
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction(
                            title = if (busy) "Проверка…" else "Проверить маршруты",
                            primary = true,
                            enabled = !busy,
                            onClick = onProbeRoutes,
                        ),
                        YokhanRouteAction(
                            title = "Синхронизировать туннель",
                            primary = false,
                            enabled = !busy,
                            onClick = onReconnectTunnel,
                        ),
                    ),
                    colors = colors,
                    typography = typography,
                )
            }

            if (probeOutput.isNotBlank()) {
                WindowsRouteProbeSummaryCard(
                    probeOutput = probeOutput,
                    colors = colors,
                    typography = typography,
                )
            }
        }

        @Composable
        fun PriorityAndProbeCards(
            colors: YokhanPalette,
            typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
        ) {
            YokhanRouteCard("Приоритет автовыбора", colors, typography) {
                YokhanCaption(
                    text = "Верхний доступный маршрут выбирается первым в режиме AUTO.",
                    colors = colors,
                    typography = typography,
                )
                Spacer(Modifier.height(YokhanSpacing.CompactGap))
                val orderedTags = remember(routeOrder, availableRoutes) {
                    val base = routeOrder.ifEmpty { listOf("bridge", "relay", "naive", "hy2") }
                    val extras = availableRoutes.map { it.tag }.filter { it !in base }
                    (base + extras).distinct()
                }
                orderedTags.forEachIndexed { index, tag ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .yokhanOutlinedSurface(colors, radius = YokhanRadius.Panel)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        YokhanMonoValue(
                            "${index + 1}. ${tag.uppercase()}",
                            colors,
                            typography,
                            sizeSp = 12,
                            modifier = Modifier.weight(1f),
                        )
                        SmallChipButton(
                            label = "↑",
                            enabled = !busy && index > 0,
                            colors = colors,
                            typography = typography,
                            tag = "routes.order.up.$tag",
                            onClick = {
                                val swapped = orderedTags.toMutableList()
                                swapped[index] = swapped[index - 1].also { swapped[index - 1] = swapped[index] }
                                onUpdateRouteOrder(swapped)
                            },
                        )
                        SmallChipButton(
                            label = "↓",
                            enabled = !busy && index < orderedTags.lastIndex,
                            colors = colors,
                            typography = typography,
                            tag = "routes.order.down.$tag",
                            onClick = {
                                val swapped = orderedTags.toMutableList()
                                swapped[index] = swapped[index + 1].also { swapped[index + 1] = swapped[index] }
                                onUpdateRouteOrder(swapped)
                            },
                        )
                    }
                }
            }
        }

        YokhanRouteFrame(
            title = "Маршруты и сеть",
            subtitle = "Выбор активного маршрута, порядок автовыбора и правила фильтрации трафика",
            systemLabel = "03",
            spaceActive = spaceActive,
            onMenuClick = onMenuClick,
            onBack = onBack,
            bottomInsetPaddingDp = bottomInsetPaddingDp,
            modifier = Modifier.testTag("routes.screen"),
            companionContent = if (wideDesktop) { colors, typography ->
                TrafficPolicyAndNetworkCards(colors, typography)
            } else null,
        ) { colors, typography ->
            if (statusMessage.isNotBlank()) {
                YokhanBody(statusMessage, colors, typography)
            }

            YokhanRouteCard("Выбор маршрута", colors, typography) {
                RouteOptionRow(
                    tag = "auto",
                    title = "Автоматически",
                    subtitle = "По приоритету и стабильности серверов",
                    selected = activeRoute == "auto",
                    enabled = !busy,
                    colors = colors,
                    typography = typography,
                    testTag = "routes.select.auto",
                    onClick = { onSelectRoute("auto") },
                )
                availableRoutes.forEach { route ->
                    Spacer(Modifier.height(YokhanSpacing.CompactGap))
                    RouteOptionRow(
                        tag = route.tag,
                        title = route.label,
                        subtitle = "${route.protocol} · ${route.server}",
                        selected = activeRoute == route.tag,
                        enabled = !busy,
                        colors = colors,
                        typography = typography,
                        testTag = "routes.select.${route.tag.lowercase().replace(':', '_')}",
                        onClick = { onSelectRoute(route.tag) },
                    )
                }
            }

            PriorityAndProbeCards(colors, typography)

            if (!wideDesktop) {
                TrafficPolicyAndNetworkCards(colors, typography)
            }
        }
    }
}

@Composable
internal fun WindowsLogsScreen(
    logText: String,
    sourceFilter: String,
    busy: Boolean,
    spaceActive: Boolean,
    statusMessage: String,
    onMenuClick: () -> Unit,
    onBack: () -> Unit,
    onSourceFilterChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onCopy: (String) -> Unit,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var levelFilter by remember { mutableStateOf("all") }
    var searchQuery by remember { mutableStateOf("") }

    val filteredLines = remember(logText, levelFilter, searchQuery) {
        val lines = logText.lineSequence()
        val levelFiltered = when (levelFilter) {
            "warn" -> lines.filter { line ->
                line.startsWith("===") ||
                    line.contains("WARN", ignoreCase = true) ||
                    line.contains("ERROR", ignoreCase = true) ||
                    line.contains("FATAL", ignoreCase = true) ||
                    line.contains("failed", ignoreCase = true)
            }
            else -> lines
        }
        val query = searchQuery.trim()
        val finalLines = if (query.isEmpty()) levelFiltered else levelFiltered.filter {
            it.startsWith("===") || it.contains(query, ignoreCase = true)
        }
        finalLines.toList()
    }
    val hasFilteredLines = remember(logText, filteredLines) {
        logText.isNotBlank() && filteredLines.any { it.isNotBlank() }
    }
    val filteredText = remember(filteredLines, hasFilteredLines) {
        if (hasFilteredLines) filteredLines.joinToString("\n") else "(нет строк по текущему фильтру)"
    }

    YokhanRouteFrame(
        title = "Логи",
        systemLabel = "GATES",
        spaceActive = spaceActive,
        onMenuClick = onMenuClick,
        onBack = onBack,
        modifier = modifier.testTag("logs.screen"),
    ) { colors, typography ->
        if (statusMessage.isNotBlank()) YokhanBody(statusMessage, colors, typography)

        YokhanRouteCard("Источник и фильтры", colors, typography) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
            ) {
                listOf(
                    "all" to "Все",
                    "service" to "Служба GatesVpn",
                    "xray" to "Xray XHTTP",
                    "local" to "Локальные",
                ).forEach { (key, label) ->
                    SmallChipButton(
                        label = label,
                        enabled = !busy,
                        selected = sourceFilter == key,
                        colors = colors,
                        typography = typography,
                        tag = "logs.source.$key",
                        onClick = { onSourceFilterChange(key) },
                    )
                }
            }
            Spacer(Modifier.height(YokhanSpacing.CompactGap))
            Row(horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                SmallChipButton(
                    label = "Все уровни",
                    enabled = true,
                    selected = levelFilter == "all",
                    colors = colors,
                    typography = typography,
                    tag = "logs.level.all",
                    onClick = { levelFilter = "all" },
                )
                SmallChipButton(
                    label = "Только WARN / ERROR",
                    enabled = true,
                    selected = levelFilter == "warn",
                    colors = colors,
                    typography = typography,
                    tag = "logs.level.warn",
                    onClick = { levelFilter = "warn" },
                )
            }
            Spacer(Modifier.height(YokhanSpacing.CompactGap))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .yokhanOutlinedSurface(colors, radius = YokhanRadius.Panel)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    cursorBrush = SolidColor(colors.accent),
                    textStyle = TextStyle(color = colors.primary, fontFamily = typography.mono, fontSize = 12.sp),
                    modifier = Modifier.weight(1f).testTag("logs.search"),
                    decorationBox = { input ->
                        Box {
                            if (searchQuery.isEmpty()) YokhanCaption("Фильтр по тексту лога", colors, typography)
                            input()
                        }
                    },
                )
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { searchQuery = "" },
                        modifier = Modifier.size(24.dp).testTag("logs.search.clear"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Очистить фильтр логов",
                            tint = colors.secondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(YokhanSpacing.CompactGap))
            Row(horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                YokhanRouteButton(
                    text = if (busy) "Чтение…" else "Обновить",
                    primary = true,
                    enabled = !busy,
                    colors = colors,
                    typography = typography,
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f).testTag("logs.refresh"),
                )
                YokhanRouteButton(
                    text = "Копировать",
                    primary = false,
                    enabled = hasFilteredLines,
                    colors = colors,
                    typography = typography,
                    onClick = { onCopy(filteredText) },
                    modifier = Modifier.weight(1f).testTag("logs.copy"),
                )
                YokhanRouteButton(
                    text = "Сохранить",
                    primary = false,
                    enabled = hasFilteredLines,
                    colors = colors,
                    typography = typography,
                    onClick = { onSave(filteredText) },
                    modifier = Modifier.weight(1f).testTag("logs.save"),
                )
            }
        }

        YokhanRouteCard("Хвост журналов (секреты скрыты)", colors, typography) {
            SelectionContainer {
                Text(
                    text = filteredText,
                    color = colors.secondary,
                    fontFamily = typography.mono,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
internal fun WindowsToolsScreen(
    statusSummary: String,
    busy: Boolean,
    spaceActive: Boolean,
    lastOutput: String,
    probeOutput: String = "",
    activeNetworkApps: List<WindowsActiveNetworkApp> = emptyList(),
    onMenuClick: () -> Unit,
    onBack: () -> Unit,
    onTestConnection: () -> Unit,
    onProbeRoutes: () -> Unit,
    onReconnectTunnel: () -> Unit,
    onResetSystemProxy: () -> Unit,
    onExportSupportBundle: () -> Unit,
    onRefreshStatus: () -> Unit,
    onRefreshActiveNetwork: () -> Unit = {},
    onAddProxy: (String) -> Unit = {},
    onAddDirect: (String) -> Unit = {},
    onMoveRule: (String, Boolean) -> Unit = { _, _ -> },
    onOpenLogs: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    bottomInsetPaddingDp: Int = 64,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val wideDesktop = maxWidth >= YokhanLayout.DesktopContentCompanionBreakpoint

        @Composable
        fun ServiceAndOutputCards(
            colors: YokhanPalette,
            typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
        ) {
            YokhanRouteCard("Состояние службы и ядра", colors, typography) {
                YokhanRouteButton(
                    text = if (busy) "Обновление…" else "Обновить статус (IP и служба)",
                    primary = false,
                    enabled = !busy,
                    colors = colors,
                    typography = typography,
                    onClick = onRefreshStatus,
                    modifier = Modifier.widthIn(min = 220.dp).testTag("tools.refresh"),
                )
                if (statusSummary.isNotBlank()) {
                    Spacer(Modifier.height(YokhanSpacing.CompactGap))
                    SelectionContainer {
                        Text(
                            text = statusSummary,
                            color = colors.secondary,
                            fontFamily = typography.mono,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }

            if (probeOutput.isNotBlank()) {
                WindowsRouteProbeSummaryCard(
                    probeOutput = probeOutput,
                    colors = colors,
                    typography = typography,
                )
            }

            if (lastOutput.isNotBlank()) {
                YokhanRouteCard("Результат последней операции", colors, typography) {
                    SelectionContainer {
                        Text(
                            text = lastOutput,
                            color = colors.primary,
                            fontFamily = typography.mono,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }

            YokhanRouteCard("Журналы и история сбоев", colors, typography) {
                YokhanAdaptiveActionPair(
                    first = { m ->
                        YokhanRouteButton(
                            text = "Логи службы и Xray",
                            primary = false,
                            enabled = !busy,
                            colors = colors,
                            typography = typography,
                            onClick = onOpenLogs,
                            modifier = m.testTag("tools.logs"),
                        )
                    },
                    second = { m ->
                        YokhanRouteButton(
                            text = "История инцидентов",
                            primary = false,
                            enabled = !busy,
                            colors = colors,
                            typography = typography,
                            onClick = onOpenHistory,
                            modifier = m.testTag("tools.history"),
                        )
                    },
                )
            }
        }

        YokhanRouteFrame(
            title = "Диагностика",
            systemLabel = "04",
            spaceActive = spaceActive,
            onMenuClick = onMenuClick,
            onBack = onBack,
            bottomInsetPaddingDp = bottomInsetPaddingDp,
            modifier = Modifier.testTag("tools.screen"),
            companionContent = if (wideDesktop) { colors, typography ->
                ServiceAndOutputCards(colors, typography)
            } else null,
        ) { colors, typography ->
            YokhanRouteCard("Быстрая диагностика", colors, typography) {
                YokhanButtonGrid(
                    actions = listOf(
                        YokhanRouteAction("Проверка соединения", primary = true, enabled = !busy, onClick = onTestConnection),
                        YokhanRouteAction("Проверить маршруты", primary = false, enabled = !busy, onClick = onProbeRoutes),
                        YokhanRouteAction("Синхронизировать туннель", primary = false, enabled = !busy, onClick = onReconnectTunnel),
                        YokhanRouteAction("Сбросить прокси Windows", primary = false, enabled = !busy, onClick = onResetSystemProxy),
                        YokhanRouteAction("Экспорт диагностики (ZIP)", primary = false, enabled = !busy, fullWidth = true, onClick = onExportSupportBundle),
                    ),
                    colors = colors,
                    typography = typography,
                )
            }

            WindowsActiveConnectionsCard(
                apps = activeNetworkApps,
                busy = busy,
                colors = colors,
                typography = typography,
                onRefresh = onRefreshActiveNetwork,
                onAddProxy = onAddProxy,
                onAddDirect = onAddDirect,
                onMoveRule = onMoveRule,
            )

            if (!wideDesktop) {
                ServiceAndOutputCards(colors, typography)
            }
        }
    }
}

@Composable
private fun RouteOptionRow(
    tag: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(YokhanRadius.Panel))
            .background(if (selected) colors.surfaceRaised else colors.surface)
            .border(
                YokhanStroke.Thin,
                if (selected) colors.accent else colors.border,
                RoundedCornerShape(YokhanRadius.Panel),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            YokhanMonoValue(title, colors, typography, sizeSp = 13)
            if (subtitle.isNotBlank()) {
                YokhanCaption(subtitle, colors, typography, modifier = Modifier.padding(top = 2.dp))
            }
        }
        YokhanStatusPill(
            text = if (selected) "АКТИВЕН" else tag.uppercase(),
            active = selected,
            colors = colors,
            typography = typography,
        )
    }
}

@Composable
private fun SmallChipButton(
    label: String,
    enabled: Boolean,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    tag: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(YokhanRadius.Pill))
            .background(if (selected) colors.accent else colors.surface)
            .border(YokhanStroke.Thin, if (selected) colors.accent else colors.border, RoundedCornerShape(YokhanRadius.Pill))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) colors.onAccent else if (enabled) colors.primary else colors.tertiary,
            fontFamily = typography.mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
