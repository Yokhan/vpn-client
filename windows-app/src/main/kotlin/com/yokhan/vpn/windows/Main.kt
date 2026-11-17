package com.yokhan.gates.windows

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
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
import com.yokhan.gates.shared.ui.access.GatesAccessScreen
import com.yokhan.gates.shared.ui.dashboard.DashboardProfileUiItem
import com.yokhan.gates.shared.ui.dashboard.DashboardUiActions
import com.yokhan.gates.shared.ui.dashboard.DashboardUiTuning
import com.yokhan.gates.shared.ui.dashboard.DashboardUiVisibility
import com.yokhan.gates.shared.ui.dashboard.YokhanDashboardScreen
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.YokhanSpacing
import com.yokhan.gates.shared.ui.design.yokhanPalette
import com.yokhan.gates.shared.ui.design.yokhanTypography
import com.yokhan.gates.shared.ui.diagnostics.GatesIncidentHistoryItem
import com.yokhan.gates.shared.ui.diagnostics.GatesIncidentHistoryScreen
import com.yokhan.gates.shared.ui.navigation.GatesBottomNavigation
import com.yokhan.gates.shared.ui.design.YokhanLayout
import com.yokhan.gates.shared.ui.navigation.GatesNavigationItem
import com.yokhan.gates.shared.ui.route.YokhanAdaptiveActionPair
import com.yokhan.gates.shared.ui.route.YokhanBody
import com.yokhan.gates.shared.ui.route.YokhanCaption
import com.yokhan.gates.shared.ui.route.YokhanMonoValue
import com.yokhan.gates.shared.ui.route.YokhanRouteButton
import com.yokhan.gates.shared.ui.route.YokhanRouteCard
import com.yokhan.gates.windows.qa.WindowsQaState
import com.yokhan.gates.windows.service.isGatesRuntimeServiceIdentitySystem
import com.yokhan.gates.windows.service.runGatesServiceProcess
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Point
import java.awt.Window as AwtWindow
import java.io.File
import java.nio.file.Path
import javax.swing.RootPaneContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private val AppWidth = 430.dp
private val AppHeight = 820.dp
private val ProductionDashboardVisibility = DashboardUiVisibility(
    showMenu = false,
    showConnectionTest = true,
    showProfiles = true,
    showAddProfile = true,
    showProfileActions = true,
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
            System.err.println()
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
        Runtime.getRuntime().halt(0)
    }

    val singleInstanceGuard = if (qa == null) {
        WindowsSingleInstanceGuard.tryAcquireOrWakeExisting() ?: return
    } else {
        null
    }
    val savedWindowPrefs = if (qa == null) WindowsWindowBoundsStore.load() else WindowsWindowSizePrefs(430, 820)

    try {
        application {
            DisposableEffect(qa) { onDispose { qa?.close() } }
            val windowState = rememberWindowState(
                size = if (qa != null) DpSize(AppWidth, AppHeight) else DpSize(savedWindowPrefs.widthDp.dp, savedWindowPrefs.heightDp.dp),
            )
            val traySupported = remember { qa == null && NativeTray.isSupported() }
            var windowVisible by remember { mutableStateOf(true) }
            val trayActions = remember { Channel<String>(Channel.UNLIMITED) }
            var trayRef by remember { mutableStateOf<NativeTray?>(null) }
            var ownerWindowRef by remember { mutableStateOf<AwtWindow?>(null) }

            LaunchedEffect(singleInstanceGuard) {
                singleInstanceGuard?.onActivateRequest = {
                    EventQueue.invokeLater {
                        windowVisible = true
                        ownerWindowRef?.let { win ->
                            if (win is Frame && (win.extendedState and Frame.ICONIFIED) != 0) {
                                win.extendedState = win.extendedState and Frame.ICONIFIED.inv()
                            }
                            win.isVisible = true
                            win.toFront()
                            win.requestFocus()
                        }
                    }
                }
            }

            if (qa == null) {
                LaunchedEffect(windowState.size) {
                    delay(450)
                    WindowsWindowBoundsStore.save(
                        widthDp = windowState.size.width.value.toInt(),
                        heightDp = windowState.size.height.value.toInt(),
                    )
                }
            }

            DisposableEffect(traySupported) {
                val tray = if (traySupported) {
                    NativeTray(
                        onOpen = {
                            windowVisible = true
                            EventQueue.invokeLater {
                                ownerWindowRef?.let { win ->
                                    if (win is Frame && (win.extendedState and Frame.ICONIFIED) != 0) {
                                        win.extendedState = win.extendedState and Frame.ICONIFIED.inv()
                                    }
                                    win.isVisible = true
                                    win.toFront()
                                }
                            }
                        },
                        onToggleConnection = { trayActions.trySend("toggle") },
                        onTestConnection = { trayActions.trySend("test") },
                        onSelectRoute = { routeTag -> trayActions.trySend("select-route:$routeTag") },
                        onToggleRuBypass = { trayActions.trySend("toggle-ru-bypass") },
                        onHideToTray = { windowVisible = false },
                        onQuit = { exitApplication() },
                    ).also { it.install() }
                } else {
                    null
                }
                trayRef = tray
                onDispose {
                    trayRef = null
                    tray?.uninstall()
                }
            }

            Window(
                visible = windowVisible,
                onCloseRequest = {
                    if (traySupported) {
                        windowVisible = false
                    } else {
                        exitApplication()
                    }
                },
                title = when {
                    qa == null -> "GATES"
                    qa.realAccess -> "GATES QA - isolated access"
                    else -> "GATES QA - synthetic data"
                },
                state = windowState,
                icon = remember { BitmapPainter(WindowsBrandIcons.load(256).toComposeImageBitmap()) },
            ) {
                DisposableEffect(window) {
                    ownerWindowRef = window
                    window.minimumSize = java.awt.Dimension(400, 620)
                    qa?.attachWindow(window)
                    onDispose {
                        if (ownerWindowRef === window) ownerWindowRef = null
                    }
                }
                WindowsApp(
                    ownerWindow = window,
                    qa = qa,
                    trayActions = if (traySupported) trayActions else null,
                    onTrayStateChanged = { connected, routeLabel, routeTag, routes, ruBypass ->
                        trayRef?.updateState(
                            connected = connected,
                            activeRouteLabel = routeLabel,
                            activeRouteTag = routeTag,
                            routes = routes,
                            enableRuBypass = ruBypass,
                        )
                    },
                )
            }
        }
    } finally {
        singleInstanceGuard?.close()
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
            onPrimary = palette.onAccent,
            secondary = palette.secondary,
            onSecondary = palette.onAccent,
            background = palette.background,
            onBackground = palette.primary,
            surface = palette.surface,
            onSurface = palette.primary,
            surfaceVariant = palette.surfaceRaised,
            onSurfaceVariant = palette.secondary,
            outline = palette.border,
            outlineVariant = palette.divider,
            error = palette.danger,
            onError = palette.onAccent,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            secondary = palette.secondary,
            onSecondary = palette.onAccent,
            background = palette.background,
            onBackground = palette.primary,
            surface = palette.surface,
            onSurface = palette.primary,
            surfaceVariant = palette.surfaceRaised,
            onSurfaceVariant = palette.secondary,
            outline = palette.border,
            outlineVariant = palette.divider,
            error = palette.danger,
            onError = palette.onAccent,
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
              GATES --cli reconnect
              GATES --cli sync-runtime
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
              GATES --cli set-route <auto|xhttp|bridge|relay|naive>
              GATES --cli set-route-order <tag1,tag2,...>
              GATES --cli set-runtime <tun|system-proxy|proxy>
              GATES --cli set-tun-routing <all|selected>
              GATES --cli set-ru-bypass <true|false>
              GATES --cli set-whole-traffic <true|false>
              GATES --cli set-bypass-lan <true|false>
              GATES --cli set-block-quic <true|false>
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
            """.trimIndent(),
        )
        return@runBlocking 0
    }

    val controller = WindowsRuntimeController()
    val argument = when (command) {
        "set-route", "set-route-order", "set-runtime", "set-tun-routing", "adopt-state",
        "set-ru-bypass", "set-whole-traffic", "set-bypass-lan", "set-block-quic" -> args.getOrNull(1)
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
private fun WindowsApp(
    ownerWindow: AwtWindow? = null,
    qa: WindowsQaSession? = null,
    trayActions: Channel<String>? = null,
    onTrayStateChanged: (
        connected: Boolean,
        activeRouteLabel: String,
        activeRouteTag: String,
        routes: List<WindowsProfileRoute>,
        enableRuBypass: Boolean,
    ) -> Unit = { _, _, _, _, _ -> },
) {
    val compositionStart = if (qa?.profiling?.value == true) System.nanoTime() else 0L
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val clipboardManager = LocalClipboardManager.current
    val routingSearchFocusRequester = remember { FocusRequester() }
    val controller by remember(qa) {
        lazy {
            check(qa == null || qa.realAccess) { "Runtime unavailable in UI QA mode" }
            if (qa?.realAccess == true) windowsAccessAcceptanceController(qa.sessionDirectory.resolve("access-data").toFile())
            else WindowsRuntimeController()
        }
    }
    val recoveryDeliveryReady = remember(qa) { CompletableDeferred<WindowsRecoveryDelivery?>() }
    var qaRevision by remember { mutableStateOf(1L) }
    val runtimeCommandGeneration = remember { WindowsRuntimeCommandGeneration() }
    var connectionStatus by remember { mutableStateOf(ConnectionStatus.Disconnected) }
    var activeRoute by remember { mutableStateOf(WindowsAppConfig().activeRoute) }
    var resolvedActiveRoute by remember { mutableStateOf("auto") }
    var tunnelStartedAtMillis by remember { mutableStateOf<Long?>(null) }
    val telemetryTracker = remember { WindowsLiveTelemetryTracker() }
    var liveTelemetry by remember { mutableStateOf(WindowsLiveTelemetrySnapshot()) }
    var latestLatencyMs by remember { mutableStateOf<Long?>(null) }
    var connectionTestRunning by remember { mutableStateOf(false) }
    var availableRoutes by remember { mutableStateOf(emptyList<WindowsProfileRoute>()) }
    var busy by remember { mutableStateOf(true) }
    var lastMessage by remember { mutableStateOf("") }
    var roomNetMessage by remember { mutableStateOf("") }
    var routeStack by remember { mutableStateOf(WindowsRouteStack()) }
    var historyLoading by remember { mutableStateOf(false) }
    var historyMessage by remember { mutableStateOf("") }
    var historyItems by remember { mutableStateOf(emptyList<GatesIncidentHistoryItem>()) }
    var bugMessage by remember { mutableStateOf("") }
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
    var installedApps by remember {
        mutableStateOf(if (qa == null) WindowsInstalledAppsScanner.cachedOrEmpty() else emptyList())
    }
    var activeNetworkApps by remember { mutableStateOf(emptyList<WindowsActiveNetworkApp>()) }
    var enableRuBypass by remember { mutableStateOf(true) }
    var wholeTrafficMode by remember { mutableStateOf(false) }
    var bypassLan by remember { mutableStateOf(true) }
    var blockQuic by remember { mutableStateOf(false) }
    var routeOrder by remember { mutableStateOf(WindowsAppConfig().routeOrder) }
    var autoStartOnLogin by remember { mutableStateOf(false) }
    var listenHost by remember { mutableStateOf("127.0.0.1") }
    var socksPort by remember { mutableStateOf(2088) }
    var httpPort by remember { mutableStateOf(2089) }
    var systemProxyEnabled by remember { mutableStateOf(true) }
    var restoreSystemProxyOnStop by remember { mutableStateOf(true) }
    var tunStack by remember { mutableStateOf("mixed") }
    var tunMtu by remember { mutableStateOf(9000) }
    var tunStrictRoute by remember { mutableStateOf(true) }
    var physicalBindEnabled by remember { mutableStateOf(true) }
    var physicalBindInterface by remember { mutableStateOf("") }
    var pendingDroppedPaths by remember { mutableStateOf(emptyList<String>()) }
    var proxyDropBounds by remember { mutableStateOf<Rect?>(null) }
    var directDropBounds by remember { mutableStateOf<Rect?>(null) }
    var globalProxyDropBounds by remember { mutableStateOf<Rect?>(null) }
    var globalDirectDropBounds by remember { mutableStateOf<Rect?>(null) }
    var externalDragPointInRoot by remember { mutableStateOf<Offset?>(null) }
    var inAppDragLabel by remember { mutableStateOf<String?>(null) }
    var inAppDragPointInRoot by remember { mutableStateOf<Offset?>(null) }
    var probeOutput by remember { mutableStateOf("") }
    var logText by remember { mutableStateOf("") }
    var logSourceFilter by remember { mutableStateOf("all") }
    var toolsStatusSummary by remember { mutableStateOf("") }
    var toolsOutput by remember { mutableStateOf("") }
    var sessionState by remember { mutableStateOf(WindowsSessionState(false, false, false, "")) }
    var manualSubscription by remember { mutableStateOf("") }
    var authJob by remember { mutableStateOf<Job?>(null) }
    var accessFailure by remember { mutableStateOf<WindowsAccessFailure?>(null) }
    var accessPending by remember { mutableStateOf(false) }
    var bootstrapMessage by remember { mutableStateOf("") }
    var bootstrapExpiresAt by remember { mutableStateOf(0L) }
    var bootstrapNow by remember { mutableStateOf(System.currentTimeMillis()) }
    val bootstrapRemaining = if (bootstrapExpiresAt > bootstrapNow) {
        val left = ((bootstrapExpiresAt - bootstrapNow).coerceAtLeast(0L) / 1000L)
        "%02d:%02d".format(left / 60L, left % 60L)
    } else {
        ""
    }
    LaunchedEffect(bootstrapExpiresAt) {
        if (bootstrapExpiresAt <= 0L) return@LaunchedEffect
        while (bootstrapExpiresAt > System.currentTimeMillis()) {
            bootstrapNow = System.currentTimeMillis()
            delay(1000)
        }
        bootstrapNow = System.currentTimeMillis()
        if (qa == null) {
            val cfg = withContext(Dispatchers.IO) { runCatching { controller.readConfig() }.getOrNull() }
            if (cfg != null && cfg.profileSource == "bootstrap" && cfg.bootstrapExpiresAtEpochMillis in 1..System.currentTimeMillis()) {
                runCatching { withContext(Dispatchers.IO) { controller.run("stop") } }
                connectionStatus = ConnectionStatus.Disconnected
                bootstrapExpiresAt = 0L
                bootstrapMessage = "Время разового доступа Древа истекло. Войдите в аккаунт или подключите подписку."
                lastMessage = bootstrapMessage
            }
        }
    }
    val authorizationPrompt by if (qa == null || qa.realAccess) controller.authorizationPrompt.collectAsState()
        else remember { mutableStateOf<WindowsAuthorizationPrompt?>(null) }
    val authorizationPhase by if (qa == null || qa.realAccess) controller.authorizationPhase.collectAsState()
        else remember { mutableStateOf(WindowsAuthorizationPhase.Idle) }

    val state = remember(connectionStatus, activeRoute, resolvedActiveRoute, liveTelemetry, latestLatencyMs, connectionTestRunning, busy) {
        dashboardState(
            status = connectionStatus,
            activeRoute = activeRoute,
            busy = busy,
            resolvedRoute = resolvedActiveRoute,
            telemetry = liveTelemetry,
            latencyMs = latestLatencyMs,
            connectionTestRunning = connectionTestRunning,
        )
    }
    val windowSpaceActive = sessionState.ready &&
        (state.shouldAnimateSpace || connectionStatus == ConnectionStatus.Disconnecting)
    val windowPalette = yokhanPalette(spaceActive = windowSpaceActive)
    val windowTypography = yokhanTypography()
    val effectiveRoute = routeStack.resolveEffective(sessionState.ready)
    val automationScreen = effectiveRoute.qaName
    val automationScreenshotAllowed = authorizationPrompt == null &&
        (automationScreen != "access" || manualSubscription.isBlank()) &&
        (automationScreen != "bug" || bugMessage.isBlank())
    val automationBusy = busy || historyLoading
    val automationRevision = qa?.reserveRevision(qaRevision) ?: 0L

    if (qa == null) {
        SyncMonolithicWindowChrome(ownerWindow, windowPalette, windowSpaceActive)
    }

    fun applyConfigSnapshot(cfg: WindowsAppConfig) {
        activeRoute = cfg.activeRoute
        runtimeMode = cfg.runtimeMode
        killSwitchEnabled = cfg.killSwitchEnabled
        bootstrapExpiresAt = cfg.bootstrapExpiresAtEpochMillis
        autoUploadRecoveryReports = cfg.autoUploadRecoveryReports
        enableRuBypass = cfg.enableRuBypass
        wholeTrafficMode = cfg.wholeTrafficMode
        bypassLan = cfg.bypassLan
        blockQuic = cfg.blockQuic
        routeOrder = cfg.routeOrder
        autoStartOnLogin = cfg.autoStartOnLogin
        listenHost = cfg.listenHost
        socksPort = cfg.socksPort
        httpPort = cfg.httpPort
        systemProxyEnabled = cfg.systemProxyEnabled
        restoreSystemProxyOnStop = cfg.restoreSystemProxyOnStop
        tunStack = cfg.tunStack
        tunMtu = cfg.tunMtu
        tunStrictRoute = cfg.tunStrictRoute
        physicalBindEnabled = cfg.physicalBindEnabled ?: true
        physicalBindInterface = cfg.physicalBindInterface.orEmpty()
    }

    LaunchedEffect(connectionStatus, activeRoute, resolvedActiveRoute, availableRoutes, enableRuBypass) {
        val label = if (activeRoute == "auto" && resolvedActiveRoute.isNotBlank() && resolvedActiveRoute != "auto") {
            "AUTO → ${activeRouteLabel(resolvedActiveRoute)}"
        } else {
            activeRouteLabel(activeRoute)
        }
        onTrayStateChanged(
            connectionStatus == ConnectionStatus.Connected,
            label,
            activeRoute,
            availableRoutes,
            enableRuBypass,
        )
    }

    SideEffect {
        if (qa != null) {
            if (compositionStart != 0L) qa.timings.composition(System.nanoTime() - compositionStart, qa.timingLabel)
            val screen = automationScreen
            val actions = buildList {
                addAll(
                    listOf(
                        "nav.dashboard", "nav.settings", "nav.access", "nav.routes", "nav.routing",
                        "nav.logs", "nav.tools", "nav.history", "nav.bug", "nav.back",
                        "fixture.disconnected", "fixture.connected", "fixture.connecting", "fixture.error",
                    ),
                )
                if (!qa.realAccess) {
                    addAll(
                        listOf(
                            "fixture.loading", "fixture.empty", "fixture.long", "fixture.default",
                            "fixture.signedout", "fixture.signedin", "fixture.update",
                            "fixture.effects-off", "fixture.effects-on",
                        ),
                    )
                }
                if (screen == "history") add("history.refresh")
            }
            qa.publish(
                WindowsQaState(
                    screen, automationRevision, automationBusy, actions,
                    fixture = !qa.realAccess, connectionStatus = connectionStatus.name,
                    signedIn = sessionState.signedIn, profileReady = sessionState.ready,
                    activeRoute = activeRoute, availableRoutes = availableRoutes.map { it.tag },
                ),
                screenshotAllowed = automationScreenshotAllowed,
            ) { id ->
                if (id.startsWith("nav.")) {
                    routeStack = routeStack.applyQaNavigation(id)
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
                            installedApps = emptyList()
                            activeNetworkApps = emptyList()
                            historyItems = emptyList()
                            appRoutingState = AppRoutingState(true, emptyList(), emptyList(), emptyList(), emptyList())
                        }
                        "fixture.long" -> {
                            qaDataSet = "long"
                            runningApps = List(40) {
                                WindowsProcessCandidate(
                                    it.toLong(),
                                    "process-$it.exe",
                                    "C:\\QA\\${"Long folder name ".repeat(8).trimEnd()}\\process-$it.exe",
                                )
                            }
                            appRoutingState = appRoutingState.copy(proxyProcessPaths = listOf(runningApps.first().path))
                        }
                        "fixture.default" -> {
                            updateAvailable = false
                            qaDataSet = "default"
                            runningApps = listOf(WindowsProcessCandidate(0, "game.exe", "C:\\QA\\game.exe"))
                            installedApps = listOf(
                                WindowsInstalledAppCandidate(
                                    displayName = "Яндекс Браузер",
                                    name = "browser.exe",
                                    path = "C:\\QA\\browser.exe",
                                    source = "qa",
                                    isRuCandidate = true,
                                ),
                            )
                            appRoutingState = AppRoutingState(true, listOf("game.exe"), emptyList(), listOf("browser.exe"), emptyList())
                        }
                        "fixture.signedout" -> {
                            sessionState = WindowsSessionState(false, false, false, "")
                            accessFailure = null
                            accessPending = false
                            routeStack = routeStack.push(WindowsRoute.Access)
                        }
                        "fixture.signedin" -> {
                            sessionState = WindowsSessionState(true, true, true, "")
                            accessFailure = null
                            accessPending = false
                            routeStack = routeStack.push(WindowsRoute.Access)
                        }
                        "fixture.update" -> {
                            updateAvailable = true
                            lastMessage = "Доступно обновление."
                        }
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
        if (profiling && qa != null) {
            while (true) {
                withFrameNanos {
                    qa.motionDurationScale = durationScale?.scaleFactor ?: 1f
                    qa.timings.frame(it, qa.timingLabel)
                }
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

    LaunchedEffect(qa) {
        if (qa != null) return@LaunchedEffect
        installedApps = withContext(Dispatchers.IO) { WindowsInstalledAppsScanner.getOrScan() }
    }

    LaunchedEffect(Unit) {
        if (qa != null && !qa.realAccess) {
            sessionState = WindowsSessionState(false, true, true, "")
            appRoutingState = AppRoutingState(true, listOf("game.exe"), emptyList(), listOf("browser.exe"), emptyList())
            runningApps = listOf(WindowsProcessCandidate(0, "game.exe", "C:\\QA\\game.exe"))
            installedApps = listOf(
                WindowsInstalledAppCandidate(
                    displayName = "Яндекс Браузер",
                    name = "browser.exe",
                    path = "C:\\QA\\browser.exe",
                    source = "qa",
                    isRuCandidate = true,
                ),
            )
            availableRoutes = listOf(
                WindowsProfileRoute("xhttp", "XHTTP Primary", "VLESS / XHTTP", "r1.yokhan.xyz"),
                WindowsProfileRoute("bridge", "Bridge TLS", "VLESS / TLS", "r2.yokhan.xyz"),
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
            applyConfigSnapshot(snapshot.config)
            availableRoutes = snapshot.routes
            appRoutingState = snapshot.appRouting
            recoveryPreferenceLoaded = true
            autoCheckUpdates = snapshot.config.autoCheckUpdates
            snapshotLoaded = true
            if (qa == null && runtimeMode == "tun") {
                lastMessage = "Проверка службы GATES…"
                val prepared = withContext(Dispatchers.IO) { controller.run("prepare-runtime") }
                lastMessage = if (prepared.exitCode == 0) "" else windowsUiResultMessage("prepare-runtime", prepared)
            }
        }
        if (!snapshotLoaded) return@LaunchedEffect
        if (qa?.realAccess == true) return@LaunchedEffect

        val startupGeneration = runtimeCommandGeneration.capture()
        runCatching {
            val live = withContext(Dispatchers.IO) { controller.queryLiveRuntimeStatus() }
            if (runtimeCommandGeneration.isUnchanged(startupGeneration)) {
                protectionState = live.protectionState
                resolvedActiveRoute = live.resolvedRoute
                tunnelStartedAtMillis = live.startedAtEpochMillis
                if (live.connected) {
                    connectionStatus = ConnectionStatus.Connected
                    val roomNetResult = withContext(Dispatchers.IO) { controller.run("roomnet-info") }
                    if (runtimeCommandGeneration.isUnchanged(startupGeneration)) {
                        roomNetMessage = roomNetUiMessage(roomNetResult)
                    }
                }
            }
        }

        if (autoCheckUpdates) {
            runCatching {
                val update = withContext(Dispatchers.IO) { controller.run("check-update") }
                if (update.exitCode == 0 && update.output.contains("Update available: true")) {
                    updateAvailable = true
                    if (!busy && lastMessage.isBlank()) {
                        lastMessage = "Доступно обновление приложения."
                    }
                }
            }
        }
    }

    // Periodic local runtime sync (3s) — never triggers network `curl`.
    LaunchedEffect(qa, killSwitchEnabled) {
        if (qa != null) return@LaunchedEffect
        while (true) {
            val generationBeforePoll = runtimeCommandGeneration.capture()
            val live = withContext(Dispatchers.IO) { runCatching { controller.queryLiveRuntimeStatus() }.getOrNull() }
            if (live != null && runtimeCommandGeneration.isUnchanged(generationBeforePoll) && !busy) {
                protectionState = live.protectionState
                resolvedActiveRoute = live.resolvedRoute
                tunnelStartedAtMillis = live.startedAtEpochMillis
                connectionStatus = when {
                    live.protectionState in setOf(WindowsProtectionState.BLOCKED, WindowsProtectionState.ERROR) ->
                        ConnectionStatus.Disconnected
                    killSwitchEnabled && live.protectionState == WindowsProtectionState.UNAVAILABLE ->
                        ConnectionStatus.Disconnected
                    live.connected -> ConnectionStatus.Connected
                    else -> ConnectionStatus.Disconnected
                }
            }
            delay(3_000)
        }
    }

    // Live 1s speed & elapsed session timer sampling while connected.
    LaunchedEffect(qa, connectionStatus, tunnelStartedAtMillis) {
        if (qa != null) return@LaunchedEffect
        if (connectionStatus != ConnectionStatus.Connected) {
            liveTelemetry = telemetryTracker.sample(false, null)
            latestLatencyMs = null
            connectionTestRunning = false
            return@LaunchedEffect
        }
        while (true) {
            val snap = withContext(Dispatchers.IO) {
                telemetryTracker.sample(connected = true, startedAtEpochMillis = tunnelStartedAtMillis)
            }
            liveTelemetry = snap
            delay(1_000)
        }
    }

    LaunchedEffect(qa, connectionStatus, activeRoute) {
        if (qa != null) return@LaunchedEffect
        if (connectionStatus != ConnectionStatus.Connected) {
            latestLatencyMs = null
            return@LaunchedEffect
        }
        delay(600)
        if (connectionStatus != ConnectionStatus.Connected || busy || latestLatencyMs != null) return@LaunchedEffect
        val probe = withContext(Dispatchers.IO) { runCatching { controller.run("test") }.getOrNull() }
        val measured = probe?.output?.let(::parseBestLatencyMsFromTestOutput)
        if (measured != null && connectionStatus == ConnectionStatus.Connected) {
            latestLatencyMs = measured
        }
    }

    fun refreshActiveNetworkApps() {
        if (qa != null) return
        scope.launch {
            activeNetworkApps = withContext(Dispatchers.IO) {
                runCatching { WindowsActiveConnections.query(appRoutingState) }.getOrDefault(emptyList())
            }
        }
    }

    LaunchedEffect(qa, effectiveRoute, appRoutingState) {
        if (qa != null) return@LaunchedEffect
        if (effectiveRoute in setOf(WindowsRoute.Dashboard, WindowsRoute.Tools, WindowsRoute.Routing)) {
            activeNetworkApps = withContext(Dispatchers.IO) {
                runCatching { WindowsActiveConnections.query(appRoutingState) }.getOrDefault(emptyList())
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

    LaunchedEffect(recoveryDeliveryReady) {
        val delivery = recoveryDeliveryReady.await() ?: return@LaunchedEffect
        while (true) {
            delay(120_000)
            withContext(Dispatchers.IO) { runCatching { delivery.sampleStandaloneProxy() } }
        }
    }

    val profiles = remember(activeRoute, resolvedActiveRoute, availableRoutes) {
        val autoSubtitle = if (activeRoute == "auto" && resolvedActiveRoute.isNotBlank() && resolvedActiveRoute != "auto") {
            "AUTO → ${activeRouteLabel(resolvedActiveRoute)}"
        } else {
            "AUTO"
        }
        listOf(DashboardProfileUiItem(1, "Автоматически", autoSubtitle, activeRoute == "auto")) +
            availableRoutes.mapIndexed { index, route ->
                DashboardProfileUiItem(
                    (index + 2).toLong(),
                    route.label,
                    "${route.protocol} · ${route.server}",
                    activeRoute == route.tag,
                )
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
                onFailure = {
                    connectionTestRunning = false
                    lastMessage = it
                },
            ) {
                if (command == "test") {
                    connectionTestRunning = true
                }
                if (command == "connect") {
                    val source = withContext(Dispatchers.IO) { runCatching { controller.readConfig().profileSource }.getOrNull() }
                    if (source == "account") {
                        withContext(Dispatchers.IO) { runCatching { controller.run("import-app") } }
                        activeRoute = withContext(Dispatchers.IO) {
                            runCatching { controller.readConfig().activeRoute }.getOrDefault(activeRoute)
                        }
                        availableRoutes = withContext(Dispatchers.IO) { controller.availableRoutes() }
                    }
                }
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
                    tunnelStartedAtMillis = System.currentTimeMillis()
                    roomNetMessage = roomNetUiMessage(
                        withContext(Dispatchers.IO) { controller.run("roomnet-info") },
                    )
                }
                if (command == "stop" && result.exitCode == 0) {
                    tunnelStartedAtMillis = null
                    roomNetMessage = ""
                }
                if (command == "set-runtime" && result.exitCode == 0) runtimeMode = argument ?: "tun"
                if (command == "set-kill-switch" && result.exitCode == 0) killSwitchEnabled = argument == "true"
                if (command in setOf(
                        "set-kill-switch", "set-ru-bypass", "set-whole-traffic", "set-bypass-lan",
                        "set-block-quic", "set-route-order", "enable-autostart",
                        "disable-autostart", "reconnect", "sync-runtime", "set-route",
                    ) && result.exitCode == 0
                ) {
                    val refreshedCfg = withContext(Dispatchers.IO) { controller.readConfig() }
                    applyConfigSnapshot(refreshedCfg)
                    appRoutingState = withContext(Dispatchers.IO) { controller.appRoutingState() }
                }
                if (command == "probe-routes") {
                    probeOutput = result.output
                    toolsOutput = result.output
                } else if (command == "test") {
                    connectionTestRunning = false
                    latestLatencyMs = parseBestLatencyMsFromTestOutput(result.output) ?: latestLatencyMs
                    toolsOutput = result.output
                } else if (command in setOf("reconnect", "sync-runtime", "disable-system-proxy", "support-bundle")) {
                    toolsOutput = result.output
                }
                val live = withContext(Dispatchers.IO) { runCatching { controller.queryLiveRuntimeStatus() }.getOrNull() }
                if (live != null) {
                    protectionState = live.protectionState
                    resolvedActiveRoute = live.resolvedRoute
                    if (live.startedAtEpochMillis != null) tunnelStartedAtMillis = live.startedAtEpochMillis
                }
                if (command == "check-update" && result.exitCode == 0) {
                    updateAvailable = result.output.contains("Update available: true")
                }
                lastMessage = windowsUiResultMessage(command, result)
                if (result.exitCode == 0) onSuccess?.invoke()
            }
        }
    }

    LaunchedEffect(trayActions) {
        val actions = trayActions ?: return@LaunchedEffect
        for (action in actions) {
            if (qa != null || busy) continue
            when {
                action == "toggle" -> {
                    if (connectionStatus == ConnectionStatus.Connected || (!killSwitchEnabled && protectionState.retainsProtection)) {
                        connectionStatus = ConnectionStatus.Disconnecting
                        launchRuntime("Отключение", "stop", nextStatus = ConnectionStatus.Disconnected)
                    } else {
                        connectionStatus = ConnectionStatus.Connecting
                        launchRuntime("Подключение", "connect", nextStatus = ConnectionStatus.Connected)
                    }
                }
                action == "test" -> launchRuntime("Проверка", "test")
                action == "toggle-ru-bypass" -> {
                    launchRuntime("RU-автообход", "set-ru-bypass", argument = (!enableRuBypass).toString())
                }
                action.startsWith("select-route:") -> {
                    val tag = action.removePrefix("select-route:")
                    if (tag.isNotBlank()) launchRuntime("Маршрут $tag", "set-route", route = tag)
                }
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
                val resolved = withContext(Dispatchers.IO) { controller.resolveAppTarget(argument) }
                val result = withContext(Dispatchers.IO) { controller.run(command, resolved) }
                appRoutingState = withContext(Dispatchers.IO) { controller.appRoutingState() }
                lastMessage = windowsUiResultMessage(command, result)
            }
        }
    }

    fun launchBatchAppRouting(paths: List<String>, proxy: Boolean) {
        if (paths.isEmpty()) return
        if (qa != null) {
            paths.forEach { path ->
                appRoutingState = windowsQaRoutingChange(appRoutingState, if (proxy) "add-app-proxy" else "add-app-direct", path)
            }
            pendingDroppedPaths = emptyList()
            return
        }
        scope.launch {
            if (busy) return@launch
            runWindowsUiAction(
                setBusy = { busy = it },
                onFailure = { lastMessage = it },
            ) {
                val resolved = withContext(Dispatchers.IO) { paths.map { controller.resolveAppTarget(it) } }
                val result = withContext(Dispatchers.IO) { controller.addAppsRouting(resolved, proxy) }
                appRoutingState = withContext(Dispatchers.IO) { controller.appRoutingState() }
                pendingDroppedPaths = emptyList()
                lastMessage = result.output.ifBlank { windowsUiResultMessage(if (proxy) "add-app-proxy" else "add-app-direct", result) }
            }
        }
    }

    fun launchMoveAppRouting(target: String, toProxy: Boolean) {
        if (qa != null) {
            appRoutingState = windowsQaRoutingChange(appRoutingState, if (toProxy) "remove-app-direct" else "remove-app-proxy", target)
            appRoutingState = windowsQaRoutingChange(appRoutingState, if (toProxy) "add-app-proxy" else "add-app-direct", target)
            return
        }
        scope.launch {
            if (busy) return@launch
            runWindowsUiAction(
                setBusy = { busy = it },
                onFailure = { lastMessage = it },
            ) {
                val result = withContext(Dispatchers.IO) { controller.moveAppRouting(target, toProxy) }
                appRoutingState = withContext(Dispatchers.IO) { controller.appRoutingState() }
                lastMessage = result.output
            }
        }
    }

    fun launchSaveNetworkSettings(
        newListenHost: String,
        newSocksPort: Int,
        newHttpPort: Int,
        newSystemProxy: Boolean,
        newRestoreProxy: Boolean,
        newTunStack: String,
        newTunMtu: Int,
        newTunStrict: Boolean,
        newPhysicalBindEnabled: Boolean,
        newPhysicalBindInterface: String,
    ) {
        if (qa != null) {
            lastMessage = "Операция недоступна в тестовом режиме."
            return
        }
        scope.launch {
            if (busy) return@launch
            runWindowsUiAction(
                setBusy = { busy = it },
                onFailure = { lastMessage = it },
            ) {
                val result = withContext(Dispatchers.IO) {
                    controller.updateNetworkSettings(
                        listenHost = newListenHost,
                        socksPort = newSocksPort,
                        httpPort = newHttpPort,
                        systemProxyEnabled = newSystemProxy,
                        restoreSystemProxyOnStop = newRestoreProxy,
                        tunStack = newTunStack,
                        tunMtu = newTunMtu,
                        tunStrictRoute = newTunStrict,
                        physicalBindEnabled = newPhysicalBindEnabled,
                        physicalBindInterface = newPhysicalBindInterface,
                    )
                }
                val cfg = withContext(Dispatchers.IO) { controller.readConfig() }
                applyConfigSnapshot(cfg)
                lastMessage = result.output
            }
        }
    }

    fun refreshLogs(filter: String = logSourceFilter) {
        if (qa != null) {
            logText = "=== QA Log ($filter) ===\nGATES runtime ready on route $activeRoute"
            return
        }
        scope.launch {
            if (busy) return@launch
            runWindowsUiAction(
                setBusy = { busy = it },
                onFailure = { lastMessage = it },
            ) {
                logText = withContext(Dispatchers.IO) { controller.readDiagnosticLogs(filter) }
            }
        }
    }

    fun refreshToolsStatus() {
        if (qa != null) {
            toolsStatusSummary = "QA режим\nМаршрут: $activeRoute\nРежим: $runtimeMode"
            return
        }
        scope.launch {
            if (busy) return@launch
            runWindowsUiAction(
                setBusy = { busy = it },
                onFailure = { lastMessage = it },
            ) {
                val st = withContext(Dispatchers.IO) { controller.run("status").output }
                val svc = withContext(Dispatchers.IO) { controller.run("service-status").output }
                toolsStatusSummary = buildString {
                    appendLine(st.trim())
                    if (svc.isNotBlank()) {
                        appendLine("---")
                        append(svc.trim())
                    }
                }.trim()
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

    fun launchLoadAppRouting(asTopTab: Boolean = false) {
        val applyRoute: () -> Unit = {
            routeStack = if (asTopTab) {
                routeStack.switchTab(WindowsRoute.Routing)
            } else {
                routeStack.push(WindowsRoute.Routing)
            }
        }
        if (qa != null) {
            applyRoute()
            return
        }
        scope.launch {
            if (busy) return@launch
            runWindowsUiAction(
                setBusy = { busy = it },
                onFailure = { lastMessage = it },
            ) {
                appRoutingState = withContext(Dispatchers.IO) { controller.appRoutingState() }
                applyRoute()
            }
            refreshRunningApps()
        }
    }

    fun applyAccessOutcome(command: String, result: RuntimeCommandResult) {
        lastMessage = windowsUiResultMessage(command, result)
        if (command == WINDOWS_ACCESS_RESTART_COMMAND || command == WINDOWS_ACCESS_RETRY_COMMAND ||
            command == WINDOWS_ACCESS_REQUEST_COMMAND
        ) {
            accessFailure = result.accessFailure
            accessPending = result.accessPending || result.accessFailure?.code == WindowsAccessCode.ACCESS_PENDING
        }
    }

    fun launchAuthSetup(forceLogin: Boolean = false) {
        if (qa != null && !qa.realAccess) {
            lastMessage = "Операция недоступна в тестовом режиме."
            return
        }
        authJob = scope.launch {
            if (busy) return@launch
            runWindowsUiAction(
                setBusy = { busy = it },
                onFailure = { lastMessage = it },
            ) {
                val currentSession = withContext(Dispatchers.IO) { controller.sessionState() }
                val command = windowsAccessCommand(currentSession.signedIn, forceLogin)
                lastMessage = if (command == WINDOWS_ACCESS_RESTART_COMMAND) {
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
                applyConfigSnapshot(snapshot.config)
                if (snapshot.config.bootstrapExpiresAtEpochMillis == 0L) bootstrapMessage = ""
                availableRoutes = snapshot.routes
                appRoutingState = snapshot.appRouting
                applyAccessOutcome(command, result)
                if (result.exitCode == 0 && sessionState.ready) {
                    routeStack = routeStack.resetToDashboard()
                }
            }
        }
    }

    fun launchRequestAccess() {
        if (qa != null && !qa.realAccess) {
            lastMessage = "Операция недоступна в тестовом режиме."
            return
        }
        authJob = scope.launch {
            if (busy) return@launch
            runWindowsUiAction(
                setBusy = { busy = it },
                onFailure = { lastMessage = it },
            ) {
                lastMessage = "Отправляю заявку на доступ."
                val result = withContext(Dispatchers.IO) { controller.run(WINDOWS_ACCESS_REQUEST_COMMAND) }
                val snapshot = loadWindowsUiSnapshot(
                    readConfig = controller::readConfig,
                    readSession = controller::sessionState,
                    readAppRouting = controller::appRoutingState,
                    readRoutes = controller::availableRoutes,
                )
                sessionState = snapshot.session
                applyConfigSnapshot(snapshot.config)
                if (snapshot.config.bootstrapExpiresAtEpochMillis == 0L) bootstrapMessage = ""
                availableRoutes = snapshot.routes
                appRoutingState = snapshot.appRouting
                applyAccessOutcome(WINDOWS_ACCESS_REQUEST_COMMAND, result)
            }
        }
    }

    fun launchBootstrapClaim(code: String) {
        if (qa != null && !qa.realAccess) {
            lastMessage = "Операция недоступна в тестовом режиме."
            return
        }
        if (busy || code.isBlank()) return
        authJob = scope.launch {
            if (busy) return@launch
            runWindowsUiAction(
                setBusy = { busy = it },
                onFailure = {
                    lastMessage = it
                    bootstrapMessage = it
                },
            ) {
                bootstrapMessage = "Древо проверяет пароль…"
                val result = withContext(Dispatchers.IO) {
                    controller.run("bootstrap-claim", code)
                }
                val snapshot = loadWindowsUiSnapshot(
                    readConfig = controller::readConfig,
                    readSession = controller::sessionState,
                    readAppRouting = controller::appRoutingState,
                    readRoutes = controller::availableRoutes,
                )
                sessionState = snapshot.session
                applyConfigSnapshot(snapshot.config)
                if (snapshot.config.bootstrapExpiresAtEpochMillis == 0L) bootstrapMessage = ""
                availableRoutes = snapshot.routes
                appRoutingState = snapshot.appRouting
                bootstrapMessage = result.output
                if (result.exitCode == 0) {
                    bootstrapExpiresAt = snapshot.config.bootstrapExpiresAtEpochMillis
                        .takeIf { it > 0L } ?: (System.currentTimeMillis() + 15 * 60 * 1000L)
                    accessFailure = null
                    accessPending = false
                    lastMessage = result.output
                    routeStack = routeStack.resetToDashboard()
                }
            }
        }
    }

    fun launchManualImport() {
        if (qa != null && !qa.realAccess) {
            lastMessage = "Операция недоступна в тестовом режиме."
            return
        }
        if (busy || manualSubscription.isBlank()) return
        val input = manualSubscription
        scope.launch {
            if (busy || input.isBlank()) return@launch
            runWindowsUiAction(
                setBusy = { busy = it },
                onFailure = { lastMessage = it },
            ) {
                val result = withContext(Dispatchers.IO) { controller.run("import-sub", input) }
                val snapshot = loadWindowsUiSnapshot(
                    readConfig = controller::readConfig,
                    readSession = controller::sessionState,
                    readAppRouting = controller::appRoutingState,
                    readRoutes = controller::availableRoutes,
                )
                sessionState = snapshot.session
                applyConfigSnapshot(snapshot.config)
                if (snapshot.config.bootstrapExpiresAtEpochMillis == 0L) bootstrapMessage = ""
                availableRoutes = snapshot.routes
                appRoutingState = snapshot.appRouting
                lastMessage = windowsUiResultMessage("import-sub", result)
                if (result.exitCode == 0) {
                    manualSubscription = ""
                    accessFailure = null
                    accessPending = false
                    routeStack = routeStack.resetToDashboard()
                }
            }
        }
    }

    fun launchLogout() {
        if (qa != null && !qa.realAccess) {
            lastMessage = "Операция недоступна в тестовом режиме."
            return
        }
        scope.launch {
            if (busy) return@launch
            runWindowsUiAction(
                setBusy = { busy = it },
                onFailure = { lastMessage = it },
            ) {
                controller.run("logout")
                val snapshot = loadWindowsUiSnapshot(
                    readConfig = controller::readConfig,
                    readSession = controller::sessionState,
                    readAppRouting = controller::appRoutingState,
                    readRoutes = controller::availableRoutes,
                )
                sessionState = snapshot.session
                applyConfigSnapshot(snapshot.config)
                availableRoutes = snapshot.routes
                appRoutingState = snapshot.appRouting
                accessFailure = null
                accessPending = false
                lastMessage = "Вышли из аккаунта. Войдите через браузер/QR, Мудрое Древо или вставьте подписку."
            }
        }
    }

    fun navigateToRoute(target: WindowsRoute) {
        lastMessage = ""
        when (target) {
            WindowsRoute.Routing -> launchLoadAppRouting(asTopTab = true)
            WindowsRoute.Logs -> {
                routeStack = routeStack.push(WindowsRoute.Logs)
                refreshLogs(logSourceFilter)
            }
            WindowsRoute.Tools -> {
                routeStack = routeStack.switchTab(WindowsRoute.Tools)
                refreshActiveNetworkApps()
            }
            else -> routeStack = routeStack.switchTab(target)
        }
    }

    val dropTargetComponent = remember(ownerWindow) {
        (ownerWindow as? RootPaneContainer)?.contentPane ?: ownerWindow
    }

    DisposableEffect(dropTargetComponent) {
        val dropTarget = dropTargetComponent?.let { targetComp ->
            NativeFileDropTarget(
                component = targetComp,
                onDragLocationChanged = { pt ->
                    externalDragPointInRoot = pt?.let {
                        mapAwtPointToComposeRootPx(it, Point(0, 0), density)
                    }
                },
                onFilesDropped = { files, pt ->
                    val dropOffset = mapAwtPointToComposeRootPx(pt, Point(0, 0), density)
                    externalDragPointInRoot = null
                    if (qa != null || files.isEmpty()) return@NativeFileDropTarget
                    scope.launch {
                        if (busy) return@launch
                        val action = withContext(Dispatchers.IO) {
                            classifyDroppedFiles(files) { paths -> resolveWindowsShortcutTargets(paths) }
                        }
                        when (action) {
                            is DropIntakeAction.ImportProfileFile -> {
                                runWindowsUiAction(
                                    setBusy = { busy = it },
                                    onFailure = { lastMessage = it },
                                ) {
                                    val content = withContext(Dispatchers.IO) { action.file.readText(Charsets.UTF_8) }
                                    val result = withContext(Dispatchers.IO) {
                                        if (action.file.extension.equals("json", ignoreCase = true)) {
                                            controller.storeImportedProfile("manual", content)
                                        } else {
                                            controller.run("import-sub", content.trim())
                                        }
                                    }
                                    val snapshot = loadWindowsUiSnapshot(
                                        readConfig = controller::readConfig,
                                        readSession = controller::sessionState,
                                        readAppRouting = controller::appRoutingState,
                                        readRoutes = controller::availableRoutes,
                                    )
                                    sessionState = snapshot.session
                                    applyConfigSnapshot(snapshot.config)
                                    availableRoutes = snapshot.routes
                                    appRoutingState = snapshot.appRouting
                                    lastMessage = result.output.ifBlank { windowsUiResultMessage("import-sub", result) }
                                    if (result.exitCode == 0) {
                                        accessFailure = null
                                        accessPending = false
                                        routeStack = routeStack.resetToDashboard()
                                    }
                                }
                            }
                            is DropIntakeAction.RouteExecutables -> {
                                val overProxy = (effectiveRoute == WindowsRoute.Routing && proxyDropBounds?.contains(dropOffset) == true) ||
                                    (effectiveRoute != WindowsRoute.Routing && globalProxyDropBounds?.contains(dropOffset) == true)
                                val overDirect = (effectiveRoute == WindowsRoute.Routing && directDropBounds?.contains(dropOffset) == true) ||
                                    (effectiveRoute != WindowsRoute.Routing && globalDirectDropBounds?.contains(dropOffset) == true)
                                when {
                                    overProxy -> launchBatchAppRouting(action.exePaths, proxy = true)
                                    overDirect -> launchBatchAppRouting(action.exePaths, proxy = false)
                                    else -> {
                                        pendingDroppedPaths = action.exePaths
                                        if (effectiveRoute != WindowsRoute.Routing) {
                                            launchLoadAppRouting(asTopTab = true)
                                        }
                                    }
                                }
                            }
                            is DropIntakeAction.Unsupported -> {
                                lastMessage = action.reason
                            }
                        }
                    }
                },
            ).also { it.install() }
        }
        onDispose {
            externalDragPointInRoot = null
            dropTarget?.uninstall()
        }
    }

    val activeDragPoint = externalDragPointInRoot ?: inAppDragPointInRoot
    val showGlobalDropOverlay = (externalDragPointInRoot != null && effectiveRoute != WindowsRoute.Routing) ||
        inAppDragPointInRoot != null

    GatesWindowsTheme(windowPalette, windowSpaceActive) {
        Box(
            Modifier
                .fillMaxSize()
                .background(windowPalette.background)
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (keyEvent.key == Key.Escape) {
                        when {
                            inAppDragPointInRoot != null || externalDragPointInRoot != null -> {
                                inAppDragPointInRoot = null
                                inAppDragLabel = null
                                externalDragPointInRoot = null
                                true
                            }
                            pendingDroppedPaths.isNotEmpty() -> {
                                pendingDroppedPaths = emptyList()
                                true
                            }
                            !busy && effectiveRoute != WindowsRoute.Access && routeStack.entries.size > 1 -> {
                                routeStack = routeStack.popBack()
                                true
                            }
                            else -> false
                        }
                    } else if (keyEvent.isCtrlPressed && sessionState.ready && !busy) {
                        when (keyEvent.key) {
                            Key.One -> { navigateToRoute(WindowsRoute.Dashboard); true }
                            Key.Two -> { navigateToRoute(WindowsRoute.Routing); true }
                            Key.Three -> { navigateToRoute(WindowsRoute.Routes); true }
                            Key.Four -> { navigateToRoute(WindowsRoute.Settings); true }
                            Key.F -> {
                                navigateToRoute(WindowsRoute.Routing)
                                scope.launch {
                                    delay(160)
                                    runCatching { routingSearchFocusRequester.requestFocus() }
                                }
                                true
                            }
                            Key.R -> {
                                when (effectiveRoute) {
                                    WindowsRoute.Routing -> {
                                        refreshRunningApps()
                                        refreshActiveNetworkApps()
                                    }
                                    WindowsRoute.Tools -> {
                                        refreshToolsStatus()
                                        refreshActiveNetworkApps()
                                    }
                                    WindowsRoute.Logs -> refreshLogs(logSourceFilter)
                                    else -> refreshActiveNetworkApps()
                                }
                                true
                            }
                            Key.V -> {
                                if (effectiveRoute == WindowsRoute.Dashboard) {
                                    val clip = clipboardManager.getText()?.text?.trim().orEmpty()
                                    if (clip.startsWith("http", ignoreCase = true) || clip.startsWith("gates://", ignoreCase = true)) {
                                        manualSubscription = clip
                                        navigateToRoute(WindowsRoute.Access)
                                        true
                                    } else false
                                } else false
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                },
        ) {
            Box(
                Modifier.fillMaxSize().drawWithContent {
                    val start = if (qa?.profiling?.value == true) System.nanoTime() else 0L
                    drawContent()
                    if (start != 0L) qa?.timings?.draw(System.nanoTime() - start, qa.timingLabel)
                    qa?.frameDrawn(automationRevision)
                },
            ) {
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

                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wideDesktop = maxWidth >= YokhanLayout.DesktopCompanionBreakpoint
                    val showDesktopSidebar = sessionState.ready && wideDesktop
                    val showMobileBottomNav = sessionState.ready && !wideDesktop &&
                        effectiveRoute !in setOf(WindowsRoute.Access, WindowsRoute.BugReport)
                    val desktopBottomInset = if (showDesktopSidebar) 0 else 64
                    val navItems = remember {
                        listOf(
                            GatesNavigationItem(WindowsRoute.Dashboard.qaName, "Обзор", "01"),
                            GatesNavigationItem(WindowsRoute.Routing.qaName, "Приложения", "02"),
                            GatesNavigationItem(WindowsRoute.Routes.qaName, "Маршруты", "03"),
                            GatesNavigationItem(WindowsRoute.Settings.qaName, "Система", "04"),
                        )
                    }
                    val toggleTunnelAction: () -> Unit = {
                        if (qa != null) {
                            lastMessage = "Операция недоступна в тестовом режиме."
                        } else if (!busy) {
                            if (connectionStatus == ConnectionStatus.Connected || (!killSwitchEnabled && protectionState.retainsProtection)) {
                                connectionStatus = ConnectionStatus.Disconnecting
                                launchRuntime("Отключение", "stop", nextStatus = ConnectionStatus.Disconnected)
                            } else {
                                connectionStatus = ConnectionStatus.Connecting
                                launchRuntime("Подключение", "connect", nextStatus = ConnectionStatus.Connected)
                            }
                        }
                    }

                    Row(Modifier.fillMaxSize()) {
                        if (showDesktopSidebar) {
                            GatesDesktopSidebar(
                                selectedKey = routeStack.bottomNavSelectedKey(effectiveRoute),
                                items = navItems,
                                connected = connectionStatus == ConnectionStatus.Connected,
                                busy = busy,
                                appVersion = windowsAppVersionForDisplay(),
                                activeRouteLabel = activeRouteLabel(
                                    if (activeRoute == "auto" && resolvedActiveRoute.isNotBlank() && resolvedActiveRoute != "auto") {
                                        resolvedActiveRoute
                                    } else activeRoute,
                                ),
                                telemetryLabel = if (connectionStatus == ConnectionStatus.Connected) {
                                    "↑ ${liveTelemetry.uploadLabel}  ↓ ${liveTelemetry.downloadLabel}"
                                } else {
                                    if (runtimeMode == "tun") "Режим TUN" else "Локальный прокси"
                                },
                                accountLabel = sessionState.accountLabel,
                                updateAvailable = updateAvailable,
                                colors = windowPalette,
                                typography = windowTypography,
                                onSelect = { destination ->
                                    val target = WindowsRoute.entries.firstOrNull { it.qaName == destination } ?: return@GatesDesktopSidebar
                                    navigateToRoute(target)
                                },
                                onToggleConnection = toggleTunnelAction,
                                onOpenAccess = {
                                    lastMessage = ""
                                    accessFailure = null
                                    accessPending = false
                                    routeStack = routeStack.push(WindowsRoute.Access)
                                },
                            )
                        }

                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            AnimatedContent(
                                targetState = effectiveRoute,
                                transitionSpec = {
                                    fadeIn(tween(150)) togetherWith fadeOut(tween(120))
                                },
                                label = "windows-route-transition",
                                modifier = Modifier.fillMaxSize(),
                            ) { activeScreen ->
                    when (activeScreen) {
                        WindowsRoute.BugReport -> {
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
                                onDismiss = { routeStack = routeStack.popBack() },
                                onSend = {
                                    launchRuntime(
                                        "Отчёт",
                                        "report-bug",
                                        argument = bugMessage,
                                        onSuccess = {
                                            bugMessage = ""
                                            routeStack = routeStack.popBack()
                                        },
                                    )
                                },
                            )
                        }

                        WindowsRoute.Routing -> {
                            WindowsAppRoutingScreen(
                                state = appRoutingState,
                                runningApps = runningApps,
                                busy = busy,
                                spaceActive = windowSpaceActive,
                                message = lastMessage,
                                onDismiss = { routeStack = routeStack.popBack() },
                                onRefreshProcesses = {
                                    refreshRunningApps()
                                    refreshActiveNetworkApps()
                                    if (qa == null) {
                                        scope.launch {
                                            installedApps = withContext(Dispatchers.IO) {
                                                WindowsInstalledAppsScanner.getOrScan(forceRefresh = true)
                                            }
                                        }
                                    }
                                },
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
                                installedApps = installedApps,
                                activeNetworkApps = activeNetworkApps,
                                enableRuBypass = enableRuBypass,
                                onRuBypassChange = { enabled ->
                                    launchRuntime("RU-автообход", "set-ru-bypass", argument = enabled.toString())
                                },
                                onMoveRule = { target, toProxy -> launchMoveAppRouting(target, toProxy) },
                                searchFocusRequester = routingSearchFocusRequester,
                                proxyDropHovered = activeDragPoint?.let { pt -> proxyDropBounds?.contains(pt) == true } == true,
                                directDropHovered = activeDragPoint?.let { pt -> directDropBounds?.contains(pt) == true } == true,
                                onProxyDropBoundsChanged = { proxyDropBounds = it },
                                onDirectDropBoundsChanged = { directDropBounds = it },
                                pendingDroppedPaths = pendingDroppedPaths,
                                onAssignPendingDrop = { proxy -> launchBatchAppRouting(pendingDroppedPaths, proxy) },
                                onDismissPendingDrop = { pendingDroppedPaths = emptyList() },
                                onInAppCardDrag = { label, _, pt ->
                                    inAppDragLabel = if (pt != null) "Перетащить «$label» в маршрут" else null
                                    inAppDragPointInRoot = pt
                                },
                                onInAppCardDrop = { target, pt ->
                                    val overProxy = proxyDropBounds?.contains(pt) == true || globalProxyDropBounds?.contains(pt) == true
                                    val overDirect = directDropBounds?.contains(pt) == true || globalDirectDropBounds?.contains(pt) == true
                                    when {
                                        overProxy -> launchAppRouting("Приложение", "add-app-proxy", target)
                                        overDirect -> launchAppRouting("Исключение", "add-app-direct", target)
                                    }
                                },
                                bottomInsetPaddingDp = desktopBottomInset,
                                activeNetworkContent = { colors, typography ->
                                    WindowsActiveConnectionsCard(
                                        apps = activeNetworkApps,
                                        busy = busy,
                                        colors = colors,
                                        typography = typography,
                                        onRefresh = { refreshActiveNetworkApps() },
                                        onAddProxy = { target -> launchAppRouting("Приложение", "add-app-proxy", target) },
                                        onAddDirect = { target -> launchAppRouting("Исключение", "add-app-direct", target) },
                                        onMoveRule = { target, toProxy -> launchMoveAppRouting(target, toProxy) },
                                    )
                                },
                            )
                        }

                        WindowsRoute.Routes -> {
                            WindowsRoutesScreen(
                                activeRoute = activeRoute,
                                availableRoutes = availableRoutes,
                                routeOrder = routeOrder,
                                enableRuBypass = enableRuBypass,
                                wholeTrafficMode = wholeTrafficMode,
                                bypassLan = bypassLan,
                                blockQuic = blockQuic,
                                busy = busy,
                                spaceActive = windowSpaceActive,
                                statusMessage = lastMessage,
                                probeOutput = probeOutput,
                                onMenuClick = {},
                                onBack = null,
                                bottomInsetPaddingDp = desktopBottomInset,
                                onSelectRoute = { route ->
                                    if (qa != null && !qa.realAccess) activeRoute = route
                                    else launchRuntime("Маршрут $route", "set-route", route = route)
                                },
                                onUpdateRouteOrder = { order ->
                                    launchRuntime("Приоритет маршрутов", "set-route-order", argument = order.joinToString(","))
                                },
                                onRuBypassChange = { enabled ->
                                    launchRuntime("RU-автообход", "set-ru-bypass", argument = enabled.toString())
                                },
                                onWholeTrafficChange = { enabled ->
                                    launchRuntime("Весь трафик", "set-whole-traffic", argument = enabled.toString())
                                },
                                onBypassLanChange = { enabled ->
                                    launchRuntime("Обход LAN", "set-bypass-lan", argument = enabled.toString())
                                },
                                onBlockQuicChange = { enabled ->
                                    launchRuntime("Блокировка QUIC", "set-block-quic", argument = enabled.toString())
                                },
                                onProbeRoutes = { launchRuntime("Маршруты", "probe-routes") },
                                onReconnectTunnel = { launchRuntime("Синхронизация", "reconnect") },
                            )
                        }

                        WindowsRoute.Logs -> {
                            WindowsLogsScreen(
                                logText = logText,
                                sourceFilter = logSourceFilter,
                                busy = busy,
                                spaceActive = windowSpaceActive,
                                statusMessage = lastMessage,
                                onMenuClick = {},
                                onBack = { routeStack = routeStack.popBack() },
                                onSourceFilterChange = { next ->
                                    logSourceFilter = next
                                    refreshLogs(next)
                                },
                                onRefresh = { refreshLogs(logSourceFilter) },
                                onCopy = { text ->
                                    clipboardManager.setText(AnnotatedString(text))
                                    lastMessage = "Логи скопированы в буфер обмена."
                                },
                                onSave = { text ->
                                    if (qa != null) return@WindowsLogsScreen
                                    val dialog = when (ownerWindow) {
                                        is java.awt.Frame -> FileDialog(ownerWindow, "Сохранить логи GATES", FileDialog.SAVE)
                                        is java.awt.Dialog -> FileDialog(ownerWindow, "Сохранить логи GATES", FileDialog.SAVE)
                                        else -> FileDialog(null as java.awt.Frame?, "Сохранить логи GATES", FileDialog.SAVE)
                                    }.apply {
                                        file = "gates-logs.txt"
                                        isVisible = true
                                    }
                                    val destination = dialog.file?.let { File(dialog.directory, it) }
                                    dialog.dispose()
                                    if (destination != null) {
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) { destination.writeText(text, Charsets.UTF_8) }
                                            }.onSuccess {
                                                lastMessage = "Логи сохранены."
                                            }.onFailure {
                                                lastMessage = "Не удалось сохранить логи."
                                            }
                                        }
                                    }
                                },
                            )
                        }

                        WindowsRoute.Tools -> {
                            WindowsToolsScreen(
                                statusSummary = toolsStatusSummary,
                                busy = busy,
                                spaceActive = windowSpaceActive,
                                lastOutput = toolsOutput,
                                probeOutput = probeOutput,
                                activeNetworkApps = activeNetworkApps,
                                onMenuClick = {},
                                onBack = { routeStack = routeStack.popBack() },
                                onTestConnection = { launchRuntime("Проверка", "test") },
                                onProbeRoutes = { launchRuntime("Маршруты", "probe-routes") },
                                onReconnectTunnel = { launchRuntime("Синхронизация", "reconnect") },
                                onResetSystemProxy = { launchRuntime("Сброс прокси", "disable-system-proxy") },
                                onExportSupportBundle = { launchRuntime("Пакет диагностики", "support-bundle") },
                                onRefreshStatus = { refreshToolsStatus() },
                                onRefreshActiveNetwork = { refreshActiveNetworkApps() },
                                onAddProxy = { target -> launchAppRouting("Приложение", "add-app-proxy", target) },
                                onAddDirect = { target -> launchAppRouting("Исключение", "add-app-direct", target) },
                                onMoveRule = { target, toProxy -> launchMoveAppRouting(target, toProxy) },
                                onOpenLogs = { navigateToRoute(WindowsRoute.Logs) },
                                onOpenHistory = { routeStack = routeStack.push(WindowsRoute.History) },
                            )
                        }

                        WindowsRoute.History -> {
                            LaunchedEffect(Unit) { refreshIncidentHistory() }
                            GatesIncidentHistoryScreen(
                                items = historyItems,
                                loading = historyLoading,
                                message = historyMessage,
                                palette = windowPalette,
                                onBack = { routeStack = routeStack.popBack() },
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

                        WindowsRoute.Settings -> {
                            WindowsSettingsScreen(
                                busy = busy,
                                spaceActive = windowSpaceActive,
                                runtimeMode = runtimeMode,
                                killSwitchEnabled = killSwitchEnabled,
                                protectionMessage = windowsProtectionLabel(protectionState),
                                appProxyProcessPaths = appRoutingState.proxyProcessPaths,
                                onKillSwitchChange = if (qa == null) ({ enabled ->
                                    if (!enabled) {
                                        launchRuntime("Защита", "set-kill-switch", argument = "false")
                                    } else {
                                        scope.launch {
                                            if (busy) return@launch
                                            val pickedExe = if (appRoutingState.proxyProcessPaths.isEmpty()) {
                                                chooseExeFile(ownerWindow).also { selected ->
                                                    if (selected == null) {
                                                        lastMessage = "Выберите хотя бы один .exe файл для защиты."
                                                    }
                                                } ?: return@launch
                                            } else null
                                            runWindowsUiAction(
                                                setBusy = { busy = it },
                                                onFailure = { lastMessage = it },
                                            ) {
                                                val result = withContext(Dispatchers.IO) {
                                                    if (pickedExe != null) {
                                                        val added = controller.addAppRouting(pickedExe, proxy = true)
                                                        if (added.exitCode != 0) return@withContext added
                                                    }
                                                    if (controller.readConfig().runtimeMode != "tun") {
                                                        val tunSet = controller.run("set-runtime", "tun")
                                                        if (tunSet.exitCode != 0) return@withContext tunSet
                                                    }
                                                    controller.run("set-kill-switch", "true")
                                                }
                                                val refreshedCfg = withContext(Dispatchers.IO) { controller.readConfig() }
                                                applyConfigSnapshot(refreshedCfg)
                                                appRoutingState = withContext(Dispatchers.IO) { controller.appRoutingState() }
                                                val live = withContext(Dispatchers.IO) { runCatching { controller.queryLiveRuntimeStatus() }.getOrNull() }
                                                if (live != null) protectionState = live.protectionState
                                                lastMessage = windowsUiResultMessage("set-kill-switch", result)
                                            }
                                        }
                                    }
                                }) else null,
                                onPickProtectedExe = if (qa == null) ({
                                    chooseExeFile(ownerWindow)?.let { path ->
                                        launchAppRouting("Приложение", "add-app-proxy", path)
                                    }
                                }) else null,
                                onOpenRouting = { routeStack = routeStack.switchTab(WindowsRoute.Routing) },
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
                                onDismiss = { routeStack = routeStack.popBack() },
                                onMenuClick = {},
                                onBack = null,
                                bottomInsetPaddingDp = desktopBottomInset,
                                accountLabel = sessionState.accountLabel,
                                statusSummary = toolsStatusSummary,
                                onRefreshStatus = { refreshToolsStatus() },
                                onOpenLogs = { navigateToRoute(WindowsRoute.Logs) },
                                onExportSupportBundle = { launchRuntime("Диагностика", "support-bundle") },
                                onResetSystemProxy = { launchRuntime("Сброс прокси", "reset-system-proxy") },
                                autoStartOnLogin = autoStartOnLogin,
                                onAutoStartChange = if (qa == null) ({ enabled ->
                                    launchRuntime(
                                        "Автозапуск",
                                        if (enabled) "enable-autostart" else "disable-autostart",
                                    )
                                }) else null,
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
                                onSaveNetworkSettings = if (qa == null) ({ lh, sp, hp, sysProxy, restoreProxy, tStack, tMtu, tStrict, physBind, physIf ->
                                    launchSaveNetworkSettings(
                                        lh, sp, hp, sysProxy, restoreProxy, tStack, tMtu, tStrict, physBind, physIf,
                                    )
                                }) else null,
                                onAccess = {
                                    lastMessage = ""
                                    accessFailure = null
                                    accessPending = false
                                    routeStack = routeStack.push(WindowsRoute.Access)
                                },
                                onApps = {
                                    launchLoadAppRouting(asTopTab = false)
                                },
                                onUpdate = {
                                    launchRuntime("Обновление", "check-update")
                                },
                                onBugReport = {
                                    lastMessage = ""
                                    routeStack = routeStack.push(WindowsRoute.BugReport)
                                },
                                onHistory = {
                                    routeStack = routeStack.push(WindowsRoute.History)
                                },
                            )
                        }

                        WindowsRoute.Access -> {
                            GatesAccessScreen(
                                signedIn = sessionState.signedIn,
                                busy = busy,
                                message = when (authorizationPhase) {
                                    WindowsAuthorizationPhase.Starting -> "Начинаю вход в браузере."
                                    WindowsAuthorizationPhase.Waiting -> "Подтвердите вход в браузере."
                                    WindowsAuthorizationPhase.ReceivingProfile -> "Получаю личную конфигурацию."
                                    else -> lastMessage
                                },
                                manualInput = manualSubscription,
                                onManualInputChanged = { manualSubscription = it },
                                onImport = { launchManualImport() },
                                onLogin = { launchAuthSetup() },
                                onCancel = {
                                    manualSubscription = ""
                                    routeStack = routeStack.popBack()
                                },
                                canCancel = sessionState.ready,
                                onBugReport = {
                                    lastMessage = ""
                                    routeStack = routeStack.push(WindowsRoute.BugReport)
                                },
                                onHistory = {
                                    routeStack = routeStack.push(WindowsRoute.History)
                                },
                                colors = windowPalette,
                                spaceActive = windowSpaceActive,
                                authorizationCode = authorizationPrompt?.userCode.orEmpty(),
                                authorizationUri = authorizationPrompt?.verificationUri.orEmpty(),
                                authorizationPending = authorizationPrompt != null,
                                onOpenAuthorization = { scope.launch(Dispatchers.IO) { controller.reopenAuthorization() } },
                                onCopyAuthorizationUri = authorizationPrompt?.verificationUri?.takeIf { it.isNotBlank() }?.let { uri ->
                                    {
                                        clipboardManager.setText(AnnotatedString(uri))
                                        lastMessage = "Ссылка для входа скопирована. Откройте её на телефоне или в браузере."
                                    }
                                },
                                onCancelAuthorization = {
                                    if (controller.cancelPendingAuthorization { authJob?.cancel() }) lastMessage = "Вход отменён."
                                },
                                profileReady = sessionState.ready,
                                accountLabel = sessionState.accountLabel,
                                accessPending = accessPending,
                                onRetryConfig = if (sessionState.signedIn) {
                                    { launchAuthSetup() }
                                } else null,
                                onRestartLogin = if (sessionState.signedIn) {
                                    { launchAuthSetup(forceLogin = true) }
                                } else null,
                                onSignOut = if (sessionState.signedIn) {
                                    { launchLogout() }
                                } else null,
                                onRequestAccess = if (sessionState.signedIn && !accessPending &&
                                    (accessFailure?.code == WindowsAccessCode.ACCESS_NOT_LINKED ||
                                        accessFailure?.code == WindowsAccessCode.USER_NOT_FOUND)
                                ) {
                                    { launchRequestAccess() }
                                } else null,
                                onBootstrapClaim = { launchBootstrapClaim(it) },
                                bootstrapBusy = busy,
                                bootstrapMessage = bootstrapMessage,
                                bootstrapRemaining = bootstrapRemaining,
                                suggestBootstrap = accessFailure?.code in setOf(
                                    WindowsAccessCode.AUTH_START_FAILED,
                                    WindowsAccessCode.AUTH_UNAVAILABLE,
                                    WindowsAccessCode.TRANSPORT_ERROR,
                                ),
                            )
                        }

                        WindowsRoute.Dashboard -> {
                            Box(Modifier.fillMaxSize().background(windowPalette.background)) {
                                YokhanDashboardScreen(
                                    state = state,
                                    drawBackgroundEffects = qa?.backgroundEffects?.value ?: true,
                                    profiles = profiles,
                                    labels = windowsProtectionDashboardLabels(
                                        protectionState,
                                        killSwitchEnabled,
                                        connectionStatus == ConnectionStatus.Connected,
                                    ),
                                    tuning = DashboardUiTuning(contentBottomPadding = if (showDesktopSidebar) 24 else 96),
                                    actions = DashboardUiActions(
                                        onMenuClick = {},
                                        onToggleConnection = toggleTunnelAction,
                                        onRunConnectionTest = { launchRuntime("Проверка", "test") },
                                        onAddProfile = { routeStack = routeStack.push(WindowsRoute.Access) },
                                        onSelectProfile = { id ->
                                            windowsRouteTagForProfileId(id, availableRoutes)?.let { route ->
                                                if (qa != null && !qa.realAccess) activeRoute = route
                                                else launchRuntime("Маршрут $route", "set-route", route = route)
                                            }
                                        },
                                        onEditProfile = { routeStack = routeStack.switchTab(WindowsRoute.Routes) },
                                        onOpenProfileMenu = { routeStack = routeStack.switchTab(WindowsRoute.Routes) },
                                        onCurrentConfigClick = { routeStack = routeStack.switchTab(WindowsRoute.Routes) },
                                    ),
                                    modifier = Modifier.fillMaxSize(),
                                    visibility = ProductionDashboardVisibility,
                                    companionContent = { colors, typography ->
                                        if (bootstrapRemaining.isNotBlank()) {
                                            YokhanRouteCard("Мудрое Древо · разовый доступ ($bootstrapRemaining)", colors, typography) {
                                                YokhanCaption(
                                                    text = "Открыт только вход в GATES и получение вашего ключа. Остальной интернет временно закрыт.",
                                                    colors = colors,
                                                    typography = typography,
                                                )
                                                Spacer(Modifier.height(YokhanSpacing.CompactGap))
                                                YokhanRouteButton(
                                                    text = "Войти в аккаунт / вставить ключ",
                                                    primary = true,
                                                    enabled = !busy,
                                                    colors = colors,
                                                    typography = typography,
                                                    onClick = { routeStack = routeStack.push(WindowsRoute.Access) },
                                                    modifier = Modifier.fillMaxWidth().testTag("dashboard.bootstrap.login"),
                                                )
                                            }
                                        }
                                        YokhanRouteCard("Сводка туннеля и телеметрия", colors, typography) {
                                            val routeDisplay = if (activeRoute == "auto" && resolvedActiveRoute.isNotBlank() && resolvedActiveRoute != "auto") {
                                                "AUTO → ${activeRouteLabel(resolvedActiveRoute)}"
                                            } else {
                                                activeRouteLabel(activeRoute)
                                            }
                                            YokhanMonoValue(routeDisplay, colors, typography, sizeSp = 14)
                                            Spacer(Modifier.height(4.dp))
                                            YokhanCaption(
                                                text = buildString {
                                                    append("Режим: ")
                                                    append(if (runtimeMode == "tun") "TUN (служба GatesVpn)" else "Локальный прокси")
                                                    append(" · Автообход РФ: ")
                                                    append(if (enableRuBypass) "ВКЛ" else "ВЫКЛ")
                                                    if (killSwitchEnabled) {
                                                        append(" · WFP Защита: ")
                                                        append(windowsProtectionLabel(protectionState))
                                                    }
                                                },
                                                colors = colors,
                                                typography = typography,
                                            )
                                            Spacer(Modifier.height(YokhanSpacing.CompactGap))
                                            YokhanAdaptiveActionPair(
                                                first = { m ->
                                                    YokhanRouteButton(
                                                        text = "Проверить маршруты",
                                                        primary = false,
                                                        enabled = !busy,
                                                        colors = colors,
                                                        typography = typography,
                                                        onClick = { launchRuntime("Маршруты", "probe-routes") },
                                                        modifier = m,
                                                    )
                                                },
                                                second = { m ->
                                                    YokhanRouteButton(
                                                        text = "Синхронизировать",
                                                        primary = false,
                                                        enabled = !busy,
                                                        colors = colors,
                                                        typography = typography,
                                                        onClick = { launchRuntime("Синхронизация", "reconnect") },
                                                        modifier = m,
                                                    )
                                                },
                                            )
                                        }

                                        if (probeOutput.isNotBlank()) {
                                            WindowsRouteProbeSummaryCard(
                                                probeOutput = probeOutput,
                                                colors = colors,
                                                typography = typography,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(bottom = if (showDesktopSidebar) 10.dp else 84.dp),
                            ) {
                                RoomNetPanel(roomNetMessage, windowPalette)
                                WindowsStatusBar(
                                    message = lastMessage,
                                    palette = windowPalette,
                                )
                            }

                            if (showMobileBottomNav) {
                                GatesBottomNavigation(
                                    selectedKey = routeStack.bottomNavSelectedKey(effectiveRoute),
                                    items = navItems,
                                    colors = windowPalette,
                                    enabled = !busy,
                                    onSelect = { destination ->
                                        val target = WindowsRoute.entries.firstOrNull { it.qaName == destination }
                                            ?: return@GatesBottomNavigation
                                        navigateToRoute(target)
                                    },
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                )
                            }
                        }
                    }
                }
            }

            GatesGlobalDropOverlay(
                visible = showGlobalDropOverlay,
                dragLabel = inAppDragLabel ?: "ПЕРЕТАЩИТЕ ПРИЛОЖЕНИЕ, ЯРЛЫК ИЛИ ПРОФИЛЬ",
                proxyHovered = activeDragPoint?.let { globalProxyDropBounds?.contains(it) == true } == true,
                directHovered = activeDragPoint?.let { globalDirectDropBounds?.contains(it) == true } == true,
                colors = windowPalette,
                typography = windowTypography,
                onProxyBoundsChanged = { globalProxyDropBounds = it },
                onDirectBoundsChanged = { globalDirectDropBounds = it },
                onDismiss = {
                    externalDragPointInRoot = null
                    inAppDragPointInRoot = null
                    inAppDragLabel = null
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
            name.endsWith(".exe", ignoreCase = true) || name.endsWith(".lnk", ignoreCase = true)
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
    resolvedRoute: String = activeRoute,
    telemetry: WindowsLiveTelemetrySnapshot = WindowsLiveTelemetrySnapshot(),
    latencyMs: Long? = null,
    connectionTestRunning: Boolean = false,
): DashboardState {
    val connected = status == ConnectionStatus.Connected
    val running = status == ConnectionStatus.Connecting || connected || status == ConnectionStatus.Disconnecting
    val effectiveLabel = if (activeRoute == "auto" && resolvedRoute.isNotBlank() && resolvedRoute != "auto") {
        "AUTO → ${activeRouteLabel(resolvedRoute)}"
    } else {
        activeRouteLabel(activeRoute)
    }
    val effectiveProtoRoute = if (activeRoute == "auto" && resolvedRoute != "auto") resolvedRoute else activeRoute
    val effectiveLatency = if (connected) latencyMs?.coerceAtLeast(0L) else null
    return DashboardState(
        connectionStatus = status,
        activeProfileName = effectiveLabel,
        activeProfileProtocol = routeProtocol(effectiveProtoRoute),
        activeProfileProtocolLabel = routeProtocolLabel(effectiveProtoRoute),
        activeProfileSubtitle = if (activeRoute == "auto") "Автоматический выбор маршрута" else "",
        uploadRate = RateDisplay(telemetry.uploadBytesPerSec),
        downloadRate = RateDisplay(telemetry.downloadBytesPerSec),
        latency = LatencyDisplay(effectiveLatency),
        uploadLabel = if (connected) telemetry.uploadLabel else "0 B/s",
        downloadLabel = if (connected) telemetry.downloadLabel else "0 B/s",
        latencyLabel = effectiveLatency?.let { "$it мс" } ?: "--",
        timerLabel = if (connected) telemetry.timerLabel else "00:00:00",
        shouldAnimateSpace = running,
        canRunConnectionTest = connected,
        isConnectionTestRunning = connectionTestRunning && connected,
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
        "xhttp" -> "XHTTP (Xray)"
        "nl-tcp" -> "Finland VLESS (r1)"
        "se-hy2" -> "SE Stockholm"
        else -> route
    }

private fun routeProtocol(route: String): VpnProtocol =
    when (route) {
        "xhttp", "bridge", "relay", "nl-tcp" -> VpnProtocol.Vless
        else -> VpnProtocol.Unknown
    }

private fun routeProtocolLabel(route: String): String =
    when (route) {
        "xhttp" -> "XHTTP"
        "bridge", "relay", "nl-tcp" -> "VLESS"
        "naive" -> "HTTPS"
        "hy2", "se-hy2" -> "HYSTERIA2"
        else -> "AUTO"
    }
