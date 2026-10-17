package com.yokhan.gates.app.diagnostics

import android.content.Context
import android.os.Looper
import com.yokhan.gates.shared.domain.diagnostics.ActiveIncident
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEvent
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEventKind
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticNetwork
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticPath
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTarget
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTransport
import com.yokhan.gates.shared.domain.diagnostics.IncidentArchive
import com.yokhan.gates.shared.domain.diagnostics.IncidentCheckpoint
import com.yokhan.gates.shared.domain.diagnostics.IncidentStatus
import com.yokhan.gates.shared.domain.diagnostics.IncidentStore
import io.nekohasekai.sagernet.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

internal class AndroidIncidentStore(private val root: File) : IncidentStore {
    private val storageLock = lockFor(root.absolutePath)
    private var checkpointGeneration = 0L
    private var activeCheckpointSlot = -1
    var checkpointDegraded: Boolean = false
        private set

    override fun loadCheckpoint(): IncidentCheckpoint? = synchronized(storageLock) {
        val directory = ensureDirectory()
        cleanupStaging(directory)
        readCheckpoint(directory)
    }

    override fun saveCheckpoint(checkpoint: IncidentCheckpoint) = synchronized(storageLock) {
        val directory = ensureDirectory()
        cleanupStaging(directory)
        val nextSlot = if (activeCheckpointSlot == 0) 1 else 0
        val nextGeneration = checkpointGeneration + 1
        val payload = JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put("generation", nextGeneration)
            .put("checkpoint", checkpointToJson(checkpoint))
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= MAX_CHECKPOINT_BYTES) { "checkpoint exceeds size limit" }
        ensureCapacity(directory, payload.size.toLong())

        val stage = child(directory, CHECKPOINT_STAGE)
        writeStage(stage, payload, MAX_CHECKPOINT_BYTES)
        val target = child(directory, checkpointName(nextSlot))
        if (target.exists() && !target.delete()) throw IOException("checkpoint slot cannot be replaced")
        if (!stage.renameTo(target)) throw IOException("checkpoint commit failed")
        checkpointGeneration = nextGeneration
        activeCheckpointSlot = nextSlot
    }

    override fun saveArchive(archive: IncidentArchive) = synchronized(storageLock) {
        val directory = ensureDirectory()
        cleanupStaging(directory)
        val target = child(directory, archiveName(archive.id))
        if (target.exists()) {
            if (readArchive(target) == archive) return@synchronized
            throw IOException("archive id already exists")
        }
        val payload = archiveToJson(archive).toString().toByteArray(StandardCharsets.UTF_8)
        if (payload.size > MAX_ARCHIVE_BYTES) throw IOException("archive exceeds size limit")
        makeArchiveRoom(directory, payload.size.toLong())

        val stage = child(directory, archiveStageName(archive.id))
        writeStage(stage, payload, MAX_ARCHIVE_BYTES)
        archiveFromJson(JSONObject(readUtf8(stage, MAX_ARCHIVE_BYTES)))
        if (!stage.renameTo(target)) throw IOException("archive commit failed")
    }

    override fun listArchives(): List<IncidentArchive> = synchronized(storageLock) {
        val directory = ensureDirectory()
        archiveFiles(directory).mapNotNull { file -> runCatching { readArchive(file) }.getOrNull() }
            .sortedByDescending { it.trigger.timestampMillis }
    }

    override fun loadArchive(id: String): IncidentArchive? = synchronized(storageLock) {
        val directory = existingDirectory() ?: return@synchronized null
        loadArchiveIn(directory, id)
    }

    fun readOnlySnapshot(): IncidentReportSnapshot? = synchronized(storageLock) {
        val directory = existingDirectory() ?: return@synchronized null
        var corruptArchiveFound = false
        val archives = archiveFiles(directory).mapNotNull { file ->
            runCatching { readArchive(file) }
                .onFailure { corruptArchiveFound = true }
                .getOrNull()
        }.toMutableList()
        val checkpoint = readCheckpoint(directory)
        checkpoint?.pendingArchives?.forEach { pending ->
            if (archives.none { it.id == pending.id }) archives += pending
        }
        val active = checkpoint?.active?.archive
        val ordered = archives.sortedByDescending { it.trigger.timestampMillis }
        IncidentReportSnapshot(
            ordered,
            active,
            checkpointDegraded,
            (ordered.size + if (active != null && ordered.none { it.id == active.id }) 1 else 0) > MAX_ARCHIVES ||
                ordered.any { it.truncated } || active?.truncated == true || corruptArchiveFound,
        )
    }

    fun readOnlyArchive(id: String): IncidentArchive? = synchronized(storageLock) {
        val directory = existingDirectory() ?: return@synchronized null
        loadArchiveIn(directory, id) ?: readCheckpoint(directory)?.let { checkpoint ->
            checkpoint.pendingArchives.firstOrNull { it.id == id } ?: checkpoint.active?.archive?.takeIf { it.id == id }
        }
    }

    private fun loadArchiveIn(directory: File, id: String): IncidentArchive? {
        if (!SAFE_ID.matches(id)) return null
        val file = child(directory, archiveName(id))
        return if (file.exists()) readArchive(file) else null
    }

    private fun readCheckpoint(directory: File): IncidentCheckpoint? {
        checkpointDegraded = false
        val slots = (0..1).mapNotNull { slot ->
            val file = child(directory, checkpointName(slot))
            if (file.exists()) slot to file else null
        }
        val candidates = slots.mapNotNull { (slot, file) ->
            runCatching {
                val json = JSONObject(readUtf8(file, MAX_CHECKPOINT_BYTES))
                require(json.requiredInt("schema_version") == SCHEMA_VERSION)
                val generation = json.requiredLong("generation").also { require(it >= 0) }
                Triple(generation, slot, checkpointFromJson(json.getJSONObject("checkpoint")))
            }.onFailure { checkpointDegraded = true }.getOrNull()
        }.sortedByDescending { it.first }
        if (candidates.isEmpty() && slots.isNotEmpty()) {
            checkpointDegraded = true
            throw IOException("no valid incident checkpoint slot")
        }
        val selected = candidates.firstOrNull() ?: run {
            checkpointGeneration = 0
            activeCheckpointSlot = -1
            return null
        }
        return selected.let { (generation, slot, checkpoint) ->
            checkpointGeneration = generation
            activeCheckpointSlot = slot
            checkpoint
        }
    }

    private fun makeArchiveRoom(directory: File, incomingBytes: Long) {
        val archives = listArchives().sortedBy { it.trigger.timestampMillis }
        val protectedIds = archives.filter(::isConfirmedIncident)
            .takeLast(PROTECTED_CONFIRMED_ARCHIVES).mapTo(mutableSetOf()) { it.id }
        var remaining = archives.toMutableList()
        while (remaining.size >= MAX_ARCHIVES || diskUsage(directory) + incomingBytes > MAX_TOTAL_BYTES) {
            val oldest = remaining.firstOrNull { it.id !in protectedIds }
                ?: throw IOException("incident archive quota is full")
            val file = child(directory, archiveName(oldest.id))
            if (!file.delete()) throw IOException("old incident archive cannot be pruned")
            remaining.remove(oldest)
        }
        ensureCapacity(directory, incomingBytes)
    }

    private fun ensureCapacity(directory: File, incomingBytes: Long) {
        if (incomingBytes < 0 || diskUsage(directory) + incomingBytes > MAX_TOTAL_BYTES) {
            throw IOException("incident storage quota is full")
        }
    }

    private fun diskUsage(directory: File): Long = directChildren(directory).sumOf { file ->
        if (!file.isFile) throw IOException("unexpected incident storage entry")
        file.length().also { if (it < 0 || it > MAX_TOTAL_BYTES) throw IOException("invalid incident file size") }
    }

    private fun archiveFiles(directory: File): List<File> = directChildren(directory).filter { file ->
        ARCHIVE_FILE.matches(file.name) && file.isFile
    }

    private fun directChildren(directory: File): List<File> =
        directory.listFiles()?.map { file -> child(directory, file.name) }
            ?: throw IOException("incident storage cannot be listed")

    private fun ensureDirectory(): File {
        val absolute = root.absoluteFile
        if (!absolute.exists() && !absolute.mkdirs() && !absolute.isDirectory) {
            throw IOException("incident storage cannot be created")
        }
        if (!absolute.isDirectory || absolute.canonicalFile.path != absolute.path) {
            throw IOException("incident storage path is not private and canonical")
        }
        return absolute
    }

    private fun existingDirectory(): File? {
        val absolute = root.absoluteFile
        if (!absolute.exists()) return null
        if (!absolute.isDirectory || absolute.canonicalFile.path != absolute.path) {
            throw IOException("incident storage path is not private and canonical")
        }
        return absolute
    }

    private fun child(directory: File, name: String): File {
        if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name) {
            throw IOException("invalid incident storage path")
        }
        val file = File(directory, name)
        if (file.parentFile?.canonicalFile?.path != directory.canonicalFile.path) {
            throw IOException("incident storage path escaped its directory")
        }
        if (file.exists() && file.canonicalFile.path != file.absoluteFile.path) {
            throw IOException("symbolic incident storage path rejected")
        }
        return file
    }

    private fun cleanupStaging(directory: File) {
        directChildren(directory).filter { STAGING_FILE.matches(it.name) }.forEach { file ->
            if (file.exists() && !file.delete()) throw IOException("stale incident staging file cannot be removed")
        }
    }

    private fun writeStage(file: File, bytes: ByteArray, limit: Int) {
        if (bytes.size > limit) throw IOException("incident staging size limit exceeded")
        if (file.exists() && !file.delete()) throw IOException("incident staging file cannot be replaced")
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        if (file.length() != bytes.size.toLong()) throw IOException("incident staging write was incomplete")
    }

    private fun readArchive(file: File): IncidentArchive =
        archiveFromJson(JSONObject(readUtf8(file, MAX_ARCHIVE_BYTES)))

    private fun readUtf8(file: File, limit: Int): String {
        if (!file.isFile || file.length() > limit) throw IOException("incident file size limit exceeded")
        FileInputStream(file).use { input ->
            val output = ByteArrayOutputStream(minOf(file.length().toInt(), limit))
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw IOException("incident file size limit exceeded")
                output.write(buffer, 0, read)
            }
            return String(output.toByteArray(), StandardCharsets.UTF_8)
        }
    }

    private fun checkpointToJson(checkpoint: IncidentCheckpoint): JSONObject = JSONObject()
        .put("schema_version", SCHEMA_VERSION)
        .put("session_id", checkpoint.sessionId ?: JSONObject.NULL)
        .put("ring", eventsToJson(checkpoint.ring))
        .put("active", checkpoint.active?.let { activeToJson(it) } ?: JSONObject.NULL)
        .put("pending_archives", JSONArray().also { array -> checkpoint.pendingArchives.forEach { array.put(archiveToJson(it)) } })
        .put("last_event", checkpoint.lastEvent?.let { eventToJson(it) } ?: JSONObject.NULL)

    private fun checkpointFromJson(json: JSONObject): IncidentCheckpoint {
        require(json.requiredInt("schema_version") == SCHEMA_VERSION)
        val sessionId = (json.opt("session_id") as? String)?.takeIf { it.isNotEmpty() }
        val ring = eventsFromJson(json.optJSONArray("ring"), MAX_RING_EVENTS)
        val activeJson = json.optJSONObject("active")
        val pending = archivesFromJson(json.optJSONArray("pending_archives"), MAX_PENDING_ARCHIVES)
        val lastEvent = json.optJSONObject("last_event")?.let(::eventFromJson)
        return IncidentCheckpoint(sessionId, ring, activeJson?.let(::activeFromJson), pending, lastEvent)
    }

    private fun activeToJson(active: ActiveIncident): JSONObject = JSONObject()
        .put("archive", archiveToJson(active.archive))
        .put("last_failure_millis", active.lastFailureMillis)
        .put("consecutive_successes", active.consecutiveSuccesses)
        .put("last_success_millis", active.lastSuccessMillis ?: JSONObject.NULL)
        .put("last_success_timestamp_millis", active.lastSuccessTimestampMillis ?: JSONObject.NULL)

    private fun activeFromJson(json: JSONObject): ActiveIncident = ActiveIncident(
        archive = archiveFromJson(json.getJSONObject("archive")),
        lastFailureMillis = json.requiredLong("last_failure_millis").boundedTimestamp(),
        consecutiveSuccesses = json.requiredInt("consecutive_successes").also { require(it in 0..2) },
        lastSuccessMillis = json.optionalLong("last_success_millis"),
        lastSuccessTimestampMillis = json.optionalLong("last_success_timestamp_millis"),
    )

    private fun archiveToJson(archive: IncidentArchive): JSONObject = JSONObject()
        .put("schema_version", SCHEMA_VERSION)
        .put("id", archive.id)
        .put("session_id", archive.sessionId)
        .put("trigger", eventToJson(archive.trigger))
        .put("status", archive.status.name)
        .put("recovered", archive.recovered)
        .put("truncated", archive.truncated)
        .put("events", eventsToJson(archive.events))

    private fun archiveFromJson(json: JSONObject): IncidentArchive {
        require(json.requiredInt("schema_version") == SCHEMA_VERSION)
        val status = enumValue<IncidentStatus>(json.getString("status"))
        return IncidentArchive(
            id = json.getString("id"),
            sessionId = json.getString("session_id"),
            trigger = eventFromJson(json.getJSONObject("trigger")),
            status = status,
            recovered = json.getBoolean("recovered"),
            truncated = json.getBoolean("truncated"),
            events = eventsFromJson(json.optJSONArray("events"), MAX_ARCHIVE_EVENTS),
        )
    }

    private fun eventsToJson(events: List<DiagnosticEvent>) = JSONArray().also { array ->
        events.forEach { array.put(eventToJson(it)) }
    }

    private fun eventsFromJson(array: JSONArray?, max: Int): List<DiagnosticEvent> {
        val values = array ?: error("missing incident event list")
        require(values.length() <= max)
        return (0 until values.length()).map { eventFromJson(values.getJSONObject(it)) }
    }

    private fun eventToJson(event: DiagnosticEvent): JSONObject = JSONObject()
        .put("timestamp_millis", event.timestampMillis)
        .put("monotonic_millis", event.monotonicMillis)
        .put("session_id", event.sessionId)
        .put("kind", event.kind.name)
        .put("target", event.target.name)
        .put("duration_millis", event.durationMillis ?: JSONObject.NULL)
        .put("transport", event.transport.name)
        .put("network", event.network.name)
        .put("code", event.code?.takeIf { it in -9_999..9_999 } ?: JSONObject.NULL)
        .put("path", event.path.name)
        .put("dns_millis", event.dnsMillis ?: JSONObject.NULL)
        .put("connect_millis", event.connectMillis ?: JSONObject.NULL)
        .put("tls_millis", event.tlsMillis ?: JSONObject.NULL)

    private fun eventFromJson(json: JSONObject): DiagnosticEvent = DiagnosticEvent(
        timestampMillis = json.requiredLong("timestamp_millis").boundedTimestamp(),
        monotonicMillis = json.requiredLong("monotonic_millis").boundedTimestamp(),
        sessionId = json.getString("session_id"),
        kind = enumValue(json.getString("kind")),
        target = enumValue(json.getString("target")),
        durationMillis = json.optionalDuration("duration_millis"),
        transport = enumValue(json.getString("transport")),
        network = enumValue(json.getString("network")),
        code = json.optionalCode("code"),
        path = enumValue(json.getString("path")),
        dnsMillis = json.optionalDuration("dns_millis"),
        connectMillis = json.optionalDuration("connect_millis"),
        tlsMillis = json.optionalDuration("tls_millis"),
    )

    private fun archivesFromJson(array: JSONArray?, max: Int): List<IncidentArchive> {
        val values = array ?: error("missing incident archive list")
        require(values.length() <= max)
        return (0 until values.length()).map { archiveFromJson(values.getJSONObject(it)) }
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T = enumValues<T>().first { it.name == value }

    private fun JSONObject.requiredLong(key: String): Long = when (val value = opt(key)) {
        is Number -> value.toLong().also { require(value.toDouble() == it.toDouble()) }
        else -> error("invalid numeric incident field")
    }

    private fun JSONObject.requiredInt(key: String): Int = when (val value = opt(key)) {
        is Number -> value.toInt().also { require(value.toDouble() == it.toDouble()) }
        else -> error("invalid integer incident field")
    }

    private fun JSONObject.optionalLong(key: String): Long? =
        when (val value = opt(key)) {
            null, JSONObject.NULL -> null
            is Number -> value.toLong().also {
                require(it >= 0 && value.toDouble() == it.toDouble())
            }
            else -> error("invalid numeric incident field")
        }

    private fun JSONObject.optionalDuration(key: String): Long? = optionalLong(key).also {
        it?.let { duration -> require(duration <= MAX_MEASUREMENT_MILLIS) }
    }

    private fun JSONObject.optionalCode(key: String): Int? = when (val value = opt(key)) {
        null, JSONObject.NULL -> null
        is Number -> value.toInt().also { code ->
            require(code in -9_999..9_999 && value.toDouble() == code.toDouble())
        }
        else -> error("invalid incident code")
    }

    private fun Long.boundedTimestamp(): Long = also {
        require(it in 0..MAX_TIMESTAMP_MILLIS)
    }

    private fun checkpointName(slot: Int) = "checkpoint-$slot.json"
    private fun archiveName(id: String): String {
        require(SAFE_ID.matches(id))
        return "$id.json"
    }

    private fun archiveStageName(id: String): String = "archive-$id.stage"

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_CHECKPOINT_BYTES = 2 * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 4 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 40L * 1024 * 1024
        const val MAX_ARCHIVES = 6
        const val PROTECTED_CONFIRMED_ARCHIVES = 2
        const val MAX_RING_EVENTS = 128
        const val MAX_PENDING_ARCHIVES = 4
        const val MAX_ARCHIVE_EVENTS = 2_048
        const val MAX_MEASUREMENT_MILLIS = 86_400_000L
        const val MAX_TIMESTAMP_MILLIS = 253_402_300_799_999L
        const val CHECKPOINT_STAGE = "checkpoint.stage"
        val SAFE_ID = Regex("[0-9a-f]{32,64}")
        val ARCHIVE_FILE = Regex("[0-9a-f]{32,64}\\.json")
        val STAGING_FILE = Regex("(?:checkpoint\\.stage|archive-[0-9a-f]{32,64}\\.stage)")
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

        fun lockFor(path: String): Any = synchronized(locks) { locks.getOrPut(path) { Any() } }
    }
}

internal data class IncidentReportSnapshot(
    val archives: List<IncidentArchive>,
    val active: IncidentArchive?,
    val checkpointDegraded: Boolean,
    val truncated: Boolean,
)

internal fun isConfirmedIncident(archive: IncidentArchive): Boolean {
    if (archive.trigger.kind == DiagnosticEventKind.RUNTIME_FAILURE) return true
    val events = (archive.events + archive.trigger).distinct()
    if (events.any { it.kind == DiagnosticEventKind.RUNTIME_FAILURE }) return true
    val failedTargets = mutableMapOf<DiagnosticPath, MutableSet<DiagnosticTarget>>()
    events.filter { it.kind == DiagnosticEventKind.PROBE_FAILURE && it.path != DiagnosticPath.UNKNOWN }
        .forEach { event ->
            if (event.target != DiagnosticTarget.NONE) {
                failedTargets.getOrPut(event.path) { mutableSetOf() }.add(event.target)
            }
        }
    return failedTargets.values.any { it.size >= 2 }
}

object AndroidIncidentReports {
    fun summary(context: Context): JSONObject {
        requireIoThread()
        val result = JSONObject()
            .put("schema", "gates-incident-summary-v1")
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("core_version", "unavailable")
            .put("checkpoint_degraded", false)
        return try {
            val store = store(context)
            val snapshot = store.readOnlySnapshot()
            if (snapshot == null) {
                result.put("availability", "not-recorded")
                    .put("truncated", false)
                    .put("archives", JSONArray())
            } else {
                val rows = snapshot.archives.filterNot { it.id == snapshot.active?.id }
                    .take(if (snapshot.active == null) MAX_SUMMARY_ARCHIVES else MAX_SUMMARY_ARCHIVES - 1)
                    .toMutableList()
                snapshot.active?.let { rows.add(0, it) }
                val maxRows = if (snapshot.active == null) MAX_SUMMARY_ARCHIVES else MAX_SUMMARY_ARCHIVES - 1
                val truncated = snapshot.truncated || snapshot.archives.size > maxRows
                result.put("availability", "available")
                    .put("truncated", truncated)
                    .put("checkpoint_degraded", snapshot.checkpointDegraded)
                    .put("archives", JSONArray().also { array ->
                        rows.forEach { archive ->
                            val row = archiveSummary(archive)
                            if (archive.id == snapshot.active?.id) row.put("recording", true)
                            array.put(row)
                        }
                    })
            }
        } catch (_: Exception) {
            result.put("availability", "unavailable")
                .put("truncated", true)
                .put("checkpoint_degraded", true)
                .put("archives", JSONArray())
        }
    }

    fun export(context: Context, id: String): String {
        requireIoThread()
        require(Regex("[0-9a-f]{32,64}").matches(id)) { "invalid incident id" }
        val result = JSONObject()
            .put("schema", "gates-incident-export-v1")
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("core_version", "unavailable")
        val archive = try {
            store(context).readOnlyArchive(id)
        } catch (_: Exception) {
            return result.put("availability", "unavailable").toString()
        } ?: return result.put("availability", "not-found").toString()
        return result.put("availability", "available")
            .put("archive", JSONObject()
            .put("schema_version", 1)
            .put("id", archive.id)
            .put("session_id", archive.sessionId)
            .put("triggered_at_millis", archive.trigger.timestampMillis)
            .put("status", archive.status.name.lowercase())
            .put("recovered", archive.recovered)
            .put("confirmed", isConfirmedIncident(archive))
            .put("truncated", archive.truncated)
            .put("events", JSONArray().also { events ->
                archive.events.forEach { event ->
                    events.put(
                        JSONObject()
                            .put("timestamp_millis", event.timestampMillis)
                            .put("monotonic_millis", event.monotonicMillis)
                            .put("kind", event.kind.name.lowercase())
                            .put("target", event.target.name.lowercase())
                            .put("duration_millis", event.durationMillis ?: JSONObject.NULL)
                            .put("transport", event.transport.name.lowercase())
                            .put("network", event.network.name.lowercase())
                            .put("path", event.path.name.lowercase())
                            .put("dns_millis", event.dnsMillis ?: JSONObject.NULL)
                            .put("connect_millis", event.connectMillis ?: JSONObject.NULL)
                            .put("tls_millis", event.tlsMillis ?: JSONObject.NULL)
                            .put("code", event.code ?: JSONObject.NULL),
                    )
                }
            }))
            .toString()
    }

    internal fun store(context: Context): AndroidIncidentStore =
        AndroidIncidentStore(File(context.applicationContext.noBackupFilesDir, "incident_diagnostics"))

    private fun archiveSummary(archive: IncidentArchive): JSONObject = JSONObject()
        .put("id", archive.id)
        .put("triggered_at_millis", archive.trigger.timestampMillis)
        .put("status", archive.status.name.lowercase())
        .put("recovered", archive.recovered)
        .put("confirmed", isConfirmedIncident(archive))
        .put("truncated", archive.truncated)
        .put("event_count", archive.events.size)

    private fun requireIoThread() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "incident reports require an IO thread" }
    }

    private const val MAX_SUMMARY_ARCHIVES = 6
}
