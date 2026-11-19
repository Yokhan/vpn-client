package com.yokhan.gates.windows

import java.awt.EventQueue
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal interface TraySystemBackend {
    fun isSupported(): Boolean
    fun add(icon: TrayIcon)
    fun remove(icon: TrayIcon)
    fun contains(icon: TrayIcon): Boolean
    fun iconSize(): Int
}

internal object AwtTraySystemBackend : TraySystemBackend {
    override fun isSupported(): Boolean = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    override fun add(icon: TrayIcon) {
        SystemTray.getSystemTray().add(icon)
    }

    override fun remove(icon: TrayIcon) {
        runCatching { SystemTray.getSystemTray().remove(icon) }
    }

    override fun contains(icon: TrayIcon): Boolean = runCatching {
        icon in SystemTray.getSystemTray().trayIcons
    }.getOrDefault(false)

    override fun iconSize(): Int = runCatching {
        val size = SystemTray.getSystemTray().trayIconSize
        maxOf(size.width, size.height, 16)
    }.getOrDefault(16)
}

internal data class NativeTrayStateSnapshot(
    val connected: Boolean = false,
    val activeRouteLabel: String = "Автоматически",
    val activeRouteTag: String = "auto",
    val routes: List<WindowsProfileRoute> = emptyList(),
    val enableRuBypass: Boolean = true,
)

class NativeTray internal constructor(
    private val onOpen: () -> Unit,
    private val onToggleConnection: () -> Unit = {},
    private val onTestConnection: () -> Unit = {},
    private val onSelectRoute: (String) -> Unit = {},
    private val onToggleRuBypass: () -> Unit = {},
    private val onHideToTray: () -> Unit = {},
    private val onQuit: () -> Unit,
    private val backend: TraySystemBackend = AwtTraySystemBackend,
    private val runOnUiThread: (() -> Unit) -> Unit = { block -> EventQueue.invokeLater(block) },
    private val startWatchdog: Boolean = true,
) {
    constructor(
        onOpen: () -> Unit,
        onToggleConnection: () -> Unit = {},
        onTestConnection: () -> Unit = {},
        onSelectRoute: (String) -> Unit = {},
        onToggleRuBypass: () -> Unit = {},
        onHideToTray: () -> Unit = {},
        onQuit: () -> Unit,
    ) : this(
        onOpen = onOpen,
        onToggleConnection = onToggleConnection,
        onTestConnection = onTestConnection,
        onSelectRoute = onSelectRoute,
        onToggleRuBypass = onToggleRuBypass,
        onHideToTray = onHideToTray,
        onQuit = onQuit,
        backend = AwtTraySystemBackend,
        runOnUiThread = { block -> EventQueue.invokeLater(block) },
        startWatchdog = true,
    )

    @Volatile
    private var trayIcon: TrayIcon? = null
    private var statusItem: MenuItem? = null
    private var toggleItem: MenuItem? = null
    private var routeMenu: Menu? = null
    private var ruBypassItem: MenuItem? = null
    private var lastRouteMenuKey: String = ""

    @Volatile
    private var latestState: NativeTrayStateSnapshot = NativeTrayStateSnapshot()
    private val disposed = AtomicBoolean(false)
    private val watchdogTickCount = AtomicInteger(0)
    private var watchdogExecutor: ScheduledExecutorService? = null

    fun install() {
        if (disposed.get()) return
        ensureWatchdogStarted()
        runOnUiThread { ensureInstalledOnUiThread() }
    }

    internal fun attemptInstallNowForTest() {
        ensureInstalledOnUiThread()
    }

    fun isInstalled(): Boolean {
        val current = trayIcon ?: return false
        return backend.isSupported() && backend.contains(current)
    }

    private fun ensureWatchdogStarted() {
        if (!startWatchdog) return
        synchronized(this) {
            if (disposed.get() || watchdogExecutor != null) return
            val executor = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "gates-tray-watchdog").apply { isDaemon = true }
            }
            watchdogExecutor = executor
            // Windows autostart apps often launch while explorer.exe / Shell_TrayWnd is still initializing.
            // Poll quickly during the first ~90s, then gently afterwards to recover if Explorer restarts.
            executor.scheduleWithFixedDelay({
                if (disposed.get()) return@scheduleWithFixedDelay
                val tick = watchdogTickCount.incrementAndGet()
                if (isInstalled() && tick % 5 != 0) return@scheduleWithFixedDelay
                if (!isInstalled() || tick % 5 == 0) {
                    runOnUiThread { ensureInstalledOnUiThread() }
                }
            }, 1500L, 2000L, TimeUnit.MILLISECONDS)
        }
    }

    private fun ensureInstalledOnUiThread() {
        if (disposed.get()) return
        if (!backend.isSupported()) return
        val existing = trayIcon
        if (existing != null) {
            if (backend.contains(existing)) return
            // Explorer restarted or dropped the shell notification icon; clean up and re-add.
            backend.remove(existing)
            clearMenuRefs()
        }

        val menu = PopupMenu()
        val status = MenuItem("GATES · проверка…").apply { isEnabled = false }
        val open = MenuItem("Открыть GATES").apply {
            addActionListener { onOpen() }
        }
        val toggle = MenuItem("Подключить туннель").apply {
            addActionListener { onToggleConnection() }
        }
        val routesSubmenu = Menu("Маршрут")
        val ruItem = MenuItem("Автообход РФ: ВКЛ").apply {
            addActionListener { onToggleRuBypass() }
        }
        val test = MenuItem("Проверить соединение").apply {
            addActionListener { onTestConnection() }
        }
        val hide = MenuItem("Свернуть в трей (служба работает)").apply {
            addActionListener { onHideToTray() }
        }
        val quit = MenuItem("Выйти из интерфейса GATES").apply {
            addActionListener { onQuit() }
        }
        menu.add(status)
        menu.addSeparator()
        menu.add(open)
        menu.add(toggle)
        menu.add(routesSubmenu)
        menu.add(ruItem)
        menu.add(test)
        menu.add(hide)
        menu.addSeparator()
        menu.add(quit)

        val icon = runCatching {
            TrayIcon(createIcon(), "GATES — Туннель Windows", menu).apply {
                isImageAutoSize = true
                addActionListener { onOpen() }
            }
        }.getOrNull() ?: return

        val added = runCatching { backend.add(icon) }.isSuccess
        if (!added) {
            runCatching { backend.remove(icon) }
            return
        }
        statusItem = status
        toggleItem = toggle
        routeMenu = routesSubmenu
        ruBypassItem = ruItem
        trayIcon = icon
        lastRouteMenuKey = ""
        applyStateOnUiThread(latestState)
    }

    fun updateStatus(connected: Boolean, activeRoute: String) {
        updateState(
            connected = connected,
            activeRouteLabel = activeRoute,
            activeRouteTag = activeRoute,
            routes = emptyList(),
            enableRuBypass = true,
        )
    }

    internal fun updateState(
        connected: Boolean,
        activeRouteLabel: String,
        activeRouteTag: String,
        routes: List<WindowsProfileRoute>,
        enableRuBypass: Boolean,
    ) {
        val snapshot = NativeTrayStateSnapshot(
            connected = connected,
            activeRouteLabel = activeRouteLabel,
            activeRouteTag = activeRouteTag,
            routes = routes,
            enableRuBypass = enableRuBypass,
        )
        latestState = snapshot
        runOnUiThread {
            if (trayIcon == null && !disposed.get()) {
                ensureInstalledOnUiThread()
            }
            applyStateOnUiThread(snapshot)
        }
    }

    private fun applyStateOnUiThread(snapshot: NativeTrayStateSnapshot) {
        val stateText = if (snapshot.connected) "Подключено (${snapshot.activeRouteLabel})" else "Отключено"
        statusItem?.label = "GATES · $stateText"
        toggleItem?.label = if (snapshot.connected) "Отключить туннель" else "Подключить туннель"
        ruBypassItem?.label = "Умный автообход РФ: ${if (snapshot.enableRuBypass) "ВКЛ" else "ВЫКЛ"}"
        trayIcon?.toolTip = "GATES — $stateText"
        val routeKey = "${snapshot.activeRouteTag}|${snapshot.routes.joinToString(",") { it.tag }}"
        if (routeKey != lastRouteMenuKey) {
            lastRouteMenuKey = routeKey
            rebuildRoutesMenu(snapshot.routes, snapshot.activeRouteTag)
        }
    }

    private fun rebuildRoutesMenu(routes: List<WindowsProfileRoute>, activeRouteTag: String) {
        val menu = routeMenu ?: return
        menu.removeAll()
        val autoMark = if (activeRouteTag == "auto") "✓ " else ""
        menu.add(
            MenuItem("${autoMark}Автоматически (AUTO)").apply {
                addActionListener { onSelectRoute("auto") }
            },
        )
        if (routes.isNotEmpty()) {
            menu.addSeparator()
            routes.forEach { route ->
                val mark = if (activeRouteTag == route.tag) "✓ " else ""
                menu.add(
                    MenuItem("$mark${route.label} (${route.protocol})").apply {
                        addActionListener { onSelectRoute(route.tag) }
                    },
                )
            }
        }
    }

    fun uninstall() {
        disposed.set(true)
        synchronized(this) {
            watchdogExecutor?.shutdownNow()
            watchdogExecutor = null
        }
        val icon = trayIcon
        clearMenuRefs()
        if (icon != null) {
            runOnUiThread { backend.remove(icon) }
        }
    }

    private fun clearMenuRefs() {
        trayIcon = null
        statusItem = null
        toggleItem = null
        routeMenu = null
        ruBypassItem = null
        lastRouteMenuKey = ""
    }

    private fun createIcon(): BufferedImage = WindowsBrandIcons.load(backend.iconSize())

    companion object {
        fun isSupported(): Boolean = AwtTraySystemBackend.isSupported()
    }
}
