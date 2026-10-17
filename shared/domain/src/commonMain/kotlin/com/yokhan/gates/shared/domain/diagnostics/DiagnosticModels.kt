package com.yokhan.gates.shared.domain.diagnostics

enum class DiagnosticEventKind {
    SESSION_STARTED,
    PROBE_SUCCESS,
    PROBE_FAILURE,
    PROBE_UNAVAILABLE,
    RUNTIME_FAILURE,
    NETWORK_CHANGED,
    SUSPENDED,
    RESUMED,
    SESSION_ENDED,
    OBSERVER_GAP,
}

enum class DiagnosticTarget { NONE, GSTATIC, YOUTUBE }
enum class DiagnosticTransport { UNKNOWN, HYSTERIA2, VLESS, OTHER }
enum class DiagnosticNetwork { UNKNOWN, WIFI, CELLULAR, ETHERNET, NONE }
enum class DiagnosticPath { UNKNOWN, SYSTEM, CORE, UNDERLAY }
enum class IncidentStatus { SUSPECTED, CONFIRMED, RECOVERED, INTERRUPTED, ENDED }

data class DiagnosticEvent(
    val timestampMillis: Long,
    val monotonicMillis: Long,
    val sessionId: String,
    val kind: DiagnosticEventKind,
    val target: DiagnosticTarget = DiagnosticTarget.NONE,
    val durationMillis: Long? = null,
    val transport: DiagnosticTransport = DiagnosticTransport.UNKNOWN,
    val network: DiagnosticNetwork = DiagnosticNetwork.UNKNOWN,
    val code: Int? = null,
    val path: DiagnosticPath = DiagnosticPath.UNKNOWN,
    val dnsMillis: Long? = null,
    val connectMillis: Long? = null,
    val tlsMillis: Long? = null,
) {
    init {
        require(isSafeId(sessionId)) { "sessionId must be 32..64 lowercase hex characters" }
        require(timestampMillis >= 0 && monotonicMillis >= 0)
        require(durationMillis == null || durationMillis >= 0)
        require(dnsMillis == null || dnsMillis >= 0)
        require(connectMillis == null || connectMillis >= 0)
        require(tlsMillis == null || tlsMillis >= 0)
    }
}

/** Coalescing may extend capture to max(postWindowMillis, mergeWindowMillis) after failure, capped by maxSegmentMillis. */
data class IncidentPolicy(
    val preWindowMillis: Long = 120_000,
    val postWindowMillis: Long = 180_000,
    val mergeWindowMillis: Long = 300_000,
    val ringMaxEvents: Int = 128,
    val maxEventsPerSegment: Int = 2_048,
    val maxSegmentMillis: Long = 1_200_000,
) {
    init {
        require(preWindowMillis >= 0 && postWindowMillis > 0 && mergeWindowMillis >= 0)
        require(ringMaxEvents in 1..128 && maxEventsPerSegment in 1..2_048)
        require(maxSegmentMillis in 1..1_200_000)
    }
}

data class IncidentArchive(
    val id: String,
    val sessionId: String,
    val trigger: DiagnosticEvent,
    val status: IncidentStatus,
    val recovered: Boolean,
    val truncated: Boolean,
    val events: List<DiagnosticEvent>,
) {
    init {
        require(isSafeId(id) && isSafeId(sessionId))
        require(trigger.sessionId == sessionId)
        require(events.size <= 2_048 && events.all { it.sessionId == sessionId })
    }
}

data class ActiveIncident(
    val archive: IncidentArchive,
    val lastFailureMillis: Long,
    val consecutiveSuccesses: Int = 0,
    val lastSuccessMillis: Long? = null,
    val lastSuccessTimestampMillis: Long? = null,
)

data class IncidentCheckpoint(
    val sessionId: String? = null,
    val ring: List<DiagnosticEvent> = emptyList(),
    val active: ActiveIncident? = null,
    val pendingArchives: List<IncidentArchive> = emptyList(),
    val lastEvent: DiagnosticEvent? = null,
) {
    init {
        require(sessionId == null || isSafeId(sessionId))
        require(ring.size <= 128)
        require(pendingArchives.size <= 4)
    }
}

/** saveArchive must be atomic and idempotent by archive.id; saved archives are immutable. */
interface IncidentStore {
    fun loadCheckpoint(): IncidentCheckpoint?
    fun saveCheckpoint(checkpoint: IncidentCheckpoint)
    fun saveArchive(archive: IncidentArchive)
    fun listArchives(): List<IncidentArchive>
    fun loadArchive(id: String): IncidentArchive?
}

internal fun isSafeId(value: String): Boolean =
    value.length in 32..64 && value.all { it in '0'..'9' || it in 'a'..'f' }
