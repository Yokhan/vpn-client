package com.yokhan.gates.windows.service

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEvent
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticEventKind
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticPath
import com.yokhan.gates.shared.domain.diagnostics.DiagnosticTarget
import com.yokhan.gates.shared.domain.diagnostics.IncidentArchive
import com.yokhan.gates.shared.domain.diagnostics.IncidentCheckpoint
import com.yokhan.gates.shared.domain.diagnostics.IncidentStatus
import com.yokhan.gates.shared.domain.diagnostics.IncidentStore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

data class WindowsIncidentSummary(
    val id: String,
    val triggeredAtMillis: Long,
    val status: String,
    val recovered: Boolean,
    val confirmed: Boolean,
    val truncated: Boolean,
    val eventCount: Int,
    val recording: Boolean,
)

data class WindowsIncidentListing(
    val summaries: List<WindowsIncidentSummary>,
    val truncated: Boolean,
)

data class WindowsIncidentExport(val archiveJson: String, val recording: Boolean)

/** Read-only surface for UI and export callers; it never recovers or mutates recorder state. */
class WindowsIncidentReportsReader(private val directory: Path) {
    private val store = WindowsIncidentStore(directory)

    fun listArchives(): WindowsIncidentListing = store.listSummaries()

    fun loadArchive(id: String): WindowsIncidentExport? = store.exportArchive(id)

    fun storageAvailability(): String = store.storageAvailability()
}

/** Creates the Windows connectivity history with the same bounded incident store used by the service. */
fun windowsConnectivityHistory(directory: Path, incidentDirectory: Path): ConnectivityHistory =
    ConnectivityHistory(directory, incidentStore = WindowsIncidentStore(incidentDirectory))

/** File-backed IncidentStore; filenames and JSON fields are constrained to this adapter's allowlist. */
internal class WindowsIncidentStore(
    private val directory: Path,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : IncidentStore {
    private val root = directory.toAbsolutePath().normalize()
    private val archivesDirectory = root.resolve(ARCHIVES_DIRECTORY)

    @Synchronized
    override fun loadCheckpoint(): IncidentCheckpoint? {
        return readCheckpointReadOnly()
    }

    @Synchronized
    internal fun readCheckpointReadOnly(): IncidentCheckpoint? {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return null
        ensureDirectory(root)
        val checkpointFile = root.resolve(CHECKPOINT_FILE)
        if (!Files.exists(checkpointFile, NOFOLLOW_LINKS)) return null
        try {
            val stored = decode(readBounded(checkpointFile, MAX_CHECKPOINT_BYTES), StoredCheckpoint::class.java)
            if (stored.schema != CHECKPOINT_SCHEMA) throw IOException("Unsupported incident checkpoint")
            validateCheckpoint(stored.checkpoint)
            return stored.checkpoint
        } catch (error: Exception) {
            if (error is IOException) throw error
            throw IOException("Incident checkpoint is invalid", error)
        }
    }

    @Synchronized
    override fun saveCheckpoint(checkpoint: IncidentCheckpoint) {
        validateCheckpoint(checkpoint)
        ensureDirectory(root)
        cleanupStagingFiles()
        val target = root.resolve(CHECKPOINT_FILE)
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            val existing = decode(readBounded(target, MAX_CHECKPOINT_BYTES), StoredCheckpoint::class.java)
            if (existing.schema != CHECKPOINT_SCHEMA) throw IOException("Unsupported incident checkpoint")
            validateCheckpoint(existing.checkpoint)
        }
        val bytes = gson.toJson(StoredCheckpoint(CHECKPOINT_SCHEMA, checkpoint)).toByteArray(UTF_8)
        require(bytes.size <= MAX_CHECKPOINT_BYTES) { "Incident checkpoint exceeds its size limit" }
        val stage = root.resolve(CHECKPOINT_STAGE_FILE)
        ensureOwnedRegularFileOrMissing(target)
        evictFor(bytes.size.toLong(), targetCountAfterWrite = false)
        try {
            writeStage(stage, bytes)
            atomicReplace(stage, target)
        } catch (error: Exception) {
            deleteOwnedStage(stage)
            throw error
        }
    }

    @Synchronized
    override fun saveArchive(archive: IncidentArchive) {
        validateArchive(archive)
        ensureDirectory(root)
        ensureDirectory(archivesDirectory)
        cleanupStagingFiles()
        val target = archivePath(archive.id)
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            val existing = decode(readBounded(target, MAX_ARCHIVE_BYTES), StoredArchive::class.java)
            if (existing.schema == ARCHIVE_SCHEMA && existing.archive == archive) return
            throw IOException("Incident archive IDs are immutable")
        }
        val bytes = gson.toJson(StoredArchive(ARCHIVE_SCHEMA, archive)).toByteArray(UTF_8)
        require(bytes.size <= MAX_ARCHIVE_BYTES) { "Incident archive exceeds its size limit" }
        val stage = archiveStagePath(archive.id)
        evictFor(bytes.size.toLong(), targetCountAfterWrite = true)
        try {
            writeStage(stage, bytes)
            atomicReplace(stage, target)
        } catch (error: Exception) {
            deleteOwnedStage(stage)
            throw error
        }
        enforceTtl()
    }

    @Synchronized
    internal fun listSummaries(maxEntries: Int = MAX_ARCHIVES): WindowsIncidentListing {
        require(maxEntries in 1..MAX_ARCHIVES)
        val candidates = listOwnedArchivePaths()
        val decoded = mutableListOf<Pair<Long, WindowsIncidentSummary>>()
        var truncated = candidates.size > maxEntries
        candidates.take(MAX_ARCHIVES_SCAN).forEach { path ->
            try {
                val archive = readArchive(path) ?: run {
                    truncated = true
                    return@forEach
                }
                decoded += archive.trigger.timestampMillis to archive.toSummary()
            } catch (_: Exception) {
                truncated = true
            }
        }
        val activeArchive = try {
            readCheckpointReadOnly()?.active?.archive
        } catch (_: Exception) {
            truncated = true
            null
        }
        if (activeArchive != null) decoded += activeArchive.trigger.timestampMillis to activeArchive.toSummary(recording = true)
        val sorted = decoded.sortedByDescending { it.first }
        if (sorted.size > maxEntries) truncated = true
        return WindowsIncidentListing(sorted.take(maxEntries).map { it.second }, truncated)
    }

    @Synchronized
    internal fun exportArchive(id: String): WindowsIncidentExport? {
        if (!SAFE_ID.matches(id)) return null
        val checkpoint = readCheckpointReadOnly()
        val active = checkpoint?.active?.archive?.takeIf { it.id == id }
        val archive = active ?: if (Files.exists(archivesDirectory, NOFOLLOW_LINKS)) {
            ensureDirectory(archivesDirectory)
            readArchive(archivePath(id))
        } else null
        if (archive == null) return null
        val json = gson.toJson(archive)
        if (json.toByteArray(UTF_8).size > MAX_ARCHIVE_EXPORT_BYTES) {
            throw IOException("Incident archive export exceeds its size limit")
        }
        return WindowsIncidentExport(json, recording = active != null)
    }

    @Synchronized
    internal fun storageAvailability(): String {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return "not-recorded"
        return try {
            ensureDirectory(root)
            val bytes = ownedBytes()
            val checkpoint = readCheckpointReadOnly()
            when {
                bytes >= MAX_TOTAL_BYTES -> "quota-full"
                checkpoint?.pendingArchives?.isNotEmpty() == true -> "write-pending"
                else -> "available"
            }
        } catch (_: Exception) {
            "unavailable"
        }
    }

    @Synchronized
    override fun listArchives(): List<IncidentArchive> =
        listOwnedArchivePaths().mapNotNull(::readArchive)
            .sortedByDescending { it.trigger.timestampMillis }
            .take(MAX_ARCHIVES)

    @Synchronized
    override fun loadArchive(id: String): IncidentArchive? {
        if (!SAFE_ID.matches(id) || !Files.exists(archivesDirectory, NOFOLLOW_LINKS)) return null
        ensureDirectory(archivesDirectory)
        return readArchive(archivePath(id))
    }

    private fun readArchive(path: Path): IncidentArchive? {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        val stored = decode(readBounded(path, MAX_ARCHIVE_BYTES), StoredArchive::class.java)
        if (stored.schema != ARCHIVE_SCHEMA) throw IOException("Unsupported incident archive")
        validateArchive(stored.archive)
        return stored.archive
    }

    private fun evictFor(additionalBytes: Long, targetCountAfterWrite: Boolean) {
        val archives = validOwnedArchives()
        val protectedIds = archives.asSequence()
            .filter { it.second.isSevere() }
            .sortedByDescending { it.second.trigger.timestampMillis }
            .take(PROTECTED_SEVERE_ARCHIVES)
            .map { it.second.id }
            .toSet()
        val maxCount = if (targetCountAfterWrite) MAX_ARCHIVES - 1 else MAX_ARCHIVES
        val candidates = archives
            .filter { it.second.id !in protectedIds }
            .sortedBy { it.second.trigger.timestampMillis }
            .toMutableList()
        while ((targetCountAfterWrite && ownedArchiveCount() >= MAX_ARCHIVES) ||
            ownedBytes() + additionalBytes > MAX_TOTAL_BYTES
        ) {
            if (candidates.isEmpty()) throw IOException("Incident storage quota is full")
            val oldest = candidates.removeAt(0).first
            Files.delete(oldest)
        }
        while (ownedArchiveCount() > maxCount) {
            if (candidates.isEmpty()) throw IOException("Incident archive count limit is full")
            Files.delete(candidates.removeAt(0).first)
        }
    }

    private fun enforceTtl() {
        val cutoff = nowMillis().coerceAtLeast(0L) - ARCHIVE_TTL_MILLIS
        val severeIds = validOwnedArchives().asSequence()
            .filter { it.second.isSevere() }
            .sortedByDescending { it.second.trigger.timestampMillis }
            .take(PROTECTED_SEVERE_ARCHIVES)
            .map { it.second.id }
            .toSet()
        validOwnedArchives().forEach { (path, archive) ->
            if (archive.id !in severeIds && archive.trigger.timestampMillis < cutoff) Files.deleteIfExists(path)
        }
    }

    private fun validOwnedArchives(): List<Pair<Path, IncidentArchive>> =
        listOwnedArchivePaths().mapNotNull { path -> runCatching { readArchive(path)?.let { path to it } }.getOrNull() }

    private fun listOwnedArchivePaths(): List<Path> {
        if (!Files.exists(archivesDirectory, NOFOLLOW_LINKS)) return emptyList()
        ensureDirectory(archivesDirectory)
        return Files.list(archivesDirectory).use { stream ->
            stream.map { it.normalize() }
                .filter { path -> path.parent == archivesDirectory && ARCHIVE_FILE.matches(path.fileName.toString()) }
                .limit(MAX_ARCHIVES_SCAN.toLong())
                .sorted()
                .toList()
        }
    }

    private fun ownedArchiveCount(): Int = listOwnedArchivePaths().size

    private fun ownedBytes(excluding: Path? = null): Long {
        var total = 0L
        val paths = buildList {
            add(root.resolve(CHECKPOINT_FILE))
            add(root.resolve(CHECKPOINT_STAGE_FILE))
            if (Files.exists(archivesDirectory, NOFOLLOW_LINKS)) {
                ensureDirectory(archivesDirectory)
                Files.list(archivesDirectory).use { stream ->
                    stream.map { it.normalize() }
                        .filter { it.parent == archivesDirectory && it.fileName.toString().matches(OWNED_ARCHIVE_FILE) }
                        .limit(MAX_ARCHIVES_SCAN.toLong())
                        .forEach { add(it) }
                }
            }
        }
        paths.filter { it != excluding && Files.exists(it, NOFOLLOW_LINKS) }.forEach { path ->
            ensureRegularFile(path)
            total = Math.addExact(total, Files.size(path))
        }
        return total
    }

    private fun cleanupStagingFiles() {
        listOf(root.resolve(CHECKPOINT_STAGE_FILE)).forEach(::deleteOwnedStage)
        if (Files.exists(archivesDirectory, NOFOLLOW_LINKS)) {
            ensureDirectory(archivesDirectory)
            Files.list(archivesDirectory).use { stream ->
                stream.map { it.normalize() }
                    .filter { it.parent == archivesDirectory && ARCHIVE_STAGE.matches(it.fileName.toString()) }
                    .limit(MAX_ARCHIVES_SCAN.toLong())
                    .forEach(::deleteOwnedStage)
            }
        }
    }

    private fun writeStage(stage: Path, bytes: ByteArray) {
        ensureOwnedRegularFileOrMissing(stage)
        Files.deleteIfExists(stage)
        FileChannel.open(stage, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) <= 0) throw IOException("Incident staging write made no progress")
            }
            channel.force(true)
        }
        if (Files.size(stage) != bytes.size.toLong()) throw IOException("Incident staging write was incomplete")
    }

    private fun atomicReplace(stage: Path, target: Path) {
        ensureOwnedRegularFileOrMissing(target)
        try {
            Files.move(stage, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (error: AtomicMoveNotSupportedException) {
            throw IOException("Atomic incident storage is unavailable", error)
        }
    }

    private fun deleteOwnedStage(path: Path) {
        ensureOwnedRegularFileOrMissing(path)
        Files.deleteIfExists(path)
    }

    private fun ensureDirectory(path: Path) {
        var ancestor: Path? = path
        while (ancestor != null) {
            if (Files.exists(ancestor, NOFOLLOW_LINKS) && Files.isSymbolicLink(ancestor)) {
                throw IOException("Incident storage path contains a symbolic link")
            }
            ancestor = ancestor.parent
        }
        if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectories(path)
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, NOFOLLOW_LINKS)) {
            throw IOException("Incident storage path is not a private directory")
        }
        var parent = path.parent
        while (parent != null) {
            if (Files.isSymbolicLink(parent)) throw IOException("Incident storage path contains a symbolic link")
            parent = parent.parent
        }
    }

    private fun ensureOwnedRegularFileOrMissing(path: Path) {
        if (Files.exists(path, NOFOLLOW_LINKS)) ensureRegularFile(path)
    }

    private fun ensureRegularFile(path: Path) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            throw IOException("Incident storage file is not a regular file")
        }
    }

    private fun readBounded(path: Path, limit: Int): ByteArray {
        ensureRegularFile(path)
        if (Files.size(path) > limit) throw IOException("Incident storage file exceeds its size limit")
        return Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val bytes = input.readNBytes(limit + 1)
            if (bytes.size > limit) throw IOException("Incident storage file exceeds its size limit")
            bytes
        }
    }

    private fun <T> decode(bytes: ByteArray, type: Class<T>): T = try {
        gson.fromJson(String(bytes, UTF_8), type) ?: throw IOException("Incident storage file is empty")
    } catch (error: JsonParseException) {
        throw IOException("Incident storage JSON is malformed", error)
    } catch (error: IllegalStateException) {
        throw IOException("Incident storage JSON has an invalid shape", error)
    }

    private fun validateCheckpoint(checkpoint: IncidentCheckpoint) {
        require(checkpoint.ring.size <= MAX_RING_EVENTS && checkpoint.pendingArchives.size <= MAX_PENDING_ARCHIVES)
        checkpoint.sessionId?.let { require(SAFE_ID.matches(it)) }
        checkpoint.ring.forEach(::validateEvent)
        checkpoint.active?.let { active ->
            validateArchive(active.archive)
            require(active.lastFailureMillis >= 0 && active.consecutiveSuccesses >= 0)
            active.lastSuccessMillis?.let { require(it >= 0) }
            active.lastSuccessTimestampMillis?.let { require(it >= 0) }
        }
        checkpoint.pendingArchives.forEach(::validateArchive)
        checkpoint.lastEvent?.let(::validateEvent)
    }

    private fun validateArchive(archive: IncidentArchive) {
        require(SAFE_ID.matches(archive.id) && SAFE_ID.matches(archive.sessionId))
        require(archive.status != null)
        require(archive.trigger.sessionId == archive.sessionId && archive.events.size <= MAX_EVENTS_PER_ARCHIVE)
        validateEvent(archive.trigger)
        archive.events.forEach {
            validateEvent(it)
            require(it.sessionId == archive.sessionId)
        }
    }

    private fun validateEvent(event: DiagnosticEvent) {
        require(SAFE_ID.matches(event.sessionId))
        require(
            event.kind != null && event.target != null && event.transport != null &&
                event.network != null && event.path != null,
        )
        require(event.timestampMillis >= 0 && event.monotonicMillis >= 0)
        event.durationMillis?.let { require(it >= 0) }
        event.dnsMillis?.let { require(it >= 0) }
        event.connectMillis?.let { require(it >= 0) }
        event.tlsMillis?.let { require(it >= 0) }
        event.code?.let { require(it in 0..999) }
    }

    private fun archivePath(id: String): Path {
        require(SAFE_ID.matches(id)) { "Incident archive ID is invalid" }
        return archivesDirectory.resolve("$id.json")
    }

    private fun archiveStagePath(id: String): Path = archivesDirectory.resolve("archive-$id.stage")

    private fun IncidentArchive.toSummary(recording: Boolean = false) = WindowsIncidentSummary(
        id = id,
        triggeredAtMillis = trigger.timestampMillis,
        status = status.name,
        recovered = recovered,
        confirmed = isConfirmedByEvents(),
        truncated = truncated,
        eventCount = events.size,
        recording = recording,
    )

    private fun IncidentArchive.isSevere(): Boolean = isConfirmedByEvents()

    private fun IncidentArchive.isConfirmedByEvents(): Boolean =
        status == IncidentStatus.CONFIRMED ||
            events.any { it.kind == DiagnosticEventKind.RUNTIME_FAILURE } ||
            events.asSequence()
                .filter {
                    it.kind == DiagnosticEventKind.PROBE_FAILURE &&
                        it.path != DiagnosticPath.UNKNOWN && it.target != DiagnosticTarget.NONE
                }
                .groupBy { it.path }
                .values
                .any { failures -> failures.map { it.target }.distinct().size >= 2 }

    private data class StoredCheckpoint(
        @SerializedName("schema") val schema: String,
        @SerializedName("checkpoint") val checkpoint: IncidentCheckpoint,
    )

    private data class StoredArchive(
        @SerializedName("schema") val schema: String,
        @SerializedName("archive") val archive: IncidentArchive,
    )

    private companion object {
        const val CHECKPOINT_FILE = "incident-checkpoint.json"
        const val CHECKPOINT_STAGE_FILE = "incident-checkpoint.stage"
        const val ARCHIVES_DIRECTORY = "archives"
        const val CHECKPOINT_SCHEMA = "gates-incident-checkpoint-v1"
        const val ARCHIVE_SCHEMA = "gates-incident-archive-v1"
        const val MAX_CHECKPOINT_BYTES = 4 * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 8 * 1024 * 1024
        const val MAX_ARCHIVE_EXPORT_BYTES = 8 * 1024 * 1024
        const val MAX_ARCHIVES = 8
        const val MAX_ARCHIVES_SCAN = 16
        const val MAX_TOTAL_BYTES = 96L * 1024 * 1024
        const val ARCHIVE_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val PROTECTED_SEVERE_ARCHIVES = 2
        const val MAX_RING_EVENTS = 128
        const val MAX_PENDING_ARCHIVES = 4
        const val MAX_EVENTS_PER_ARCHIVE = 2_048
        val SAFE_ID = Regex("[0-9a-f]{32,64}")
        val ARCHIVE_FILE = Regex("[0-9a-f]{32,64}\\.json")
        val ARCHIVE_STAGE = Regex("archive-[0-9a-f]{32,64}\\.stage")
        val OWNED_ARCHIVE_FILE = Regex("(?:[0-9a-f]{32,64}\\.json|archive-[0-9a-f]{32,64}\\.stage)")
        val gson = Gson()
    }
}
