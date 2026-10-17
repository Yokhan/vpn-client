package com.yokhan.gates.windows.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEvent
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEventKind
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticPath
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTransport as IncidentDiagnosticTransport
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTarget as IncidentDiagnosticTarget
import com.yokhan.gates.shared.domain.diagnostics.IncidentPolicy
import com.yokhan.gates.shared.domain.diagnostics.IncidentRecorder
import com.yokhan.gates.shared.domain.diagnostics.IncidentStore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque

enum class ProbeOutcome { SUCCESS, FAILURE, UNAVAILABLE }

enum class DiagnosticTarget { GSTATIC, YOUTUBE }

data class ConnectivityProbe(
    val outcome: ProbeOutcome,
    val target: DiagnosticTarget,
    val curlExit: Int? = null,
    val httpStatus: Int? = null,
    val dnsMs: Long? = null,
    val tcpMs: Long? = null,
    val tlsMs: Long? = null,
    val totalMs: Long? = null,
)

data class ConnectivityObservation(
    val capturedAtMillis: Long,
    val monotonicMillis: Long,
    val sessionId: String,
    val runtimeAlive: Boolean,
    val systemProbe: ConnectivityProbe,
    val proxyProbe: ConnectivityProbe,
    val transport: IncidentDiagnosticTransport = IncidentDiagnosticTransport.UNKNOWN,
    val runtimeFailureCode: Int? = null,
)

enum class ConnectivityCategory { SYSTEM_PATH, PROXY_PATH, BOTH_PATHS, RUNTIME_DOWN }

enum class IncidentTransition { SUSPECTED, CONFIRMED, RECOVERED, SESSION_ENDED }

private data class ConnectivityEvent(
    val transition: IncidentTransition,
    val capturedAtMillis: Long,
    val sessionId: String,
    val category: ConnectivityCategory,
    val durationMillis: Long,
    val systemProbe: ConnectivityProbe? = null,
    val proxyProbe: ConnectivityProbe? = null,
)

private class ConnectivityIncidentTracker {
    private var sessionId: String? = null
    private var incidentStartedAt: Long? = null
    private var incidentCategory: ConnectivityCategory? = null
    private var failureCount = 0
    private var recoveryCount = 0
    private var confirmed = false

    fun observe(observation: ConnectivityObservation): List<ConnectivityEvent> {
        val events = mutableListOf<ConnectivityEvent>()
        if (sessionId != null && sessionId != observation.sessionId) {
            closeSession(observation.capturedAtMillis, observation.monotonicMillis)?.let(events::add)
        }
        sessionId = observation.sessionId

        if (!observation.runtimeAlive) {
            val startedAt = incidentStartedAt ?: observation.monotonicMillis.also {
                incidentStartedAt = it
            }
            incidentCategory = ConnectivityCategory.RUNTIME_DOWN
            failureCount = 2
            recoveryCount = 0
            if (!confirmed) {
                confirmed = true
                events += event(
                    IncidentTransition.CONFIRMED,
                    observation.capturedAtMillis,
                    observation.monotonicMillis,
                    startedAt,
                    observation,
                )
            }
            return events
        }

        if (observation.systemProbe.outcome == ProbeOutcome.UNAVAILABLE ||
            observation.proxyProbe.outcome == ProbeOutcome.UNAVAILABLE
        ) {
            failureCount = 0
            recoveryCount = 0
            return events
        }

        val category = failureCategory(observation)
        if (category != null) {
            recoveryCount = 0
            if (incidentStartedAt == null) {
                incidentStartedAt = observation.monotonicMillis
                incidentCategory = category
                failureCount = 1
                confirmed = false
                events += event(
                    IncidentTransition.SUSPECTED,
                    observation.capturedAtMillis,
                    observation.monotonicMillis,
                    observation.monotonicMillis,
                    observation,
                )
            } else {
                incidentCategory = category
                failureCount += 1
                if (failureCount >= 2 && !confirmed) {
                    confirmed = true
                    events += event(
                        IncidentTransition.CONFIRMED,
                        observation.capturedAtMillis,
                        observation.monotonicMillis,
                        incidentStartedAt!!,
                        observation,
                    )
                }
            }
        } else if (
            observation.systemProbe.outcome == ProbeOutcome.SUCCESS &&
            observation.proxyProbe.outcome == ProbeOutcome.SUCCESS &&
            incidentStartedAt != null
        ) {
            recoveryCount += 1
            failureCount = 0
            if (recoveryCount >= 2) {
                events += event(
                    IncidentTransition.RECOVERED,
                    observation.capturedAtMillis,
                    observation.monotonicMillis,
                    incidentStartedAt!!,
                    observation,
                )
                resetIncident()
            }
        }
        return events
    }

    fun endSession(capturedAtMillis: Long, monotonicMillis: Long): ConnectivityEvent? =
        closeSession(capturedAtMillis, monotonicMillis)

    private fun closeSession(capturedAtMillis: Long, monotonicMillis: Long): ConnectivityEvent? {
        val activeSessionId = sessionId
        val startedAt = incidentStartedAt
        val event = if (activeSessionId != null && startedAt != null) {
            ConnectivityEvent(
                transition = IncidentTransition.SESSION_ENDED,
                capturedAtMillis = capturedAtMillis,
                sessionId = activeSessionId,
                category = incidentCategory ?: ConnectivityCategory.BOTH_PATHS,
                durationMillis = elapsed(monotonicMillis, startedAt),
            )
        } else {
            null
        }
        sessionId = null
        resetIncident()
        return event
    }

    private fun event(
        transition: IncidentTransition,
        capturedAtMillis: Long,
        monotonicMillis: Long,
        startedAt: Long,
        observation: ConnectivityObservation,
    ) = ConnectivityEvent(
        transition = transition,
        capturedAtMillis = capturedAtMillis,
        sessionId = sessionId!!,
        category = incidentCategory ?: ConnectivityCategory.BOTH_PATHS,
        durationMillis = elapsed(monotonicMillis, startedAt),
        systemProbe = observation.systemProbe,
        proxyProbe = observation.proxyProbe,
    )

    private fun resetIncident() {
        incidentStartedAt = null
        incidentCategory = null
        failureCount = 0
        recoveryCount = 0
        confirmed = false
    }

    private fun failureCategory(observation: ConnectivityObservation): ConnectivityCategory? {
        val systemFailed = observation.systemProbe.outcome == ProbeOutcome.FAILURE
        val proxyFailed = observation.proxyProbe.outcome == ProbeOutcome.FAILURE
        val systemSucceeded = observation.systemProbe.outcome == ProbeOutcome.SUCCESS
        val proxySucceeded = observation.proxyProbe.outcome == ProbeOutcome.SUCCESS
        return when {
            systemFailed && proxyFailed -> ConnectivityCategory.BOTH_PATHS
            systemFailed && proxySucceeded -> ConnectivityCategory.SYSTEM_PATH
            proxyFailed && systemSucceeded -> ConnectivityCategory.PROXY_PATH
            else -> null
        }
    }

    private fun elapsed(now: Long, startedAt: Long): Long = (now - startedAt).coerceAtLeast(0L)
}

class ConnectivityHistory(
    directory: Path,
    maxFileBytes: Int = DEFAULT_MAX_FILE_BYTES,
    private val incidentStore: IncidentStore? = null,
) {
    private val directory = directory
    private val maxFileBytes = maxFileBytes.coerceIn(MIN_FILE_BYTES, DEFAULT_MAX_FILE_BYTES)
    private val tracker = ConnectivityIncidentTracker()
    private val pendingEvents = ArrayDeque<ConnectivityEvent>()
    private var incidentRecorder: IncidentRecorder? = null
    private var incidentRecorderRetryAfterNanos = 0L
    private var incidentRecorderInitializationFailure: IOException? = null
    private var incidentSessionId: String? = null
    private var incidentSessionTransport = IncidentDiagnosticTransport.UNKNOWN

    @Synchronized
    fun record(observation: ConnectivityObservation) {
        validate(observation)
        val incidentFailure = runCatching { recordIncidentObservation(observation) }.exceptionOrNull()
        flushPendingEvents()
        enqueueEvents(tracker.observe(observation))
        flushPendingEvents()
        append(SAMPLES_FILE, PREVIOUS_SAMPLES_FILE, sampleJson(observation).toByteArray(UTF_8))
        incidentFailure?.let { throw it }
    }

    @Synchronized
    fun observeSession(
        sessionId: String,
        capturedAtMillis: Long,
        monotonicMillis: Long,
        transport: IncidentDiagnosticTransport = IncidentDiagnosticTransport.UNKNOWN,
    ) {
        require(SESSION_ID.matches(sessionId)) { "Session id must be a lowercase SHA-256 hex digest" }
        require(capturedAtMillis >= 0L && monotonicMillis >= 0L) { "Clock values must be non-negative" }
        val recorder = incidentRecorder() ?: return
        recordIncidentSession(recorder, sessionId, capturedAtMillis, monotonicMillis, transport)
    }

    @Synchronized
    fun endSession(capturedAtMillis: Long, monotonicMillis: Long) {
        require(capturedAtMillis >= 0L && monotonicMillis >= 0L) { "Clock values must be non-negative" }
        val incidentFailure = runCatching {
            incidentSessionId?.let { sessionId ->
                incidentRecorder()?.let { recorder ->
                    recorder.endSession(
                        DiagnosticEvent(
                            timestampMillis = capturedAtMillis,
                            monotonicMillis = monotonicMillis,
                            sessionId = sessionId,
                            kind = DiagnosticEventKind.SESSION_ENDED,
                            transport = incidentSessionTransport,
                        ),
                    )
                    incidentSessionId = null
                }
            }
        }.exceptionOrNull()
        val historyFailure = runCatching {
            flushPendingEvents()
            tracker.endSession(capturedAtMillis, monotonicMillis)?.let { enqueueEvents(listOf(it)) }
            flushPendingEvents()
        }.exceptionOrNull()
        incidentFailure?.let { failure ->
            historyFailure?.let(failure::addSuppressed)
            throw failure
        }
        historyFailure?.let { throw it }
    }

    @Synchronized
    fun tick(capturedAtMillis: Long, monotonicMillis: Long) {
        require(capturedAtMillis >= 0L && monotonicMillis >= 0L) { "Clock values must be non-negative" }
        incidentRecorder()?.tick(capturedAtMillis, monotonicMillis)
    }

    private fun recordIncidentObservation(observation: ConnectivityObservation) {
        val recorder = incidentRecorder() ?: return
        recordIncidentSession(
            recorder,
            observation.sessionId,
            observation.capturedAtMillis,
            observation.monotonicMillis,
            observation.transport,
        )
        if (!observation.runtimeAlive) {
            recorder.record(
                DiagnosticEvent(
                    timestampMillis = observation.capturedAtMillis,
                    monotonicMillis = observation.monotonicMillis,
                    sessionId = observation.sessionId,
                    kind = DiagnosticEventKind.RUNTIME_FAILURE,
                    path = DiagnosticPath.CORE,
                    code = observation.runtimeFailureCode,
                    transport = observation.transport,
                ),
            )
            return
        }
        listOf(
            DiagnosticPath.SYSTEM to observation.systemProbe,
            DiagnosticPath.CORE to observation.proxyProbe,
        ).forEach { (path, probe) ->
            val kind = when (probe.outcome) {
                ProbeOutcome.SUCCESS -> DiagnosticEventKind.PROBE_SUCCESS
                ProbeOutcome.FAILURE -> DiagnosticEventKind.PROBE_FAILURE
                ProbeOutcome.UNAVAILABLE -> DiagnosticEventKind.PROBE_UNAVAILABLE
            }
            recorder.record(
                DiagnosticEvent(
                    timestampMillis = observation.capturedAtMillis,
                    monotonicMillis = observation.monotonicMillis,
                    sessionId = observation.sessionId,
                    kind = kind,
                    transport = observation.transport,
                    target = when (probe.target) {
                        DiagnosticTarget.GSTATIC -> IncidentDiagnosticTarget.GSTATIC
                        DiagnosticTarget.YOUTUBE -> IncidentDiagnosticTarget.YOUTUBE
                    },
                    path = path,
                    dnsMillis = probe.dnsMs,
                    connectMillis = probe.tcpMs,
                    tlsMillis = probe.tlsMs,
                    code = probe.curlExit ?: probe.httpStatus,
                ),
            )
        }
    }

    private fun recordIncidentSession(
        recorder: IncidentRecorder,
        sessionId: String,
        capturedAtMillis: Long,
        monotonicMillis: Long,
        transport: IncidentDiagnosticTransport,
    ) {
        val previousSession = incidentSessionId
        if (previousSession != sessionId) {
            if (previousSession != null) {
                recorder.endSession(
                    DiagnosticEvent(
                        timestampMillis = capturedAtMillis,
                        monotonicMillis = monotonicMillis,
                        sessionId = previousSession,
                        kind = DiagnosticEventKind.SESSION_ENDED,
                        transport = incidentSessionTransport,
                    ),
                )
                incidentSessionId = null
            }
            recorder.record(
                DiagnosticEvent(
                    timestampMillis = capturedAtMillis,
                    monotonicMillis = monotonicMillis,
                    sessionId = sessionId,
                    kind = DiagnosticEventKind.SESSION_STARTED,
                    transport = transport,
                ),
            )
            incidentSessionId = sessionId
            incidentSessionTransport = transport
        }
    }

    private fun incidentRecorder(): IncidentRecorder? {
        incidentRecorder?.let { return it }
        val store = incidentStore ?: return null
        val nowNanos = System.nanoTime()
        if (incidentRecorderRetryAfterNanos - nowNanos > 0L) {
            throw IOException("Native incident recorder is waiting to retry initialization", incidentRecorderInitializationFailure)
        }
        return try {
            IncidentRecorder(IncidentPolicy(), store) { java.util.UUID.randomUUID().toString().replace("-", "") }
                .also {
                    incidentRecorder = it
                    incidentRecorderRetryAfterNanos = 0L
                    incidentRecorderInitializationFailure = null
                }
        } catch (error: Exception) {
            incidentRecorderRetryAfterNanos = System.nanoTime() + INCIDENT_RECORDER_RETRY_NANOS
            val failure = IOException("Native incident recorder initialization failed", error)
            incidentRecorderInitializationFailure = failure
            throw failure
        }
    }

    private fun enqueueEvents(events: List<ConnectivityEvent>) {
        // A failed flush stops tracker advancement; only one observation's transitions can remain pending.
        check(pendingEvents.size + events.size <= MAX_PENDING_EVENTS) { "Pending connectivity event limit exceeded" }
        pendingEvents.addAll(events)
    }

    private fun flushPendingEvents() {
        while (pendingEvents.isNotEmpty()) {
            append(EVENTS_FILE, PREVIOUS_EVENTS_FILE, eventJson(pendingEvents.getFirst()).toByteArray(UTF_8))
            pendingEvents.removeFirst()
        }
    }

    @Synchronized
    fun recentEvents(maxBytes: Int = DEFAULT_RECENT_BYTES): String {
        require(maxBytes in MIN_RECENT_BYTES..MAX_RECENT_BYTES) {
            "Recent event byte limit must be between $MIN_RECENT_BYTES and $MAX_RECENT_BYTES"
        }
        val byteLimit = maxBytes
        val readLimit = minOf(byteLimit, maxFileBytes * 2)
        var remaining = readLimit
        val current = readTail(directory.resolve(EVENTS_FILE), remaining)
        remaining -= current.bytesRead
        val previous = readTail(directory.resolve(PREVIOUS_EVENTS_FILE), remaining)
        val records = (previous.lines + current.lines).mapNotNull(::parseEvent)
        val selected = mutableListOf<String>()
        for (record in records.asReversed()) {
            val candidate = (listOf(record) + selected).joinToString(prefix = "[", postfix = "]", separator = ",")
            if (candidate.toByteArray(UTF_8).size > byteLimit) break
            selected.add(0, record)
        }
        return selected.joinToString(prefix = "[", postfix = "]", separator = ",")
    }

    private fun validate(observation: ConnectivityObservation) {
        require(observation.capturedAtMillis >= 0L && observation.monotonicMillis >= 0L) {
            "Clock values must be non-negative"
        }
        require(SESSION_ID.matches(observation.sessionId)) { "Session id must be a lowercase SHA-256 hex digest" }
        listOf(observation.systemProbe, observation.proxyProbe).forEach { probe ->
            require(probe.curlExit == null || probe.curlExit >= 0) { "Curl exit code must be non-negative" }
            require(probe.httpStatus == null || probe.httpStatus == 0 || probe.httpStatus in 100..599) {
                "HTTP status must be valid"
            }
            listOf(probe.dnsMs, probe.tcpMs, probe.tlsMs, probe.totalMs).forEach { value ->
                require(value == null || value >= 0L) { "Probe durations must be non-negative" }
            }
        }
    }

    private fun append(currentName: String, previousName: String, line: ByteArray) {
        require(line.size + 1 <= maxFileBytes) { "Record exceeds the configured history file bound" }
        Files.createDirectories(directory)
        val current = directory.resolve(currentName)
        val previous = directory.resolve(previousName)
        val currentSize = if (Files.exists(current)) Files.size(current) else 0L
        val newline = byteArrayOf('\n'.code.toByte())
        var needsSeparator = false
        if (currentSize > 0L) {
            val lastByte = ByteBuffer.allocate(1)
            Files.newByteChannel(current).use { channel ->
                channel.position(currentSize - 1L)
                check(channel.read(lastByte) == 1) { "History journal changed during append" }
            }
            needsSeparator = lastByte.get(0) != newline[0]
        }
        if (currentSize + line.size + 1 + (if (needsSeparator) 1 else 0) > maxFileBytes) {
            Files.deleteIfExists(previous)
            Files.move(current, previous, StandardCopyOption.REPLACE_EXISTING)
            needsSeparator = false
        }
        val prefix = if (needsSeparator) newline else byteArrayOf()
        Files.write(current, prefix + line + newline, CREATE, APPEND)
    }

    private fun sampleJson(observation: ConnectivityObservation): String {
        val json = JsonObject().apply {
            addProperty("capturedAtMillis", observation.capturedAtMillis)
            addProperty("monotonicMillis", observation.monotonicMillis)
            addProperty("sessionId", observation.sessionId)
            addProperty("runtimeAlive", observation.runtimeAlive)
            observationCategory(observation)?.let { addProperty("category", it.name) }
            add("systemProbe", probeJson(observation.systemProbe))
            add("proxyProbe", probeJson(observation.proxyProbe))
        }
        return json.toString()
    }

    private fun observationCategory(observation: ConnectivityObservation): ConnectivityCategory? = when {
        !observation.runtimeAlive -> ConnectivityCategory.RUNTIME_DOWN
        observation.systemProbe.outcome == ProbeOutcome.FAILURE &&
            observation.proxyProbe.outcome == ProbeOutcome.FAILURE -> ConnectivityCategory.BOTH_PATHS
        observation.systemProbe.outcome == ProbeOutcome.FAILURE &&
            observation.proxyProbe.outcome == ProbeOutcome.SUCCESS -> ConnectivityCategory.SYSTEM_PATH
        observation.proxyProbe.outcome == ProbeOutcome.FAILURE &&
            observation.systemProbe.outcome == ProbeOutcome.SUCCESS -> ConnectivityCategory.PROXY_PATH
        else -> null
    }

    private fun probeJson(probe: ConnectivityProbe) = JsonObject().apply {
        addProperty("outcome", probe.outcome.name)
        addProperty("target", probe.target.name)
        probe.curlExit?.let { addProperty("curlExit", it) }
        probe.httpStatus?.let { addProperty("httpStatus", it) }
        probe.dnsMs?.let { addProperty("dnsMs", it) }
        probe.tcpMs?.let { addProperty("tcpMs", it) }
        probe.tlsMs?.let { addProperty("tlsMs", it) }
        probe.totalMs?.let { addProperty("totalMs", it) }
    }

    private fun eventJson(event: ConnectivityEvent) = JsonObject().apply {
        addProperty("transition", event.transition.name)
        addProperty("capturedAtMillis", event.capturedAtMillis)
        addProperty("sessionId", event.sessionId)
        addProperty("category", event.category.name)
        addProperty("durationMillis", event.durationMillis)
        event.systemProbe?.let { add("systemProbe", probeJson(it)) }
        event.proxyProbe?.let { add("proxyProbe", probeJson(it)) }
    }.toString()

    private fun parseEvent(line: String): String? {
        return runCatching {
            val source = JsonParser.parseString(line).asJsonObject
            val transition = IncidentTransition.valueOf(source.get("transition").asString)
            val capturedAt = source.get("capturedAtMillis").asLong
            val sessionId = source.get("sessionId").asString
            val category = ConnectivityCategory.valueOf(source.get("category").asString)
            val duration = source.get("durationMillis").asLong
            require(capturedAt >= 0L && duration >= 0L && SESSION_ID.matches(sessionId))
            val systemProbe = source.get("systemProbe")?.let(::parseProbe)
            val proxyProbe = source.get("proxyProbe")?.let(::parseProbe)
            require((systemProbe == null) == (proxyProbe == null))
            require(transition == IncidentTransition.SESSION_ENDED || systemProbe != null)
            require(transition != IncidentTransition.SESSION_ENDED || systemProbe == null)
            eventJson(
                ConnectivityEvent(
                    transition,
                    capturedAt,
                    sessionId,
                    category,
                    duration,
                    systemProbe,
                    proxyProbe,
                ),
            )
        }.getOrNull()
    }

    private fun parseProbe(source: com.google.gson.JsonElement): ConnectivityProbe = run {
        val json = source.asJsonObject
        fun optionalInt(name: String): Int? = json.get(name)?.takeUnless { it.isJsonNull }?.asInt
        fun optionalLong(name: String): Long? = json.get(name)?.takeUnless { it.isJsonNull }?.asLong
        val probe = ConnectivityProbe(
            outcome = ProbeOutcome.valueOf(json.get("outcome").asString),
            target = DiagnosticTarget.valueOf(json.get("target").asString),
            curlExit = optionalInt("curlExit"),
            httpStatus = optionalInt("httpStatus"),
            dnsMs = optionalLong("dnsMs"),
            tcpMs = optionalLong("tcpMs"),
            tlsMs = optionalLong("tlsMs"),
            totalMs = optionalLong("totalMs"),
        )
        require(probe.curlExit == null || probe.curlExit >= 0)
        require(probe.httpStatus == null || probe.httpStatus == 0 || probe.httpStatus in 100..599)
        require(listOf(probe.dnsMs, probe.tcpMs, probe.tlsMs, probe.totalMs).all { it == null || it >= 0L })
        probe
    }

    private fun readTail(path: Path, maxBytes: Int): Tail {
        if (maxBytes <= 0 || !Files.exists(path)) return Tail(emptyList(), 0)
        val size = Files.size(path)
        val count = minOf(size, maxBytes.toLong()).toInt()
        val offset = size - count
        val bytes = Files.newInputStream(path).use { input ->
            input.skipNBytes(offset)
            input.readNBytes(count)
        }
        val text = String(bytes, UTF_8)
        val lines = text.split('\n').let { parts ->
            if (offset > 0L) parts.drop(1) else parts
        }.filter(String::isNotBlank)
        return Tail(lines, bytes.size)
    }

    private data class Tail(val lines: List<String>, val bytesRead: Int)

    private companion object {
        const val DEFAULT_MAX_FILE_BYTES = 524_288
        const val MIN_FILE_BYTES = 1_024
        const val MIN_RECENT_BYTES = 2
        const val DEFAULT_RECENT_BYTES = 12_000
        const val MAX_RECENT_BYTES = 65_536
        const val MAX_PENDING_EVENTS = 32
        const val INCIDENT_RECORDER_RETRY_NANOS = 30_000_000_000L
        const val SAMPLES_FILE = "connectivity-samples.jsonl"
        const val PREVIOUS_SAMPLES_FILE = "connectivity-samples.previous.jsonl"
        const val EVENTS_FILE = "connectivity-events.jsonl"
        const val PREVIOUS_EVENTS_FILE = "connectivity-events.previous.jsonl"
        val SESSION_ID = Regex("[0-9a-f]{64}")
    }
}
