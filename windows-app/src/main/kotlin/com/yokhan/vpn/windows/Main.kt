package com.yokhan.gates.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.yokhan.gates.shared.domain.ConnectionStatus
import com.yokhan.gates.shared.domain.DashboardState
import com.yokhan.gates.shared.domain.LatencyDisplay
import com.yokhan.gates.shared.domain.RateDisplay
import com.yokhan.gates.shared.domain.VpnProtocol
import com.yokhan.gates.shared.ui.dashboard.DashboardProfileUiItem
import com.yokhan.gates.shared.ui.dashboard.DashboardUiActions
import com.yokhan.gates.shared.ui.dashboard.DashboardUiLabels
import com.yokhan.gates.shared.ui.dashboard.DashboardUiVisibility
import com.yokhan.gates.shared.ui.dashboard.YokhanDashboardScreen
import com.yokhan.gates.shared.ui.design.YokhanLightPalette
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanRed
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.YokhanStroke
import com.yokhan.gates.shared.ui.design.yokhanPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Window as AwtWindow
import java.io.File
import kotlin.system.exitProcess

private val AppWidth = 430.dp
private val AppHeight = 820.dp
private val ProductionDashboardVisibility = DashboardUiVisibility(
    showMenu = false,
    showConnectionTest = false,
    showAddProfile = false,
    showProfileActions = false,
)

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--cli") {
        val exitCode = runCli(args.drop(1))
        System.out.flush()
        System.err.flush()
        exitProcess(exitCode)
    }

    application {
        val windowState = rememberWindowState(size = DpSize(AppWidth, AppHeight))
        val traySupported = remember { NativeTray.isSupported() }
        var windowVisible by remember { mutableStateOf(true) }
        DisposableEffect(traySupported) {
            val tray = if (traySupported) {
                NativeTray(
                    onOpen = { windowVisible = true },
                    onQuit = { exitApplication() },
                ).also { it.install() }
            } else {
                null
            }
            onDispose { tray?.uninstall() }
        }

        if (windowVisible) {
            Window(
                onCloseRequest = {
                    if (traySupported) {
                        windowVisible = false
                    } else {
                        exitApplication()
                    }
                },
                title = "GATES",
                state = windowState,
            ) {
                WindowsApp(window)
            }
        }
    }
}

@Composable
private fun GatesWindowsTheme(
    palette: YokhanPalette,
    spaceActive: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = windowsColorScheme(palette, spaceActive),
        content = content,
    )
}

private fun windowsColorScheme(
    palette: YokhanPalette,
    spaceActive: Boolean,
): ColorScheme {
    return if (spaceActive) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.background,
            secondary = palette.secondary,
            onSecondary = palette.background,
            background = palette.background,
            onBackground = palette.primary,
            surface = palette.orbCenter,
            onSurface = palette.primary,
            surfaceVariant = palette.orbCenter,
            onSurfaceVariant = palette.secondary,
            outline = palette.border,
            outlineVariant = palette.divider,
            error = YokhanRed,
            onError = palette.background,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.background,
            secondary = palette.secondary,
            onSecondary = palette.background,
            background = palette.background,
            onBackground = palette.primary,
            surface = palette.orbCenter,
            onSurface = palette.primary,
            surfaceVariant = palette.orbCenter,
            onSurfaceVariant = palette.secondary,
            outline = palette.border,
            outlineVariant = palette.divider,
            error = YokhanRed,
            onError = palette.background,
        )
    }
}

private fun runCli(args: List<String>): Int = runBlocking {
    val command = args.getOrNull(0)
    if (command == null || command == "help" || command == "--help") {
        println(
            """
            GATES Windows control

            Usage:
              GATES --cli init
              GATES --cli login
              GATES --cli import-app
              GATES --cli adopt-state <runtime-dir>
              GATES --cli import-sub
              GATES --cli generate
              GATES --cli check
              GATES --cli connect
              GATES --cli start-proxy
              GATES --cli status
              GATES --cli test
              GATES --cli probe-routes
              GATES --cli roomnet-info
              GATES --cli roomnet-start
              GATES --cli roomnet-stop
              GATES --cli support-bundle
              GATES --cli check-update
              GATES --cli update
              GATES --cli report-bug <message>
              GATES --cli set-route <auto|bridge|relay|naive|hy2>
              GATES --cli set-runtime <tun|system-proxy|proxy>
              GATES --cli set-tun-routing <all|selected>
              GATES --cli apps
              GATES --cli running-apps
              GATES --cli add-app-proxy <exe-path-or-name>
              GATES --cli add-app-direct <exe-path-or-name>
              GATES --cli remove-app-proxy <exe-path-or-name>
              GATES --cli remove-app-direct <exe-path-or-name>
              GATES --cli enable-system-proxy
              GATES --cli disable-system-proxy
              GATES --cli enable-autostart
              GATES --cli disable-autostart
              GATES --cli logs
              GATES --cli stop

            Safety:
              CLI controls only local Windows runtime state. It does not change
              TUN, Windows default route, DNS, NekoBox, phone, or server.
            """.trimIndent(),
        )
        return@runBlocking 0
    }

    val controller = WindowsRuntimeController()
    val argument = when (command) {
        "set-route", "set-runtime", "set-tun-routing", "adopt-state" -> args.getOrNull(1)
        "add-app-proxy", "add-app-direct", "remove-app-proxy", "remove-app-direct" -> args.drop(1).joinToString(" ")
        "report-bug" -> args.drop(1).joinToString(" ")
        else -> null
    }
    val result = controller.run(command, argument)
    controller.writeCliTranscript(command, args.drop(1), result)
    println("exit=${result.exitCode}")
    if (result.output.isNotBlank()) {
        println(result.output)
    }
    result.exitCode
}

@Composable
private fun WindowsApp(ownerWindow: AwtWindow? = null) {
    val scope = rememberCoroutineScope()
    val controller = remember { WindowsRuntimeController() }
    val initialConfig = remember { controller.readConfig() }
    var connectionStatus by remember { mutableStateOf(ConnectionStatus.Disconnected) }
    var activeRoute by remember { mutableStateOf(initialConfig.activeRoute) }
    var busy by remember { mutableStateOf(false) }
    var lastMessage by remember { mutableStateOf("") }
    var roomNetMessage by remember { mutableStateOf("") }
    var showBugDialog by remember { mutableStateOf(false) }
    var showAppsDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var bugMessage by remember { mutableStateOf("") }
    var appRoutingState by remember { mutableStateOf(controller.appRoutingState()) }
    var runningApps by remember { mutableStateOf(emptyList<WindowsProcessCandidate>()) }
    var sessionState by remember { mutableStateOf(controller.sessionState()) }
    val state = remember(connectionStatus, activeRoute, busy) {
        dashboardState(connectionStatus, activeRoute, busy)
    }
    val windowSpaceActive = sessionState.ready &&
        (state.shouldAnimateSpace || connectionStatus == ConnectionStatus.Disconnecting)
    val windowPalette = if (sessionState.ready) {
        yokhanPalette(spaceActive = windowSpaceActive)
    } else {
        YokhanLightPalette
    }

    LaunchedEffect(Unit) {
        busy = true
        sessionState = controller.sessionState()
        val result = withContext(Dispatchers.IO) {
            controller.run("status")
        }
        if (result.exitCode == 0 && result.output.contains("Process: running")) {
            connectionStatus = ConnectionStatus.Connected
            roomNetMessage = roomNetUiMessage(
                withContext(Dispatchers.IO) {
                    controller.run("roomnet-info")
                },
            )
        }
        activeRoute = controller.readConfig().activeRoute
        if (controller.readConfig().autoCheckUpdates) {
            val update = withContext(Dispatchers.IO) {
                controller.run("check-update")
            }
            if (update.exitCode == 0 && update.output.contains("Update available: true")) {
                lastMessage = "Доступно обновление приложения."
            }
        }
        busy = false
    }

    val profiles = remember(activeRoute) {
        listOf(
            DashboardProfileUiItem(1, "Auto route", "AUTO", activeRoute == "auto"),
            DashboardProfileUiItem(2, "Bridge", "VLESS", activeRoute == "bridge"),
            DashboardProfileUiItem(3, "Relay", "VLESS", activeRoute == "relay"),
            DashboardProfileUiItem(4, "Naive", "HTTPS", activeRoute == "naive"),
            DashboardProfileUiItem(5, "HY2", "HYSTERIA2", activeRoute == "hy2"),
        )
    }

    fun launchRuntime(
        label: String,
        command: String,
        route: String? = null,
        argument: String? = null,
        nextStatus: ConnectionStatus? = null,
    ) {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                controller.run(command, argument ?: route)
            }
            sessionState = controller.sessionState()
            if (result.exitCode == 0 && nextStatus != null) {
                connectionStatus = nextStatus
            } else if (result.exitCode != 0) {
                connectionStatus = ConnectionStatus.Disconnected
            }
            if (command == "set-route" && result.exitCode == 0 && route != null) {
                activeRoute = route
            }
            if (command == "connect" && result.exitCode == 0) {
                roomNetMessage = roomNetUiMessage(
                    withContext(Dispatchers.IO) {
                        controller.run("roomnet-info")
                    },
                )
            }
            if (command == "stop" && result.exitCode == 0) {
                roomNetMessage = ""
            }
            lastMessage = resultMessage(label, result)
            busy = false
        }
    }

    fun launchAppRouting(label: String, command: String, argument: String = "") {
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                controller.run(command, argument)
            }
            appRoutingState = controller.appRoutingState()
            lastMessage = resultMessage(label, result)
            busy = false
        }
    }

    fun refreshRunningApps() {
        scope.launch {
            runningApps = withContext(Dispatchers.IO) {
                controller.listRunningApps()
            }
        }
    }

    fun launchAuthSetup() {
        scope.launch {
            busy = true
            val currentSession = controller.sessionState()
            val command = if (currentSession.signedIn) "import-app" else "login"
            val label = if (currentSession.signedIn) "Доступ" else "Вход"
            lastMessage = if (command == "login") {
                "Открываю браузер для входа."
            } else {
                "Получаю личную конфигурацию."
            }
            val result = withContext(Dispatchers.IO) {
                controller.run(command)
            }
            sessionState = controller.sessionState()
            activeRoute = controller.readConfig().activeRoute
            lastMessage = resultMessage(label, result)
            busy = false
        }
    }

    DisposableEffect(ownerWindow) {
        val dropTarget = ownerWindow?.let { window ->
            NativeFileDropTarget(window) { files ->
                files.firstOrNull()?.let { file ->
                    launchAppRouting("Приложение", "add-app-proxy", file.absolutePath)
                }
            }.also { it.install() }
        }
        onDispose { dropTarget?.uninstall() }
    }

    if (showBugDialog) {
        GatesWindowsTheme(windowPalette, windowSpaceActive) {
            BugReportDialog(
                message = bugMessage,
                onMessageChange = { bugMessage = it },
                onDismiss = { showBugDialog = false },
                onSend = {
                    val text = bugMessage
                    bugMessage = ""
                    showBugDialog = false
                    launchRuntime("Отчёт", "report-bug", argument = text)
                },
            )
        }
    }

    if (showAppsDialog) {
        GatesWindowsTheme(windowPalette, windowSpaceActive) {
            AppRoutingDialog(
                state = appRoutingState,
                runningApps = runningApps,
                busy = busy,
                palette = windowPalette,
                onDismiss = { showAppsDialog = false },
                onRefreshProcesses = { refreshRunningApps() },
                onPickExe = {
                    chooseExeFile(ownerWindow)?.let { path ->
                        launchAppRouting("Приложение", "add-app-proxy", path)
                    }
                },
                onRouteAll = { launchAppRouting("Режим", "set-tun-routing", "all") },
                onRouteSelected = { launchAppRouting("Режим", "set-tun-routing", "selected") },
                onAddProxy = { path -> launchAppRouting("Приложение", "add-app-proxy", path) },
                onAddDirect = { path -> launchAppRouting("Исключение", "add-app-direct", path) },
                onRemoveProxy = { value -> launchAppRouting("Приложение", "remove-app-proxy", value) },
                onRemoveDirect = { value -> launchAppRouting("Исключение", "remove-app-direct", value) },
            )
        }
    }

    if (showSettingsDialog) {
        GatesWindowsTheme(windowPalette, windowSpaceActive) {
            SettingsDialog(
                busy = busy,
                onDismiss = { showSettingsDialog = false },
                onApps = {
                    showSettingsDialog = false
                    appRoutingState = controller.appRoutingState()
                    showAppsDialog = true
                    refreshRunningApps()
                },
                onUpdate = {
                    showSettingsDialog = false
                    launchRuntime("Обновление", "update")
                },
                onBugReport = {
                    showSettingsDialog = false
                    showBugDialog = true
                },
            )
        }
    }

    if (!sessionState.ready) {
        GatesWindowsTheme(windowPalette, windowSpaceActive) {
            AuthGateScreen(
                sessionState = sessionState,
                busy = busy,
                message = lastMessage,
                palette = windowPalette,
                onLogin = { launchAuthSetup() },
                onBugReport = { showBugDialog = true },
            )
        }
        return
    }

    GatesWindowsTheme(windowPalette, windowSpaceActive) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(windowPalette.background),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                YokhanDashboardScreen(
                    state = state,
                    profiles = profiles,
                    labels = DashboardUiLabels.Russian,
                    actions = DashboardUiActions(
                        onToggleConnection = {
                            if (connectionStatus == ConnectionStatus.Connected) {
                                connectionStatus = ConnectionStatus.Disconnecting
                                launchRuntime("Отключение", "stop", nextStatus = ConnectionStatus.Disconnected)
                            } else {
                                connectionStatus = ConnectionStatus.Connecting
                                launchRuntime("Подключение", "connect", nextStatus = ConnectionStatus.Connected)
                            }
                        },
                        onSelectProfile = { id ->
                            val route = when (id) {
                                1L -> "auto"
                                2L -> "bridge"
                                3L -> "relay"
                                4L -> "naive"
                                5L -> "hy2"
                                else -> "auto"
                            }
                            launchRuntime("Маршрут $route", "set-route", route = route)
                        },
                    ),
                    modifier = Modifier.fillMaxSize(),
                    visibility = ProductionDashboardVisibility,
                )
            }
            RoomNetPanel(roomNetMessage, windowPalette)
            WindowsStatusBar(
                message = lastMessage,
                palette = windowPalette,
                onSettings = { showSettingsDialog = true },
            )
        }
    }
}

@Composable
private fun RoomNetPanel(
    message: String,
    palette: YokhanPalette,
) {
    if (message.isBlank()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.orbCenter)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Комната",
            color = palette.secondary,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            color = palette.primary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AuthGateScreen(
    sessionState: WindowsSessionState,
    busy: Boolean,
    message: String,
    palette: YokhanPalette,
    onLogin: () -> Unit,
    onBugReport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "GATES",
            color = palette.primary,
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (sessionState.signedIn) {
                "Получаем личную конфигурацию."
            } else {
                "Войдите через браузер, чтобы получить личный доступ."
            },
            color = palette.secondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onLogin,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (sessionState.signedIn) "Получить доступ" else "Войти через браузер")
        }
        TextButton(
            onClick = onBugReport,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Сообщить об ошибке")
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = message,
                color = palette.primary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WindowsStatusBar(
    message: String,
    palette: YokhanPalette,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.orbCenter)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message.ifBlank { "Готово" },
                color = palette.primary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSettings) {
                Text("Настройки")
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onApps: () -> Unit,
    onUpdate: () -> Unit,
    onBugReport: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onApps,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Маршрутизация приложений")
                }
                OutlinedButton(
                    onClick = onUpdate,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Обновить приложение")
                }
                OutlinedButton(
                    onClick = onBugReport,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Отправить отчёт")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Готово")
            }
        },
    )
}

@Composable
private fun BugReportDialog(
    message: String,
    onMessageChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сообщить об ошибке") },
        text = {
            OutlinedTextField(
                value = message,
                onValueChange = onMessageChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 5,
                label = { Text("Что не работает") },
            )
        },
        confirmButton = {
            TextButton(onClick = onSend) {
                Text("Отправить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        },
    )
}

@Composable
private fun AppRoutingDialog(
    state: AppRoutingState,
    runningApps: List<WindowsProcessCandidate>,
    busy: Boolean,
    palette: YokhanPalette,
    onDismiss: () -> Unit,
    onRefreshProcesses: () -> Unit,
    onPickExe: () -> Unit,
    onRouteAll: () -> Unit,
    onRouteSelected: () -> Unit,
    onAddProxy: (String) -> Unit,
    onAddDirect: (String) -> Unit,
    onRemoveProxy: (String) -> Unit,
    onRemoveDirect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Приложения") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Перетащи .exe в окно GATES или выбери приложение ниже.",
                    color = palette.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Режим",
                    color = palette.secondary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.routeAll) {
                        Button(
                            onClick = onRouteAll,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Все")
                        }
                    } else {
                        OutlinedButton(
                            onClick = onRouteAll,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Все")
                        }
                    }
                    if (!state.routeAll) {
                        Button(
                            onClick = onRouteSelected,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Выбранные")
                        }
                    } else {
                        OutlinedButton(
                            onClick = onRouteSelected,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Выбранные")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onPickExe,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Выбрать .exe")
                    }
                    OutlinedButton(
                        onClick = onRefreshProcesses,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Процессы")
                    }
                }
                AppRouteSection(
                    title = "Через GATES",
                    names = state.proxyProcessNames,
                    paths = state.proxyProcessPaths,
                    palette = palette,
                    onRemove = onRemoveProxy,
                )
                AppRouteSection(
                    title = "Напрямую",
                    names = state.directProcessNames,
                    paths = state.directProcessPaths,
                    palette = palette,
                    onRemove = onRemoveDirect,
                )
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(YokhanStroke.Thin)
                        .background(palette.divider),
                )
                Text(
                    text = "Запущенные",
                    color = palette.secondary,
                    style = MaterialTheme.typography.labelMedium,
                )
                runningApps.take(24).forEach { app ->
                    RunningAppRow(
                        app = app,
                        busy = busy,
                        palette = palette,
                        onAddProxy = { onAddProxy(app.path) },
                        onAddDirect = { onAddDirect(app.path) },
                    )
                }
                if (runningApps.isEmpty()) {
                    Text(
                        text = "Нажми «Процессы».",
                        color = palette.secondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Готово")
            }
        },
    )
}

@Composable
private fun AppRouteSection(
    title: String,
    names: List<String>,
    paths: List<String>,
    palette: YokhanPalette,
    onRemove: (String) -> Unit,
) {
    Text(
        text = title,
        color = palette.secondary,
        style = MaterialTheme.typography.labelMedium,
    )
    val entries = paths.map { "path" to it } + names.map { "name" to it }
    if (entries.isEmpty()) {
        Text(
            text = "Пусто",
            color = palette.tertiary,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    entries.forEach { (_, value) ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                color = palette.primary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onRemove(value) }) {
                Text("Убрать")
            }
        }
    }
}

@Composable
private fun RunningAppRow(
    app: WindowsProcessCandidate,
    busy: Boolean,
    palette: YokhanPalette,
    onAddProxy: () -> Unit,
    onAddDirect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.orbCenter)
            .padding(horizontal = YokhanSpacing.CompactGap, vertical = YokhanSpacing.CompactGap),
    ) {
        Text(
            text = app.name,
            color = palette.primary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = app.path,
            color = palette.secondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onAddProxy,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text("GATES")
            }
            OutlinedButton(
                onClick = onAddDirect,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text("Мимо")
            }
        }
    }
}

private fun chooseExeFile(owner: AwtWindow?): String? {
    val dialog = when (owner) {
        is java.awt.Frame -> FileDialog(owner, "Выбрать приложение", FileDialog.LOAD)
        is java.awt.Dialog -> FileDialog(owner, "Выбрать приложение", FileDialog.LOAD)
        else -> FileDialog(null as java.awt.Frame?, "Выбрать приложение", FileDialog.LOAD)
    }.apply {
        filenameFilter = java.io.FilenameFilter { _, name ->
            name.endsWith(".exe", ignoreCase = true)
        }
        isVisible = true
    }
    val directory = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(directory, file).absolutePath
}

private fun resultMessage(label: String, result: RuntimeCommandResult): String {
    val firstLine = result.output
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    val text = firstLine.ifBlank { "Готово" }
    return if (result.exitCode == 0) {
        "$label: $text"
    } else {
        "$label: ошибка. $text"
    }.take(220)
}

private fun roomNetUiMessage(result: RuntimeCommandResult): String {
    if (result.exitCode != 0) return ""
    val output = result.output.trim()
    if (
        output.isBlank() ||
        output.contains("disabled", ignoreCase = true) ||
        output.contains("not enabled", ignoreCase = true) ||
        output.contains("sign in first", ignoreCase = true)
    ) {
        return ""
    }
    val lines = output.lines()
    val room = lines.firstOrNull { it.startsWith("Room:") }
        ?.substringAfter(":")
        ?.trim()
        .orEmpty()
    val myAddress = lines.firstOrNull { it.startsWith("My room address:") }
        ?.substringAfter(":")
        ?.trim()
        .orEmpty()
    if (room.isBlank() || myAddress.isBlank() || myAddress == "-") {
        return ""
    }
    val peers = lines
        .filter { it.trimStart().contains("->") }
        .map { it.trim().removePrefix("-").trim() }
        .map { item ->
            item
                .replace(" ports=", ":")
                .replace(" online", " онлайн")
                .replace(" offline", " офлайн")
        }

    return buildString {
        append("Комната $room. Мой адрес: $myAddress")
        if (peers.isNotEmpty()) {
            appendLine()
            append(peers.joinToString("; "))
        }
    }.take(260)
}

private fun dashboardState(
    status: ConnectionStatus,
    activeRoute: String,
    busy: Boolean,
): DashboardState {
    val connected = status == ConnectionStatus.Connected
    val running = status == ConnectionStatus.Connecting || connected || busy
    return DashboardState(
        connectionStatus = status,
        activeProfileName = activeRouteLabel(activeRoute),
        activeProfileProtocol = routeProtocol(activeRoute),
        activeProfileProtocolLabel = routeProtocolLabel(activeRoute),
        activeProfileSubtitle = "Windows runtime adapter",
        uploadRate = RateDisplay(if (connected) 12_000 else 0),
        downloadRate = RateDisplay(if (connected) 42_000 else 0),
        latency = LatencyDisplay(if (connected) 98 else null),
        uploadLabel = if (connected) "12 KB/s" else "0 B/s",
        downloadLabel = if (connected) "42 KB/s" else "0 B/s",
        latencyLabel = if (connected) "98 ms" else "--",
        timerLabel = if (connected) "00:00:42" else "00:00:00",
        shouldAnimateSpace = running,
        canRunConnectionTest = connected,
        isConnectionTestRunning = busy,
        errorMessage = null,
    )
}

private fun activeRouteLabel(route: String): String =
    when (route) {
        "auto" -> "Auto route"
        "bridge" -> "Bridge"
        "relay" -> "Relay"
        "naive" -> "Naive"
        "hy2" -> "HY2"
        else -> route
    }

private fun routeProtocol(route: String): VpnProtocol =
    when (route) {
        "bridge", "relay" -> VpnProtocol.Vless
        else -> VpnProtocol.Unknown
    }

private fun routeProtocolLabel(route: String): String =
    when (route) {
        "bridge", "relay" -> "VLESS"
        "naive" -> "HTTPS"
        "hy2" -> "HYSTERIA2"
        else -> "AUTO"
    }
