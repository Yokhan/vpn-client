package com.yokhan.gates.windows

import java.awt.EventQueue
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

class NativeTray(
    private val onOpen: () -> Unit,
    private val onToggleConnection: () -> Unit = {},
    private val onTestConnection: () -> Unit = {},
    private val onSelectRoute: (String) -> Unit = {},
    private val onToggleRuBypass: () -> Unit = {},
    private val onHideToTray: () -> Unit = {},
    private val onQuit: () -> Unit,
) {
    private var trayIcon: TrayIcon? = null
    private var statusItem: MenuItem? = null
    private var toggleItem: MenuItem? = null
    private var routeMenu: Menu? = null
    private var ruBypassItem: MenuItem? = null
    private var lastRouteMenuKey: String = ""

    fun install() {
        if (!isSupported() || trayIcon != null) return
        EventQueue.invokeLater {
            if (trayIcon != null) return@invokeLater
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

            val icon = TrayIcon(createIcon(), "GATES — Туннель Windows", menu).apply {
                isImageAutoSize = true
                addActionListener { onOpen() }
            }
            SystemTray.getSystemTray().add(icon)
            statusItem = status
            toggleItem = toggle
            routeMenu = routesSubmenu
            ruBypassItem = ruItem
            trayIcon = icon
            rebuildRoutesMenu(emptyList(), "auto")
        }
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
        EventQueue.invokeLater {
            val stateText = if (connected) "Подключено ($activeRouteLabel)" else "Отключено"
            statusItem?.label = "GATES · $stateText"
            toggleItem?.label = if (connected) "Отключить туннель" else "Подключить туннель"
            ruBypassItem?.label = "Умный автообход РФ: ${if (enableRuBypass) "ВКЛ" else "ВЫКЛ"}"
            trayIcon?.toolTip = "GATES — $stateText"
            val routeKey = "$activeRouteTag|${routes.joinToString(",") { it.tag }}"
            if (routeKey != lastRouteMenuKey) {
                lastRouteMenuKey = routeKey
                rebuildRoutesMenu(routes, activeRouteTag)
            }
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
        val icon = trayIcon ?: return
        trayIcon = null
        statusItem = null
        toggleItem = null
        routeMenu = null
        ruBypassItem = null
        EventQueue.invokeLater {
            runCatching { SystemTray.getSystemTray().remove(icon) }
        }
    }

    private fun createIcon(): BufferedImage {
        val size = SystemTray.getSystemTray().trayIconSize
        return WindowsBrandIcons.load(maxOf(size.width, size.height))
    }

    companion object {
        fun isSupported(): Boolean = runCatching { SystemTray.isSupported() }.getOrDefault(false)
    }
}
