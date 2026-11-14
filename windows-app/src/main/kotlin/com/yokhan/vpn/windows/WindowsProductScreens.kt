package com.yokhan.gates.windows

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yokhan.gates.shared.ui.design.YokhanAccent
import com.yokhan.gates.shared.ui.design.YokhanLayout
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanRadius
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.YokhanStroke
import com.yokhan.gates.shared.ui.route.YokhanAdaptiveActionPair
import com.yokhan.gates.shared.ui.route.YokhanBody
import com.yokhan.gates.shared.ui.route.YokhanCaption
import com.yokhan.gates.shared.ui.route.YokhanMonoValue
import com.yokhan.gates.shared.ui.route.YokhanRouteButton
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanRouteFrame
import com.yokhan.gates.shared.ui.route.YokhanRouteLazyFrame
import com.yokhan.gates.shared.ui.route.YokhanSectionLabel
import com.yokhan.gates.shared.ui.route.YokhanToggleRow
import com.yokhan.gates.shared.ui.route.yokhanOutlinedSurface
import java.io.File

private const val REPORT_LIMIT = 2_000

private object WindowsRoutingCopy {
    const val VIA_GATES = "Через GATES"
    const val DIRECT = "Напрямую"
    const val UNSET = "Не настроено"
}

@Composable
internal fun WindowsNetworkSettingsSection(
    busy: Boolean,
    runtimeMode: String,
    onRuntimeModeChange: (String) -> Unit,
    autoStartOnLogin: Boolean,
    onAutoStartChange: ((Boolean) -> Unit)?,
    killSwitchEnabled: Boolean,
    protectionMessage: String,
    appProxyProcessPaths: List<String>,
    onKillSwitchChange: ((Boolean) -> Unit)?,
    onPickProtectedExe: (() -> Unit)?,
    onOpenRouting: (() -> Unit)?,
    listenHost: String,
    socksPort: Int,
    httpPort: Int,
    systemProxyEnabled: Boolean,
    restoreSystemProxyOnStop: Boolean,
    tunStack: String,
    tunMtu: Int,
    tunStrictRoute: Boolean,
    physicalBindEnabled: Boolean,
    physicalBindInterface: String,
    onSaveNetworkSettings: ((
        listenHost: String,
        socksPort: Int,
        httpPort: Int,
        systemProxyEnabled: Boolean,
        restoreSystemProxyOnStop: Boolean,
        tunStack: String,
        tunMtu: Int,
        tunStrictRoute: Boolean,
        physicalBindEnabled: Boolean,
        physicalBindInterface: String,
    ) -> Unit)?,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
) {
    var draftListenHost by remember(listenHost) { mutableStateOf(listenHost) }
    var draftSocksPort by remember(socksPort) { mutableStateOf(socksPort.toString()) }
    var draftHttpPort by remember(httpPort) { mutableStateOf(httpPort.toString()) }
    var draftSystemProxy by remember(systemProxyEnabled) { mutableStateOf(systemProxyEnabled) }
    var draftRestoreProxy by remember(restoreSystemProxyOnStop) { mutableStateOf(restoreSystemProxyOnStop) }
    var draftTunStack by remember(tunStack) { mutableStateOf(tunStack) }
    var draftTunMtu by remember(tunMtu) { mutableStateOf(tunMtu.toString()) }
    var draftTunStrict by remember(tunStrictRoute) { mutableStateOf(tunStrictRoute) }
    var draftPhysicalBind by remember(physicalBindEnabled) { mutableStateOf(physicalBindEnabled) }
    var draftPhysicalIface by remember(physicalBindInterface) { mutableStateOf(physicalBindInterface) }

    YokhanRouteCard("Режим ядра", colors, typography) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
            listOf("tun" to "TUN (служба)", "proxy" to "Локальный прокси").forEach { (mode, label) ->
                SettingsOptionPill(
                    label = label,
                    selected = runtimeMode.equals(mode, ignoreCase = true),
                    enabled = !busy,
                    colors = colors,
                    typography = typography,
                    tag = "settings.runtime.$mode",
                    onClick = { onRuntimeModeChange(mode) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (onKillSwitchChange != null) {
        YokhanRouteCard("Защита при обрыве (WFP)", colors, typography) {
            YokhanToggleRow(
                title = "Защищать EXE из списка «${WindowsRoutingCopy.VIA_GATES}»",
                summary = protectionMessage.ifBlank {
                    "Блокирует прямой выход выбранных EXE при обрыве или отключении туннеля."
                },
                checked = killSwitchEnabled,
                colors = colors,
                typography = typography,
                onCheckedChange = { if (!busy) onKillSwitchChange(it) },
                modifier = Modifier.testTag("settings.kill-switch"),
            )
            Spacer(Modifier.height(YokhanSpacing.CompactGap))
            if (appProxyProcessPaths.isEmpty()) {
                YokhanCaption(
                    text = "Добавьте хотя бы один .exe файл — Kill Switch будет блокировать его выход в обход GATES.",
                    colors = colors,
                    typography = typography,
                )
            } else {
                val exeNames = appProxyProcessPaths.joinToString(", ") { it.substringAfterLast('\\') }
                YokhanCaption(
                    text = "Под защитой (${appProxyProcessPaths.size}): $exeNames",
                    colors = colors,
                    typography = typography,
                )
            }
            if (onPickProtectedExe != null || onOpenRouting != null) {
                Spacer(Modifier.height(YokhanSpacing.CompactGap))
                Row(horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                    if (onPickProtectedExe != null) {
                        YokhanRouteButton(
                            text = if (appProxyProcessPaths.isEmpty()) "Выбрать .exe" else "Добавить .exe",
                            primary = appProxyProcessPaths.isEmpty(),
                            enabled = !busy,
                            colors = colors,
                            typography = typography,
                            onClick = onPickProtectedExe,
                            modifier = Modifier.widthIn(min = 140.dp).testTag("settings.kill-switch.pick-exe"),
                        )
                    }
                    if (onOpenRouting != null) {
                        YokhanRouteButton(
                            text = "Список приложений",
                            primary = false,
                            enabled = !busy,
                            colors = colors,
                            typography = typography,
                            onClick = onOpenRouting,
                            modifier = Modifier.widthIn(min = 156.dp).testTag("settings.kill-switch.apps"),
                        )
                    }
                }
            }
        }
    }

    if (onAutoStartChange != null) {
        YokhanRouteCard("Запуск Windows", colors, typography) {
            YokhanToggleRow(
                title = "Запускать при входе в Windows",
                summary = "Открывает GATES автоматически; закрытие окна оставляет туннель в фоновой службе.",
                checked = autoStartOnLogin,
                colors = colors,
                typography = typography,
                onCheckedChange = { if (!busy) onAutoStartChange(it) },
                modifier = Modifier.testTag("settings.autostart"),
            )
        }
    }

    if (onSaveNetworkSettings != null) {
        YokhanRouteCard("Сетевые порты, TUN и Physical Bind", colors, typography) {
            YokhanSectionLabel("Локальные порты", colors, typography)
            Spacer(Modifier.height(YokhanSpacing.CompactGap))
            ProductTextInput(
                value = draftListenHost,
                onValueChange = { draftListenHost = it },
                placeholder = "Listen host (127.0.0.1)",
                colors = colors,
                typography = typography,
                enabled = !busy,
                showSearchIcon = false,
                fieldTag = "settings.listen_host",
                clearTag = "settings.listen_host.clear",
            )
            Spacer(Modifier.height(YokhanSpacing.CompactGap))
            Row(horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                ProductTextInput(
                    value = draftSocksPort,
                    onValueChange = { draftSocksPort = it.filter(Char::isDigit).take(5) },
                    placeholder = "SOCKS5 (2088)",
                    colors = colors,
                    typography = typography,
                    enabled = !busy,
                    showSearchIcon = false,
                    fieldTag = "settings.socks_port",
                    clearTag = "settings.socks_port.clear",
                    modifier = Modifier.weight(1f),
                )
                ProductTextInput(
                    value = draftHttpPort,
                    onValueChange = { draftHttpPort = it.filter(Char::isDigit).take(5) },
                    placeholder = "HTTP (2089)",
                    colors = colors,
                    typography = typography,
                    enabled = !busy,
                    showSearchIcon = false,
                    fieldTag = "settings.http_port",
                    clearTag = "settings.http_port.clear",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(YokhanSpacing.CompactGap))
            YokhanToggleRow(
                title = "Системный прокси Windows",
                summary = "Включать системный прокси в режиме Proxy и сбрасывать при остановке.",
                checked = draftSystemProxy,
                colors = colors,
                typography = typography,
                onCheckedChange = { if (!busy) draftSystemProxy = it },
                modifier = Modifier.testTag("settings.system_proxy"),
            )
            YokhanToggleRow(
                title = "Восстанавливать системный прокси",
                summary = "Возвращает прежние параметры IE/WinINet при отключении.",
                checked = draftRestoreProxy,
                colors = colors,
                typography = typography,
                onCheckedChange = { if (!busy) draftRestoreProxy = it },
                modifier = Modifier.testTag("settings.restore_proxy"),
            )

            Spacer(Modifier.height(YokhanSpacing.CompactGap))
            YokhanSectionLabel("Стек и MTU TUN", colors, typography)
            Spacer(Modifier.height(YokhanSpacing.CompactGap))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                listOf("mixed", "system", "gvisor").forEach { stack ->
                    SettingsOptionPill(
                        label = stack.uppercase(),
                        selected = draftTunStack == stack,
                        enabled = !busy,
                        colors = colors,
                        typography = typography,
                        tag = "settings.tun_stack.$stack",
                        onClick = { draftTunStack = stack },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(YokhanSpacing.CompactGap))
            ProductTextInput(
                value = draftTunMtu,
                onValueChange = { draftTunMtu = it.filter(Char::isDigit).take(4) },
                placeholder = "MTU (1280..9000, по умолч. 9000)",
                colors = colors,
                typography = typography,
                enabled = !busy,
                showSearchIcon = false,
                fieldTag = "settings.tun_mtu",
                clearTag = "settings.tun_mtu.clear",
            )
            Spacer(Modifier.height(YokhanSpacing.CompactGap))
            YokhanToggleRow(
                title = "Strict Route",
                summary = "Строгая изоляция системных маршрутов в режиме TUN.",
                checked = draftTunStrict,
                colors = colors,
                typography = typography,
                onCheckedChange = { if (!busy) draftTunStrict = it },
                modifier = Modifier.testTag("settings.tun_strict"),
            )
            YokhanToggleRow(
                title = "Physical Bind",
                summary = "Привязка XHTTP/Bridge/Relay к физическому адаптеру для защиты от TUN-петли.",
                checked = draftPhysicalBind,
                colors = colors,
                typography = typography,
                onCheckedChange = { if (!busy) draftPhysicalBind = it },
                modifier = Modifier.testTag("settings.physical_bind"),
            )
            if (draftPhysicalBind) {
                Spacer(Modifier.height(YokhanSpacing.CompactGap))
                ProductTextInput(
                    value = draftPhysicalIface,
                    onValueChange = { draftPhysicalIface = it },
                    placeholder = "Адаптер (пусто = авто, например Wi-Fi / Ethernet)",
                    colors = colors,
                    typography = typography,
                    enabled = !busy,
                    showSearchIcon = false,
                    fieldTag = "settings.physical_iface",
                    clearTag = "settings.physical_iface.clear",
                )
            }
            Spacer(Modifier.height(YokhanSpacing.CardTitleGap))
            val parsedSocks = draftSocksPort.toIntOrNull()
            val parsedHttp = draftHttpPort.toIntOrNull()
            val parsedMtu = draftTunMtu.toIntOrNull()
            YokhanRouteButton(
                text = "Применить сетевые параметры",
                primary = true,
                enabled = !busy && parsedSocks != null && parsedHttp != null && parsedMtu != null,
                colors = colors,
                typography = typography,
                onClick = {
                    if (parsedSocks != null && parsedHttp != null && parsedMtu != null) {
                        onSaveNetworkSettings(
                            draftListenHost,
                            parsedSocks,
                            parsedHttp,
                            draftSystemProxy,
                            draftRestoreProxy,
                            draftTunStack,
                            parsedMtu,
                            draftTunStrict,
                            draftPhysicalBind,
                            draftPhysicalIface,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("settings.save_network"),
            )
        }
    }
}

@Composable
internal fun WindowsSettingsScreen(
    busy: Boolean,
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
    appProxyProcessPaths: List<String> = emptyList(),
    onKillSwitchChange: ((Boolean) -> Unit)? = null,
    onPickProtectedExe: (() -> Unit)? = null,
    onOpenRouting: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    bottomInsetPaddingDp: Int = 64,
    accountLabel: String = "",
    statusSummary: String = "",
    onRefreshStatus: (() -> Unit)? = null,
    onOpenLogs: (() -> Unit)? = null,
    onExportSupportBundle: (() -> Unit)? = null,
    onResetSystemProxy: (() -> Unit)? = null,
    autoStartOnLogin: Boolean = false,
    onAutoStartChange: ((Boolean) -> Unit)? = null,
    listenHost: String = "127.0.0.1",
    socksPort: Int = 2088,
    httpPort: Int = 2089,
    systemProxyEnabled: Boolean = true,
    restoreSystemProxyOnStop: Boolean = true,
    tunStack: String = "mixed",
    tunMtu: Int = 9000,
    tunStrictRoute: Boolean = true,
    physicalBindEnabled: Boolean = true,
    physicalBindInterface: String = "",
    onSaveNetworkSettings: ((
        listenHost: String,
        socksPort: Int,
        httpPort: Int,
        systemProxyEnabled: Boolean,
        restoreSystemProxyOnStop: Boolean,
        tunStack: String,
        tunMtu: Int,
        tunStrictRoute: Boolean,
        physicalBindEnabled: Boolean,
        physicalBindInterface: String,
    ) -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val wideDesktop = maxWidth >= YokhanLayout.DesktopContentCompanionBreakpoint

        @Composable
        fun DiagnosticsAndShortcutsCards(
            colors: YokhanPalette,
            typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
        ) {
            YokhanRouteCard("Диагностика и журналы", colors, typography) {
                if (onRefreshStatus != null || statusSummary.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            YokhanCaption(
                                text = statusSummary.ifBlank { "Состояние службы Windows и внешнего IP-адреса" },
                                colors = colors,
                                typography = typography,
                            )
                        }
                        if (onRefreshStatus != null) {
                            YokhanRouteButton(
                                text = if (busy) "Обновление…" else "Проверить службу и IP",
                                primary = false,
                                enabled = !busy,
                                colors = colors,
                                typography = typography,
                                onClick = onRefreshStatus,
                                modifier = Modifier.widthIn(min = 186.dp).testTag("settings.refresh_status"),
                            )
                        }
                    }
                    Spacer(Modifier.height(YokhanSpacing.CompactGap))
                }
                YokhanAdaptiveActionPair(
                    first = { actionModifier ->
                        YokhanRouteButton(
                            text = "Логи службы и Xray",
                            primary = false,
                            enabled = !busy && onOpenLogs != null,
                            colors = colors,
                            typography = typography,
                            onClick = { onOpenLogs?.invoke() },
                            modifier = actionModifier.testTag("settings.logs"),
                        )
                    },
                    second = { actionModifier ->
                        YokhanRouteButton(
                            text = "История соединения",
                            primary = false,
                            enabled = !busy,
                            colors = colors,
                            typography = typography,
                            onClick = onHistory,
                            modifier = actionModifier.testTag("settings.history"),
                        )
                    },
                )
                if (onExportSupportBundle != null || onResetSystemProxy != null) {
                    Spacer(Modifier.height(YokhanSpacing.CompactGap))
                    YokhanAdaptiveActionPair(
                        first = { actionModifier ->
                            YokhanRouteButton(
                                text = "Экспорт ZIP-диагностики",
                                primary = false,
                                enabled = !busy && onExportSupportBundle != null,
                                colors = colors,
                                typography = typography,
                                onClick = { onExportSupportBundle?.invoke() },
                                modifier = actionModifier.testTag("settings.support_bundle"),
                            )
                        },
                        second = { actionModifier ->
                            YokhanRouteButton(
                                text = "Сбросить прокси Windows",
                                primary = false,
                                enabled = !busy && onResetSystemProxy != null,
                                colors = colors,
                                typography = typography,
                                onClick = { onResetSystemProxy?.invoke() },
                                modifier = actionModifier.testTag("settings.reset_proxy"),
                            )
                        },
                    )
                }
            }

            YokhanRouteCard("Поддержка", colors, typography) {
                if (onAutoUploadRecoveryReportsChange != null) {
                    YokhanToggleRow(
                        title = "Компактные отчёты о сбоях",
                        summary = "Автоматически отправлять обезличенную диагностику при сбоях восстановления.",
                        checked = autoUploadRecoveryReports,
                        colors = colors,
                        typography = typography,
                        onCheckedChange = { if (!busy) onAutoUploadRecoveryReportsChange(it) },
                        modifier = Modifier.testTag("settings.recovery-upload"),
                    )
                    Spacer(Modifier.height(YokhanSpacing.CompactGap))
                }
                YokhanRouteButton(
                    text = "Сообщить об ошибке",
                    primary = false,
                    enabled = !busy,
                    colors = colors,
                    typography = typography,
                    onClick = onBugReport,
                    modifier = Modifier.widthIn(min = 190.dp).testTag("settings.report"),
                )
            }

            YokhanRouteCard("Горячие клавиши", colors, typography) {
                listOf(
                    "Ctrl + 1..4" to "Переключение 4 разделов рабочего стола",
                    "Ctrl + F" to "Быстрый фокус поиска приложений",
                    "Ctrl + R" to "Обновить статус, сеть или список программ",
                    "Ctrl + V" to "Вставить подписку на экране входа",
                    "Esc" to "Назад или сброс текущего выбора",
                ).forEach { (key, desc) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        YokhanMonoValue(key, colors, typography, sizeSp = 11, modifier = Modifier.width(104.dp))
                        YokhanCaption(desc, colors, typography, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        YokhanRouteFrame(
            title = "Система и доступ",
            subtitle = "Учётная запись, режим Windows-службы, обновления и диагностика",
            systemLabel = "04",
            spaceActive = spaceActive,
            onMenuClick = onMenuClick ?: onDismiss,
            onBack = onBack,
            bottomInsetPaddingDp = bottomInsetPaddingDp,
            modifier = Modifier.testTag("settings.screen"),
            companionContent = if (wideDesktop) { colors, typography ->
                DiagnosticsAndShortcutsCards(colors, typography)
            } else null,
        ) { colors, typography ->
            if (statusMessage.isNotBlank()) YokhanBody(statusMessage, colors, typography)

            YokhanRouteCard("Аккаунт и подписка", colors, typography) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        YokhanMonoValue(
                            accountLabel.ifBlank { "Аккаунт не выбран" },
                            colors,
                            typography,
                            sizeSp = 13,
                        )
                        YokhanCaption("Токен или ссылка подписки", colors, typography)
                    }
                    YokhanRouteButton(
                        text = "Сменить / подписка",
                        primary = false,
                        enabled = !busy,
                        colors = colors,
                        typography = typography,
                        onClick = onAccess,
                        modifier = Modifier.widthIn(min = 160.dp).testTag("settings.access"),
                    )
                }
            }

            WindowsNetworkSettingsSection(
                busy = busy,
                runtimeMode = runtimeMode,
                onRuntimeModeChange = onRuntimeModeChange,
                autoStartOnLogin = autoStartOnLogin,
                onAutoStartChange = onAutoStartChange,
                killSwitchEnabled = killSwitchEnabled,
                protectionMessage = protectionMessage,
                appProxyProcessPaths = appProxyProcessPaths,
                onKillSwitchChange = onKillSwitchChange,
                onPickProtectedExe = onPickProtectedExe,
                onOpenRouting = onOpenRouting,
                listenHost = listenHost,
                socksPort = socksPort,
                httpPort = httpPort,
                systemProxyEnabled = systemProxyEnabled,
                restoreSystemProxyOnStop = restoreSystemProxyOnStop,
                tunStack = tunStack,
                tunMtu = tunMtu,
                tunStrictRoute = tunStrictRoute,
                physicalBindEnabled = physicalBindEnabled,
                physicalBindInterface = physicalBindInterface,
                onSaveNetworkSettings = onSaveNetworkSettings,
                colors = colors,
                typography = typography,
            )

            YokhanRouteCard("Обновления и версия", colors, typography) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        YokhanMonoValue(appVersion.ifBlank { "—" }, colors, typography, sizeSp = 13)
                        YokhanCaption("Версия Windows-клиента", colors, typography)
                    }
                    YokhanRouteButton(
                        text = "Проверить обновления",
                        primary = false,
                        enabled = !busy,
                        colors = colors,
                        typography = typography,
                        onClick = onUpdate,
                        modifier = Modifier.widthIn(min = 172.dp).testTag("settings.update"),
                    )
                }
                if (onInstallUpdate != null) {
                    Spacer(Modifier.height(YokhanSpacing.CompactGap))
                    YokhanRouteButton(
                        "Установить обновление",
                        true,
                        !busy,
                        colors,
                        typography,
                        onInstallUpdate,
                        Modifier.widthIn(min = 190.dp).testTag("settings.install"),
                    )
                }
            }
            if (!wideDesktop) {
                DiagnosticsAndShortcutsCards(colors, typography)
            }
        }
    }
}

@Composable
private fun SettingsOptionPill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(YokhanRadius.Pill))
            .background(if (selected) colors.accent else colors.surface)
            .border(YokhanStroke.Thin, if (selected) colors.accent else colors.border, RoundedCornerShape(YokhanRadius.Pill))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) colors.onAccent else colors.primary,
            fontFamily = typography.mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WindowsAppRoutingScreen(
    state: AppRoutingState,
    runningApps: List<WindowsProcessCandidate>,
    busy: Boolean,
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
    installedApps: List<WindowsInstalledAppCandidate> = emptyList(),
    activeNetworkApps: List<WindowsActiveNetworkApp> = emptyList(),
    enableRuBypass: Boolean = true,
    onRuBypassChange: ((Boolean) -> Unit)? = null,
    onMoveRule: ((target: String, toProxy: Boolean) -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
    searchFocusRequester: FocusRequester? = null,
    proxyDropHovered: Boolean = false,
    directDropHovered: Boolean = false,
    onProxyDropBoundsChanged: (Rect?) -> Unit = {},
    onDirectDropBoundsChanged: (Rect?) -> Unit = {},
    pendingDroppedPaths: List<String> = emptyList(),
    onAssignPendingDrop: ((proxy: Boolean) -> Unit)? = null,
    onDismissPendingDrop: (() -> Unit)? = null,
    onInAppCardDrag: ((label: String, target: String, pointInRoot: Offset?) -> Unit)? = null,
    onInAppCardDrop: ((target: String, pointInRoot: Offset) -> Unit)? = null,
    bottomInsetPaddingDp: Int = 64,
    activeNetworkContent: (@Composable (YokhanPalette, com.yokhan.gates.shared.ui.design.YokhanTypography) -> Unit)? = null,
) {
    var searchText by remember { mutableStateOf("") }
    var manualTargetText by remember { mutableStateOf("") }
    var refreshingProcesses by remember { mutableStateOf(false) }
    var sourceTab by remember(installedApps.isNotEmpty()) {
        mutableStateOf(if (installedApps.isNotEmpty()) "installed" else "running")
    }
    var categoryFilter by remember { mutableStateOf("all") }

    val manualTarget = remember(manualTargetText) { parseWindowsAppRoutingTarget(manualTargetText) }
    val manualTargetError = manualTargetText.isNotBlank() && manualTarget == null
    LaunchedEffect(busy) {
        if (!busy) refreshingProcesses = false
    }

    val networkExeSet = remember(activeNetworkApps) {
        activeNetworkApps.map { it.name.lowercase() }.toSet()
    }

    val matchingRunningApps = remember(runningApps, searchText, categoryFilter, state, networkExeSet) {
        val query = searchText.trim()
        runningApps.filter { app ->
            val matchesQuery = query.isEmpty() || app.name.contains(query, true) || app.path.contains(query, true)
            val status = windowsAppRouteStatus(app.name, app.path, state)
            val matchesCategory = when (categoryFilter) {
                "ru" -> isRuCompatibilityCandidate(app.name, app.path)
                "selected" -> status != WindowsAppRouteStatus.UNSET
                "network" -> app.name.lowercase() in networkExeSet
                else -> true
            }
            matchesQuery && matchesCategory
        }
    }

    val matchingInstalledApps = remember(installedApps, searchText, categoryFilter, state, networkExeSet) {
        val query = searchText.trim()
        installedApps.filter { app ->
            val matchesQuery = query.isEmpty() ||
                app.displayName.contains(query, true) ||
                app.name.contains(query, true) ||
                app.path.contains(query, true)
            val status = windowsAppRouteStatus(app.name, app.path, state)
            val matchesCategory = when (categoryFilter) {
                "ru" -> app.isRuCandidate
                "selected" -> status != WindowsAppRouteStatus.UNSET
                "network" -> app.name.lowercase() in networkExeSet
                else -> true
            }
            matchesQuery && matchesCategory
        }
    }

    val proxyValues = remember(state) { state.proxyProcessPaths + state.proxyProcessNames }
    val directValues = remember(state) { state.directProcessPaths + state.directProcessNames }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val wideDesktop = maxWidth >= YokhanLayout.DesktopContentCompanionBreakpoint

        @Composable
        fun DropAndRuleControls(
            colors: YokhanPalette,
            typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(YokhanSpacing.SectionGap)) {
                if (pendingDroppedPaths.isNotEmpty() && onAssignPendingDrop != null) {
                    YokhanRouteCard("Куда направить перетащенные (${pendingDroppedPaths.size})", colors, typography) {
                        YokhanCaption(
                            text = pendingDroppedPaths.take(3).joinToString(", ") { windowsAppDisplayName(it) },
                            colors = colors,
                            typography = typography,
                        )
                        Spacer(Modifier.height(YokhanSpacing.CompactGap))
                        Row(horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                            YokhanRouteButton(
                                text = WindowsRoutingCopy.VIA_GATES,
                                primary = true,
                                enabled = !busy,
                                colors = colors,
                                typography = typography,
                                onClick = { onAssignPendingDrop(true) },
                                modifier = Modifier.weight(1f).testTag("routing.pending_drop.proxy"),
                            )
                            YokhanRouteButton(
                                text = WindowsRoutingCopy.DIRECT,
                                primary = false,
                                enabled = !busy,
                                colors = colors,
                                typography = typography,
                                onClick = { onAssignPendingDrop(false) },
                                modifier = Modifier.weight(1f).testTag("routing.pending_drop.direct"),
                            )
                        }
                        if (onDismissPendingDrop != null) {
                            Spacer(Modifier.height(YokhanSpacing.CompactGap))
                            YokhanRouteButton(
                                text = "Отмена",
                                primary = false,
                                enabled = !busy,
                                colors = colors,
                                typography = typography,
                                onClick = onDismissPendingDrop,
                                modifier = Modifier.fillMaxWidth().testTag("routing.pending_drop.cancel"),
                            )
                        }
                    }
                }

                YokhanRouteCard("Перетащите .exe, ярлык .lnk, папку или карточку", colors, typography) {
                    Row(horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                        DropZoneBox(
                            title = "Перетащить в GATES",
                            subtitle = "Трафик пойдёт через туннель",
                            highlighted = proxyDropHovered,
                            accentHighlight = true,
                            enabled = !busy,
                            colors = colors,
                            typography = typography,
                            tag = "routing.drop.proxy",
                            onBoundsChanged = onProxyDropBoundsChanged,
                            onClick = { onPickExe(true) },
                            modifier = Modifier.weight(1f),
                        )
                        DropZoneBox(
                            title = "Перетащить Напрямую",
                            subtitle = "В обход туннеля (Direct)",
                            highlighted = directDropHovered,
                            accentHighlight = false,
                            enabled = !busy,
                            colors = colors,
                            typography = typography,
                            tag = "routing.drop.direct",
                            onBoundsChanged = onDirectDropBoundsChanged,
                            onClick = { onPickExe(false) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (onRuBypassChange != null) {
                    YokhanRouteCard("Умный автообход РФ", colors, typography) {
                        YokhanToggleRow(
                            title = "Автообход российских программ и .ru/.рф",
                            summary = "Яндекс, VK, 1С, СБИС, 2ГИС и банки работают напрямую; ваши правила «Через GATES» всегда важнее.",
                            checked = enableRuBypass,
                            colors = colors,
                            typography = typography,
                            onCheckedChange = { if (!busy) onRuBypassChange(it) },
                            modifier = Modifier.testTag("routing.ru_bypass_toggle"),
                        )
                    }
                }

                YokhanRouteCard("Режим туннеля и ручной ввод", colors, typography) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                        listOf(true to "Весь трафик (кроме прямых)", false to "Только выбранные").forEach { (isAll, label) ->
                            SettingsOptionPill(
                                label = label,
                                selected = if (isAll) state.routeAll else !state.routeAll,
                                enabled = !busy,
                                colors = colors,
                                typography = typography,
                                tag = if (isAll) "routing.mode.all" else "routing.mode.selected",
                                onClick = if (isAll) onRouteAll else onRouteSelected,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(YokhanSpacing.CompactGap))
                    Row(horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                        ProductAddAction(
                            label = "Выбрать .exe → GATES",
                            tooltip = "Выбрать .exe или .lnk для маршрута через GATES",
                            tag = "routing.pick-exe.proxy",
                            enabled = !busy,
                            colors = colors,
                            typography = typography,
                            onClick = { onPickExe(true) },
                            modifier = Modifier.weight(1f),
                        )
                        ProductAddAction(
                            label = "Выбрать .exe → Прямо",
                            tooltip = "Выбрать .exe или .lnk для прямого маршрута",
                            tag = "routing.pick-exe.direct",
                            enabled = !busy,
                            colors = colors,
                            typography = typography,
                            onClick = { onPickExe(false) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(YokhanSpacing.CompactGap))
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
                    Spacer(Modifier.height(YokhanSpacing.CompactGap))
                    Row(horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                        ProductAddAction(
                            label = "Через GATES",
                            tooltip = "Добавить введённый .exe в маршрут через GATES",
                            tag = "routing.manual-exe.proxy",
                            enabled = !busy && manualTarget != null,
                            colors = colors,
                            typography = typography,
                            onClick = { manualTarget?.let { onAddProxy(it.label) } },
                            modifier = Modifier.weight(1f),
                        )
                        ProductAddAction(
                            label = "Напрямую",
                            tooltip = "Добавить введённый .exe в прямой маршрут",
                            tag = "routing.manual-exe.direct",
                            enabled = !busy && manualTarget != null,
                            colors = colors,
                            typography = typography,
                            onClick = { manualTarget?.let { onAddDirect(it.label) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                    YokhanSectionLabel("Через GATES (${proxyValues.size})", colors, typography)
                    ConfiguredAppGrid(
                        values = proxyValues,
                        status = WindowsAppRouteStatus.PROXY,
                        tagPrefix = "routing.proxy.remove",
                        moveTagPrefix = "routing.proxy.move",
                        busy = busy,
                        colors = colors,
                        typography = typography,
                        onRemove = onRemoveProxy,
                        onMove = onMoveRule?.let { move -> { value -> move(value, false) } },
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                    YokhanSectionLabel("Напрямую (${directValues.size})", colors, typography)
                    ConfiguredAppGrid(
                        values = directValues,
                        status = WindowsAppRouteStatus.DIRECT,
                        tagPrefix = "routing.direct.remove",
                        moveTagPrefix = "routing.direct.move",
                        busy = busy,
                        colors = colors,
                        typography = typography,
                        onRemove = onRemoveDirect,
                        onMove = onMoveRule?.let { move -> { value -> move(value, true) } },
                    )
                }
            }
        }

        YokhanRouteLazyFrame(
            title = "Приложения и обход",
            subtitle = "Клик по плитке меняет маршрут · перетащите .exe или .lnk для добавления",
            systemLabel = "02",
            spaceActive = spaceActive,
            onMenuClick = onMenuClick ?: onDismiss,
            onBack = null,
            bottomInsetPaddingDp = bottomInsetPaddingDp,
            modifier = Modifier.testTag("routing.screen"),
            fullWidthContent = wideDesktop,
            companionContent = null,
        ) { colors, typography ->
            if (message.isNotBlank()) {
                item(key = "routing-message") {
                    YokhanBody(message, colors, typography)
                }
            }

            if (busy && !refreshingProcesses) {
                item(key = "routing-pending-status") {
                    ProductStatus(
                        "Применение изменений маршрутизации…",
                        colors,
                        typography,
                        modifier = Modifier.testTag("routing.pending"),
                    )
                }
            }

            if (wideDesktop) {
                if (pendingDroppedPaths.isNotEmpty() && onAssignPendingDrop != null) {
                    item(key = "routing-desktop-pending-drop") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .yokhanOutlinedSurface(colors, radius = YokhanRadius.Panel)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            YokhanCaption(
                                text = "Добавить (${pendingDroppedPaths.size}): ${pendingDroppedPaths.take(3).joinToString(", ") { windowsAppDisplayName(it) }}",
                                colors = colors,
                                typography = typography,
                                modifier = Modifier.weight(1f),
                            )
                            YokhanRouteButton(
                                text = WindowsRoutingCopy.VIA_GATES,
                                primary = true,
                                enabled = !busy,
                                colors = colors,
                                typography = typography,
                                onClick = { onAssignPendingDrop(true) },
                                modifier = Modifier.testTag("routing.pending_drop.proxy"),
                            )
                            YokhanRouteButton(
                                text = WindowsRoutingCopy.DIRECT,
                                primary = false,
                                enabled = !busy,
                                colors = colors,
                                typography = typography,
                                onClick = { onAssignPendingDrop(false) },
                                modifier = Modifier.testTag("routing.pending_drop.direct"),
                            )
                            if (onDismissPendingDrop != null) {
                                YokhanRouteButton(
                                    text = "Отмена",
                                    primary = false,
                                    enabled = !busy,
                                    colors = colors,
                                    typography = typography,
                                    onClick = onDismissPendingDrop,
                                    modifier = Modifier.testTag("routing.pending_drop.cancel"),
                                )
                            }
                        }
                    }
                }

                item(key = "routing-desktop-toolbar") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProductTextInput(
                            value = searchText,
                            onValueChange = { searchText = it },
                            placeholder = "Поиск…",
                            colors = colors,
                            typography = typography,
                            fieldTag = "routing.search",
                            clearTag = "routing.search.clear",
                            inputModifier = Modifier.then(
                                if (searchFocusRequester != null) Modifier.focusRequester(searchFocusRequester) else Modifier,
                            ),
                            modifier = Modifier.weight(1f).widthIn(min = 160.dp),
                        )
                        SettingsOptionPill(
                            label = "Устан. (${installedApps.size})",
                            selected = sourceTab == "installed",
                            enabled = true,
                            colors = colors,
                            typography = typography,
                            tag = "routing.source.installed",
                            onClick = { sourceTab = "installed" },
                        )
                        SettingsOptionPill(
                            label = "Запущ. (${runningApps.size})",
                            selected = sourceTab == "running",
                            enabled = true,
                            colors = colors,
                            typography = typography,
                            tag = "routing.source.running",
                            onClick = { sourceTab = "running" },
                        )
                        listOf(
                            "all" to "Все",
                            "selected" to "Выбр.",
                            "ru" to "РФ",
                            "network" to "Сеть",
                        ).forEach { (key, label) ->
                            SettingsOptionPill(
                                label = label,
                                selected = categoryFilter == key,
                                enabled = true,
                                colors = colors,
                                typography = typography,
                                tag = "routing.filter.$key",
                                onClick = { categoryFilter = key },
                            )
                        }
                        SettingsOptionPill(
                            label = "+ .exe",
                            selected = false,
                            enabled = !busy,
                            colors = colors,
                            typography = typography,
                            tag = "routing.pick-exe.proxy",
                            onClick = { onPickExe(true) },
                        )
                        SettingsOptionPill(
                            label = if (refreshingProcesses && busy) "…" else "↻",
                            selected = false,
                            enabled = !busy,
                            colors = colors,
                            typography = typography,
                            tag = "routing.refresh",
                            onClick = {
                                refreshingProcesses = true
                                onRefreshProcesses()
                            },
                        )
                    }
                }

                item(key = "routing-desktop-legend") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .yokhanOutlinedSurface(colors, radius = YokhanRadius.Panel)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompactLegendChip(
                            label = "Через GATES (${proxyValues.size})",
                            tint = colors.success,
                            colors = colors,
                            typography = typography,
                        )
                        CompactLegendChip(
                            label = "Напрямую (${directValues.size})",
                            tint = YokhanAccent,
                            colors = colors,
                            typography = typography,
                        )
                        CompactLegendChip(
                            label = "По режиму",
                            tint = colors.border,
                            colors = colors,
                            typography = typography,
                        )
                        Spacer(Modifier.weight(1f))
                        SettingsOptionPill(
                            label = "Весь трафик",
                            selected = state.routeAll,
                            enabled = !busy,
                            colors = colors,
                            typography = typography,
                            tag = "routing.mode.all",
                            onClick = onRouteAll,
                        )
                        SettingsOptionPill(
                            label = "Только выбр.",
                            selected = !state.routeAll,
                            enabled = !busy,
                            colors = colors,
                            typography = typography,
                            tag = "routing.mode.selected",
                            onClick = onRouteSelected,
                        )
                        if (onRuBypassChange != null) {
                            SettingsOptionPill(
                                label = if (enableRuBypass) "РФ-обход: ВКЛ" else "РФ-обход: ВЫКЛ",
                                selected = enableRuBypass,
                                enabled = !busy,
                                colors = colors,
                                typography = typography,
                                tag = "routing.ru_bypass_toggle",
                                onClick = { onRuBypassChange(!enableRuBypass) },
                            )
                        }
                    }
                }

                val gridCols = ((maxWidth - 48.dp) / 100.dp).toInt().coerceIn(5, 8)
                val gridApps = if (sourceTab == "installed") {
                    matchingInstalledApps.map { app ->
                        Triple(app.displayName, app.path, windowsProcessRoutingValue(app.name, app.path))
                    }
                } else {
                    matchingRunningApps.map { app ->
                        Triple(app.name, app.path, windowsProcessRoutingValue(app.name, app.path))
                    }
                }
                if (gridApps.isEmpty()) {
                    item(key = "routing-desktop-grid-empty") {
                        ProductStatus(
                            text = if (sourceTab == "installed") {
                                "Нажмите ↻ для сканирования «Пуск» и установленных программ"
                            } else {
                                "Нет приложений по текущему фильтру"
                            },
                            colors = colors,
                            typography = typography,
                        )
                    }
                } else {
                    val gridRows = gridApps.chunked(gridCols)
                    itemsIndexed(
                        items = gridRows,
                        key = { idx, row -> "grid-$sourceTab-$idx-${row.first().second.lowercase()}" },
                    ) { rowIndex, rowApps ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowApps.forEachIndexed { colIndex, (name, path, targetValue) ->
                                val itemIndex = rowIndex * gridCols + colIndex
                                val status = windowsAppRouteStatus(name, path, state)
                                CompactDesktopAppTile(
                                    name = windowsAppDisplayName(name),
                                    path = path,
                                    status = status,
                                    enabled = !busy,
                                    colors = colors,
                                    typography = typography,
                                    tag = "routing.tile.$itemIndex",
                                    onClick = {
                                        when (status) {
                                            WindowsAppRouteStatus.UNSET -> onAddProxy(targetValue)
                                            WindowsAppRouteStatus.PROXY -> onAddDirect(targetValue)
                                            WindowsAppRouteStatus.DIRECT -> onRemoveDirect(targetValue)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(gridCols - rowApps.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                item(key = "routing-desktop-drop-footer") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .onGloballyPositioned { onProxyDropBoundsChanged(it.boundsInRoot()) }
                                .clip(RoundedCornerShape(YokhanRadius.Panel))
                                .background(if (proxyDropHovered) colors.success.copy(alpha = 0.14f) else colors.surface.copy(alpha = 0.5f))
                                .border(YokhanStroke.Thin, if (proxyDropHovered) colors.success else colors.border, RoundedCornerShape(YokhanRadius.Panel))
                                .clickable(enabled = !busy) { onPickExe(true) }
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                                .testTag("routing.drop.proxy"),
                        ) {
                            YokhanCaption("Перетащить .exe → Через GATES", colors, typography)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .onGloballyPositioned { onDirectDropBoundsChanged(it.boundsInRoot()) }
                                .clip(RoundedCornerShape(YokhanRadius.Panel))
                                .background(if (directDropHovered) YokhanAccent.copy(alpha = 0.14f) else colors.surface.copy(alpha = 0.5f))
                                .border(YokhanStroke.Thin, if (directDropHovered) YokhanAccent else colors.border, RoundedCornerShape(YokhanRadius.Panel))
                                .clickable(enabled = !busy) { onPickExe(false) }
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                                .testTag("routing.drop.direct"),
                        ) {
                            YokhanCaption("Перетащить .exe → Напрямую", colors, typography)
                        }
                    }
                }
            } else {
                item(key = "routing-inline-controls") {
                    DropAndRuleControls(colors, typography)
                }

                item(key = "routing-add-tile") {
                    Column(verticalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                        AppAddTile(
                            busy = busy,
                            colors = colors,
                            typography = typography,
                            onPickExe = { onPickExe(true) },
                        )
                        YokhanRouteButton(
                            text = if (refreshingProcesses && busy) "Обновление…" else "Обновить список приложений",
                            primary = false,
                            enabled = !busy,
                            colors = colors,
                            typography = typography,
                            onClick = {
                                refreshingProcesses = true
                                onRefreshProcesses()
                            },
                            modifier = Modifier.fillMaxWidth().testTag("routing.refresh"),
                        )
                    }
                }

                item(key = "routing-running-header") {
                    Column(verticalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                            SettingsOptionPill(
                                label = "Установленные (${installedApps.size})",
                                selected = sourceTab == "installed",
                                enabled = true,
                                colors = colors,
                                typography = typography,
                                tag = "routing.source.installed",
                                onClick = { sourceTab = "installed" },
                                modifier = Modifier.weight(1f),
                            )
                            SettingsOptionPill(
                                label = "Запущенные (${runningApps.size})",
                                selected = sourceTab == "running",
                                enabled = true,
                                colors = colors,
                                typography = typography,
                                tag = "routing.source.running",
                                onClick = { sourceTab = "running" },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap)) {
                            listOf(
                                "all" to "Все",
                                "selected" to "Выбранные",
                                "ru" to "РФ",
                                "network" to "В сети",
                            ).forEach { (key, label) ->
                                SettingsOptionPill(
                                    label = label,
                                    selected = categoryFilter == key,
                                    enabled = true,
                                    colors = colors,
                                    typography = typography,
                                    tag = "routing.filter.$key",
                                    onClick = { categoryFilter = key },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        ProductTextInput(
                            value = searchText,
                            onValueChange = { searchText = it },
                            placeholder = "Поиск по названию, процессу или пути",
                            colors = colors,
                            typography = typography,
                            fieldTag = "routing.search",
                            clearTag = "routing.search.clear",
                            inputModifier = if (searchFocusRequester != null) {
                                Modifier.focusRequester(searchFocusRequester)
                            } else {
                                Modifier
                            },
                        )
                    }
                }

                if (sourceTab == "installed") {
                    when {
                        installedApps.isEmpty() -> item(key = "routing-installed-empty") {
                            ProductStatus("Нажмите «Обновить список приложений» для сканирования «Пуск» и установленных программ", colors, typography)
                        }
                        matchingInstalledApps.isEmpty() -> item(key = "routing-installed-no-match") {
                            ProductStatus("Установленных приложений по фильтру не найдено", colors, typography)
                        }
                        else -> {
                            val appCols = if (maxWidth >= 520.dp) 2 else 1
                            val rows = matchingInstalledApps.chunked(appCols)
                            itemsIndexed(
                                items = rows,
                                key = { idx, row -> "inst-$idx-${row.first().path.lowercase()}" },
                            ) { rowIndex, rowApps ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
                                ) {
                                    rowApps.forEachIndexed { colIndex, app ->
                                        val index = rowIndex * appCols + colIndex
                                        val targetValue = windowsProcessRoutingValue(app.name, app.path)
                                        val appStatus = windowsAppRouteStatus(app.name, app.path, state)
                                        RunningAppCard(
                                            app = WindowsProcessCandidate(index.toLong(), app.displayName, app.path),
                                            status = appStatus,
                                            index = index,
                                            busy = busy,
                                            colors = colors,
                                            typography = typography,
                                            ruCandidate = app.isRuCandidate,
                                            onAddProxy = { onAddProxy(targetValue) },
                                            onAddDirect = { onAddDirect(targetValue) },
                                            onRemoveRule = when (appStatus) {
                                                WindowsAppRouteStatus.PROXY -> { { onRemoveProxy(targetValue) } }
                                                WindowsAppRouteStatus.DIRECT -> { { onRemoveDirect(targetValue) } }
                                                WindowsAppRouteStatus.UNSET -> null
                                            },
                                            dragTarget = targetValue,
                                            onDragMove = onInAppCardDrag,
                                            onDragDrop = onInAppCardDrop,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (appCols > 1 && rowApps.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                } else {
                    when {
                        refreshingProcesses && busy -> item(key = "routing-loading") {
                            ProductStatus("Загрузка процессов…", colors, typography)
                        }
                        runningApps.isEmpty() -> item(key = "routing-empty") {
                            ProductStatus("Нет запущенных процессов", colors, typography)
                        }
                        matchingRunningApps.isEmpty() -> item(key = "routing-no-match") {
                            ProductStatus("Совпадений не найдено", colors, typography)
                        }
                        else -> {
                            val appCols = if (maxWidth >= 520.dp) 2 else 1
                            val rows = matchingRunningApps.chunked(appCols)
                            itemsIndexed(
                                items = rows,
                                key = { idx, row -> "run-$idx-${row.first().pid}-${row.first().path.lowercase()}" },
                            ) { rowIndex, rowApps ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
                                ) {
                                    rowApps.forEachIndexed { colIndex, app ->
                                        val index = rowIndex * appCols + colIndex
                                        val targetValue = windowsProcessRoutingValue(app.name, app.path)
                                        val appStatus = windowsAppRouteStatus(app.name, app.path, state)
                                        RunningAppCard(
                                            app = app,
                                            status = appStatus,
                                            index = index,
                                            busy = busy,
                                            colors = colors,
                                            typography = typography,
                                            ruCandidate = isRuCompatibilityCandidate(app.name, app.path),
                                            onAddProxy = { onAddProxy(targetValue) },
                                            onAddDirect = { onAddDirect(targetValue) },
                                            onRemoveRule = when (appStatus) {
                                                WindowsAppRouteStatus.PROXY -> { { onRemoveProxy(targetValue) } }
                                                WindowsAppRouteStatus.DIRECT -> { { onRemoveDirect(targetValue) } }
                                                WindowsAppRouteStatus.UNSET -> null
                                            },
                                            dragTarget = targetValue,
                                            onDragMove = onInAppCardDrag,
                                            onDragDrop = onInAppCardDrop,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (appCols > 1 && rowApps.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DropZoneBox(
    title: String,
    subtitle: String,
    highlighted: Boolean,
    accentHighlight: Boolean,
    enabled: Boolean,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    tag: String,
    onBoundsChanged: (Rect?) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeTint = if (accentHighlight) colors.accent else colors.danger
    val borderColor = if (highlighted) activeTint else colors.border
    val bgColor = if (highlighted) activeTint.copy(alpha = 0.14f) else colors.surfaceRaised.copy(alpha = 0.45f)
    Column(
        modifier = modifier
            .heightIn(min = 78.dp)
            .onGloballyPositioned { coordinates -> onBoundsChanged(coordinates.boundsInRoot()) }
            .clip(RoundedCornerShape(YokhanRadius.Panel))
            .background(bgColor)
            .border(YokhanStroke.Thin, borderColor, RoundedCornerShape(YokhanRadius.Panel))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            color = if (highlighted) activeTint else colors.primary,
            fontFamily = typography.mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            color = colors.secondary,
            fontFamily = typography.text,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
    moveTagPrefix: String,
    busy: Boolean,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    onRemove: (String) -> Unit,
    onMove: ((String) -> Unit)? = null,
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
                            moveTagPrefix = moveTagPrefix,
                            busy = busy,
                            colors = colors,
                            typography = typography,
                            onRemove = onRemove,
                            onMove = onMove,
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
private fun ConfiguredAppCard(
    value: String,
    status: WindowsAppRouteStatus,
    index: Int,
    tagPrefix: String,
    moveTagPrefix: String,
    busy: Boolean,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    onRemove: (String) -> Unit,
    onMove: ((String) -> Unit)?,
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
        onAddProxy = if (status == WindowsAppRouteStatus.DIRECT && onMove != null) ({ onMove(value) }) else null,
        onAddDirect = if (status == WindowsAppRouteStatus.PROXY && onMove != null) ({ onMove(value) }) else null,
        onRemoveRule = { onRemove(value) },
        modifier = modifier,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(YokhanSpacing.CompactGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onMove != null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 34.dp)
                        .clip(RoundedCornerShape(YokhanRadius.Pill))
                        .background(colors.surface)
                        .border(YokhanStroke.Thin, colors.border, RoundedCornerShape(YokhanRadius.Pill))
                        .clickable(enabled = !busy, role = Role.Button, onClick = { onMove(value) })
                        .testTag("$moveTagPrefix.$index")
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (status == WindowsAppRouteStatus.PROXY) "↔ Напрямую" else "↔ Через GATES",
                        color = colors.primary,
                        fontFamily = typography.mono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
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
    ruCandidate: Boolean,
    onAddProxy: () -> Unit,
    onAddDirect: () -> Unit,
    onRemoveRule: (() -> Unit)? = null,
    dragTarget: String? = null,
    onDragMove: ((label: String, target: String, pointInRoot: Offset?) -> Unit)? = null,
    onDragDrop: ((target: String, pointInRoot: Offset) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    AppCardFrame(
        iconPath = app.path,
        name = app.name,
        path = app.path,
        status = status,
        ruCandidate = ruCandidate,
        colors = colors,
        typography = typography,
        onAddProxy = onAddProxy,
        onAddDirect = onAddDirect,
        onRemoveRule = onRemoveRule,
        dragTarget = dragTarget,
        onDragMove = onDragMove,
        onDragDrop = onDragDrop,
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
    ruCandidate: Boolean = false,
    onAddProxy: (() -> Unit)? = null,
    onAddDirect: (() -> Unit)? = null,
    onRemoveRule: (() -> Unit)? = null,
    dragTarget: String? = null,
    onDragMove: ((label: String, target: String, pointInRoot: Offset?) -> Unit)? = null,
    onDragDrop: ((target: String, pointInRoot: Offset) -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    var contextMenuExpanded by remember { mutableStateOf(false) }
    var cardCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val clipboard = LocalClipboardManager.current
    val currentDragMove by rememberUpdatedState(onDragMove)
    val currentDragDrop by rememberUpdatedState(onDragDrop)

    Box(
        modifier = modifier
            .onGloballyPositioned { cardCoords = it }
            .pointerInput(name, path) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            contextMenuExpanded = true
                        }
                    }
                }
            }
            .let { base ->
                if (dragTarget != null && currentDragMove != null && currentDragDrop != null) {
                    base.pointerInput(dragTarget) {
                        var lastRootPoint = Offset.Zero
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                val root = cardCoords?.localToRoot(startOffset) ?: Offset.Zero
                                lastRootPoint = root
                                currentDragMove?.invoke(name, dragTarget, root)
                            },
                            onDrag = { change, _ ->
                                val root = cardCoords?.localToRoot(change.position) ?: lastRootPoint
                                lastRootPoint = root
                                currentDragMove?.invoke(name, dragTarget, root)
                            },
                            onDragEnd = {
                                currentDragMove?.invoke(name, dragTarget, null)
                                currentDragDrop?.invoke(dragTarget, lastRootPoint)
                            },
                            onDragCancel = {
                                currentDragMove?.invoke(name, dragTarget, null)
                            },
                        )
                    }
                } else {
                    base
                }
            },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().yokhanOutlinedSurface(colors).padding(YokhanSpacing.ControlHorizontal),
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppStatusBadge(status, colors, typography)
                if (ruCandidate) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(YokhanRadius.Pill))
                            .border(YokhanStroke.Thin, colors.secondary.copy(alpha = 0.45f), RoundedCornerShape(YokhanRadius.Pill))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "РФ · Автообход",
                            color = colors.secondary,
                            fontFamily = typography.mono,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            actions()
        }

        DropdownMenu(
            expanded = contextMenuExpanded,
            onDismissRequest = { contextMenuExpanded = false },
        ) {
            if (onAddProxy != null) {
                DropdownMenuItem(
                    text = { Text("Направить через GATES") },
                    onClick = {
                        contextMenuExpanded = false
                        onAddProxy()
                    },
                )
            }
            if (onAddDirect != null) {
                DropdownMenuItem(
                    text = { Text("Направить напрямую") },
                    onClick = {
                        contextMenuExpanded = false
                        onAddDirect()
                    },
                )
            }
            if (onRemoveRule != null) {
                DropdownMenuItem(
                    text = { Text("Убрать правило") },
                    onClick = {
                        contextMenuExpanded = false
                        onRemoveRule()
                    },
                )
            }
            if (path.isNotBlank() && File(path).exists()) {
                DropdownMenuItem(
                    text = { Text("Открыть расположение файла") },
                    onClick = {
                        contextMenuExpanded = false
                        runCatching {
                            ProcessBuilder("explorer.exe", "/select,$path").start()
                        }
                    },
                )
            }
            if (path.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("Копировать путь") },
                    onClick = {
                        contextMenuExpanded = false
                        clipboard.setText(AnnotatedString(path))
                    },
                )
            }
        }
    }
}

@Composable
private fun AppIcon(path: String, modifier: Modifier = Modifier) {
    val bitmap = rememberWindowsAppIcon(path)
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier.size(34.dp).clip(RoundedCornerShape(YokhanRadius.Small)),
    )
}

@Composable
private fun CompactLegendChip(
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
) {
    val active = tint != colors.border
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (active) tint.copy(alpha = 0.25f) else colors.surface)
                .border(1.5.dp, if (active) tint else colors.border, RoundedCornerShape(3.dp)),
        )
        Text(
            text = label,
            color = if (active) colors.primary else colors.secondary,
            fontFamily = typography.text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun CompactDesktopAppTile(
    name: String,
    path: String,
    status: WindowsAppRouteStatus,
    enabled: Boolean,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (bgColor, borderColor, borderWidth) = when (status) {
        WindowsAppRouteStatus.PROXY -> Triple(colors.success.copy(alpha = 0.16f), colors.success, 1.5.dp)
        WindowsAppRouteStatus.DIRECT -> Triple(YokhanAccent.copy(alpha = 0.16f), YokhanAccent, 1.5.dp)
        WindowsAppRouteStatus.UNSET -> Triple(colors.surface, colors.border, YokhanStroke.Thin)
    }
    Column(
        modifier = modifier
            .height(78.dp)
            .clip(RoundedCornerShape(YokhanRadius.Panel))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(YokhanRadius.Panel))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        AppIcon(path = path, modifier = Modifier.size(30.dp))
        Text(
            text = name,
            color = colors.primary,
            fontFamily = typography.text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AppStatusBadge(
    status: WindowsAppRouteStatus,
    colors: YokhanPalette,
    typography: com.yokhan.gates.shared.ui.design.YokhanTypography,
    modifier: Modifier = Modifier,
) {
    val (label, tint) = when (status) {
        WindowsAppRouteStatus.PROXY -> WindowsRoutingCopy.VIA_GATES to colors.accent
        WindowsAppRouteStatus.DIRECT -> WindowsRoutingCopy.DIRECT to colors.danger
        WindowsAppRouteStatus.UNSET -> WindowsRoutingCopy.UNSET to colors.tertiary
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
            text = "Выбрать .exe или ярлык .lnk",
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
    inputModifier: Modifier = Modifier,
    multiline: Boolean = false,
    showSearchIcon: Boolean = true,
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
        if (!multiline && showSearchIcon) {
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
            modifier = inputModifier.weight(1f)
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
