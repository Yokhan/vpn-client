package com.yokhan.gates.windows

import androidx.compose.ui.geometry.Offset
import java.awt.Point
import java.io.File

private const val MAX_EXECUTABLES_PER_DROPPED_DIR = 12
private const val MAX_DROPPED_EXECUTABLES = 24
private const val MAX_DROP_FOLDER_DEPTH = 4

internal sealed interface DropIntakeAction {
    data class ImportProfileFile(val file: File) : DropIntakeAction
    data class RouteExecutables(val exePaths: List<String>, val skippedCount: Int) : DropIntakeAction
    data class Unsupported(val reason: String) : DropIntakeAction
}

internal fun mapAwtPointToComposeRootPx(
    pointInWindow: Point,
    contentPaneOriginInWindow: Point = Point(0, 0),
    density: Float = 1f,
): Offset {
    val safeDensity = density.coerceAtLeast(0.5f)
    return Offset(
        x = (pointInWindow.x - contentPaneOriginInWindow.x) * safeDensity,
        y = (pointInWindow.y - contentPaneOriginInWindow.y) * safeDensity,
    )
}

internal fun isWindowsUninstallerOrHelperExe(fileName: String): Boolean {
    val lower = fileName.trim().lowercase()
    if (!lower.endsWith(".exe")) return true
    if (lower in setOf(
            "gates.exe",
            "gates.service.exe",
            "sing-box.exe",
            "xray.exe",
            "dxsetup.exe",
            "crashpad_handler.exe",
            "elevateddiagnostics.exe",
            "createdump.exe",
            "update.exe",
            "squirrel.exe",
        )
    ) {
        return true
    }
    return lower.startsWith("unins") ||
        lower.startsWith("uninstall") ||
        lower.startsWith("setup") ||
        lower.startsWith("vc_redist") ||
        lower.startsWith("vcredist") ||
        lower.startsWith("unitycrashhandler") ||
        lower.startsWith("crashpad_handler") ||
        lower.startsWith("squirrel") ||
        lower.endsWith("helper.exe") ||
        lower.endsWith("_helper.exe") ||
        lower.endsWith("-helper.exe")
}

internal fun classifyDroppedFiles(
    files: List<File>,
    shortcutResolver: (List<String>) -> Map<String, String> = { emptyMap() },
): DropIntakeAction {
    val existing = files.filter { it.exists() }
    if (existing.isEmpty()) {
        return DropIntakeAction.Unsupported("Перетащенные файлы не найдены.")
    }

    if (existing.size == 1 && existing[0].isFile) {
        val single = existing[0]
        val lowerName = single.name.lowercase()
        if (lowerName.endsWith(".json") ||
            lowerName.endsWith(".singbox") ||
            lowerName.endsWith(".txt") ||
            lowerName.endsWith(".conf") ||
            lowerName.endsWith(".gates")
        ) {
            return DropIntakeAction.ImportProfileFile(single)
        }
        if (lowerName.endsWith(".url")) {
            return DropIntakeAction.Unsupported(
                "Ярлык .url содержит веб-ссылку или запуск через лаунчер (Steam/Epic). Перетащите папку приложения или его .exe файл.",
            )
        }
    }

    val directExes = mutableListOf<String>()
    val lnkFiles = mutableListOf<String>()
    var skipped = 0
    var sawUrlFile = false

    for (entry in existing) {
        when {
            entry.isFile && entry.name.endsWith(".exe", ignoreCase = true) -> {
                if (isWindowsUninstallerOrHelperExe(entry.name)) {
                    skipped += 1
                } else {
                    directExes += entry.absolutePath
                }
            }
            entry.isFile && entry.name.endsWith(".lnk", ignoreCase = true) -> {
                lnkFiles += entry.absolutePath
            }
            entry.isFile && entry.name.endsWith(".url", ignoreCase = true) -> {
                sawUrlFile = true
                skipped += 1
            }
            entry.isDirectory -> {
                val collectedFromDir = collectDirectoryExecutables(entry, MAX_DROP_FOLDER_DEPTH)
                val useful = collectedFromDir.filterNot { isWindowsUninstallerOrHelperExe(it.name) }
                skipped += (collectedFromDir.size - useful.size).coerceAtLeast(0)
                useful.take(MAX_EXECUTABLES_PER_DROPPED_DIR).forEach { directExes += it.absolutePath }
                if (useful.size > MAX_EXECUTABLES_PER_DROPPED_DIR) {
                    skipped += useful.size - MAX_EXECUTABLES_PER_DROPPED_DIR
                }
            }
            else -> skipped += 1
        }
    }

    val resolvedShortcuts = if (lnkFiles.isNotEmpty()) shortcutResolver(lnkFiles) else emptyMap()
    lnkFiles.forEach { lnk ->
        val target = resolvedShortcuts[lnk]
        if (target != null && !isWindowsUninstallerOrHelperExe(File(target).name)) {
            directExes += target
        } else {
            skipped += 1
        }
    }

    val deduplicated = linkedMapOf<String, String>()
    directExes.forEach { path ->
        val normalized = path.replace('/', '\\').trim()
        if (normalized.isNotBlank()) {
            deduplicated.putIfAbsent(normalized.lowercase(), normalized)
        }
    }

    val finalExes = deduplicated.values.take(MAX_DROPPED_EXECUTABLES)
    if (deduplicated.size > MAX_DROPPED_EXECUTABLES) {
        skipped += deduplicated.size - MAX_DROPPED_EXECUTABLES
    }
    return when {
        finalExes.isNotEmpty() -> DropIntakeAction.RouteExecutables(finalExes, skipped)
        sawUrlFile -> DropIntakeAction.Unsupported(
            "Ярлык .url содержит веб-ссылку или запуск через лаунчер (Steam/Epic). Перетащите папку приложения или его .exe файл.",
        )
        else -> DropIntakeAction.Unsupported("Перетащите файл .exe, ярлык .lnk, папку программы или JSON-профиль GATES.")
    }
}

private fun collectDirectoryExecutables(root: File, maxDepth: Int): List<File> {
    val result = mutableListOf<File>()
    fun walk(dir: File, depth: Int) {
        val children = dir.listFiles()?.toList().orEmpty()
        val files = children.filter { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
        result.addAll(files)
        if (depth >= maxDepth) return
        val subdirs = children.filter { it.isDirectory }
            .sortedWith(
                compareByDescending<File> { it.name.startsWith("app-", ignoreCase = true) }
                    .thenByDescending { if (it.name.startsWith("app-", ignoreCase = true)) it.name.lowercase() else "" }
                    .thenBy { it.name.lowercase() },
            )
        subdirs.forEach { sub -> walk(sub, depth + 1) }
    }
    walk(root, 0)
    return result
}
