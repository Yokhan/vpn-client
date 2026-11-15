package io.nekohasekai.sagernet.bg.proto

import android.os.Build
import android.system.Os
import android.system.OsConstants
import io.nekohasekai.sagernet.fmt.xray.XrayConfigException
import io.nekohasekai.sagernet.fmt.xray.XrayConfigFiles
import io.nekohasekai.sagernet.fmt.xray.XrayRouteSpec
import java.io.File
import java.io.IOException
import java.util.ArrayDeque

data class XrayRestartPolicy(
    val readinessTimeoutMillis: Long = 3_000,
    val readinessPollMillis: Long = 100,
    val shutdownGraceMillis: Long = 3_000,
    val maxRestartsPerWindow: Int = 3,
    val restartWindowMillis: Long = 60_000,
    val initialBackoffMillis: Long = 1_000,
    val maxBackoffMillis: Long = 4_000,
)

/**
 * Owns one Xray child process for one active XHTTP route.
 *
 * Design constraints taken from the E3 plan and the 2026-11-09 spike report:
 *  - readiness is required before sing-box starts accepting traffic (≤ 3 s, 100 ms poll);
 *  - a fast exit or a not-ready child gets exactly one retry, then the route fails closed;
 *  - later crashes restart with bounded backoff (1 s, 2 s, 4 s; max 3 attempts/60 s), then fail;
 *  - stop terminates the child and deletes the config, in that order;
 *  - nothing here writes credentials to logs; config and binary paths only.
 *
 * All side effects (process, readiness, clock, sleep) are injectable so the lifecycle is testable on
 * a JVM without a device.
 */
class XraySidecarSupervisor(
    private val executable: File,
    private val engineVersion: String,
    private val binarySha256: String? = null,
    private val adapterVersion: String = ADAPTER_VERSION,
    private val launcher: XrayProcessLauncher = ProcessBuilderXrayLauncher(),
    private val readinessProbe: XrayReadinessProbe = TcpXrayReadinessProbe(),
    private val logger: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val policy: XrayRestartPolicy = XrayRestartPolicy(),
) : XraySidecar {

    companion object {
        const val ADAPTER_VERSION = "android-xray-sidecar/0.1.0-spike"
        private const val TAG = "[xray]"
        private const val START_ATTEMPTS = 2
        private const val MAX_CHILD_LOG_LINE = 512
        private const val LOOPBACK = "127.0.0.1"
    }

    override val descriptor = XraySidecarDescriptor(
        id = "xray-xhttp",
        kind = XraySidecarKind.Xray,
        adapterVersion = adapterVersion,
        capabilities = setOf(
            XraySidecarCapability.SocksEgress,
            XraySidecarCapability.ProcessSidecar,
            XraySidecarCapability.ActiveVerify,
        ),
    )

    private var process: XrayChildProcess? = null
    private var spec: XrayRouteSpec? = null
    private var configFile: File? = null
    private var state: XraySidecarState = XraySidecarState.Stopped
    private var stateSince: Long = clock()
    private var epoch: Long = 0
    private var totalRestarts: Int = 0
    private var socks: XrayLoopbackEndpoint? = null
    private var lastFailure: XrayFailure? = null
    private val restarts = ArrayDeque<Long>()

    override fun start(spec: XrayRouteSpec, configDir: File): XrayStartResult {
        if (state == XraySidecarState.Running || state == XraySidecarState.Degraded) {
            val current = socks
            if (current != null && process?.isAlive() == true) {
                return XrayStartResult.Ready(epoch, current)
            }
        }
        if (state == XraySidecarState.Starting) {
            return XrayStartResult.Rejected(XrayFailure(XrayFailureCode.Internal, "sidecar is already starting"))
        }

        resetRuntime(removeConfig = true)
        this.spec = spec
        restarts.clear()
        epoch += 1
        changeState(XraySidecarState.Starting)

        val file = try {
            XrayConfigFiles.write(configDir, spec, clock())
        } catch (e: XrayConfigException) {
            return failStart(XrayFailureCode.ConfigRejected, "child config rejected")
        } catch (e: IOException) {
            return failStart(XrayFailureCode.ConfigRejected, "cannot write child config")
        } catch (e: SecurityException) {
            return failStart(XrayFailureCode.ConfigRejected, "cannot write child config")
        }
        configFile = file

        log("start binary=${executable.absolutePath} config=${file.absolutePath}")
        var attempt = 0
        var lastCode = XrayFailureCode.NotReady
        var lastDetail = "sidecar did not become ready"
        while (attempt < START_ATTEMPTS) {
            attempt += 1
            if (attempt > 1) {
                log("retrying once after a start failure")
            }
            val launched = try {
                launcher.launch(
                    command = command(file),
                    workingDir = configDir,
                    onStdout = ::logChildLine,
                    onStderr = ::logChildLine,
                )
            } catch (e: IOException) {
                lastCode = XrayFailureCode.StartFailed
                lastDetail = "cannot execute the sidecar binary"
                break
            } catch (e: SecurityException) {
                lastCode = XrayFailureCode.StartFailed
                lastDetail = "cannot execute the sidecar binary"
                break
            }
            process = launched

            val ready = readinessProbe.awaitReady(
                host = LOOPBACK,
                port = spec.sidecarPort,
                timeoutMillis = policy.readinessTimeoutMillis,
                pollMillis = policy.readinessPollMillis,
                isAlive = launched::isAlive,
            )
            if (ready) {
                socks = XrayLoopbackEndpoint(port = spec.sidecarPort)
                lastFailure = null
                stateSince = clock()
                changeState(XraySidecarState.Running)
                log("ready on loopback:${spec.sidecarPort}")
                return XrayStartResult.Ready(epoch, socks!!)
            }

            val exitedFast = !launched.isAlive()
            lastCode = if (exitedFast) XrayFailureCode.Crashed else XrayFailureCode.NotReady
            lastDetail = if (exitedFast) {
                "sidecar exited before becoming ready"
            } else {
                "sidecar did not become ready in time"
            }
            terminateQuietly(launched)
            process = null
        }

        return failStart(lastCode, lastDetail)
    }

    override fun stop(reason: XrayStopReason) {
        if (state == XraySidecarState.Stopped) {
            resetRuntime(removeConfig = true)
            return
        }
        log("stop reason=$reason")
        resetRuntime(removeConfig = true)
        lastFailure = null
        changeState(XraySidecarState.Stopped)
    }

    override fun health(): XraySidecarHealth = XraySidecarHealth(
        state = state,
        sinceMillis = stateSince,
        restarts = totalRestarts,
        lastFailure = lastFailure,
    )

    override fun version(): XrayTransportVersion = XrayTransportVersion(
        engine = "xray",
        engineVersion = engineVersion,
        adapterVersion = adapterVersion,
        binarySha256 = binarySha256,
    )

    override fun localSocks(): XrayLoopbackEndpoint? =
        if (state == XraySidecarState.Running || state == XraySidecarState.Degraded) socks else null

    override fun tick(nowMillis: Long) {
        if (state != XraySidecarState.Running && state != XraySidecarState.Degraded) return
        val current = process
        if (current != null && current.isAlive()) {
            if (state == XraySidecarState.Degraded) changeState(XraySidecarState.Running)
            return
        }

        val activeSpec = spec
        val file = configFile
        if (activeSpec == null || file == null) {
            failRuntime("sidecar crashed and the config is gone")
            return
        }

        pruneRestartWindow(nowMillis)
        if (restarts.size >= policy.maxRestartsPerWindow) {
            failRuntime("sidecar restart budget exhausted")
            return
        }

        val attempt = restarts.size + 1
        val backoff = backoffMillis(attempt)
        log("sidecar exited; restart $attempt/${policy.maxRestartsPerWindow} in ${backoff}ms")
        sleeper(backoff)

        val launched = try {
            launcher.launch(
                command = command(file),
                workingDir = file.parentFile,
                onStdout = ::logChildLine,
                onStderr = ::logChildLine,
            )
        } catch (e: IOException) {
            restarts.addLast(nowMillis)
            failRuntime("cannot restart the sidecar binary")
            return
        } catch (e: SecurityException) {
            restarts.addLast(nowMillis)
            failRuntime("cannot restart the sidecar binary")
            return
        }
        process = launched
        restarts.addLast(nowMillis)
        totalRestarts += 1
        stateSince = clock()

        val ready = readinessProbe.awaitReady(
            host = LOOPBACK,
            port = activeSpec.sidecarPort,
            timeoutMillis = policy.readinessTimeoutMillis,
            pollMillis = policy.readinessPollMillis,
            isAlive = launched::isAlive,
        )
        if (ready) {
            socks = XrayLoopbackEndpoint(port = activeSpec.sidecarPort)
            changeState(XraySidecarState.Running)
            log("recovered on loopback:${activeSpec.sidecarPort}")
        } else {
            terminateQuietly(launched)
            process = null
            lastFailure = XrayFailure(XrayFailureCode.Crashed, "sidecar restart did not become ready")
            changeState(XraySidecarState.Degraded)
        }
    }

    private fun failStart(code: XrayFailureCode, detail: String): XrayStartResult {
        val failure = XrayFailure(code, detail)
        lastFailure = failure
        resetRuntime(removeConfig = true)
        changeState(XraySidecarState.Failed)
        log("start failed code=$code")
        return XrayStartResult.Rejected(failure)
    }

    private fun failRuntime(detail: String) {
        lastFailure = XrayFailure(XrayFailureCode.Crashed, detail)
        log("runtime failure: $detail")
        resetRuntime(removeConfig = true)
        changeState(XraySidecarState.Failed)
    }

    private fun resetRuntime(removeConfig: Boolean) {
        val running = process
        process = null
        running?.let { terminateQuietly(it) }
        if (removeConfig) {
            XrayConfigFiles.delete(configFile)
            configFile = null
        }
        spec = null
        socks = null
    }

    private fun terminateQuietly(target: XrayChildProcess) {
        if (!target.isAlive()) return
        if (!target.terminate(policy.shutdownGraceMillis)) {
            target.destroyForcibly()
        }
    }

    private fun pruneRestartWindow(nowMillis: Long) {
        while (restarts.isNotEmpty() && nowMillis - restarts.first() > policy.restartWindowMillis) {
            restarts.removeFirst()
        }
    }

    private fun backoffMillis(attempt: Int): Long {
        var value = policy.initialBackoffMillis
        repeat(attempt - 1) {
            value = (value * 2).coerceAtMost(policy.maxBackoffMillis)
        }
        return value.coerceAtMost(policy.maxBackoffMillis)
    }

    private fun command(file: File): List<String> =
        listOf(executable.absolutePath, "run", "-c", file.absolutePath)

    private fun logChildLine(line: String) {
        val clean = line.filter { it.code >= 0x20 || it == '\t' }.take(MAX_CHILD_LOG_LINE)
        if (clean.isNotBlank()) log(clean)
    }

    private fun log(message: String) {
        logger("$TAG $message")
    }

    private fun changeState(next: XraySidecarState) {
        state = next
        stateSince = clock()
    }

}

/** Real launcher: child of `:bg`, stdout/stderr forwarded line by line to the app log. */
class ProcessBuilderXrayLauncher : XrayProcessLauncher {

    override fun launch(
        command: List<String>,
        workingDir: File?,
        onStdout: (String) -> Unit,
        onStderr: (String) -> Unit,
    ): XrayChildProcess {
        val builder = ProcessBuilder(command)
        if (workingDir != null) builder.directory(workingDir)
        val started = builder.start()
        pump(started.inputStream.bufferedReader(), onStdout)
        pump(started.errorStream.bufferedReader(), onStderr)
        return ProcessBuilderXrayChild(started)
    }

    private fun pump(reader: java.io.BufferedReader, sink: (String) -> Unit) {
        val thread = Thread {
            try {
                reader.forEachLine(sink)
            } catch (_: IOException) {
            }
        }
        thread.isDaemon = true
        thread.name = "xray-child"
        thread.start()
    }
}

class ProcessBuilderXrayChild(private val process: Process) : XrayChildProcess {

    override val pid: Long by lazy {
        try {
            val impl = Class.forName("java.lang.ProcessManager\$ProcessImpl")
            val field = impl.getDeclaredField("pid").apply { isAccessible = true }
            (field.get(process) as Int).toLong()
        } catch (_: Throwable) {
            -1L
        }
    }

    override fun isAlive(): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    override fun terminate(graceMillis: Long): Boolean {
        if (!isAlive()) return true
        process.destroy()
        val deadline = System.currentTimeMillis() + graceMillis
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive()) return true
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return !isAlive()
    }

    override fun destroyForcibly() {
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching { process.destroyForcibly() }
            return
        }
        // destroyForcibly() is API 26+; mirror GuardedProcessPool's fallback on older devices.
        if (pid > 0) {
            runCatching { Os.kill(pid.toInt(), OsConstants.SIGKILL) }
        }
    }
}

/** Polls the loopback SOCKS port; a TCP accept is the readiness signal. */
class TcpXrayReadinessProbe(
    private val connectTimeoutMillis: Int = 200,
) : XrayReadinessProbe {

    override fun awaitReady(
        host: String,
        port: Int,
        timeoutMillis: Long,
        pollMillis: Long,
        isAlive: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive()) return false
            if (canConnect(host, port)) return true
            try {
                Thread.sleep(pollMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun canConnect(host: String, port: Int): Boolean = try {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), connectTimeoutMillis)
        }
        true
    } catch (_: IOException) {
        false
    }
}
