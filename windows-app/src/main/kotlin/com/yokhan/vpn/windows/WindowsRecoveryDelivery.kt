package com.yokhan.gates.windows

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.yokhan.gates.shared.domain.diagnostics.IncidentArchive
import com.yokhan.gates.shared.domain.diagnostics.RecoveryDeliveryQueue
import com.yokhan.gates.shared.domain.diagnostics.RecoveryReport
import com.yokhan.gates.shared.domain.diagnostics.RecoveryDeliveryState
import com.yokhan.gates.shared.domain.diagnostics.RecoveryDeliveryStore
import com.yokhan.gates.shared.domain.diagnostics.compactRecoveryReport
import com.yokhan.gates.windows.service.ConnectivityHistory
import com.yokhan.gates.windows.service.ConnectivityMonitor
import com.yokhan.gates.windows.service.ConnectivityObservation
import com.yokhan.gates.windows.service.ConnectivityProbe
import com.yokhan.gates.windows.service.ConnectivityProbeRunner
import com.yokhan.gates.windows.service.DiagnosticTarget
import com.yokhan.gates.windows.service.ProbeOutcome
import com.yokhan.gates.windows.service.TunnelDiagnosticSnapshot
import com.yokhan.gates.windows.service.WindowsCurlConnectivityRunner
import com.yokhan.gates.windows.service.WindowsIncidentReportsReader
import com.yokhan.gates.windows.service.windowsConnectivityHistory
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.charset.CodingErrorAction
import java.time.Duration
import java.util.UUID
import java.security.MessageDigest

internal class WindowsRecoveryDelivery(
    appDirectory: File,
    serviceConnectivityDirectory: Path?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val coreVersion: String = "unknown",
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    standaloneSnapshot: (String) -> TunnelDiagnosticSnapshot? = { null },
    probeRunner: ConnectivityProbeRunner = WindowsCurlConnectivityRunner(),
    private val send: (ByteArray) -> Boolean = ::sendRecoveryPayload,
) {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val incidentsDirectory = File(appDirectory, "recovery/incidents").toPath()
    private val history = windowsConnectivityHistory(File(appDirectory, "recovery/connectivity").toPath(), incidentsDirectory)
    private val queue by lazy { RecoveryDeliveryQueue(AtomicRecoveryDeliveryStore(appDirectory, gson)) }
    private val archiveDirectories = listOfNotNull(
        incidentsDirectory,
        serviceConnectivityDirectory?.resolve("incidents"),
    ).distinct()
    private val sessionId = MessageDigest.getInstance("SHA-256")
        .digest(UUID.randomUUID().toString().toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun monotonicMillis() = elapsedMillis().coerceAtLeast(0)
    private val monitor = ConnectivityMonitor(
        snapshot = { standaloneSnapshot(sessionId) },
        runner = probeRunner,
        history = history,
        wallMillis = nowMillis,
        monotonicMillis = ::monotonicMillis,
    )

    @Synchronized
    fun recordFailure(stage: RecoveryFailureStage) {
        if (stage != RecoveryFailureStage.CORE_STARTUP) return
        val timestamp = nowMillis().coerceAtLeast(0)
        val skipped = ConnectivityProbe(ProbeOutcome.UNAVAILABLE, DiagnosticTarget.GSTATIC)
        history.record(ConnectivityObservation(
            capturedAtMillis = timestamp,
            monotonicMillis = monotonicMillis(),
            sessionId = sessionId,
            runtimeAlive = false,
            systemProbe = skipped,
            proxyProbe = skipped,
            runtimeFailureCode = stage.code,
        ))
    }

    @Synchronized
    fun sampleStandaloneProxy() = monitor.sample()

    @Synchronized
    fun runOnce(enabled: Boolean) {
        runCatching { history.tick(nowMillis().coerceAtLeast(0), monotonicMillis()) }
        archiveDirectories.forEach { directory ->
            runCatching {
                val reader = WindowsIncidentReportsReader(directory)
                reader.listArchives().summaries.forEach { summary ->
                    val exported = reader.loadArchive(summary.id) ?: return@forEach
                    if (exported.recording) return@forEach
                    val archive = gson.fromJson(
                        JsonParser.parseString(exported.archiveJson),
                        IncidentArchive::class.java,
                    ) ?: return@forEach
                    compactRecoveryReport(archive)?.let(queue::enqueue)
                }
            }
        }
        val item = queue.nextDue(nowMillis().coerceAtLeast(0), enabled, networkReady = true) ?: return
        val payload = recoveryPayload(item.report)
        val delivered = runCatching { payload.size <= RecoveryDeliveryQueue.MAX_PAYLOAD_BYTES && send(payload) }
            .getOrDefault(false)
        runCatching { queue.complete(item.report.id, nowMillis().coerceAtLeast(0), delivered) }
    }

    private fun recoveryPayload(report: RecoveryReport): ByteArray =
        gson.toJson(mapOf(
            "schema" to "gates-recovery-v1",
            "platform" to "windows",
            "app_version" to windowsAppVersionForDisplay(),
            "core_version" to coreVersion,
            "report_id" to report.id,
            "message" to "Automatic Windows connectivity recovery report.",
            "diagnostics" to report,
        )).toByteArray(Charsets.UTF_8)
}

enum class RecoveryFailureStage(val code: Int) {
    CORE_STARTUP(1),
}

internal fun windowsRecoveryFailureStage(command: String, result: RuntimeCommandResult): RecoveryFailureStage? =
    if (result.exitCode == 0) null else when (command) {
        "connect", "start-proxy" -> result.recoveryFailureStage
        else -> null
    }

private class AtomicRecoveryDeliveryStore(
    root: File,
    private val gson: Gson,
) : RecoveryDeliveryStore {
    private val file = File(root, OUTBOX_FILE)
    private val atomicStore = WindowsAtomicStore(root)

    override fun load(): RecoveryDeliveryState? = atomicStore.transaction {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return@transaction null
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) ||
            Files.size(file.toPath()) > RecoveryDeliveryQueue.MAX_STORE_BYTES
        ) {
            throw IOException("Recovery outbox is not a bounded regular file")
        }
        val bytes = Files.newInputStream(file.toPath()).use {
            it.readNBytes(RecoveryDeliveryQueue.MAX_STORE_BYTES + 1)
        }
        if (bytes.size > RecoveryDeliveryQueue.MAX_STORE_BYTES) throw IOException("Recovery outbox exceeds its size limit")
        try {
            val jsonText = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
            val rootJson = JsonParser.parseString(jsonText).asJsonObject
            requireKeys(rootJson, setOf("schema", "state"))
            if (rootJson.get("schema")?.asString != OUTBOX_SCHEMA) throw IOException("Unsupported recovery outbox schema")
            val stateJson = rootJson.getAsJsonObject("state") ?: throw IOException("Recovery outbox state is missing")
            validateStateShape(stateJson)
            gson.fromJson(stateJson, RecoveryDeliveryState::class.java)
                ?: throw IOException("Recovery outbox is empty")
        } catch (error: RuntimeException) {
            throw IOException("Recovery outbox is corrupt", error)
        }
    }

    override fun save(state: RecoveryDeliveryState) = atomicStore.transaction {
        val bytes = gson.toJson(mapOf("schema" to OUTBOX_SCHEMA, "state" to state)).toByteArray(Charsets.UTF_8)
        if (bytes.size > RecoveryDeliveryQueue.MAX_STORE_BYTES) throw IOException("Recovery outbox exceeds its size limit")
        atomicStore.writeAtomic(file, bytes)
    }

    private companion object {
        const val OUTBOX_FILE = "recovery-delivery.json"
        const val OUTBOX_SCHEMA = "gates-recovery-delivery-v1"
    }

    private fun validateStateShape(state: JsonObject) {
        requireKeys(state, setOf("pending", "acknowledgedIds", "capacityReached"))
        state.getAsJsonArray("pending")?.forEach { pendingElement ->
            val pending = pendingElement.asJsonObject
            requireKeys(pending, setOf("report", "attempts", "lastAttemptMillis", "nextAttemptMillis"))
            val report = pending.getAsJsonObject("report")
            requireKeys(report, setOf("id", "sessionId", "triggeredAtMillis", "status", "recovered", "truncated", "eventCount", "events"))
            report.getAsJsonArray("events")?.forEach { eventElement ->
                val event = eventElement.asJsonObject
                requireKeys(event, setOf("timestampMillis", "monotonicMillis", "sessionId", "kind", "target", "transport", "network", "path"),
                    setOf("durationMillis", "code", "dnsMillis", "connectMillis", "tlsMillis"))
            }
        }
    }

    private fun requireKeys(json: JsonObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val actual = json.keySet()
        if (!actual.containsAll(required) || actual.any { it !in required && it !in optional }) {
            throw IOException("Recovery outbox shape is invalid")
        }
    }
}

private fun sendRecoveryPayload(payload: ByteArray): Boolean {
    if (payload.size > RecoveryDeliveryQueue.MAX_PAYLOAD_BYTES) return false
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    val request = HttpRequest.newBuilder(URI.create(RECOVERY_REPORT_URL))
        .timeout(Duration.ofSeconds(10))
        .header("Accept", "application/json")
        .header("Content-Type", "application/json; charset=utf-8")
        .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    response.body().close()
    return response.statusCode() in 200..299
}

private const val RECOVERY_REPORT_URL = "https://app.yokhan.xyz/gates/api/app/bug-report"
