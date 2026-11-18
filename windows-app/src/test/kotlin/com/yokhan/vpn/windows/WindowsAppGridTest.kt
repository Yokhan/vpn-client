package com.yokhan.gates.windows

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsAppGridTest {
    @Test
    fun supportedIntakeExtensions() {
        assertTrue(isWindowsAppTargetPath("C:\\Games\\game.exe"))
        assertTrue(isWindowsAppTargetPath("C:\\Games\\game.EXE"))
        assertTrue(isWindowsAppTargetPath("C:\\Games\\game.lnk"))
        assertTrue(isWindowsAppTargetPath("C:\\Games\\game.LNK"))
        assertTrue(isWindowsAppTargetPath("\"C:\\Games\\game.exe\""))
        assertFalse(isWindowsAppTargetPath("C:\\Games\\game.dll"))
        assertFalse(isWindowsAppTargetPath("C:\\Games\\readme.txt"))
        assertFalse(isWindowsAppTargetPath(""))
        assertFalse(isWindowsAppTargetPath("   "))
        assertFalse(isWindowsAppTargetPath("C:\\Games"))
    }

    @Test
    fun shortcutResolutionUsesInjectedRunner() {
        val runner: (List<String>, File, Int) -> RuntimeCommandResult = { args, _, _ ->
            assertTrue(args.contains("-Command"))
            RuntimeCommandResult(0, "C:\\Program Files\\App\\app.exe")
        }
        assertEquals(
            "C:\\Program Files\\App\\app.exe",
            resolveWindowsShortcutTarget("C:\\shortcut.lnk", runner),
        )
    }

    @Test
    fun shortcutResolutionRejectsNonExecutableTarget() {
        val runner: (List<String>, File, Int) -> RuntimeCommandResult = { _, _, _ ->
            RuntimeCommandResult(0, "C:\\Program Files\\App\\readme.txt")
        }
        assertNull(resolveWindowsShortcutTarget("C:\\shortcut.lnk", runner))
    }

    @Test
    fun shortcutResolutionReturnsNullForNonShortcut() {
        val runner: (List<String>, File, Int) -> RuntimeCommandResult = { _, _, _ ->
            RuntimeCommandResult(1, "unused")
        }
        assertNull(resolveWindowsShortcutTarget("C:\\app.exe", runner))
    }

    @Test
    fun shortcutScriptQuotesPathSafely() {
        val script = windowsShortcutTargetScript("C:\\Quotes\\it's.exe.lnk")
        assertTrue(script.contains("'C:\\Quotes\\it''s.exe.lnk'"))
    }

    @Test
    fun statusMatchesByPathAndName() {
        val state = AppRoutingState(
            routeAll = true,
            proxyProcessNames = listOf("browser.exe"),
            proxyProcessPaths = listOf("C:\\Games\\game.exe"),
            directProcessNames = listOf("steam.exe"),
            directProcessPaths = listOf("C:\\Tools\\tool.exe"),
        )
        assertEquals(WindowsAppRouteStatus.PROXY, windowsAppRouteStatus("game.exe", "C:\\Games\\game.exe", state))
        assertEquals(WindowsAppRouteStatus.DIRECT, windowsAppRouteStatus("tool.exe", "C:\\Tools\\tool.exe", state))
        assertEquals(WindowsAppRouteStatus.PROXY, windowsAppRouteStatus("browser.exe", "C:\\X\\browser.exe", state))
        assertEquals(WindowsAppRouteStatus.DIRECT, windowsAppRouteStatus("steam.exe", "C:\\X\\steam.exe", state))
        assertEquals(WindowsAppRouteStatus.UNSET, windowsAppRouteStatus("other.exe", "C:\\X\\other.exe", state))
    }

    @Test
    fun statusPrecedenceMatchesRuleOrder() {
        val state = AppRoutingState(
            routeAll = true,
            proxyProcessNames = listOf("shared.exe"),
            proxyProcessPaths = emptyList(),
            directProcessNames = listOf("shared.exe"),
            directProcessPaths = listOf("C:\\Direct\\app.exe"),
        )
        // Exact direct path precedes a proxy name-wide rule.
        assertEquals(WindowsAppRouteStatus.DIRECT, windowsAppRouteStatus("app.exe", "C:\\Direct\\app.exe", state))
        // Direct name-wide rule precedes a proxy name-wide rule.
        assertEquals(WindowsAppRouteStatus.DIRECT, windowsAppRouteStatus("shared.exe", "C:\\Other\\shared.exe", state))
    }

    @Test
    fun iconFallbackIsNeverNull() {
        WindowsAppIcons.clear()
        assertNotNull(WindowsAppIcons.fallback())
        assertNotNull(WindowsAppIcons.icon(""))
        assertNotNull(WindowsAppIcons.icon("C:\\Definitely\\Missing\\app.exe"))
        assertTrue(WindowsAppIcons.fallback().width > 0)
        assertTrue(WindowsAppIcons.fallback().height > 0)
    }

    @Test
    fun iconCacheReturnsSameInstanceForSamePath() {
        WindowsAppIcons.clear()
        val first = WindowsAppIcons.icon("C:\\Cache\\probe.exe")
        val second = WindowsAppIcons.icon("C:\\Cache\\probe.exe")
        assertTrue(first === second)
    }

    @Test
    fun smallerSwingIconIsCenteredInsideTargetCanvasInsteadOfTopLeftCorner() {
        val smallIcon = object : javax.swing.Icon {
            override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
                g.color = java.awt.Color.WHITE
                g.fillRect(x, y, iconWidth, iconHeight)
            }

            override fun getIconWidth(): Int = 16
            override fun getIconHeight(): Int = 16
        }

        val rendered = WindowsAppIcons.renderCenteredIcon(smallIcon, minSize = 32)
        assertEquals(32, rendered.width)
        assertEquals(32, rendered.height)
        assertEquals(0, rendered.getRGB(0, 0))
        assertEquals(java.awt.Color.WHITE.rgb, rendered.getRGB(16, 16))
        assertEquals(0, rendered.getRGB(31, 31))
    }
}
