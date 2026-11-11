package com.yokhan.gates.windows

import java.io.File

/**
 * Shortcut / executable intake helpers shared by the file picker and the native
 * drop target. Only `.exe` and `.lnk` targets are accepted; directories and
 * other file kinds are rejected by callers via [File.isFile].
 */
internal fun isWindowsAppTargetPath(path: String): Boolean {
    val normalized = path.trim().trim('"').trim()
    if (normalized.isEmpty()) return false
    return normalized.endsWith(".exe", ignoreCase = true) ||
        normalized.endsWith(".lnk", ignoreCase = true)
}

/**
 * Builds the PowerShell command that reads a `.lnk` target through
 * `WScript.Shell.CreateShortcut(...).TargetPath`. The shortcut path is passed
 * as a single-quoted literal (embedded single quotes are doubled), never
 * concatenated into a shell string, so no command injection is possible.
 */
internal fun windowsShortcutTargetScript(shortcutPath: String): String =
    "\$ErrorActionPreference = 'Stop'; " +
        "\$wsh = New-Object -ComObject WScript.Shell; " +
        "\$shortcut = \$wsh.CreateShortcut(${psShortcutQuote(shortcutPath)}); " +
        "Write-Output \$shortcut.TargetPath"

private fun psShortcutQuote(value: String): String = "'${value.replace("'", "''")}'"

/**
 * Resolves a `.lnk` path to its target executable, or returns null when the
 * input is not a shortcut or the target is not an executable. The process
 * runner is injected so tests can mock the PowerShell call.
 */
internal fun resolveWindowsShortcutTarget(
    shortcutPath: String,
    runner: (List<String>, File, Int) -> RuntimeCommandResult,
): String? {
    if (!shortcutPath.endsWith(".lnk", ignoreCase = true)) return null
    val script = windowsShortcutTargetScript(shortcutPath)
    val result = runner(
        listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script),
        File("."),
        10,
    )
    if (result.exitCode != 0) return null
    val target = result.output.lineSequence()
        .map { it.trim().trim('"') }
        .lastOrNull { it.isNotBlank() }
        ?: return null
    return target.takeIf { it.endsWith(".exe", ignoreCase = true) }
}
