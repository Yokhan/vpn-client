package com.yokhan.gates.windows

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinReg
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class WindowsInstalledAppCandidate(
    val displayName: String,
    val name: String,
    val path: String,
    val source: String,
    val isRuCandidate: Boolean,
)

internal object WindowsInstalledAppsScanner {
    @Volatile
    private var cached: List<WindowsInstalledAppCandidate>? = null
    private val lock = Any()

    fun cachedOrEmpty(): List<WindowsInstalledAppCandidate> = cached.orEmpty()

    fun getOrScan(forceRefresh: Boolean = false): List<WindowsInstalledAppCandidate> {
        val current = cached
        if (!forceRefresh && current != null) return current
        return synchronized(lock) {
            if (!forceRefresh && cached != null) return@synchronized cached!!
            val scanned = scan()
            cached = scanned
            scanned
        }
    }

    fun scan(): List<WindowsInstalledAppCandidate> {
        val found = linkedMapOf<String, WindowsInstalledAppCandidate>()

        fun register(displayName: String, rawExePath: String, publisher: String, source: String) {
            val clean = rawExePath.trim().trim('"').replace('/', '\\')
            if (!clean.endsWith(".exe", ignoreCase = true)) return
            val file = File(clean)
            if (!file.isFile) return
            val lowerPath = clean.lowercase()
            if (lowerPath.startsWith("c:\\windows\\") ||
                lowerPath.contains("\\windowsapps\\") && !file.exists()
            ) {
                return
            }
            if (isWindowsUninstallerOrHelperExe(file.name)) return
            val title = displayName.trim().ifBlank { file.nameWithoutExtension }
            val ru = isRuCompatibilityCandidate(file.name, clean, "$title $publisher")
            val candidate = WindowsInstalledAppCandidate(
                displayName = title,
                name = file.name,
                path = clean,
                source = source,
                isRuCandidate = ru,
            )
            val existing = found[lowerPath]
            if (existing == null || (existing.displayName == existing.name && title != file.name)) {
                found[lowerPath] = candidate
            }
        }

        scanStartMenuLinks(::register)
        scanUninstallRegistryNative(::register)

        return found.values.sortedWith(
            compareByDescending<WindowsInstalledAppCandidate> { it.isRuCandidate }
                .thenBy { it.displayName.lowercase() },
        )
    }

    private fun scanStartMenuLinks(
        register: (displayName: String, exePath: String, publisher: String, source: String) -> Unit,
    ) {
        val roots = listOfNotNull(
            System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                ?.let { File(it, "Microsoft\\Windows\\Start Menu\\Programs") },
            System.getenv("ProgramData")?.takeIf { it.isNotBlank() }
                ?.let { File(it, "Microsoft\\Windows\\Start Menu\\Programs") },
            System.getProperty("user.home")?.takeIf { it.isNotBlank() }
                ?.let { File(it, "Desktop") },
        ).filter { it.isDirectory }

        val lnkFiles = roots
            .flatMap { root ->
                runCatching {
                    root.walkTopDown()
                        .maxDepth(4)
                        .filter { it.isFile && it.name.endsWith(".lnk", ignoreCase = true) }
                        .take(180)
                        .toList()
                }.getOrDefault(emptyList())
            }
            .distinctBy { it.absolutePath.lowercase() }
            .take(260)

        if (lnkFiles.isEmpty()) return
        val resolved = resolveWindowsShortcutsDetailed(
            shortcutPaths = lnkFiles.map { it.absolutePath },
            runner = { args, dir, timeout -> runProcess(args, dir, timeout) },
        )
        lnkFiles.forEach { lnk ->
            val details = resolved[lnk.absolutePath] ?: return@forEach
            val target = resolveSquirrelOrDirectTarget(details) ?: return@forEach
            register(lnk.nameWithoutExtension, target, "", "start-menu")
        }
    }

    private fun scanUninstallRegistryNative(
        register: (displayName: String, exePath: String, publisher: String, source: String) -> Unit,
    ) {
        val roots = listOf(
            Triple(WinReg.HKEY_LOCAL_MACHINE, "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall", WinNT.KEY_WOW64_64KEY),
            Triple(WinReg.HKEY_LOCAL_MACHINE, "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall", WinNT.KEY_WOW64_32KEY),
            Triple(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall", 0),
        )
        var count = 0
        for ((hkey, basePath, wowFlag) in roots) {
            val sam = WinNT.KEY_READ or wowFlag
            val subKeys = runCatching {
                if (Advapi32Util.registryKeyExists(hkey, basePath, sam)) {
                    Advapi32Util.registryGetKeys(hkey, basePath, sam)
                } else {
                    emptyArray()
                }
            }.getOrDefault(emptyArray())

            for (subKey in subKeys) {
                if (count >= 280) break
                val keyPath = "$basePath\\$subKey"
                val displayName = readRegString(hkey, keyPath, "DisplayName", sam)
                if (displayName.isBlank()) continue
                val publisher = readRegString(hkey, keyPath, "Publisher", sam)
                val iconRaw = readRegString(hkey, keyPath, "DisplayIcon", sam)
                val exeFromIcon = iconRaw.substringBeforeLast(',').trim().trim('"')
                if (exeFromIcon.endsWith(".exe", ignoreCase = true) && File(exeFromIcon).isFile) {
                    register(displayName, exeFromIcon, publisher, "registry")
                    count += 1
                    continue
                }
                val installDir = readRegString(hkey, keyPath, "InstallLocation", sam).trim('"')
                if (installDir.isNotBlank()) {
                    val dir = File(installDir)
                    if (dir.isDirectory) {
                        val candidateExe = dir.listFiles()
                            ?.filter { it.isFile && it.name.endsWith(".exe", ignoreCase = true) && !isWindowsUninstallerOrHelperExe(it.name) }
                            ?.maxByOrNull { it.length() }
                        if (candidateExe != null) {
                            register(displayName, candidateExe.absolutePath, publisher, "registry")
                            count += 1
                        }
                    }
                }
            }
        }
    }

    private fun readRegString(
        hkey: WinReg.HKEY,
        keyPath: String,
        valueName: String,
        sam: Int,
    ): String = runCatching {
        if (Advapi32Util.registryValueExists(hkey, keyPath, valueName, sam)) {
            Advapi32Util.registryGetStringValue(hkey, keyPath, valueName, sam).orEmpty().trim()
        } else {
            ""
        }
    }.getOrDefault("")

    private fun runProcess(args: List<String>, dir: File, timeoutSeconds: Int): RuntimeCommandResult {
        return runCatching {
            val process = ProcessBuilder(args)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                RuntimeCommandResult(124, "timeout")
            } else {
                val out = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()
                RuntimeCommandResult(process.exitValue(), out)
            }
        }.getOrElse { RuntimeCommandResult(1, it.message.orEmpty()) }
    }
}
