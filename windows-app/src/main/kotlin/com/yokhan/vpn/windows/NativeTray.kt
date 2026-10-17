package com.yokhan.gates.windows

import java.awt.EventQueue
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

class NativeTray(
    private val onOpen: () -> Unit,
    private val onQuit: () -> Unit,
) {
    private var trayIcon: TrayIcon? = null

    fun install() {
        if (!isSupported() || trayIcon != null) return
        EventQueue.invokeLater {
            if (trayIcon != null) return@invokeLater
            val menu = PopupMenu()
            val open = MenuItem("Open GATES").apply {
                addActionListener { onOpen() }
            }
            val quit = MenuItem("Quit").apply {
                addActionListener { onQuit() }
            }
            menu.add(open)
            menu.addSeparator()
            menu.add(quit)

            val icon = TrayIcon(createIcon(), "GATES", menu).apply {
                isImageAutoSize = true
                addActionListener { onOpen() }
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
        }
    }

    fun uninstall() {
        val icon = trayIcon ?: return
        trayIcon = null
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
