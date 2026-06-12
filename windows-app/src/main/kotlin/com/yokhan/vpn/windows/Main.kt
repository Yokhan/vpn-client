package com.yokhan.gates.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
        Window(
            onCloseRequest = ::exitApplication,
            title = "GATES",
            state = windowState,
        ) {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    WindowsApp()
                }
            }
        }
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
              GATES --cli logs
              GATES --cli stop

            Safety:
              CLI controls only safe local proxy mode. It does not change TUN,
              Windows default route, system proxy, DNS, NekoBox, phone, or server.
            """.trimIndent(),
        )
        return@runBlocking 0
    }

    val controller = WindowsRuntimeController()
    val argument = when (command) {
        "set-route", "adopt-state" -> args.getOrNull(1)
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
private fun WindowsApp() {
    val scope = rememberCoroutineScope()
    val controller = remember { WindowsRuntimeController() }
    val initialConfig = remember { controller.readConfig() }
    var connectionStatus by remember { mutableStateOf(ConnectionStatus.Disconnected) }
    var activeRoute by remember { mutableStateOf(initialConfig.activeRoute) }
    var busy by remember { mutableStateOf(false) }
    var lastMessage by remember { mutableStateOf("") }
    var roomNetMessage by remember { mutableStateOf("") }
    var showBugDialog by remember { mutableStateOf(false) }
    var bugMessage by remember { mutableStateOf("") }
    val state = remember(connectionStatus, activeRoute, busy) {
        dashboardState(connectionStatus, activeRoute, busy)
    }

    LaunchedEffect(Unit) {
        busy = true
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

    if (showBugDialog) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080812)),
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
        RoomNetPanel(roomNetMessage)
        WindowsActionBar(
            busy = busy,
            message = lastMessage,
            onLogin = { launchRuntime("Вход", "login") },
            onUpdate = { launchRuntime("Обновление", "update") },
            onBugReport = { showBugDialog = true },
        )
    }
}

@Composable
private fun RoomNetPanel(message: String) {
    if (message.isBlank()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111628))
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Комната",
            color = Color(0xFFAEB8FF),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            color = Color(0xFFF1F3FF),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WindowsActionBar(
    busy: Boolean,
    message: String,
    onLogin: () -> Unit,
    onUpdate: () -> Unit,
    onBugReport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xEE0E1020))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onLogin,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text("Войти")
            }
            Button(
                onClick = onUpdate,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text("Обновить")
            }
            Button(
                onClick = onBugReport,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text("Ошибка")
            }
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                color = Color(0xFFE6E8FF),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
        activeProfileSubtitle = "Windows runtime adapter - proxy mode",
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
