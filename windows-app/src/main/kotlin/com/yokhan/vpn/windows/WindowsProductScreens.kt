package com.yokhan.gates.windows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanRadius
import com.yokhan.gates.shared.ui.design.YokhanRed
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.YokhanStroke
import com.yokhan.gates.shared.ui.route.YokhanBody
import com.yokhan.gates.shared.ui.route.YokhanAdaptiveActionPair
import com.yokhan.gates.shared.ui.route.YokhanCaption
import com.yokhan.gates.shared.ui.route.YokhanMonoValue
import com.yokhan.gates.shared.ui.route.YokhanRouteButton
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanRouteFrame
import com.yokhan.gates.shared.ui.route.YokhanRouteLazyFrame
import com.yokhan.gates.shared.ui.route.YokhanSectionLabel
import com.yokhan.gates.shared.ui.route.yokhanOutlinedSurface

private const val REPORT_LIMIT = 2_000

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WindowsSettingsScreen(
    busy: Boolean,
    palette: YokhanPalette,
    spaceActive: Boolean,
    onDismiss: () -> Unit,
    onAccess: () -> Unit,
    onApps: () -> Unit,
    onUpdate: () -> Unit,
    onBugReport: () -> Unit,
    onHistory: () -> Unit,
    appVersion: String = "",
    runtimeMode: String = "tun",
    onRuntimeModeChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    statusMessage: String = "",
    onInstallUpdate: (() -> Unit)? = null,
    autoUploadRecoveryReports: Boolean = true,
    onAutoUploadRecoveryReportsChange: ((Boolean) -> Unit)? = null,
    killSwitchEnabled: Boolean = false,
    protectionMessage: String = "",
    onKillSwitchChange: ((Boolean) -> Unit)? = null,
) {
    YokhanRouteFrame(
        title = "Настройки",
        systemLabel = "GATES",
        spaceActive = spaceActive,
        onMenuClick = onDismiss,
        onBack = onDismiss,
        modifier = modifier,
    ) { colors, typography ->
        if (statusMessage.isNotBlank()) YokhanBody(statusMessage, colors, typography)
        YokhanRouteCard("Подключение", colors, typography) {
            YokhanRouteButton(
                text = "Аккаунт и подписка",
                primary = false,
                enabled = !busy,
                colors = colors,
                typography = typography,
                onClick = onAccess,
                modifier = Modifier.fillMaxWidth().testTag("settings.access"),
            )
            Spacer(Modifier.height(YokhanSpacing.CompactGap))
            YokhanRouteButton(
                text = "Маршрутизация приложений",
                primary = false,
                enabled = !busy,
                colors = colors,
                typography = typography,
                onClick = onApps,
                modifier = Modifier.fillMaxWidth().testTag("settings.routing"),
            )
            Spacer(Modifier.height(YokhanSpacing.CardTitleGap))
            YokhanSectionLabel("Режим", colors, typography)
            Spacer(Modifier.height(YokhanSpacing.CompactGap))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("tun" to "TUN", "proxy" to "Прокси").forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = runtimeMode.equals(mode, ignoreCase = true),
                        onClick = { onRuntimeModeChange(mode) },
                        enabled = !busy,
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = colors.accent,
                            activeContentColor = colors.background,
                            inactiveContainerColor = colors.background,
                            inactiveContentColor = colors.primary,
                            activeBorderColor = colors.accent,
                            inactiveBorderColor = colors.border,
                        ),
                        modifier = Modifier.testTag("settings.runtime.$mode"),
                    ) { Text(label) }
                }
            }
        }

        if (onKillSwitchChange != null) {
            YokhanRouteCard("Защита при обрыве", colors, typography) {
                Row(Modifier.fillMaxWidth().testTag("settings.kill-switch"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(killSwitchEnabled, onKillSwitchChange, enabled = !busy)
                    Text("Защищать EXE из списка «Через VPN»", Modifier.weight(1f),
                        color = colors.primary, fontFamily = typography.text)
                }
                if (protectionMessage.isNotBlank()) YokhanCaption(protectionMessage, colors, typography)
            }
        }

        YokhanRouteCard("Приложение", colors, typography) {
            if (appVersion.isNotBlank()) {
                YokhanMonoValue(appVersion, colors, typography, sizeSp = 13)
                YokhanCaption(
                    text = "Версия",
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.padding(top = 4.dp, bottom = YokhanSpacing.CompactGap),
                )
            }
            YokhanRouteButton(
                text = "Проверить обновления",
                primary = false,
                enabled = !busy,
                colors = colors,
                typography = typography,
                onClick = onUpdate,
                modifier = Modifier.fillMaxWidth().testTag("settings.update"),
            )
            if (onInstallUpdate != null) {
                Spacer(Modifier.height(YokhanSpacing.CompactGap))
                YokhanRouteButton("Установить обновление", true, !busy, colors, typography,
                    onInstallUpdate, Modifier.fillMaxWidth().testTag("settings.install"))
            }
        }

        YokhanRouteCard("Поддержка", colors, typography) {
            if (onAutoUploadRecoveryReportsChange != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("settings.recovery-upload"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = autoUploadRecoveryReports,
                        onCheckedChange = onAutoUploadRecoveryReportsChange,
                        enabled = !busy,
                    )
                    Text(
                        "Автоматически отправлять компактные отчёты о сбоях",
                        modifier = Modifier.weight(1f),
                        color = colors.primary,
                        style = TextStyle(
                            fontFamily = typography.text,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        ),
                    )
                }
                Spacer(Modifier.height(YokhanSpacing.CompactGap))
            }
            YokhanAdaptiveActionPair(first = { actionModifier ->
                YokhanRouteButton(
                    text = "История соединения",
                    primary = false,
                    enabled = !busy,
                    colors = colors,
                    typography = typography,
                    onClick = onHistory,
                    modifier = actionModifier.testTag("settings.history"),
                )
            }, second = { actionModifier ->
                YokhanRouteButton(
                    text = "Сообщить об ошибке",
                    primary = true,
                    enabled = !busy,
                    colors = colors,
                    typography = typography,
                    onClick = onBugReport,
                    modifier = actionModifier.testTag("settings.report"),
                )
            })
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WindowsAppRoutingScreen(
    state: AppRoutingState,
    runningApps: List<WindowsProcessCandidate>,
    busy: Boolean,
    palette: YokhanPalette,
    spaceActive: Boolean,
    message: String,
    onDismiss: () -> Unit,
    onRefreshProcesses: () -> Unit,
    onPickExe: (proxy: Boolean) -> Unit,
    onRouteAll: () -> Unit,
    onRouteSelected: () -> Unit,
    onAddProxy: (String) -> Unit,
    onAddDirect: (String) -> Unit,
    onRemoveProxy: (String) -> Unit,
    onRemoveDirect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchText by remember { mutableStateOf("") }
    var manualTargetText by remember { mutableStateOf("") }
    var refreshingProcesses by remember { mutableStateOf(false) }
    val manualTarget = remember(manualTargetText) { parseWindowsAppRoutingTarget(manualTargetText) }
    val manualTargetError = manualTargetText.isNotBlank() && manualTarget == null
    LaunchedEffect(busy) {
        if (!busy) refreshingProcesses = false
    }
    val matchingApps = remember(runningApps, searchText) {
        val query = searchText.trim()
        if (query.isEmpty()) runningApps
        else runningApps.filter { it.name.contains(query, true) || it.path.contains(query, true) }
    }
    val proxyValues = remember(state) { state.proxyProcessPaths + state.proxyProcessNames }
    val directValues = remember(state) { state.directProcessPaths + state.directProcessNames }

    YokhanRouteLazyFrame(
        title = "Приложения",
        systemLabel = "GATES",
        spaceActive = spaceActive,
        onMenuClick = onDismiss,
        onBack = onDismiss,
        modifier = modifier,
    ) { colors, typography ->
        item(key = "routing-controls") {
        Column(verticalArrangement = Arrangement.spacedBy(YokhanSpacing.SectionGap)) {
        if (message.isNotBlank()) YokhanBody(message, colors, typography)
        if (busy && !refreshingProcesses) {
            ProductStatus(
                "Применение изменений маршрутизации…",
                colors,
                typography,
                modifier = Modifier.testTag("routing.pending"),
            )
        }

        YokhanRouteCard("Режим", colors, typography) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Все", "Выбранные").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = if (index == 0) state.routeAll else !state.routeAll,
                        onClick = if (index == 0) onRouteAll else onRouteSelected,
                        enabled = !busy,
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = colors.accent,
                            activeContentColor = colors.background,
                            inactiveContainerColor = colors.background,
                            inactiveContentColor = colors.primary,
                            activeBorderColor = colors.accent,
                            inactiveBorderColor = colors.border,
                        ),
                        modifier = Modifier.testTag(
                            if (index == 0) "routing.mode.all" else "routing.mode.selected",
                        ),
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(YokhanSpacing.CompactGap))
            Row(horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                ProductAddAction(
                    label = "Для GATES",
                    tooltip = "Выбрать .exe для маршрута через GATES",
                    tag = "routing.pick-exe.proxy",
                    enabled = !busy,
                    colors = colors,
                    typography = typography,
                    onClick = { onPickExe(true) },
                    modifier = Modifier.weight(1f),
                )
                ProductAddAction(
                    label = "Напрямую",
                    tooltip = "Выбрать .exe для прямого маршрута",
                    tag = "routing.pick-exe.direct",
                    enabled = !busy,
                    colors = colors,
                    typography = typography,
                    onClick = { onPickExe(false) },
                    modifier = Modifier.weight(1f),
                )
            }
            ProductTextInput(
                value = manualTargetText,
                onValueChange = { manualTargetText = it },
                placeholder = "Имя .exe или полный путь",
                colors = colors,
                typography = typography,
                enabled = !busy,
                fieldTag = "routing.manual-exe",
                clearTag = "routing.manual-exe.clear",
            )
            if (manualTargetError) {
                YokhanCaption(
                    text = "Укажите имя .exe или абсолютный путь к нему",
                    colors = colors,
                    typography = typography,
                    modifier = Modifier.testTag("routing.manual-exe.error"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                ProductAddAction(
                    label = "Через GATES",
                    tooltip = "Добавить введённый .exe в маршрут через GATES",
                    tag = "routing.manual-exe.proxy",
                    enabled = !busy && manualTarget != null,
                    colors = colors,
                    typography = typography,
                    onClick = {
                        manualTarget?.let { onAddProxy(it.label) }
                    },
                    modifier = Modifier.weight(1f),
                )
                ProductAddAction(
                    label = "Напрямую",
                    tooltip = "Добавить введённый .exe в прямой маршрут",
                    tag = "routing.manual-exe.direct",
                    enabled = !busy && manualTarget != null,
                    colors = colors,
                    typography = typography,
                    onClick = {
                        manualTarget?.let { onAddDirect(it.label) }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                YokhanRouteButton(
                    text = if (refreshingProcesses && busy) "Обновление…" else "Обновить",
                    primary = false,
                    enabled = !busy,
                    colors = colors,
                    typography = typography,
                    onClick = {
                        refreshingProcesses = true
                        onRefreshProcesses()
                    },
                    modifier = Modifier.weight(1f).testTag("routing.refresh"),
                )
            }
        }

        }
        }

        item(key = "routing-add-tile") {
            AppAddTile(
                busy = busy,
                colors = colors,
                typography = typography,
                onPickExe = { onPickExe(true) },
            )
        }

        item(key = "routing-proxy-grid") {
            Column(verticalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                YokhanSectionLabel("Через GATES", colors, typography)
                ConfiguredAppGrid(
                    values = proxyValues,
                    status = WindowsAppRouteStatus.PROXY,
                    tagPrefix = "routing.proxy.remove",
                    busy = busy,
                    colors = colors,
                    typography = typography,
                    onRemove = onRemoveProxy,
                )
            }
        }

        item(key = "routing-direct-grid") {
            Column(verticalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                YokhanSectionLabel("Напрямую", colors, typography)
                ConfiguredAppGrid(
                    values = directValues,
                    status = WindowsAppRouteStatus.DIRECT,
                    tagPrefix = "routing.direct.remove",
                    busy = busy,
                    colors = colors,
                    typography = typography,
                    onRemove = onRemoveDirect,
                )
            }
        }

        item(key = "routing-running-header") {
            Column(verticalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                YokhanSectionLabel("Запущенные", colors, typography)
                ProductTextInput(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = "Поиск процессов",
                    colors = colors,
                    typography = typography,
                    fieldTag = "routing.search",
                    clearTag = "routing.search.clear",
                )
            }
        }

        when {
            refreshingProcesses && busy -> item(key = "routing-loading") {
                ProductStatus("Загрузка процессов…", colors, typography)
            }
            runningApps.isEmpty() -> item(key = "routing-empty") {
                ProductStatus("Нет запущенных процессов", colors, typography)
            }
            matchingApps.isEmpty() -> item(key = "routing-no-match") {
                ProductStatus("Совпадений не найдено", colors, typography)
            }
            else -> item(key = "routing-running-grid") {
                RunningAppGrid(
                    apps = matchingApps,
                    state = state,
                    busy = busy,
                    colors = colors,
                    typography = typography,
                    onAddProxy = onAddProxy,
                    onAddDirect = onAddDirect,
                )
            }
        }
    }
}

@Composable
internal fun WindowsBugReportScreen(
    message: String,
    onMessageChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
    spaceActive: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
    statusMessage: String = "",
) {
    YokhanRouteFrame(
        title = "Отчёт об ошибке",
        systemLabel = "GATES",
        spaceActive = spaceActive,
        onMenuClick = onDismiss,
        onBack = onDismiss,
        modifier = modifier,
    ) { colors, typography ->
        if (statusMessage.isNotBlank()) YokhanBody(statusMessage, colors, typography)
        YokhanRouteCard("Сообщение", colors, typography) {
            ProductTextInput(
                value = message,
                onValueChange = { onMessageChange(it.take(REPORT_LIMIT)) },
                placeholder = "Опишите проблему",
                colors = colors,
                typography = typography,
                multiline = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                fieldTag = "report.message",
                clearTag = "report.message.clear",
            )
            Text(
                text = "${message.length.coerceAtMost(REPORT_LIMIT)}/$REPORT_LIMIT",
                color = colors.tertiary,
                fontFamily = typography.mono,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.End).padding(top = YokhanSpacing.CompactGap),
            )
            Spacer(Modifier.height(YokhanSpacing.CardTitleGap))
            YokhanRouteButton(
                text = "Отправить",
                primary = true,
                enabled = !busy && message.isNotBlank(),
                colors = colors,
                typography = typography,
                onClick = onSend,
                modifier = Modifier.fillMaxWidth().testTag("report.send"),
            )
        }
    }
}

@Composable
private fun ConfiguredAppGrid(
    values: List<String>,
    status: WindowsAppRouteStatus,
    tagPrefix: String,
    busy: Boolean,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    onRemove: (String) -> Unit,
) {
    if (values.isEmpty()) {
        ProductStatus("Пусто", colors, typography)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 520.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
            values.chunked(columns).forEachIndexed { rowIndex, rowValues ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
                ) {
                    rowValues.forEachIndexed { colIndex, value ->
                        ConfiguredAppCard(
                            value = value,
                            status = status,
                            index = rowIndex * columns + colIndex,
                            tagPrefix = tagPrefix,
                            busy = busy,
                            colors = colors,
                            typography = typography,
                            onRemove = onRemove,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowValues.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun RunningAppGrid(
    apps: List<WindowsProcessCandidate>,
    state: AppRoutingState,
    busy: Boolean,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    onAddProxy: (String) -> Unit,
    onAddDirect: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 520.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
            apps.chunked(columns).forEachIndexed { rowIndex, rowApps ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
                ) {
                    rowApps.forEachIndexed { colIndex, app ->
                        val index = rowIndex * columns + colIndex
                        RunningAppCard(
                            app = app,
                            status = windowsAppRouteStatus(app.name, app.path, state),
                            index = index,
                            busy = busy,
                            colors = colors,
                            typography = typography,
                            onAddProxy = { onAddProxy(windowsProcessRoutingValue(app.name, app.path)) },
                            onAddDirect = { onAddDirect(windowsProcessRoutingValue(app.name, app.path)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowApps.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ConfiguredAppCard(
    value: String,
    status: WindowsAppRouteStatus,
    index: Int,
    tagPrefix: String,
    busy: Boolean,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayName = windowsAppDisplayName(value)
    val shortPath = windowsAppShortPath(value)
    AppCardFrame(
        iconPath = shortPath,
        name = displayName,
        path = shortPath,
        status = status,
        colors = colors,
        typography = typography,
        modifier = modifier,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ProductIconAction(
                icon = Icons.Filled.Close,
                tooltip = "Убрать правило",
                description = "Убрать правило $displayName",
                enabled = !busy,
                tag = "$tagPrefix.$index",
                colors = colors,
                onClick = { onRemove(value) },
            )
        }
    }
}

@Composable
private fun RunningAppCard(
    app: WindowsProcessCandidate,
    status: WindowsAppRouteStatus,
    index: Int,
    busy: Boolean,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    onAddProxy: () -> Unit,
    onAddDirect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppCardFrame(
        iconPath = app.path,
        name = app.name,
        path = app.path,
        status = status,
        colors = colors,
        typography = typography,
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
            ProductAddAction(
                label = "Через GATES",
                tooltip = "Добавить маршрут через GATES",
                tag = "routing.process.proxy.$index",
                enabled = !busy,
                colors = colors,
                typography = typography,
                onClick = onAddProxy,
                modifier = Modifier.weight(1f),
            )
            ProductAddAction(
                label = "Напрямую",
                tooltip = "Добавить прямой маршрут",
                tag = "routing.process.direct.$index",
                enabled = !busy,
                colors = colors,
                typography = typography,
                onClick = onAddDirect,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AppCardFrame(
    iconPath: String,
    name: String,
    path: String,
    status: WindowsAppRouteStatus,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.yokhanOutlinedSurface(colors).padding(YokhanSpacing.ControlHorizontal),
        verticalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
        ) {
            AppIcon(iconPath)
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = colors.primary,
                    fontFamily = typography.mono,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (path.isNotBlank()) {
                    Text(
                        text = path,
                        color = colors.secondary,
                        fontFamily = typography.mono,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        AppStatusBadge(status, colors, typography)
        actions()
    }
}

@Composable
private fun AppIcon(path: String, modifier: Modifier = Modifier) {
    val bitmap = remember(path) {
        if (path.isBlank()) WindowsAppIcons.fallback() else WindowsAppIcons.icon(path)
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier.size(34.dp).clip(RoundedCornerShape(YokhanRadius.Small)),
    )
}

@Composable
private fun AppStatusBadge(
    status: WindowsAppRouteStatus,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    modifier: Modifier = Modifier,
) {
    val (label, tint) = when (status) {
        WindowsAppRouteStatus.PROXY -> "Через GATES" to colors.accent
        WindowsAppRouteStatus.DIRECT -> "Мимо туннеля" to YokhanRed
        WindowsAppRouteStatus.UNSET -> "Не настроено" to colors.tertiary
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(YokhanRadius.Pill))
            .border(YokhanStroke.Thin, tint.copy(alpha = 0.55f), RoundedCornerShape(YokhanRadius.Pill))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.StatusDot),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
        Text(
            text = label,
            color = tint,
            fontFamily = typography.mono,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppAddTile(
    busy: Boolean,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    onPickExe: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = YokhanSpacing.ControlMinHeight)
            .yokhanOutlinedSurface(colors, radius = YokhanRadius.Pill)
            .clickable(enabled = !busy, role = Role.Button, onClick = onPickExe)
            .testTag("routing.add")
            .semantics { contentDescription = "Добавить приложение" }
            .padding(horizontal = YokhanSpacing.ControlHorizontal, vertical = YokhanSpacing.ControlVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(YokhanSpacing.CompactGap))
        Text(
            text = "Добавить приложение",
            color = colors.primary,
            fontFamily = typography.mono,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductAddAction(
    label: String,
    tooltip: String,
    tag: String,
    enabled: Boolean,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = YokhanSpacing.ControlMinHeight)
                .yokhanOutlinedSurface(colors, radius = YokhanRadius.Pill)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .testTag(tag)
                .semantics { contentDescription = label }
                .padding(horizontal = YokhanSpacing.CompactGap, vertical = YokhanSpacing.ControlVertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
            Text(
                text = label,
                color = colors.primary,
                fontFamily = typography.mono,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    modifier: Modifier = Modifier,
    multiline: Boolean = false,
    fieldTag: String,
    clearTag: String,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth().yokhanOutlinedSurface(
            colors = colors,
            radius = YokhanRadius.Panel,
            backgroundColor = colors.primary.copy(alpha = 0.035f),
        ).padding(horizontal = YokhanSpacing.ControlHorizontal, vertical = YokhanSpacing.ControlVertical),
        verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
    ) {
        if (!multiline) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Поиск процессов") } },
                state = rememberTooltipState(),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = "Поиск процессов", tint = colors.secondary)
            }
        }
        BasicTextField(
            enabled = enabled,
            value = value,
            onValueChange = onValueChange,
            singleLine = !multiline,
            maxLines = if (multiline) 7 else 1,
            cursorBrush = SolidColor(colors.accent),
            textStyle = TextStyle(
                color = colors.primary,
                fontFamily = typography.mono,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            ),
            modifier = Modifier.weight(1f)
                .padding(top = if (multiline) 0.dp else 2.dp)
                .testTag(fieldTag),
            decorationBox = { input ->
                Box {
                    if (value.isEmpty()) {
                        YokhanCaption(placeholder, colors, typography)
                    }
                    input()
                }
            },
        )
        if (value.isNotEmpty()) {
            ProductIconAction(
                icon = Icons.Filled.Close,
                tooltip = "Очистить поле",
                description = "Очистить поле",
                enabled = enabled,
                tag = clearTag,
                colors = colors,
                onClick = { onValueChange("") },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tooltip: String,
    description: String,
    enabled: Boolean,
    tag: String,
    colors: YokhanPalette,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(YokhanSpacing.ControlMinHeight).testTag(tag),
        ) {
            Icon(icon, contentDescription = description, tint = colors.primary)
        }
    }
}

@Composable
private fun ProductStatus(
    text: String,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    modifier: Modifier = Modifier,
) {
    YokhanBody(text, colors, typography, modifier)
}
