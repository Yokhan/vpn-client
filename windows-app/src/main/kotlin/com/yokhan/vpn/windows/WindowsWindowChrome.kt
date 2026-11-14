package com.yokhan.gates.windows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.yokhan.gates.shared.ui.design.YokhanPalette
import com.yokhan.gates.shared.ui.design.toWin32ColorRef
import java.awt.Window
import java.io.File
import java.nio.charset.StandardCharsets

private interface Dwmapi : StdCallLibrary {
    fun DwmSetWindowAttribute(
        hwnd: WinDef.HWND,
        dwAttribute: Int,
        pvAttribute: Pointer,
        cbAttribute: Int,
    ): Int

    companion object {
        val INSTANCE: Dwmapi? by lazy {
            runCatching { Native.load("dwmapi", Dwmapi::class.java) }.getOrNull()
        }
    }
}

private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_BORDER_COLOR = 34
private const val DWMWA_CAPTION_COLOR = 35
private const val DWMWA_TEXT_COLOR = 36

internal fun applyMonolithicWindowChrome(
    window: Window?,
    palette: YokhanPalette,
    spaceActive: Boolean,
) {
    if (window == null || !window.isDisplayable) return
    val dwm = Dwmapi.INSTANCE ?: return
    val rawPtr = runCatching { Native.getComponentPointer(window) }.getOrNull() ?: return
    if (Pointer.nativeValue(rawPtr) == 0L) return
    val hwnd = WinDef.HWND(rawPtr)
    setIntAttribute(dwm, hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, if (spaceActive) 1 else 0)
    setIntAttribute(dwm, hwnd, DWMWA_CAPTION_COLOR, palette.background.toWin32ColorRef())
    setIntAttribute(dwm, hwnd, DWMWA_TEXT_COLOR, palette.primary.toWin32ColorRef())
    setIntAttribute(dwm, hwnd, DWMWA_BORDER_COLOR, palette.border.toWin32ColorRef())
}

private fun setIntAttribute(
    dwm: Dwmapi,
    hwnd: WinDef.HWND,
    attribute: Int,
    value: Int,
) {
    runCatching {
        val ref = IntByReference(value)
        dwm.DwmSetWindowAttribute(hwnd, attribute, ref.pointer, 4)
    }
}

/** Synchronizes the native Windows titlebar and border colors with [palette] on palette transitions. */
@Composable
internal fun SyncMonolithicWindowChrome(
    window: Window?,
    palette: YokhanPalette,
    spaceActive: Boolean,
) {
    LaunchedEffect(window, palette.background, palette.primary, palette.border, spaceActive) {
        applyMonolithicWindowChrome(window, palette, spaceActive)
    }
}

internal data class WindowsWindowSizePrefs(
    val widthDp: Int = 1080,
    val heightDp: Int = 760,
)

internal object WindowsWindowBoundsStore {
    private const val MIN_WIDTH = 400
    private const val MAX_WIDTH = 2560
    private const val MIN_HEIGHT = 620
    private const val MAX_HEIGHT = 1600

    private fun storeFile(appDir: File = windowsGatesAppDir()): File =
        File(appDir, "ui-window.json")

    fun load(appDir: File = windowsGatesAppDir()): WindowsWindowSizePrefs {
        val file = storeFile(appDir)
        if (!file.isFile) return WindowsWindowSizePrefs()
        return runCatching {
            val obj = JsonParser.parseString(file.readText(StandardCharsets.UTF_8)).asJsonObject
            val width = obj.get("widthDp")?.asInt?.coerceIn(MIN_WIDTH, MAX_WIDTH) ?: 1080
            val height = obj.get("heightDp")?.asInt?.coerceIn(MIN_HEIGHT, MAX_HEIGHT) ?: 760
            WindowsWindowSizePrefs(widthDp = width, heightDp = height)
        }.getOrDefault(WindowsWindowSizePrefs())
    }

    fun save(widthDp: Int, heightDp: Int, appDir: File = windowsGatesAppDir()) {
        val clampedW = widthDp.coerceIn(MIN_WIDTH, MAX_WIDTH)
        val clampedH = heightDp.coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        runCatching {
            appDir.mkdirs()
            val obj = JsonObject().apply {
                addProperty("widthDp", clampedW)
                addProperty("heightDp", clampedH)
            }
            storeFile(appDir).writeText(obj.toString(), StandardCharsets.UTF_8)
        }
    }
}
