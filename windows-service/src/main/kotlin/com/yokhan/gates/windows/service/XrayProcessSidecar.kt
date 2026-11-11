package com.yokhan.gates.windows.service

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Observable lifecycle of the optional Xray XHTTP sidecar. */
enum class XhttpSidecarState {
    DISABLED,
    STOPPED,
    STARTING,
    READY,
    FAILED,
}

enum class SidecarFailure {
    NOT_SUPPORTED,
    EXECUTABLE_MISSING,
    HASH_MISMATCH,
    PORT_IN_USE,
    CONFIG_WRITE_FAILED,
    PROCESS_EXITED,
    NOT_READY,
}

sealed interface SidecarStartResult {
    data object Started : SidecarStartResult
    data object AlreadyRunning : SidecarStartResult
    data class Failed(val reason: SidecarFailure) : SidecarStartResult
}

/**
 * Process owner for the loopback Xray sidecar.
 *
 * The sidecar never owns a TUN interface, a DNS listener or routing rules; it
 * only accepts SOCKS5 on loopback and carries VLESS/XHTTP to the configured
 * entry. sing-box remains the single TUN/DNS owner.
 */
interface XraySidecar {
    fun preflight(spec: XhttpSidecarSpec?): SidecarFailure?
    fun start(spec: XhttpSidecarSpec): SidecarStartResult
    fun isHealthy(): Boolean
    fun stop()
    fun state(): XhttpSidecarState
    fun lastFailure(): SidecarFailure?
}

object DisabledXraySidecar : XraySidecar {
    override fun preflight(spec: XhttpSidecarSpec?): SidecarFailure? =
        if (spec == null) null else SidecarFailure.NOT_SUPPORTED

    override fun start(spec: XhttpSidecarSpec): SidecarStartResult =
        SidecarStartResult.Failed(SidecarFailure.NOT_SUPPORTED)

    override fun isHealthy(): Boolean = false
    override fun stop() = Unit
    override fun state(): XhttpSidecarState = XhttpSidecarState.DISABLED
    override fun lastFailure(): SidecarFailure? = null
}

internal interface SidecarProcessHandle {
    val isAlive: Boolean
    fun stop(timeoutMillis: Long)
}

internal fun interface SidecarProcessFactory {
    fun start(executable: Path, configFile: Path, logFile: Path): SidecarProcessHandle
}

internal object DefaultSidecarProcessFactory : SidecarProcessFactory {
    override fun start(executable: Path, configFile: Path, logFile: Path): SidecarProcessHandle {
        val process = ProcessBuilder(
            executable.toString(),
            "run",
            "-c",
            configFile.toString(),
        )
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
            .start()
        return object : SidecarProcessHandle {
            override val isAlive: Boolean get() = process.isAlive

            override fun stop(timeoutMillis: Long) {
                process.destroy()
                if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                }
            }
        }
    }
}

internal class XrayProcessSidecar(
    private val paths: WindowsServicePaths,
    private val processFactory: SidecarProcessFactory = DefaultSidecarProcessFactory,
    private val portIsFree: (Int) -> Boolean = ::isLoopbackPortFree,
    private val readinessProbe: (Int) -> Boolean = ::isLoopbackPortConnectable,
    private val readinessTimeoutMillis: Long = READY_TIMEOUT_MILLIS,
) : XraySidecar {
    private val lock = Any()
    private val hashCache = ConcurrentHashMap<String, String>()
    private var process: SidecarProcessHandle? = null
    private var runningSpec: XhttpSidecarSpec? = null
    private var currentState = XhttpSidecarState.STOPPED
    private var failure: SidecarFailure? = null

    override fun preflight(spec: XhttpSidecarSpec?): SidecarFailure? {
        return synchronized(lock) {
            if (spec == null) return null
            if (!Files.isRegularFile(paths.xrayExecutable)) return SidecarFailure.EXECUTABLE_MISSING
            val expected = spec.executableSha256
            val actual = executableSha256(paths.xrayExecutable) ?: return SidecarFailure.EXECUTABLE_MISSING
            if (!actual.equals(expected, ignoreCase = true)) return SidecarFailure.HASH_MISMATCH
            null
        }
    }

    override fun start(spec: XhttpSidecarSpec): SidecarStartResult {
        return synchronized(lock) {
            val live = process
            if (live != null && live.isAlive && runningSpec == spec && isReady()) {
                currentState = XhttpSidecarState.READY
                return SidecarStartResult.AlreadyRunning
            }
            stopLocked()
            preflight(spec)?.let { reason ->
                failure = reason
                currentState = XhttpSidecarState.FAILED
                return SidecarStartResult.Failed(reason)
            }
            if (!portIsFree(spec.listenPort)) {
                stopStaleProcesses()
                if (!portIsFree(spec.listenPort)) {
                    failure = SidecarFailure.PORT_IN_USE
                    currentState = XhttpSidecarState.FAILED
                    return SidecarStartResult.Failed(SidecarFailure.PORT_IN_USE)
                }
            }
            val configFile = paths.xhttpDirectory.resolve(RUNTIME_CONFIG_FILE)
            try {
                Files.createDirectories(paths.xhttpDirectory)
                writeConfigAtomically(configFile, spec.xrayConfigJson)
                rotateLog(paths.xhttpDirectory.resolve(LOG_FILE))
            } catch (_: Exception) {
                failure = SidecarFailure.CONFIG_WRITE_FAILED
                currentState = XhttpSidecarState.FAILED
                return SidecarStartResult.Failed(SidecarFailure.CONFIG_WRITE_FAILED)
            }
            currentState = XhttpSidecarState.STARTING
            failure = null
            val started = try {
                processFactory.start(paths.xrayExecutable, configFile, paths.xhttpDirectory.resolve(LOG_FILE))
            } catch (_: Exception) {
                failure = SidecarFailure.PROCESS_EXITED
                currentState = XhttpSidecarState.FAILED
                return SidecarStartResult.Failed(SidecarFailure.PROCESS_EXITED)
            }
            process = started
            runningSpec = spec
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readinessTimeoutMillis)
            while (System.nanoTime() < deadline) {
                if (!started.isAlive) {
                    stopLocked()
                    failure = SidecarFailure.PROCESS_EXITED
                    currentState = XhttpSidecarState.FAILED
                    return SidecarStartResult.Failed(SidecarFailure.PROCESS_EXITED)
                }
                if (isReady(spec.listenPort)) {
                    currentState = XhttpSidecarState.READY
                    return SidecarStartResult.Started
                }
                try {
                    Thread.sleep(READY_POLL_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    stopLocked()
                    failure = SidecarFailure.NOT_READY
                    currentState = XhttpSidecarState.FAILED
                    return SidecarStartResult.Failed(SidecarFailure.NOT_READY)
                }
            }
            stopLocked()
            failure = SidecarFailure.NOT_READY
            currentState = XhttpSidecarState.FAILED
            SidecarStartResult.Failed(SidecarFailure.NOT_READY)
        }
    }

    override fun isHealthy(): Boolean {
        return synchronized(lock) {
            val live = process ?: return false
            live.isAlive && isReady(runningSpec?.listenPort)
        }
    }

    override fun stop() = synchronized(lock) { stopLocked() }

    override fun state(): XhttpSidecarState = synchronized(lock) { currentState }

    override fun lastFailure(): SidecarFailure? = synchronized(lock) { failure }

    private fun stopLocked() {
        val live = process
        process = null
        runningSpec = null
        try {
            live?.stop(STOP_TIMEOUT_MILLIS)
        } catch (_: Exception) {
        }
        runCatching { Files.deleteIfExists(paths.xhttpDirectory.resolve(RUNTIME_CONFIG_FILE)) }
        if (currentState != XhttpSidecarState.FAILED) currentState = XhttpSidecarState.STOPPED
    }

    private fun isReady(): Boolean = isReady(runningSpec?.listenPort)

    private fun isReady(port: Int?): Boolean {
        if (port == null) return false
        return readinessProbe(port)
    }

    private fun stopStaleProcesses() {
        val expected = runCatching { paths.xrayExecutable.toAbsolutePath().normalize().toString() }.getOrNull() ?: return
        ProcessHandle.allProcesses().forEach { handle ->
            val command = runCatching { handle.info().command().orElse("") }.getOrDefault("")
            if (command.equals(expected, ignoreCase = true) || command.equals(paths.xrayExecutable.toString(), ignoreCase = true)) {
                runCatching { handle.destroy() }
            }
        }
        Thread.sleep(STALE_STOP_WAIT_MILLIS)
    }

    private fun executableSha256(path: Path): String? = try {
        val size = Files.size(path)
        val modified = Files.getLastModifiedTime(path).toMillis()
        val key = "$path|$size|$modified"
        hashCache[key] ?: MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
            .also { hashCache.clear(); hashCache[key] = it }
    } catch (_: Exception) {
        null
    }

    private fun writeConfigAtomically(file: Path, json: String) {
        val temporary = Files.createTempFile(paths.xhttpDirectory, "xhttp-", ".json")
        try {
            Files.writeString(temporary, json, UTF_8)
            try {
                Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, file, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun rotateLog(file: Path) {
        if (Files.isRegularFile(file) && Files.size(file) > MAX_LOG_BYTES) Files.delete(file)
    }
}

private fun isLoopbackPortFree(port: Int): Boolean = try {
    ServerSocket().use { socket ->
        socket.bind(InetSocketAddress(LOOPBACK, port))
    }
    true
} catch (_: IOException) {
    false
}

private fun isLoopbackPortConnectable(port: Int): Boolean = try {
    Socket().use { socket ->
        socket.connect(InetSocketAddress(LOOPBACK, port), READY_PROBE_TIMEOUT_MILLIS)
    }
    true
} catch (_: Exception) {
    false
}

private const val LOOPBACK = "127.0.0.1"
private const val RUNTIME_CONFIG_FILE = "active.json"
private const val LOG_FILE = "xray.log"
private const val MAX_LOG_BYTES = 5L * 1024 * 1024
private const val READY_TIMEOUT_MILLIS = 6_000L
private const val READY_POLL_MILLIS = 100L
private const val READY_PROBE_TIMEOUT_MILLIS = 250
private const val STOP_TIMEOUT_MILLIS = 3_000L
private const val STALE_STOP_WAIT_MILLIS = 1_000L
