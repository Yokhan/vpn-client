package com.yokhan.gates.shared.domain.diagnostics

/** Compact typed evidence only. Platform serializers must not add raw logs or configuration. */
data class RecoveryReport(
    val id: String,
    val sessionId: String,
    val triggeredAtMillis: Long,
    val status: IncidentStatus,
    val recovered: Boolean,
    val truncated: Boolean,
    val eventCount: Int,
    val events: List<DiagnosticEvent>,
) {
    init {
        require(isSafeId(id) && isSafeId(sessionId))
        require(triggeredAtMillis >= 0 && eventCount >= events.size)
        require(events.size in 1..24 && events.all { it.sessionId == sessionId })
    }
}

fun compactRecoveryReport(archive: IncidentArchive): RecoveryReport? {
    if (archive.trigger.kind != DiagnosticEventKind.RUNTIME_FAILURE &&
        archive.trigger.kind != DiagnosticEventKind.PROBE_FAILURE) return null
    val ordered = (archive.events + archive.trigger).distinct()
        .sortedBy { it.monotonicMillis }
    val selected = (listOf(archive.trigger) + ordered.take(5) + ordered.takeLast(18))
        .distinct().sortedBy { it.monotonicMillis }.take(24)
    return RecoveryReport(archive.id, archive.sessionId, archive.trigger.timestampMillis,
        archive.status, archive.recovered, archive.truncated || selected.size < ordered.size,
        ordered.size, selected)
}

data class PendingRecoveryReport(
    val report: RecoveryReport,
    val attempts: Int = 0,
    val lastAttemptMillis: Long = 0,
    val nextAttemptMillis: Long = 0,
)

data class RecoveryDeliveryState(
    val pending: List<PendingRecoveryReport> = emptyList(),
    val acknowledgedIds: List<String> = emptyList(),
    val capacityReached: Boolean = false,
)

/** Owner serializes every access and atomically persists state outside the UI/runtime thread. */
interface RecoveryDeliveryStore {
    fun load(): RecoveryDeliveryState?
    fun save(state: RecoveryDeliveryState)
}

enum class RecoveryEnqueueResult { QUEUED, ALREADY_KNOWN, FULL }

/** At-least-once delivery. A response lost before acknowledgment may produce a duplicate report ID. */
class RecoveryDeliveryQueue(private val store: RecoveryDeliveryStore) {
    private var state = store.load()?.also(::validateState) ?: RecoveryDeliveryState()

    fun snapshot(): RecoveryDeliveryState = state.copy(pending = state.pending.toList(),
        acknowledgedIds = state.acknowledgedIds.toList())

    fun enqueue(report: RecoveryReport): RecoveryEnqueueResult {
        validateReport(report)
        if (report.id in state.acknowledgedIds) {
            return RecoveryEnqueueResult.ALREADY_KNOWN
        }
        val pending = state.pending.firstOrNull { it.report.id == report.id }
        if (pending != null) {
            if (report.eventCount >= pending.report.eventCount && report != pending.report) {
                commit(state.copy(pending = state.pending.map {
                    if (it.report.id == report.id) it.copy(report = report) else it
                }))
            }
            return RecoveryEnqueueResult.ALREADY_KNOWN
        }
        if (state.pending.size >= MAX_PENDING) {
            if (!state.capacityReached) commit(state.copy(capacityReached = true))
            return RecoveryEnqueueResult.FULL
        }
        commit(state.copy(pending = state.pending + PendingRecoveryReport(report), capacityReached = false))
        return RecoveryEnqueueResult.QUEUED
    }

    fun nextDue(nowMillis: Long, enabled: Boolean, networkReady: Boolean): PendingRecoveryReport? {
        require(nowMillis >= 0)
        if (!enabled || !networkReady) return null
        return state.pending.firstOrNull {
            it.nextAttemptMillis <= nowMillis || it.lastAttemptMillis > nowMillis
        }
    }

    fun complete(id: String, nowMillis: Long, delivered: Boolean, retryAfterMillis: Long? = null) {
        require(isSafeId(id) && nowMillis >= 0)
        val item = state.pending.firstOrNull { it.report.id == id } ?: return
        if (delivered) {
            commit(state.copy(pending = state.pending.filterNot { it.report.id == id },
                acknowledgedIds = (state.acknowledgedIds + id).distinct().takeLast(MAX_ACKNOWLEDGED),
                capacityReached = false))
        } else {
            val attempts = (item.attempts + 1).coerceAtMost(30)
            val backoff = (MIN_RETRY_MILLIS * (1L shl (attempts - 1).coerceAtMost(9)))
                .coerceAtMost(MAX_RETRY_MILLIS)
            val delay = maxOf(backoff, (retryAfterMillis ?: 0).coerceIn(0, MAX_RETRY_MILLIS))
            val next = if (nowMillis > Long.MAX_VALUE - delay) Long.MAX_VALUE else nowMillis + delay
            val updated = item.copy(attempts = attempts, lastAttemptMillis = nowMillis, nextAttemptMillis = next)
            commit(state.copy(pending = state.pending.map { if (it.report.id == id) updated else it }))
        }
    }

    private fun commit(next: RecoveryDeliveryState) {
        validateState(next)
        store.save(next)
        state = next
    }

    companion object {
        const val MAX_PENDING = 8
        const val MAX_ACKNOWLEDGED = 256
        const val MAX_PAYLOAD_BYTES = 16 * 1024
        const val MAX_STORE_BYTES = 256 * 1024
        const val MIN_RETRY_MILLIS = 60_000L
        const val MAX_RETRY_MILLIS = 6 * 60 * 60_000L

        fun validateReport(report: RecoveryReport) {
            report.copy(events = report.events.map { it.copy() })
            require(report.status in IncidentStatus.entries)
            require(report.events.all { it.kind in DiagnosticEventKind.entries &&
                it.target in DiagnosticTarget.entries && it.path in DiagnosticPath.entries &&
                it.network in DiagnosticNetwork.entries && it.transport in DiagnosticTransport.entries })
        }

        fun validateState(value: RecoveryDeliveryState) {
            require(value.pending.size <= MAX_PENDING && value.acknowledgedIds.size <= MAX_ACKNOWLEDGED)
            require(value.acknowledgedIds.all(::isSafeId) && value.acknowledgedIds.distinct() == value.acknowledgedIds)
            require(value.pending.map { it.report.id }.distinct().size == value.pending.size)
            require(value.pending.none { it.report.id in value.acknowledgedIds })
            value.pending.forEach {
                validateReport(it.report)
                require(it.attempts in 0..30 && it.lastAttemptMillis >= 0 && it.nextAttemptMillis >= 0)
            }
        }
    }
}
