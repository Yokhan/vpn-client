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
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON,
            )
            graphics.color = java.awt.Color(36, 42, 74)
            graphics.fillRoundRect(2, 2, 28, 28, 8, 8)
            graphics.color = java.awt.Color(132, 151, 255)
            graphics.fillRoundRect(8, 8, 16, 16, 5, 5)
            graphics.color = java.awt.Color.WHITE
            graphics.drawLine(12, 16, 16, 20)
            graphics.drawLine(16, 20, 22, 12)
        } finally {
            graphics.dispose()
        }
        return image
    }

    companion object {
        fun isSupported(): Boolean = runCatching { SystemTray.isSupported() }.getOrDefault(false)
    }
}
