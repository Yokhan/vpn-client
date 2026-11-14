package com.yokhan.gates.windows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.filechooser.FileSystemView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Windows shell icon extraction for app-routing cards.
 *
 * Icons are painted through [FileSystemView], converted to Compose [ImageBitmap]s,
 * and cached by canonical path. Compose callers should prefer [rememberWindowsAppIcon]
 * so shell extraction runs on [Dispatchers.IO] without blocking scrolling frames.
 */
internal object WindowsAppIcons {
    private const val DEFAULT_ICON_SIZE = 32
    private const val MAX_CACHE_ENTRIES = 512

    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    /** Returns an already-cached bitmap without touching the filesystem, or null. */
    fun cachedOrNull(path: String): ImageBitmap? {
        if (path.isBlank()) return null
        return cache[canonicalKey(path)]
    }

    /** Load (or reuse the cached) icon for an executable path. Never null. */
    fun icon(path: String): ImageBitmap {
        if (path.isBlank()) return fallback()
        val key = canonicalKey(path)
        cache[key]?.let { return it }
        if (cache.size >= MAX_CACHE_ENTRIES) {
            cache.keys.take(MAX_CACHE_ENTRIES / 4).forEach { cache.remove(it) }
        }
        return cache.computeIfAbsent(key) { load(path) }
    }

    /** Neutral placeholder shown while an icon is unavailable or still loading. */
    fun fallback(): ImageBitmap = fallbackBitmap

    fun forget(path: String) {
        if (path.isBlank()) return
        cache.remove(canonicalKey(path))
    }

    fun clear() = cache.clear()

    private val fallbackImage: BufferedImage by lazy { neutralFallback(DEFAULT_ICON_SIZE) }
    private val fallbackBitmap: ImageBitmap by lazy { fallbackImage.toComposeImageBitmap() }

    private fun load(path: String): ImageBitmap = runCatching {
        val file = File(path)
        if (!file.exists()) return@runCatching fallback()
        val icon = FileSystemView.getFileSystemView().getSystemIcon(file)
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
        path.trim().replace('/', '\\').lowercase()

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

@Composable
internal fun rememberWindowsAppIcon(path: String): ImageBitmap {
    val clean = path.trim()
    val initial = remember(clean) {
        WindowsAppIcons.cachedOrNull(clean) ?: WindowsAppIcons.fallback()
    }
    var bitmap by remember(clean) { mutableStateOf(initial) }
    LaunchedEffect(clean) {
        if (clean.isNotBlank() && WindowsAppIcons.cachedOrNull(clean) == null) {
            bitmap = withContext(Dispatchers.IO) { WindowsAppIcons.icon(clean) }
        }
    }
    return bitmap
}
