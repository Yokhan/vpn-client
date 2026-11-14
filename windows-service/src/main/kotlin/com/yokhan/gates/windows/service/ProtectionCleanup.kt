package com.yokhan.gates.windows.service

import com.yokhan.gates.windows.service.wfp.WfpGuardBackend
import com.yokhan.gates.windows.service.wfp.WindowsFilteringPlatformBackend
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.time.Instant
import java.util.concurrent.TimeUnit

fun cleanupSelectedProtection(
    paths: WindowsServicePaths,
    serviceRunning: () -> Boolean,
    backendFactory: () -> WfpGuardBackend = WindowsFilteringPlatformBackend::create,
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    if (serviceRunning()) throw IOException("GatesVpn service must be stopped before protection cleanup")

    val activeBytes = if (Files.isRegularFile(paths.configFile)) Files.readAllBytes(paths.configFile) else null
    val guardedConfig = activeBytes?.let { bytes ->
        (SingBoxConfigValidator.validate(bytes, paths.runtimeExecutable.toString()) as? ConfigValidationResult.Accepted)
            ?.config?.takeIf { it.protection != null }
    }
    val archive = if (guardedConfig != null && activeBytes != null) {
        archiveAndClearGuardedConfig(paths, activeBytes, nowMillis())
    } else null
    Files.deleteIfExists(paths.standingProtectionFile)

    try {
        val backend = backendFactory()
        try {
            backend.disarm()
            check(backend.inspect().rules.isEmpty()) { "Provider-owned WFP rules remain after cleanup" }
        } finally {
            backend.close()
        }
    } catch (failure: Exception) {
        val archiveMessage = archive?.let { "; guarded desired state is archived at $it" }.orEmpty()
        throw IOException("Protection cleanup failed$archiveMessage", failure)
    }
}

fun gatesVpnServiceIsRunning(): Boolean {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return true
    val systemRoot = System.getenv("SystemRoot") ?: return true
    val sc = java.nio.file.Path.of(systemRoot, "System32", "sc.exe")
    if (!Files.isRegularFile(sc)) return true
    val process = try {
        ProcessBuilder(sc.toString(), "query", "GatesVpn").redirectErrorStream(true).start()
    } catch (_: IOException) {
        return true
    }
    val output = ByteArrayOutputStream()
    val reader = Thread {
        process.inputStream.use { input ->
            val buffer = ByteArray(512)
            while (output.size() <= MAX_SCM_OUTPUT_BYTES) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
        }
    }.apply { isDaemon = true; start() }
    val completed = try { process.waitFor(SCM_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
    if (!completed) process.destroyForcibly()
    runCatching { reader.join(SCM_QUERY_TIMEOUT_MILLIS) }
    if (!completed || reader.isAlive || output.size() > MAX_SCM_OUTPUT_BYTES) return true
    val text = output.toString(UTF_8)
    if (text.contains("FAILED 1060", ignoreCase = true)) return false
    val state = Regex("(?im)^\\s*STATE\\s*:\\s*(\\d+)\\b").find(text)?.groupValues?.get(1)?.toIntOrNull()
        ?: return true
    return state != SCM_STOPPED
}

private fun archiveAndClearGuardedConfig(paths: WindowsServicePaths, bytes: ByteArray, timestamp: Long): java.nio.file.Path {
    val archiveDirectory = paths.dataDirectory.resolve(PROTECTION_ARCHIVE_DIRECTORY)
    Files.createDirectories(archiveDirectory)
    val instant = Instant.ofEpochMilli(timestamp).toString().replace(':', '-')
    var index = 0
    while (true) {
        val suffix = if (index == 0) "" else "-$index"
        val destination = archiveDirectory.resolve("active-guarded-$instant$suffix.json")
        try {
            Files.move(paths.configFile, destination, ATOMIC_MOVE)
            check(bytes.contentEquals(Files.readAllBytes(destination))) {
                "Guarded desired state changed while cleanup was archiving it"
            }
            return destination
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            index++
        }
    }
}

private const val PROTECTION_ARCHIVE_DIRECTORY = "protection-archive"
private const val SCM_QUERY_TIMEOUT_MILLIS = 3_000L
private const val MAX_SCM_OUTPUT_BYTES = 8 * 1024
private const val SCM_STOPPED = 1
