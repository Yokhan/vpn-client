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
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
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
import com.yokhan.gates.shared.ui.access.GatesAccessScreen
import com.yokhan.gates.shared.ui.diagnostics.GatesIncidentHistoryItem
import com.yokhan.gates.shared.ui.diagnostics.GatesIncidentHistoryScreen
import com.yokhan.gates.shared.ui.route.YokhanRouteFrame
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.shared.ui.route.YokhanRouteButton
import com.yokhan.gates.shared.ui.route.YokhanRouteAction
import com.yokhan.gates.shared.ui.route.YokhanButtonGrid
import com.yokhan.gates.shared.ui.form.YokhanFormEvent
import com.yokhan.gates.shared.ui.form.YokhanFormField
import com.yokhan.gates.shared.ui.form.YokhanFormSection
import com.yokhan.gates.shared.ui.form.YokhanFormSectionCard
import com.yokhan.gates.shared.ui.design.YokhanLightPalette
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanRed
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.YokhanStroke
import com.yokhan.gates.shared.ui.design.yokhanPalette
import com.yokhan.gates.shared.ui.design.yokhanTypography
import com.yokhan.gates.shared.ui.navigation.GatesBottomNavigation
import com.yokhan.gates.shared.ui.navigation.GatesNavigationItem
import com.yokhan.gates.windows.service.isGatesRuntimeServiceIdentitySystem
import com.yokhan.gates.windows.service.runGatesServiceProcess
import com.yokhan.gates.windows.qa.WindowsQaState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Window as AwtWindow
import java.io.File
import java.nio.file.Path

private val AppWidth = 430.dp
private val AppHeight = 820.dp
private val ProductionDashboardVisibility = DashboardUiVisibility(
    showMenu = false,
    showConnectionTest = false,
    showProfiles = true,
    showAddProfile = false,
    showProfileActions = false,
)

fun main(args: Array<String>) {
    when (classifyGatesRuntimeLauncherArgs(args.toList())) {
        GatesRuntimeLauncherCommand.SERVICE -> {
            if (!isGatesRuntimeServiceIdentitySystem()) {
                System.err.println("GATES service mode requires the Windows LocalSystem identity.")
                Runtime.getRuntime().halt(5)
            }
            val exitCode = runGatesServiceProcess(emptyArray())
            System.out.flush()
            System.err.flush()
            Runtime.getRuntime().halt(exitCode)
        }
        GatesRuntimeLauncherCommand.CLEANUP_PROTECTION -> {
            val exitCode = runGatesServiceProcess(arrayOf("--cleanup-protection"))
            System.out.flush()
            System.err.flush()
            Runtime.getRuntime().halt(exitCode)
        }
        GatesRuntimeLauncherCommand.INVALID -> {
            System.err.println("Unsupported GATES runtime service arguments.")
            Runtime.getRuntime().halt(64)
        }
        GatesRuntimeLauncherCommand.NOT_SERVICE -> Unit
    }
    val qa = if (windowsQaRequested(args)) WindowsQaSession(realAccess = args.single() == "--qa-access") else null
    if (args.firstOrNull() == "--cli") {
        val exitCode = runCli(args.drop(1))
        System.out.flush()
        System.err.flush()
        // jpackage's Windows launcher can keep the wrapper alive after a non-zero child exit.
        // The CLI prints and persists the logical exit code, so automation should read `exit=...`.
        Runtime.getRuntime().halt(0)
    }

    application {
        DisposableEffect(qa) { onDispose { qa?.close() } }
        val windowState = rememberWindowState(size = DpSize(AppWidth, AppHeight))
        val traySupported = remember { qa == null && NativeTray.isSupported() }
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
                title = when { qa == null -> "GATES"; qa.realAccess -> "GATES QA - isolated access"; else -> "GATES QA - synthetic data" },
                state = windowState,
                icon = remember { BitmapPainter(WindowsBrandIcons.load(256).toComposeImageBitmap()) },
            ) {
                DisposableEffect(window) {
                    qa?.attachWindow(window)
                    onDispose { }
                }
                WindowsApp(window, qa)
            }
        }
    }
}

internal enum class GatesRuntimeLauncherCommand { NOT_SERVICE, SERVICE, CLEANUP_PROTECTION, INVALID }

internal fun classifyGatesRuntimeLauncherArgs(args: List<String>): GatesRuntimeLauncherCommand {
    if (args.firstOrNull() != "--runtime-service") return GatesRuntimeLauncherCommand.NOT_SERVICE
    return when (args) {
        listOf("--runtime-service") -> GatesRuntimeLauncherCommand.SERVICE
        listOf("--runtime-service", "--cleanup-protection") -> GatesRuntimeLauncherCommand.CLEANUP_PROTECTION
        else -> GatesRuntimeLauncherCommand.INVALID
    }
}

@Composable
private fun GatesWindowsTheme(
    palette: YokhanPalette,
    spaceActive: Boolean,
    content: @Composable () -> Unit,
) {
    val fonts = yokhanTypography()
    val baseTypography = MaterialTheme.typography
    MaterialTheme(
        colorScheme = windowsColorScheme(palette, spaceActive),
        typography = baseTypography.copy(
            titleLarge = baseTypography.titleLarge.copy(fontFamily = fonts.text),
            titleMedium = baseTypography.titleMedium.copy(fontFamily = fonts.text),
            bodyLarge = baseTypography.bodyLarge.copy(fontFamily = fonts.text),
            bodyMedium = baseTypography.bodyMedium.copy(fontFamily = fonts.text),
            bodySmall = baseTypography.bodySmall.copy(fontFamily = fonts.text),
            labelLarge = baseTypography.labelLarge.copy(fontFamily = fonts.mono),
        ),
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
              GATES --cli install-service
              GATES --cli uninstall-service
              GATES --cli service-status
              GATES --cli logs
              GATES --cli stop

            Safety:
              CLI controls only local Windows app/runtime state. TUN mode can
              change local Windows routes while connected. It does not change
              NekoBox, phone, server, or live VPN transports.
            """.trimIndent(),
        )
        return@runBlocking 0
    }

    val controller = WindowsRuntimeController()
    val argument = when (command) {
        "set-route", "set-runtime", "set-tun-routing", "adopt-state" -> args.getOrNull(1)
        "add-app-proxy", "add-app-direct", "remove-app-proxy", "remove-app-direct" -> args.drop(1).joinToString(" ")
        "report-bug", "import-sub" -> args.drop(1).joinToString(" ")
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
private fun WindowsApp(ownerWindow: AwtWindow? = null, qa: WindowsQaSession? = null) {
    val compositionStart = if (qa?.profiling?.value == true) System.nanoTime() else 0L
    val scope = rememberCoroutineScope()
    // QA must fail closed if a new callback accidentally tries to access runtime state.
    val controller by remember(qa) { lazy {
        check(qa == null || qa.realAccess) { "Runtime unavailable in UI QA mode" }
        if (qa?.realAccess == true) windowsAccessAcceptanceController(qa.sessionDirectory.resolve("access-data").toFile())
        else WindowsRuntimeController()
    } }
    val recoveryDeliveryReady = remember(qa) { CompletableDeferred<WindowsRecoveryDelivery?>() }
    var qaRevision by remember { mutableStateOf(1L) }
    val runtimeCommandGeneration = remember { WindowsRuntimeCommandGeneration() }
    var connectionStatus by remember { mutableStateOf(ConnectionStatus.Disconnected) }
    var activeRoute by remember { mutableStateOf(WindowsAppConfig().activeRoute) }
    var availableRoutes by remember { mutableStateOf(emptyList<WindowsProfileRoute>()) }
    var busy by remember { mutableStateOf(true) }
    var lastMessage by remember { mutableStateOf("") }
    var roomNetMessage by remember { mutableStateOf("") }
    var showBugDialog by remember { mutableStateOf(false) }
    var showAppsDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var historyFromSettings by remember { mutableStateOf(false) }
    var historyLoading by remember { mutableStateOf(false) }
    var historyMessage by remember { mutableStateOf("") }
    var historyItems by remember { mutableStateOf(emptyList<GatesIncidentHistoryItem>()) }
    var bugMessage by remember { mutableStateOf("") }
    var reportReturnTo by remember { mutableStateOf("dashboard") }
    var runtimeMode by remember { mutableStateOf("tun") }
    var killSwitchEnabled by remember { mutableStateOf(false) }
    var protectionState by remember { mutableStateOf(WindowsProtectionState.UNAVAILABLE) }
    var autoUploadRecoveryReports by remember { mutableStateOf(true) }
    var recoveryPreferenceLoaded by remember { mutableStateOf(false) }
    var updateAvailable by remember { mutableStateOf(false) }
    var qaDataSet by remember { mutableStateOf("default") }
    var appRoutingState by remember {
        mutableStateOf(AppRoutingState(false, emptyList(), emptyList(), emptyList(), emptyList()))
    }
    var runningApps by remember { mutableStateOf(emptyList<WindowsProcessCandidate>()) }
    var sessionState by remember { mutableStateOf(WindowsSessionState(false, false, false, "")) }
    var showAccess by remember { mutableStateOf(false) }
    var accessReturnTo by remember { mutableStateOf("dashboard") }
    var manualSubscription by remember { mutableStateOf("") }
    var authJob by remember { mutableStateOf<Job?>(null) }
    val authorizationPrompt by if (qa == null || qa.realAccess) controller.authorizationPrompt.collectAsState()
        else remember { mutableStateOf<WindowsAuthorizationPrompt?>(null) }
    val authorizationPhase by if (qa == null || qa.realAccess) controller.authorizationPhase.collectAsState()
        else remember { mutableStateOf(WindowsAuthorizationPhase.Idle) }
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
    val automationScreen = when {
        showBugDialog -> "bug"
        showAppsDialog -> "routing"
        showHistory -> "history"
        showSettingsDialog -> "settings"
        !sessionState.ready || showAccess -> "access"
        else -> "dashboard"
    }
    val automationScreenshotAllowed = authorizationPrompt == null && (automationScreen != "access" || manualSubscription.isBlank()) &&
        (automationScreen != "bug" || bugMessage.isBlank())
    val automationBusy = busy || historyLoading
    val automationRevision = qa?.reserveRevision(qaRevision) ?: 0L

    SideEffect {
        if (qa != null) {
            if (compositionStart != 0L) qa.timings.composition(System.nanoTime() - compositionStart, qa.timingLabel)
            val screen = automationScreen
            val actions = buildList {
                addAll(listOf("nav.dashboard", "nav.settings", "nav.access", "nav.routing",
                    "nav.history", "nav.bug", "nav.back", "fixture.disconnected",
                    "fixture.connected", "fixture.connecting", "fixture.error"))
                if (!qa.realAccess) addAll(listOf("fixture.loading", "fixture.empty", "fixture.long", "fixture.default", "fixture.signedout", "fixture.signedin", "fixture.update", "fixture.effects-off", "fixture.effects-on"))
                if (screen == "history") add("history.refresh")
            }
            qa.publish(WindowsQaState(screen, automationRevision, automationBusy, actions,
                fixture = !qa.realAccess, connectionStatus = connectionStatus.name,
                signedIn = sessionState.signedIn, profileReady = sessionState.ready,
                activeRoute = activeRoute, availableRoutes = availableRoutes.map { it.tag }),
                screenshotAllowed = automationScreenshotAllowed) { id ->
                if (id.startsWith("nav.")) {
                    showBugDialog = false
                    showAppsDialog = false
                    showHistory = false
                    showSettingsDialog = false
                    showAccess = false
                    when (id) {
                        "nav.settings" -> showSettingsDialog = true
                        "nav.access" -> showAccess = true
                        "nav.routing" -> showAppsDialog = true
                        "nav.history" -> { historyFromSettings = true; showHistory = true }
                        "nav.bug" -> showBugDialog = true
                        "nav.back" -> when (screen) {
                            "history", "routing", "bug" -> showSettingsDialog = true
                            else -> Unit
                        }
                    }
                } else if (id.startsWith("fixture.")) {
                    busy = id == "fixture.loading"
                    connectionStatus = when (id) {
                        "fixture.connected" -> ConnectionStatus.Connected
                        "fixture.connecting" -> ConnectionStatus.Connecting
                        "fixture.effects-off", "fixture.effects-on" -> connectionStatus
                        else -> ConnectionStatus.Disconnected
                    }
                    lastMessage = if (id == "fixture.error") WINDOWS_UI_FAILURE_MESSAGE else ""
                    when (id) {
                        "fixture.empty" -> {
                            qaDataSet = "empty"
                            runningApps = emptyList()
                            historyItems = emptyList()
                            appRoutingState = AppRoutingState(true, emptyList(), emptyList(), emptyList(), emptyList())
                        }
                        "fixture.long" -> {
                            qaDataSet = "long"
                            runningApps = List(40) { WindowsProcessCandidate(it.toLong(), "process-$it.exe",
                                "C:\\QA\\${"Long folder name ".repeat(8).trimEnd()}\\process-$it.exe") }
                            appRoutingState = appRoutingState.copy(proxyProcessPaths = listOf(runningApps.first().path))
                        }
                        "fixture.default" -> {
                            updateAvailable = false
                            qaDataSet = "default"
                            runningApps = listOf(WindowsProcessCandidate(0, "game.exe", "C:\\QA\\game.exe"))
                            appRoutingState = AppRoutingState(true, listOf("game.exe"), emptyList(), listOf("browser.exe"), emptyList())
                        }
                        "fixture.signedout" -> { sessionState = WindowsSessionState(false, false, false, ""); showAccess = true }
                        "fixture.signedin" -> { sessionState = WindowsSessionState(true, true, true, ""); showAccess = true }
                        "fixture.update" -> { updateAvailable = true; lastMessage = "Доступно обновление." }
                        "fixture.effects-off" -> qa.backgroundEffects.value = false
                        "fixture.effects-on" -> qa.backgroundEffects.value = true
                    }
                } else if (id == "history.refresh") {
                    historyItems = windowsQaIncidentItems()
                }
                qaRevision += 1
            }
        }
    }

    val profiling = qa?.profiling?.value == true
    LaunchedEffect(profiling) {
        val durationScale = kotlin.coroutines.coroutineContext[MotionDurationScale]
        if (profiling && qa != null) while (true) {
            withFrameNanos {
                qa.motionDurationScale = durationScale?.scaleFactor ?: 1f
                qa.timings.frame(it, qa.timingLabel)
            }
        }
    }

    LaunchedEffect(qa) {
        val delivery = if (qa == null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val programData = System.getenv("ProgramData")?.takeIf(String::isNotBlank)
                        ?.let { Path.of(it, "GATES", "Service", "connectivity") }
                    WindowsRecoveryDelivery(
                        windowsGatesAppDir(),
                        programData,
                        coreVersion = controller.recoveryCoreVersion(),
                        standaloneSnapshot = controller::standaloneProxyDiagnosticSnapshot,
                    )
                }.getOrNull()
            }
        } else null
        recoveryDeliveryReady.complete(delivery)
    }

    LaunchedEffect(Unit) {
        if (qa != null && !qa.realAccess) {
            sessionState = WindowsSessionState(false, true, true, "")
            appRoutingState = AppRoutingState(true, listOf("game.exe"), emptyList(),
                listOf("browser.exe"), emptyList())
            runningApps = listOf(WindowsProcessCandidate(0, "game.exe", "C:\\QA\\game.exe"))
            availableRoutes = listOf(
                WindowsProfileRoute("nl-tcp", "NL VLESS", "VLESS / TCP", "45.134.174.246"),
                WindowsProfileRoute("hy2", "Hysteria2", "HY2 / UDP", "45.134.174.246"),
            )
            busy = false
            return@LaunchedEffect
        }
        var snapshotLoaded = false
        var autoCheckUpdates = false
        runWindowsUiAction(
            setBusy = { busy = it },
            onFailure = { lastMessage = it },
        ) {
            val snapshot = loadWindowsUiSnapshot(
                readConfig = controller::readConfig,
                readSession = controller::sessionState,
                readAppRouting = controller::appRoutingState,
                readRoutes = controller::availableRoutes,
            )
            sessionState = snapshot.session
            activeRoute = snapshot.config.activeRoute
            availableRoutes = snapshot.routes
            appRoutingState = snapshot.appRouting
            runtimeMode = snapshot.config.runtimeMode
            killSwitchEnabled = snapshot.config.killSwitchEnabled
            autoUploadRecoveryReports = snapshot.config.autoUploadRecoveryReports
            recoveryPreferenceLoaded = true
            autoCheckUpdates = snapshot.config.autoCheckUpdates
            snapshotLoaded = true
            if (qa == null && runtimeMode == "tun") {
                lastMessage = "Проверка службы GATES…"
                val prepared = withContext(Dispatchers.IO) { controller.run("prepare-runtime") }
                lastMessage = if (prepared.exitCode == 0) "" else windowsUiResultMessage("prepare-runtime", prepared)
            }
        }
        if (!snapshotLoaded) {
            return@LaunchedEffect
        }
        if (qa?.realAccess == true) return@LaunchedEffect

        val startupGeneration = runtimeCommandGeneration.capture()
        try {
            val result = withContext(Dispatchers.IO) { controller.run("status") }
            if (runtimeCommandGeneration.isUnchanged(startupGeneration) &&
                result.exitCode == 0 && result.output.contains("Process: running")
            ) {
                connectionStatus = ConnectionStatus.Connected
                try {
                    val roomNetResult = withContext(Dispatchers.IO) { controller.run("roomnet-info") }
                    if (runtimeCommandGeneration.isUnchanged(startupGeneration)) {
                        roomNetMessage = roomNetUiMessage(roomNetResult)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (!busy && lastMessage.isBlank()) lastMessage = WINDOWS_UI_FAILURE_MESSAGE
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!busy && lastMessage.isBlank()) lastMessage = WINDOWS_UI_FAILURE_MESSAGE
        }

        if (autoCheckUpdates) {
            try {
                val update = withContext(Dispatchers.IO) { controller.run("check-update") }
                if (update.exitCode == 0 && update.output.contains("Update available: true")) {
                    updateAvailable = true
                    if (!busy && lastMessage.isBlank()) {
                        lastMessage = "Доступно обновление приложения."
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!busy && lastMessage.isBlank()) lastMessage = WINDOWS_UI_FAILURE_MESSAGE
            }
        }
    }

    LaunchedEffect(recoveryDeliveryReady, recoveryPreferenceLoaded, autoUploadRecoveryReports) {
        val delivery = recoveryDeliveryReady.await() ?: return@LaunchedEffect
        if (!recoveryPreferenceLoaded) return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) { runCatching { delivery.runOnce(autoUploadRecoveryReports) } }
            delay(60_000)
        }
    }

    LaunchedEffect(qa, killSwitchEnabled) {
        if (qa != null) return@LaunchedEffect
        while (true) {
            val observed = withContext(Dispatchers.IO) {
                parseWindowsProtectionState(controller.run("protection-status"))
            }
            protectionState = observed
            if (!busy && (observed in setOf(WindowsProtectionState.BLOCKED, WindowsProtectionState.ERROR) ||
                    (killSwitchEnabled && observed == WindowsProtectionState.UNAVAILABLE))) {
                connectionStatus = ConnectionStatus.Disconnected
            }
            delay(5_000)
        }
    }

    LaunchedEffect(recoveryDeliveryReady) {
        val delivery = recoveryDeliveryReady.await() ?: return@LaunchedEffect
        while (true) {
            delay(120_000)
            withContext(Dispatchers.IO) { runCatching { delivery.sampleStandaloneProxy() } }
        }
    }

    val profiles = remember(activeRoute, availableRoutes) {
        listOf(DashboardProfileUiItem(1, "Автоматически", "AUTO", activeRoute == "auto")) +
            availableRoutes.mapIndexed { index, route ->
                DashboardProfileUiItem((index + 2).toLong(), route.label,
                    "${route.protocol} · ${route.server}", activeRoute == route.tag)
            }
    }

    fun launchRuntime(
        label: String,
        command: String,
        route: String? = null,
        argument: String? = null,
        nextStatus: ConnectionStatus? = null,
        onSuccess: (() -> Unit)? = null,
    ) {
        if (qa != null) {
            if (command == "set-runtime") runtimeMode = argument ?: "tun"
            else lastMessage = "Операция недоступна в тестовом режиме."
            return
        }
        scope.launch {
            if (busy) return@launch
            runtimeCommandGeneration.commandStarted()
            runWindowsUiAction(
                setBusy = { busy = it },
                onFailure = { lastMessage = it },
            ) {
                val result = withContext(Dispatchers.IO) { controller.run(command, argument ?: route) }
                val failureStage = windowsRecoveryFailureStage(command, result)
                if (failureStage != null) {
                    val delivery = recoveryDeliveryReady.await()
                    withContext(Dispatchers.IO) { runCatching { delivery?.recordFailure(failureStage) } }
                }
                connectionStatus = windowsUiStatusAfterCommand(connectionStatus, nextStatus, result.exitCode)
                sessionState = withContext(Dispatchers.IO) { controller.sessionState() }
                availableRoutes = withContext(Dispatchers.IO) { controller.availableRoutes() }
                if (command == "set-route" && result.exitCode == 0 && route != null) {
                    activeRoute = route
                }
                if (command == "connect" && result.exitCode == 0) {
                    roomNetMessage = roomNetUiMessage(
                        withContext(Dispatchers.IO) { controller.run("roomnet-info") },
                    )
                }
                if (command == "stop" && result.exitCode == 0) {
                    roomNetMessage = ""
                }
                if (command == "set-runtime" && result.exitCode == 0) runtimeMode = argument ?: "tun"
                if (command == "set-kill-switch" && result.exitCode == 0) killSwitchEnabled = argument == "true"
                protectionState = withContext(Dispatchers.IO) {
                    parseWindowsProtectionState(controller.run("protection-status"))
                }
                if (command == "check-update" && result.exitCode == 0) {
                    updateAvailable = result.output.contains("Update available: true")
                }
                lastMessage = windowsUiResultMessage(command, result)
                if (result.exitCode == 0) onSuccess?.invoke()
            }
        }
    }

    fun launchAppRouting(label: String, command: String, argument: String = "") {
        if (qa != null) {
            appRoutingState = windowsQaRoutingChange(appRoutingState, command, argument)
            return
        }
        scope.launch {
            if (busy) return@launch
            runWindowsUiAction(
                setBusy = { busy = it },
                onFailure = { lastMessage = it },
            ) {
                val result = withContext(Dispatchers.IO) { controller.run(command, argument) }
                appRoutingState = withContext(Dispatchers.IO) { controller.appRoutingState() }
                lastMessage = windowsUiResultMessage(command, result)
            }
        }
    }

    fun refreshRunningApps() {
        if (qa != null) return
        scope.launch {
            if (busy) return@launch
            runWindowsUiAction(
                setBusy = { busy = it },
                onFailure = { lastMessage = it },
            ) {
                runningApps = withContext(Dispatchers.IO) { controller.listRunningApps() }
            }
        }
    }

    fun launchLoadAppRouting() {
        if (qa != null) { showAppsDialog = true; return }
        scope.launch {
            if (busy) return@launch
            runWindowsUiAction(
                setBusy = { busy = it },
                onFailure = { lastMessage = it },
            ) {
                appRoutingState = withContext(Dispatchers.IO) { controller.appRoutingState() }
                showAppsDialog = true
            }
            refreshRunningApps()
        }
    }

    fun launchAuthSetup() {
        if (qa != null && !qa.realAccess) { lastMessage = "Операция недоступна в тестовом режиме."; return }
        authJob = scope.launch {
            if (busy) return@launch
            runWindowsUiAction(
                setBusy = { busy = it },
                onFailure = { lastMessage = it },
            ) {
                val currentSession = withContext(Dispatchers.IO) { controller.sessionState() }
                val command = if (currentSession.signedIn) "import-app" else "login"
                val label = if (currentSession.signedIn) "Доступ" else "Вход"
                lastMessage = if (command == "login") {
                    "Открываю браузер для входа."
                } else {
                    "Получаю личную конфигурацию."
                }
                val result = withContext(Dispatchers.IO) { controller.run(command) }
                val snapshot = loadWindowsUiSnapshot(
                    readConfig = controller::readConfig,
                    readSession = controller::sessionState,
                    readAppRouting = controller::appRoutingState,
                    readRoutes = controller::availableRoutes,
                )
                sessionState = snapshot.session
                activeRoute = snapshot.config.activeRoute
                availableRoutes = snapshot.routes
                appRoutingState = snapshot.appRouting
                lastMessage = windowsUiResultMessage(command, result)
                if (result.exitCode == 0 && sessionState.ready) showAccess = false
            }
        }
    }

    fun launchManualImport() {
        if (qa != null && !qa.realAccess) { lastMessage = "Операция недоступна в тестовом режиме."; return }
        if (busy || manualSubscription.isBlank()) return
        val input = manualSubscription
        scope.launch {
            if (busy || input.isBlank()) return@launch
            runWindowsUiAction(
                setBusy = { busy = it },
                onFailure = { lastMessage = it },
            ) {
                val result = withContext(Dispatchers.IO) { controller.run("import-sub", input) }
                val snapshot = loadWindowsUiSnapshot(readConfig = controller::readConfig,
                    readSession = controller::sessionState, readAppRouting = controller::appRoutingState,
                    readRoutes = controller::availableRoutes)
                sessionState = snapshot.session
                activeRoute = snapshot.config.activeRoute
                availableRoutes = snapshot.routes
                appRoutingState = snapshot.appRouting
                lastMessage = windowsUiResultMessage("import-sub", result)
                if (result.exitCode == 0) {
                    manualSubscription = ""
                    showAccess = false
                }
            }
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

    Column(Modifier.fillMaxSize().background(windowPalette.background).onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape && !busy && automationScreen != "access") {
                when {
                    showBugDialog -> { showBugDialog = false; showSettingsDialog = reportReturnTo == "settings" }
                    showAppsDialog -> { showAppsDialog = false; showSettingsDialog = true }
                    showHistory -> { showHistory = false; showSettingsDialog = historyFromSettings }
                    showSettingsDialog -> showSettingsDialog = false
                }
                true
            } else false
        }) {
        Box(Modifier.fillMaxWidth().weight(1f).drawWithContent {
            val start = if (qa?.profiling?.value == true) System.nanoTime() else 0L
            drawContent()
            if (start != 0L) qa?.timings?.draw(System.nanoTime() - start, qa.timingLabel)
            qa?.frameDrawn(automationRevision)
        }) {
            if (showBugDialog) {
                GatesWindowsTheme(windowPalette, windowSpaceActive) {
                    WindowsBugReportScreen(
                        modifier = if (qa == null) Modifier else Modifier.drawWithContent {
                            drawContent()
                            qa.frameDrawn(automationRevision, WindowsQaSurface.BUG)
                        },
                        message = bugMessage,
                        spaceActive = windowSpaceActive,
                        busy = busy,
                        statusMessage = lastMessage,
                        onMessageChange = { bugMessage = it },
                        onDismiss = {
                            showBugDialog = false
                            if (reportReturnTo == "settings") showSettingsDialog = true
                            if (reportReturnTo == "access") showAccess = true
                        },
                        onSend = {
                            launchRuntime("Отчёт", "report-bug", argument = bugMessage, onSuccess = {
                                bugMessage = ""
                                showBugDialog = false
                                if (reportReturnTo == "settings") showSettingsDialog = true
                            })
                        },
                    )
                }
                return@Box
            }

            if (showAppsDialog) {
                GatesWindowsTheme(windowPalette, windowSpaceActive) {
                    WindowsAppRoutingScreen(
                        state = appRoutingState,
                        runningApps = runningApps,
                        busy = busy,
                        palette = windowPalette,
                        spaceActive = windowSpaceActive,
                        message = lastMessage,
                        onDismiss = { showAppsDialog = false; showSettingsDialog = true },
                        onRefreshProcesses = { refreshRunningApps() },
                        onPickExe = { proxy ->
                            if (qa == null) chooseExeFile(ownerWindow)?.let { path ->
                                launchAppRouting("Приложение", if (proxy) "add-app-proxy" else "add-app-direct", path)
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
                return@Box
            }

            fun refreshIncidentHistory() {
                if (qa != null) {
                    historyItems = if (qaDataSet == "empty") emptyList() else windowsQaIncidentItems()
                    historyMessage = ""
                    return
                }
                scope.launch {
                    if (historyLoading) return@launch
                    historyLoading = true
                    try {
                        val summary = withContext(Dispatchers.IO) { windowsIncidentReports() }
                        historyItems = windowsIncidentHistoryItems(summary)
                        historyMessage = windowsIncidentHistoryMessage(summary)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        historyMessage = "Не удалось прочитать историю."
                    } finally {
                        historyLoading = false
                    }
                }
            }

            if (showHistory) {
                LaunchedEffect(Unit) { refreshIncidentHistory() }
                GatesWindowsTheme(windowPalette, windowSpaceActive) {
                    GatesIncidentHistoryScreen(
                        items = historyItems,
                        loading = historyLoading,
                        message = historyMessage,
                        palette = windowPalette,
                        onBack = { showHistory = false; showSettingsDialog = historyFromSettings },
                        spaceActive = windowSpaceActive,
                        onRefresh = ::refreshIncidentHistory,
                        onExport = { id ->
                            if (qa != null) return@GatesIncidentHistoryScreen
                            val dialog = when (ownerWindow) {
                                is java.awt.Frame -> FileDialog(ownerWindow, "Сохранить отчёт", FileDialog.SAVE)
                                is java.awt.Dialog -> FileDialog(ownerWindow, "Сохранить отчёт", FileDialog.SAVE)
                                else -> FileDialog(null as java.awt.Frame?, "Сохранить отчёт", FileDialog.SAVE)
                            }.apply {
                                file = "gates-incident-${id.take(12)}.json"
                                isVisible = true
                            }
                            val destination = dialog.file?.let { File(dialog.directory, it) }
                            dialog.dispose()
                            if (destination != null) scope.launch {
                                historyLoading = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        val report = windowsIncidentArchiveExport(id)
                                        check(report.get("availability")?.asString == "available")
                                        destination.writeText(report.toString(), Charsets.UTF_8)
                                    }
                                    historyMessage = "Отчёт сохранён."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    historyMessage = "Не удалось сохранить отчёт."
                                } finally {
                                    historyLoading = false
                                }
                            }
                        },
                    )
                }
                return@Box
            }

            if (showSettingsDialog) {
                GatesWindowsTheme(windowPalette, windowSpaceActive) {
                    WindowsSettingsScreen(
                        busy = busy,
                        palette = windowPalette,
                        spaceActive = windowSpaceActive,
                        runtimeMode = runtimeMode,
                        killSwitchEnabled = killSwitchEnabled,
                        protectionMessage = windowsProtectionLabel(protectionState),
                        onKillSwitchChange = if (qa == null) ({ enabled ->
                            launchRuntime("Защита", "set-kill-switch", argument = enabled.toString())
                        }) else null,
                        autoUploadRecoveryReports = autoUploadRecoveryReports,
                        onAutoUploadRecoveryReportsChange = if (qa == null) { enabled ->
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        controller.writeConfig(controller.readConfig().copy(autoUploadRecoveryReports = enabled))
                                    }
                                }.onSuccess {
                                    autoUploadRecoveryReports = enabled
                                    lastMessage = ""
                                }.onFailure {
                                    lastMessage = "Не удалось сохранить настройку отчётов."
                                }
                            }
                        } else null,
                        appVersion = windowsAppVersionForDisplay(),
                        onInstallUpdate = if (updateAvailable) ({ launchRuntime("Обновление", "update") }) else null,
                        statusMessage = lastMessage,
                        onRuntimeModeChange = { launchRuntime("Режим", "set-runtime", argument = it) },
                        onDismiss = { showSettingsDialog = false },
                        onAccess = {
                            showSettingsDialog = false
                            accessReturnTo = "settings"
                            lastMessage = ""
                            showAccess = true
                        },
                        onApps = {
                            showSettingsDialog = false
                            launchLoadAppRouting()
                        },
                        onUpdate = {
                            launchRuntime("Обновление", "check-update")
                        },
                        onBugReport = {
                            showSettingsDialog = false
                            reportReturnTo = "settings"
                            lastMessage = ""
                            showBugDialog = true
                        },
                        onHistory = {
                            showSettingsDialog = false
                            historyFromSettings = true
                            showHistory = true
                        },
                    )
                }
                return@Box
            }

            if (!sessionState.ready || showAccess) {
                GatesWindowsTheme(windowPalette, windowSpaceActive) {
                    GatesAccessScreen(
                        signedIn = sessionState.signedIn,
                        busy = busy,
                        message = when (authorizationPhase) {
                            WindowsAuthorizationPhase.Waiting -> "Подтвердите вход в браузере."
                            WindowsAuthorizationPhase.ReceivingProfile -> "Получаю личную конфигурацию."
                            else -> lastMessage
                        },
                        manualInput = manualSubscription,
                        onManualInputChanged = { manualSubscription = it },
                        onImport = { launchManualImport() },
                        onLogin = { launchAuthSetup() },
                        onCancel = {
                            showAccess = false; manualSubscription = ""
                            if (accessReturnTo == "settings") showSettingsDialog = true
                            if (accessReturnTo == "routing") showAppsDialog = true
                        if (accessReturnTo == "history") showHistory = true
                        },
                        canCancel = sessionState.ready,
                        onBugReport = { reportReturnTo = "access"; lastMessage = ""; showBugDialog = true },
                        onHistory = { historyFromSettings = false; showHistory = true },
                        colors = YokhanLightPalette,
                        authorizationCode = authorizationPrompt?.userCode.orEmpty(),
                        authorizationPending = authorizationPrompt != null,
                        onOpenAuthorization = { scope.launch(Dispatchers.IO) { controller.reopenAuthorization() } },
                        onCancelAuthorization = {
                            if (controller.cancelPendingAuthorization { authJob?.cancel() }) lastMessage = "Вход отменён."
                        },
                    )
                }
                return@Box
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
                            drawBackgroundEffects = qa?.backgroundEffects?.value ?: true,
                            profiles = profiles,
                            labels = windowsProtectionDashboardLabels(protectionState, killSwitchEnabled,
                                connectionStatus == ConnectionStatus.Connected),
                            actions = DashboardUiActions(
                                onToggleConnection = {
                                    if (qa != null) {
                                        lastMessage = "Операция недоступна в тестовом режиме."
                                    } else if (!busy) {
                                        if (connectionStatus == ConnectionStatus.Connected || protectionState.retainsProtection) {
                                            connectionStatus = ConnectionStatus.Disconnecting
                                            launchRuntime("Отключение", "stop", nextStatus = ConnectionStatus.Disconnected)
                                        } else {
                                            connectionStatus = ConnectionStatus.Connecting
                                            launchRuntime("Подключение", "connect", nextStatus = ConnectionStatus.Connected)
                                        }
                                    }
                                },
                                onSelectProfile = { id ->
                                    windowsRouteTagForProfileId(id, availableRoutes)?.let { route ->
                                        if (qa != null && !qa.realAccess) activeRoute = route
                                        else launchRuntime("Маршрут $route", "set-route", route = route)
                                    }
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
                    )
                }
            }
        }
        if (sessionState.ready && automationScreen !in listOf("access", "bug")) {
            GatesBottomNavigation(
                selectedKey = when (automationScreen) { "history" -> "settings"; else -> automationScreen },
                items = listOf(GatesNavigationItem("dashboard", "Главная", "01"),
                    GatesNavigationItem("access", "Доступ", "02"), GatesNavigationItem("routing", "Приложения", "03"),
                    GatesNavigationItem("settings", "Настройки", "04")),
                colors = windowPalette,
                enabled = !busy,
                onSelect = { destination ->
                    if (destination == "access") accessReturnTo = automationScreen
                    showSettingsDialog = false; showAppsDialog = false; showHistory = false; showAccess = false
                    lastMessage = ""
                    when (destination) {
                        "settings" -> showSettingsDialog = true
                        "access" -> showAccess = true
                        "routing" -> launchLoadAppRouting()
                    }
                },
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
private fun WindowsStatusBar(
    message: String,
    palette: YokhanPalette,
) {
    if (message.isBlank() || message in listOf("Подключено.", "Отключено.")) return
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
                text = message,
                color = palette.primary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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

internal fun dashboardState(
    status: ConnectionStatus,
    activeRoute: String,
    busy: Boolean,
): DashboardState {
    val connected = status == ConnectionStatus.Connected
    val running = status == ConnectionStatus.Connecting || connected || status == ConnectionStatus.Disconnecting
    return DashboardState(
        connectionStatus = status,
        activeProfileName = activeRouteLabel(activeRoute),
        activeProfileProtocol = routeProtocol(activeRoute),
        activeProfileProtocolLabel = routeProtocolLabel(activeRoute),
        activeProfileSubtitle = if (activeRoute == "auto") "Автоматический выбор маршрута" else "",
        uploadRate = RateDisplay(0),
        downloadRate = RateDisplay(0),
        latency = LatencyDisplay(null),
        uploadLabel = if (connected) "--" else "0 B/s",
        downloadLabel = if (connected) "--" else "0 B/s",
        latencyLabel = "--",
        timerLabel = if (connected) "--:--:--" else "00:00:00",
        shouldAnimateSpace = running,
        canRunConnectionTest = connected,
        isConnectionTestRunning = false,
        errorMessage = null,
    )
}

private fun activeRouteLabel(route: String): String =
    when (route) {
        "auto" -> "Автоматически"
        "bridge" -> "Bridge"
        "relay" -> "Relay"
        "naive" -> "Naive"
        "hy2" -> "HY2"
        "nl-tcp" -> "NL VLESS"
        else -> route
    }

private fun routeProtocol(route: String): VpnProtocol =
    when (route) {
        "bridge", "relay", "nl-tcp" -> VpnProtocol.Vless
        else -> VpnProtocol.Unknown
    }

private fun routeProtocolLabel(route: String): String =
    when (route) {
        "bridge", "relay", "nl-tcp" -> "VLESS"
        "naive" -> "HTTPS"
        "hy2" -> "HYSTERIA2"
        else -> "AUTO"
    }
