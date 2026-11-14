package com.yokhan.gates.windows

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal data class WindowsResolvedShortcut(
    val targetPath: String,
    val arguments: String = "",
    val workingDirectory: String = "",
)

private fun defaultShortcutProcessRunner(
    command: List<String>,
    workDir: File,
    timeoutSeconds: Int,
): RuntimeCommandResult = runCatching {
    val process = ProcessBuilder(command)
        .directory(workDir)
        .redirectErrorStream(true)
        .start()
    val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        RuntimeCommandResult(1, "Shortcut lookup timed out")
    } else {
        val out = process.inputStream.bufferedReader().readText()
        RuntimeCommandResult(process.exitValue(), out)
    }
}.getOrElse { RuntimeCommandResult(1, it.message ?: "Shortcut lookup failed") }

/**
 * Shortcut / executable intake helpers shared by the file picker and the native
 * drop target.
 */
internal fun isWindowsAppTargetPath(path: String): Boolean {
    val normalized = path.trim().trim('"').trim()
    if (normalized.isEmpty()) return false
    return normalized.endsWith(".exe", ignoreCase = true) ||
        normalized.endsWith(".lnk", ignoreCase = true)
}

/**
 * Builds the PowerShell command that reads a `.lnk` target through
 * `WScript.Shell.CreateShortcut(...).TargetPath`.
 */
internal fun windowsShortcutTargetScript(shortcutPath: String): String =
    "\$ErrorActionPreference = 'Stop'; " +
        "\$wsh = New-Object -ComObject WScript.Shell; " +
        "\$shortcut = \$wsh.CreateShortcut(${psShortcutQuote(shortcutPath)}); " +
        "Write-Output \$shortcut.TargetPath"

private fun psShortcutQuote(value: String): String = "'${value.replace("'", "''")}'"

internal fun resolveWindowsShortcutTarget(
    shortcutPath: String,
    runner: (List<String>, File, Int) -> RuntimeCommandResult,
): String? {
    if (!shortcutPath.endsWith(".lnk", ignoreCase = true)) return null
    val localFile = File(shortcutPath.trim().trim('"'))
    if (localFile.isFile) {
        parseWindowsShortcutBinary(localFile)?.let { parsed ->
            val target = resolveSquirrelOrDirectTarget(parsed)
            if (target != null) return target
        }
    }
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

internal fun resolveWindowsShortcutTargets(
    shortcutPaths: List<String>,
    runner: (List<String>, File, Int) -> RuntimeCommandResult = ::defaultShortcutProcessRunner,
): Map<String, String> {
    val detailed = resolveWindowsShortcutsDetailed(shortcutPaths, runner)
    val mapped = linkedMapOf<String, String>()
    detailed.forEach { (source, resolved) ->
        resolveSquirrelOrDirectTarget(resolved)?.let { exe ->
            mapped[source] = exe
        }
    }
    return mapped
}

internal fun resolveWindowsShortcutsDetailed(
    shortcutPaths: List<String>,
    runner: (List<String>, File, Int) -> RuntimeCommandResult = ::defaultShortcutProcessRunner,
    env: Map<String, String> = System.getenv(),
): Map<String, WindowsResolvedShortcut> {
    val links = shortcutPaths
        .map { it.trim().trim('"') }
        .filter { it.endsWith(".lnk", ignoreCase = true) }
        .distinct()
    if (links.isEmpty()) return emptyMap()

    val resolved = linkedMapOf<String, WindowsResolvedShortcut>()
    val needsPowershell = mutableListOf<String>()

    links.forEach { link ->
        val file = File(link)
        val parsed = if (file.isFile) parseWindowsShortcutBinary(file, env) else null
        if (parsed != null && parsed.targetPath.isNotBlank()) {
            resolved[link] = parsed
        } else {
            needsPowershell += link
        }
    }

    if (needsPowershell.isNotEmpty()) {
        val literals = needsPowershell.joinToString(", ") { psShortcutQuote(it) }
        val script =
            "\$ErrorActionPreference = 'SilentlyContinue'; " +
                "\$wsh = New-Object -ComObject WScript.Shell; " +
                "foreach (\$p in @($literals)) { " +
                "try { \$s = \$wsh.CreateShortcut(\$p); \$t = \$s.TargetPath; " +
                "if (\$t) { Write-Output (\$p + \"`t\" + \$t + \"`t\" + (\$s.Arguments -replace \"[`r`n]+\", ' ') + \"`t\" + \$s.WorkingDirectory) } } catch {} }"
        val result = runner(
            listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script),
            File("."),
            20,
        )
        if (result.exitCode == 0) {
            result.output.lineSequence().forEach { line ->
                val parts = line.split('\t')
                if (parts.size < 2) return@forEach
                val source = parts[0].trim().trim('"')
                val target = expandWindowsEnvVars(parts[1].trim().trim('"'), env)
                val args = if (parts.size >= 3) parts[2].trim() else ""
                val workDir = if (parts.size >= 4) expandWindowsEnvVars(parts[3].trim().trim('"'), env) else ""
                if (source.isNotBlank() && target.isNotBlank()) {
                    resolved[source] = WindowsResolvedShortcut(target, args, workDir)
                }
            }
        }
    }

    return resolved
}

internal fun parseWindowsShortcutBinary(
    file: File,
    env: Map<String, String> = System.getenv(),
): WindowsResolvedShortcut? {
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    return parseWindowsShortcutBytes(bytes, file.parentFile, env)
}

internal fun parseWindowsShortcutBytes(
    bytes: ByteArray,
    shortcutParentDir: File? = null,
    env: Map<String, String> = System.getenv(),
): WindowsResolvedShortcut? {
    if (bytes.size < 76) return null
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val headerSize = buf.getInt(0)
    if (headerSize != 0x4C) return null

    val linkFlags = buf.getInt(20)
    val hasTargetIdList = (linkFlags and 0x01) != 0
    val hasLinkInfo = (linkFlags and 0x02) != 0
    val hasName = (linkFlags and 0x04) != 0
    val hasRelativePath = (linkFlags and 0x08) != 0
    val hasWorkingDir = (linkFlags and 0x10) != 0
    val hasArguments = (linkFlags and 0x20) != 0
    val isUnicode = (linkFlags and 0x80) != 0

    var pos = 76
    if (hasTargetIdList) {
        if (pos + 2 > bytes.size) return null
        val idListSize = buf.getShort(pos).toInt() and 0xFFFF
        pos += 2 + idListSize
        if (pos > bytes.size) return null
    }

    var localBasePath = ""
    var commonPathSuffix = ""
    if (hasLinkInfo) {
        if (pos + 28 > bytes.size) return null
        val linkInfoStart = pos
        val linkInfoSize = buf.getInt(linkInfoStart)
        if (linkInfoSize < 28 || linkInfoStart + linkInfoSize > bytes.size) return null
        val linkInfoHeaderSize = buf.getInt(linkInfoStart + 4)
        val linkInfoFlags = buf.getInt(linkInfoStart + 8)
        val localBasePathOffset = buf.getInt(linkInfoStart + 16)
        val commonPathSuffixOffset = buf.getInt(linkInfoStart + 24)

        if ((linkInfoFlags and 0x01) != 0) {
            if (linkInfoHeaderSize >= 0x24 && linkInfoStart + 36 <= bytes.size) {
                val unicodeOffset = buf.getInt(linkInfoStart + 28)
                val unicodeSuffixOffset = buf.getInt(linkInfoStart + 32)
                if (unicodeOffset > 0 && linkInfoStart + unicodeOffset < linkInfoStart + linkInfoSize) {
                    localBasePath = readNullTerminatedUtf16(bytes, linkInfoStart + unicodeOffset, linkInfoStart + linkInfoSize)
                }
                if (unicodeSuffixOffset > 0 && linkInfoStart + unicodeSuffixOffset < linkInfoStart + linkInfoSize) {
                    commonPathSuffix = readNullTerminatedUtf16(bytes, linkInfoStart + unicodeSuffixOffset, linkInfoStart + linkInfoSize)
                }
            }
            if (localBasePath.isBlank() && localBasePathOffset > 0 && linkInfoStart + localBasePathOffset < linkInfoStart + linkInfoSize) {
                localBasePath = readNullTerminatedAnsi(bytes, linkInfoStart + localBasePathOffset, linkInfoStart + linkInfoSize)
            }
            if (commonPathSuffix.isBlank() && commonPathSuffixOffset > 0 && linkInfoStart + commonPathSuffixOffset < linkInfoStart + linkInfoSize) {
                commonPathSuffix = readNullTerminatedAnsi(bytes, linkInfoStart + commonPathSuffixOffset, linkInfoStart + linkInfoSize)
            }
        }
        pos += linkInfoSize
    }

    fun readStringData(): String {
        if (pos + 2 > bytes.size) return ""
        val charCount = buf.getShort(pos).toInt() and 0xFFFF
        pos += 2
        if (charCount == 0) return ""
        val byteCount = if (isUnicode) charCount * 2 else charCount
        if (pos + byteCount > bytes.size) {
            pos = bytes.size
            return ""
        }
        val str = if (isUnicode) {
            String(bytes, pos, byteCount, StandardCharsets.UTF_16LE)
        } else {
            String(bytes, pos, byteCount, ansiCharset())
        }
        pos += byteCount
        return str.trimEnd('\u0000')
    }

    if (hasName) readStringData()
    val relativePath = if (hasRelativePath) readStringData() else ""
    val workingDir = if (hasWorkingDir) expandWindowsEnvVars(readStringData(), env) else ""
    val arguments = if (hasArguments) expandWindowsEnvVars(readStringData(), env) else ""

    var combinedPath = when {
        localBasePath.isNotBlank() && commonPathSuffix.isNotBlank() && !localBasePath.endsWith(commonPathSuffix, ignoreCase = true) ->
            File(localBasePath, commonPathSuffix).path
        localBasePath.isNotBlank() -> localBasePath
        else -> ""
    }
    combinedPath = expandWindowsEnvVars(combinedPath, env).trim().trim('"')

    if (combinedPath.isBlank() && relativePath.isNotBlank() && shortcutParentDir != null) {
        val candidate = runCatching { File(shortcutParentDir, expandWindowsEnvVars(relativePath, env)).canonicalPath }.getOrNull()
        if (!candidate.isNullOrBlank()) {
            combinedPath = candidate
        }
    }

    if (combinedPath.isBlank()) return null
    return WindowsResolvedShortcut(
        targetPath = combinedPath.replace('/', '\\'),
        arguments = arguments.trim(),
        workingDirectory = workingDir.trim().replace('/', '\\'),
    )
}

internal fun resolveSquirrelOrDirectTarget(resolved: WindowsResolvedShortcut): String? {
    val target = resolved.targetPath.trim().trim('"')
    if (!target.endsWith(".exe", ignoreCase = true)) return null
    val targetFile = File(target)
    if (targetFile.name.equals("Update.exe", ignoreCase = true)) {
        val squirrelExe = extractSquirrelProcessStartExe(resolved.arguments)
        if (!squirrelExe.isNullOrBlank()) {
            val found = findExecutableInAppDirs(
                exeName = squirrelExe,
                searchRoots = listOfNotNull(
                    targetFile.parentFile,
                    resolved.workingDirectory.takeIf { it.isNotBlank() }?.let(::File),
                ),
            )
            if (found != null) return found
        }
    }
    return target
}

private fun extractSquirrelProcessStartExe(arguments: String): String? {
    if (arguments.isBlank()) return null
    val regex = Regex("""(?:--processStart(?:-args)?)[=\s]+(?:"([^"]+\.exe)"|([^\s"]+\.exe))""", RegexOption.IGNORE_CASE)
    val match = regex.find(arguments) ?: return null
    val raw = match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
    return raw.takeIf { it.endsWith(".exe", ignoreCase = true) }?.let { File(it).name }
}

private fun findExecutableInAppDirs(exeName: String, searchRoots: List<File>): String? {
    val normalizedTarget = exeName.lowercase()
    searchRoots.distinctBy { it.absolutePath.lowercase() }.forEach { root ->
        if (!root.isDirectory) return@forEach
        val versionedDirs = root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("app-", ignoreCase = true) }
            ?.sortedWith(compareByDescending<File> { parseAppDirVersion(it.name) }.thenByDescending { it.name.lowercase() })
            .orEmpty()
        versionedDirs.forEach { appDir ->
            val direct = File(appDir, exeName)
            if (direct.isFile) return direct.absolutePath
            val ci = appDir.listFiles()?.firstOrNull { it.isFile && it.name.lowercase() == normalizedTarget }
            if (ci != null) return ci.absolutePath
        }
        val directInRoot = File(root, exeName)
        if (directInRoot.isFile) return directInRoot.absolutePath
    }
    return null
}

private fun parseAppDirVersion(dirName: String): ComparableVersion {
    val parts = dirName.removePrefix("app-").removePrefix("APP-")
        .split('.')
        .map { it.toIntOrNull() ?: 0 }
    return ComparableVersion(parts)
}

private data class ComparableVersion(val parts: List<Int>) : Comparable<ComparableVersion> {
    override fun compareTo(other: ComparableVersion): Int {
        val max = maxOf(parts.size, other.parts.size)
        for (i in 0 until max) {
            val diff = (parts.getOrElse(i) { 0 }).compareTo(other.parts.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}

private fun expandWindowsEnvVars(input: String, env: Map<String, String>): String {
    if (!input.contains('%') || env.isEmpty()) return input
    val ciEnv = env.entries.associate { it.key.lowercase() to it.value }
    return Regex("""%([A-Za-z0-9_()-]+)%""").replace(input) { match ->
        ciEnv[match.groupValues[1].lowercase()] ?: match.value
    }
}

private fun readNullTerminatedUtf16(bytes: ByteArray, start: Int, limit: Int): String {
    var end = start
    while (end + 1 < limit && end + 1 < bytes.size) {
        if (bytes[end] == 0.toByte() && bytes[end + 1] == 0.toByte()) break
        end += 2
    }
    return if (end > start) String(bytes, start, end - start, StandardCharsets.UTF_16LE) else ""
}

private fun readNullTerminatedAnsi(bytes: ByteArray, start: Int, limit: Int): String {
    var end = start
    while (end < limit && end < bytes.size && bytes[end] != 0.toByte()) {
        end += 1
    }
    return if (end > start) String(bytes, start, end - start, ansiCharset()) else ""
}

private fun ansiCharset(): Charset =
    runCatching { Charset.forName("windows-1251") }.getOrElse { Charset.defaultCharset() }
