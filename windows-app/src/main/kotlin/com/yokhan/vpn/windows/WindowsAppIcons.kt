package com.yokhan.gates.windows

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.filechooser.FileSystemView

/**
 * Windows shell icon extraction for app-routing cards.
 *
 * Icons are painted through [FileSystemView] (the same source Task Manager uses
 * for "open file location"), converted to Compose [ImageBitmap]s and cached by
 * canonical path. A neutral, non-null fallback is returned whenever an icon
 * cannot be resolved so callers never branch on a missing bitmap.
 */
internal object WindowsAppIcons {
    private const val DEFAULT_ICON_SIZE = 32

    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    /** Load (or reuse the cached) icon for an executable path. Never null. */
    fun icon(path: String): ImageBitmap {
        if (path.isBlank()) return fallback()
        val key = canonicalKey(path)
        return cache.computeIfAbsent(key) { load(path) }
    }

    /** Neutral placeholder shown while an icon is unavailable or still loading. */
    fun fallback(): ImageBitmap = fallbackImage.toComposeImageBitmap()

    fun forget(path: String) {
        if (path.isBlank()) return
        cache.remove(canonicalKey(path))
    }

    fun clear() = cache.clear()

    private val fallbackImage: BufferedImage by lazy { neutralFallback(DEFAULT_ICON_SIZE) }

    private fun load(path: String): ImageBitmap = runCatching {
        val icon = FileSystemView.getFileSystemView().getSystemIcon(File(path))
        if (icon == null || icon.iconWidth <= 0 || icon.iconHeight <= 0) {
            fallback()
        } else {
            val width = icon.iconWidth.coerceAtLeast(DEFAULT_ICON_SIZE)
            val height = icon.iconHeight.coerceAtLeast(DEFAULT_ICON_SIZE)
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                icon.paintIcon(null, graphics, 0, 0)
            } finally {
                graphics.dispose()
            }
            image.toComposeImageBitmap()
        }
    }.getOrElse { fallback() }

    private fun canonicalKey(path: String): String =
        runCatching { File(path).canonicalPath.lowercase() }.getOrDefault(path.lowercase())

    private fun neutralFallback(size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val argb = 0xFF9A9AA4.toInt()
        for (y in 0 until size) {
            for (x in 0 until size) {
                image.setRGB(x, y, argb)
            }
        }
        return image
    }
}
