package com.yokhan.gates.windows.service

import com.google.gson.JsonParser
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTransport as IncidentDiagnosticTransport
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface ConnectivityProbeRunner {
    fun probe(target: DiagnosticTarget, proxyPort: Int?): ConnectivityProbe
}

/** Observes only: it cannot start, stop, reload or choose a tunnel. */
class ConnectivityMonitor(
    private val snapshot: () -> TunnelDiagnosticSnapshot?,
    private val runner: ConnectivityProbeRunner,
    private val history: ConnectivityHistory,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val monotonicMillis: () -> Long = diagnosticClock(),
    private val onUnavailable: () -> Unit = { System.err.println("GATES connectivity diagnostics unavailable") },
    private val recordObservation: (ConnectivityObservation) -> Unit = history::record,
) {
    private data class RuntimePoll(val state: TunnelDiagnosticSnapshot?, val wall: Long, val monotonic: Long)
    private val lock = Any()
    private val runtimePolls = ArrayBlockingQueue<RuntimePoll>(16)
    private val captureFailed = AtomicBoolean(false)
    @Volatile private var unavailableReported = false
    private var lastDeadSession: String? = null
    private var targetIndex = 0

    // The reconcile thread may call this: no journal locks, file I/O or probes.
    fun captureRuntime() {
        try {
            val poll = RuntimePoll(snapshot(), wallMillis(), monotonicMillis())
            if (!runtimePolls.offer(poll)) {
                runtimePolls.poll()
                runtimePolls.offer(poll)
            }
        } catch (_: Exception) {
            captureFailed.set(true)
        }
    }

    fun observeRuntime() = safely {
        check(!captureFailed.getAndSet(false)) { "Diagnostic state unavailable" }
        synchronized(lock) {
            while (true) {
                val poll = runtimePolls.poll() ?: break
                val current = poll.state
                if (current == null) {
                    history.endSession(poll.wall, poll.monotonic)
                    lastDeadSession = null
                } else if (!current.runtimeAlive && lastDeadSession != current.sessionId) {
                    val skipped = ConnectivityProbe(ProbeOutcome.UNAVAILABLE, DiagnosticTarget.GSTATIC)
                    recordObservation(ConnectivityObservation(
                        poll.wall, poll.monotonic, current.sessionId, false, skipped, skipped,
                        transport = diagnosticTransport(current.config),
                    ))
                    lastDeadSession = current.sessionId
                } else if (current.runtimeAlive) {
                    history.observeSession(
                        current.sessionId,
                        poll.wall,
                        poll.monotonic,
                        diagnosticTransport(current.config),
                    )
                    lastDeadSession = null
                }
            }
        }
        history.tick(wallMillis(), monotonicMillis())
    }

    fun sample() = safely {
        val before = snapshot() ?: return@safely
        if (!before.runtimeAlive) {
            captureRuntime()
            observeRuntime()
            return@safely
        }
        val target = if (targetIndex++ % 2 == 0) DiagnosticTarget.GSTATIC else DiagnosticTarget.YOUTUBE
        val systemResult = runner.probe(target, null)
        val proxyResult = diagnosticProxyPort(before.config)?.let { runner.probe(target, it) }
            ?: ConnectivityProbe(ProbeOutcome.UNAVAILABLE, target)
        observeRuntime()
        synchronized(lock) {
            // Discard delayed results after an explicit stop or profile replacement.
            val after = snapshot()
            if (after?.sessionId == before.sessionId && after.runtimeAlive) {
                recordObservation(observation(after, systemResult, proxyResult))
            }
        }
    }

    fun endSession() = safely {
        synchronized(lock) { history.endSession(wallMillis(), monotonicMillis()) }
    }

    private fun observation(
        current: TunnelDiagnosticSnapshot,
        systemResult: ConnectivityProbe,
        proxyResult: ConnectivityProbe,
    ) = ConnectivityObservation(
        wallMillis(), monotonicMillis(), current.sessionId, current.runtimeAlive, systemResult, proxyResult,
        transport = diagnosticTransport(current.config),
    )

    private inline fun safely(action: () -> Unit) {
        try {
            action()
            unavailableReported = false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            if (!unavailableReported) runCatching(onUnavailable)
            unavailableReported = true
        }
    }
}

private fun diagnosticClock(): () -> Long {
    val origin = System.nanoTime()
    return { ((System.nanoTime() - origin) / 1_000_000).coerceAtLeast(0) }
}

internal fun diagnosticProxyPort(config: ValidatedSingBoxConfig): Int? = runCatching {
    val root = JsonParser.parseString(config.canonicalJson).asJsonObject
    root.getAsJsonArray("inbounds").firstNotNullOfOrNull { element ->
        val inbound = element.asJsonObject
        if (inbound.get("type")?.asString != "mixed" ||
            inbound.get("tag")?.asString != "mixed-in" ||
            inbound.get("listen")?.asString != "127.0.0.1" ||
            (inbound.getAsJsonArray("users")?.size() ?: 0) != 0
        ) null
        else inbound.get("listen_port")?.asInt?.takeIf { it in 1..65535 }
    }
}.getOrNull()

internal fun diagnosticTransport(config: ValidatedSingBoxConfig): IncidentDiagnosticTransport = runCatching {
    val root = JsonParser.parseString(config.canonicalJson).asJsonObject
    val finalTag = root.getAsJsonObject("route")
        ?.get("final")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
        ?: return@runCatching IncidentDiagnosticTransport.UNKNOWN
    val outbounds = root.getAsJsonArray("outbounds")
        ?: return@runCatching IncidentDiagnosticTransport.UNKNOWN
    val byTag = outbounds.mapNotNull { element ->
        element.takeIf { it.isJsonObject }?.asJsonObject
    }.mapNotNull { outbound ->
        val tag = outbound.get("tag")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
        tag?.let { it to outbound }
    }.groupBy({ it.first }, { it.second })
    val finalOutbound = byTag[finalTag]?.singleOrNull()
        ?: return@runCatching IncidentDiagnosticTransport.UNKNOWN

    fun remoteFamily(type: String?): IncidentDiagnosticTransport? = when (type) {
        "hysteria2" -> IncidentDiagnosticTransport.HYSTERIA2
        "vless" -> IncidentDiagnosticTransport.VLESS
        "naive" -> IncidentDiagnosticTransport.OTHER
        else -> null
    }

    val finalType = finalOutbound.get("type")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString
    remoteFamily(finalType) ?: when (finalType) {
        "selector", "urltest" -> {
            val members = finalOutbound.getAsJsonArray("outbounds")
                ?: return@runCatching IncidentDiagnosticTransport.UNKNOWN
            if (members.size() == 0) return@runCatching IncidentDiagnosticTransport.UNKNOWN
            val families = members.map { member ->
                val tag = member.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString ?: return@runCatching IncidentDiagnosticTransport.UNKNOWN
                val candidate = byTag[tag]?.singleOrNull()
                    ?: return@runCatching IncidentDiagnosticTransport.UNKNOWN
                val candidateType = candidate.get("type")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                remoteFamily(candidateType)
                    ?: return@runCatching IncidentDiagnosticTransport.UNKNOWN
            }.distinct()
            families.singleOrNull() ?: IncidentDiagnosticTransport.UNKNOWN
        }
        else -> IncidentDiagnosticTransport.UNKNOWN
    }
}.getOrDefault(IncidentDiagnosticTransport.UNKNOWN)

class WindowsCurlConnectivityRunner(
    private val executable: Path = Path.of(System.getenv("SystemRoot"), "System32", "curl.exe"),
) : ConnectivityProbeRunner {
    override fun probe(target: DiagnosticTarget, proxyPort: Int?): ConnectivityProbe {
        if (proxyPort != null) require(proxyPort in 1..65535)
        val command = mutableListOf(
            executable.toString(), "--silent", "--max-time", "6", "--connect-timeout", "3",
            "--output", "NUL", "--write-out",
            "%{http_code} %{time_namelookup} %{time_connect} %{time_appconnect} %{time_total}",
        )
        if (proxyPort == null) command += listOf("--noproxy", "*")
        else command += listOf("--noproxy=", "--proxy", "socks5h://127.0.0.1:$proxyPort")
        command += when (target) {
            DiagnosticTarget.GSTATIC -> "https://www.gstatic.com/generate_204"
            DiagnosticTarget.YOUTUBE -> "https://www.youtube.com/generate_204"
        }
        val process = try {
            ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: Exception) {
            return ConnectivityProbe(ProbeOutcome.UNAVAILABLE, target)
        }
        return try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                ConnectivityProbe(ProbeOutcome.FAILURE, target, curlExit = 28, totalMs = 8_000)
            } else {
                val metrics = process.inputStream.readNBytes(512).toString(Charsets.US_ASCII)
                parseConnectivityCurl(target, process.exitValue(), metrics)
            }
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            process.inputStream.close()
        }
    }
}

internal fun parseConnectivityCurl(target: DiagnosticTarget, exit: Int, output: String): ConnectivityProbe {
    val parts = output.trim().split(Regex("\\s+"))
    val times = parts.drop(1).map { it.toDoubleOrNull() }
    if (parts.size != 5 || times.any { it == null || !it.isFinite() || it !in 0.0..60.0 }) {
        return ConnectivityProbe(ProbeOutcome.UNAVAILABLE, target, curlExit = exit)
    }
    val status = parts[0].toIntOrNull()?.takeIf { it in 0..599 }
        ?: return ConnectivityProbe(ProbeOutcome.UNAVAILABLE, target, curlExit = exit)
    return ConnectivityProbe(
        outcome = if (exit == 0 && status == 204) ProbeOutcome.SUCCESS else ProbeOutcome.FAILURE,
        target = target, curlExit = exit, httpStatus = status,
        dnsMs = (times[0]!! * 1_000).toLong(), tcpMs = (times[1]!! * 1_000).toLong(),
        tlsMs = (times[2]!! * 1_000).toLong(), totalMs = (times[3]!! * 1_000).toLong(),
    )
}
