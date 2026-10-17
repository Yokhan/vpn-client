package com.yokhan.gates.shared.domain.diagnostics

/** Uses no OS APIs or direct IO; IncidentStore calls must run serialized on the owner's IO worker. */
class IncidentRecorder(
    private val policy: IncidentPolicy,
    private val store: IncidentStore,
    private val newId: () -> String,
) {
    private var checkpoint: IncidentCheckpoint = store.loadCheckpoint() ?: IncidentCheckpoint()
    private var checkpointDirty = false

    init {
        require(checkpoint.ring.size <= policy.ringMaxEvents)
        checkpoint.active?.let { require(it.archive.events.size <= policy.maxEventsPerSegment) }
        if (checkpoint.pendingArchives.isNotEmpty()) flushArchive()
        val priorActive = checkpoint.active
        val recoveredActive = priorActive?.archive?.let { archive ->
            val recoveredEvents = (checkpoint.ring + archive.events).distinct()
            archive.copy(
                status = IncidentStatus.INTERRUPTED,
                recovered = false,
                truncated = archive.truncated || recoveredEvents.size > policy.maxEventsPerSegment,
                events = boundedEvents(recoveredEvents, archive.trigger),
            )
        }
        val recoveredRing = if (recoveredActive == null) checkpoint.ring.takeIf { it.isNotEmpty() }?.let { events ->
            interruptedArchive(events.first(), events)
        } else null
        checkpoint = checkpoint.copy(
            ring = emptyList(),
            active = null,
            pendingArchives = checkpoint.pendingArchives + listOfNotNull(recoveredActive, recoveredRing),
            lastEvent = null,
        )
        if (recoveredActive != null || recoveredRing != null || checkpoint.pendingArchives.isNotEmpty() || checkpoint.sessionId != null) {
            checkpointDirty = true
            persistAndFlush()
        }
    }

    fun record(event: DiagnosticEvent) {
        require(event.sessionId.length in 32..64 && isSafeId(event.sessionId))
        retryWrites()
        if (event == checkpoint.lastEvent) return

        var next = checkpoint
        if (next.sessionId != null && next.sessionId != event.sessionId) {
            next = interruptCurrent(next)
        } else if (next.lastEvent != null && event.monotonicMillis < next.lastEvent.monotonicMillis) {
            next = interruptCurrent(next)
        }
        if (next.pendingArchives.isNotEmpty()) {
            checkpoint = next
            checkpointDirty = true
            persistAndFlush()
            next = checkpoint
        }
        next = next.copy(sessionId = event.sessionId)
        next = accept(next, event)
        checkpoint = next.copy(lastEvent = event)
        checkpointDirty = true
        persistAndFlush()
    }

    fun tick(timestampMillis: Long, monotonicMillis: Long) {
        require(timestampMillis >= 0 && monotonicMillis >= 0)
        retryWrites()
        val active = checkpoint.active ?: return
        val age = monotonicMillis - active.archive.trigger.monotonicMillis
        if (age < 0) {
            sealActive(IncidentStatus.INTERRUPTED, recovered = false, truncated = false)
        } else if (age >= policy.maxSegmentMillis) {
            sealActive(active.archive.status, active.archive.recovered, truncated = true)
        } else if (monotonicMillis - active.lastFailureMillis >=
            maxOf(policy.postWindowMillis, policy.mergeWindowMillis)) {
            sealActive(active.archive.status, active.archive.recovered, truncated = false)
        }
    }

    fun endSession(event: DiagnosticEvent) {
        require(event.kind == DiagnosticEventKind.SESSION_ENDED)
        record(event)
        val active = checkpoint.active
        if (active?.archive?.sessionId == event.sessionId) {
            sealActive(IncidentStatus.ENDED, active.archive.recovered, truncated = false)
        }
        if (checkpoint.sessionId == event.sessionId || checkpoint.ring.isNotEmpty()) {
            checkpoint = checkpoint.copy(sessionId = null, ring = emptyList())
            checkpointDirty = true
            persistAndFlush()
        }
    }

    fun listArchives(): List<IncidentArchive> = store.listArchives()

    fun loadArchive(id: String): IncidentArchive? {
        require(isSafeId(id))
        return store.loadArchive(id)
    }

    private fun accept(source: IncidentCheckpoint, event: DiagnosticEvent): IncidentCheckpoint {
        var pendingArchives = source.pendingArchives
        var active = source.active
        val activeAge = active?.let { event.monotonicMillis - it.archive.trigger.monotonicMillis }
        val incidentGap = active != null && event.isTrigger() &&
            event.monotonicMillis - active.lastFailureMillis > policy.mergeWindowMillis
        val segmentExpired = active != null && activeAge != null && activeAge >= policy.maxSegmentMillis
        if (incidentGap || segmentExpired) {
            val previous = checkNotNull(active)
            pendingArchives = pendingArchives + previous.archive.copy(
                status = if (event.kind == DiagnosticEventKind.SESSION_ENDED) IncidentStatus.ENDED
                else previous.archive.status,
                truncated = previous.archive.truncated || segmentExpired,
            )
            active = null
        }
        val ring = trimRing((if (active == null) source.ring else emptyList()) + event, event.monotonicMillis)
        var justTriggered = false
        if (active == null && event.isTrigger()) {
            val events = ring.filter { it.monotonicMillis <= event.monotonicMillis }
            val archive = IncidentArchive(
                id = validNewId(),
                sessionId = event.sessionId,
                trigger = event,
                status = if (event.kind == DiagnosticEventKind.RUNTIME_FAILURE) IncidentStatus.CONFIRMED
                else IncidentStatus.SUSPECTED,
                recovered = false,
                truncated = events.size > policy.maxEventsPerSegment,
                events = boundedEvents(events, event),
            )
            active = ActiveIncident(archive, event.monotonicMillis)
            justTriggered = true
        } else if (active != null) {
            active = updateActive(active, event)
        }
        if (active != null && !justTriggered) {
            val archive = active.archive
            val elapsed = event.monotonicMillis - archive.trigger.monotonicMillis
            val extended = boundedEvents(archive.events + event, archive.trigger)
            val truncated = archive.truncated || archive.events.size + 1 > policy.maxEventsPerSegment
            active = active.copy(archive = archive.copy(events = extended, truncated = truncated))
            if (elapsed >= policy.maxSegmentMillis || event.kind == DiagnosticEventKind.SESSION_ENDED) {
                val status = if (event.kind == DiagnosticEventKind.SESSION_ENDED) IncidentStatus.ENDED
                else active.archive.status
                return source.copy(ring = emptyList(), active = null,
                    pendingArchives = pendingArchives + active.archive.copy(
                        status = status,
                        truncated = active.archive.truncated || event.kind != DiagnosticEventKind.SESSION_ENDED,
                    ))
            }
        }
        return source.copy(
            ring = if (active == null) ring else emptyList(),
            active = active,
            pendingArchives = pendingArchives,
        )
    }

    private fun updateActive(active: ActiveIncident, event: DiagnosticEvent): ActiveIncident {
        var archive = active.archive
        var lastFailure = active.lastFailureMillis
        var successes = active.consecutiveSuccesses
        var lastSuccess = active.lastSuccessMillis
        var lastSuccessTimestamp = active.lastSuccessTimestampMillis
        when (event.kind) {
            DiagnosticEventKind.PROBE_FAILURE -> {
                if (event.monotonicMillis - lastFailure > policy.mergeWindowMillis) {
                    archive = archive.copy(status = IncidentStatus.SUSPECTED, recovered = false)
                }
                if (archive.recovered) archive = archive.copy(status = IncidentStatus.SUSPECTED, recovered = false)
                lastFailure = event.monotonicMillis
                successes = 0
                lastSuccess = null
                lastSuccessTimestamp = null
                if (archive.trigger.kind == DiagnosticEventKind.PROBE_FAILURE &&
                    archive.trigger.path != DiagnosticPath.UNKNOWN &&
                    event.path == archive.trigger.path &&
                    archive.trigger.target != DiagnosticTarget.NONE &&
                    event.target != DiagnosticTarget.NONE && event.target != archive.trigger.target
                ) archive = archive.copy(status = IncidentStatus.CONFIRMED)
            }
            DiagnosticEventKind.RUNTIME_FAILURE -> {
                archive = archive.copy(status = IncidentStatus.CONFIRMED, recovered = false)
                lastFailure = event.monotonicMillis
                successes = 0
                lastSuccess = null
                lastSuccessTimestamp = null
            }
            DiagnosticEventKind.PROBE_SUCCESS -> {
                if (event.path != DiagnosticPath.UNKNOWN &&
                    lastSuccess != event.monotonicMillis && lastSuccessTimestamp != event.timestampMillis
                ) {
                    successes = if (lastSuccess != null && event.monotonicMillis > lastSuccess) successes + 1 else 1
                    lastSuccess = event.monotonicMillis
                    lastSuccessTimestamp = event.timestampMillis
                }
                if (allAffectedPathsRecovered(active, event)) {
                    archive = archive.copy(status = IncidentStatus.RECOVERED, recovered = true)
                }
            }
            DiagnosticEventKind.PROBE_UNAVAILABLE -> {
                if (event.path != DiagnosticPath.UNKNOWN &&
                    activeAffectedPaths(active).contains(event.path)
                ) {
                    successes = 0
                    lastSuccess = null
                    lastSuccessTimestamp = null
                    if (archive.recovered) archive = archive.copy(status = IncidentStatus.SUSPECTED, recovered = false)
                }
            }
            else -> Unit
        }
        return active.copy(archive = archive, lastFailureMillis = lastFailure,
            consecutiveSuccesses = successes, lastSuccessMillis = lastSuccess,
            lastSuccessTimestampMillis = lastSuccessTimestamp)
    }

    private fun trimRing(events: List<DiagnosticEvent>, now: Long): List<DiagnosticEvent> =
        events.filter { it.monotonicMillis <= now && now - it.monotonicMillis <= policy.preWindowMillis }
            .takeLast(policy.ringMaxEvents)

    private fun relevantHistory(active: ActiveIncident): List<DiagnosticEvent> {
        val events = active.archive.events
        val triggerIndex = events.indexOf(active.archive.trigger)
        val afterTrigger = if (triggerIndex >= 0) events.drop(triggerIndex) else
            events.filter { it.monotonicMillis > active.archive.trigger.monotonicMillis }
        return (listOf(active.archive.trigger) + afterTrigger).distinct()
    }

    private fun activeAffectedPaths(active: ActiveIncident): Set<DiagnosticPath> =
        relevantHistory(active).mapNotNull { event ->
            failurePath(event)?.takeUnless { it == DiagnosticPath.UNKNOWN }
        }.toSet()

    private fun allAffectedPathsRecovered(active: ActiveIncident, event: DiagnosticEvent): Boolean {
        if (active.archive.truncated || active.archive.events.size + 1 > policy.maxEventsPerSegment) return false
        val history = (relevantHistory(active) + event).distinct()
        val affectedPaths = history.mapNotNull { failurePath(it) }
        if (affectedPaths.isEmpty() || affectedPaths.any { it == DiagnosticPath.UNKNOWN }) return false

        for (path in affectedPaths.toSet()) {
            var failed = false
            var successes = 0
            var lastSuccessMonotonic: Long? = null
            var lastSuccessWall: Long? = null
            for (item in history) {
                if (failurePath(item) == path) {
                    failed = true
                    successes = 0
                    lastSuccessMonotonic = null
                    lastSuccessWall = null
                } else if (item.kind == DiagnosticEventKind.PROBE_UNAVAILABLE && item.path == path) {
                    successes = 0
                    lastSuccessMonotonic = null
                    lastSuccessWall = null
                } else if (failed && item.kind == DiagnosticEventKind.PROBE_SUCCESS && item.path == path) {
                    val monotonic = lastSuccessMonotonic
                    val wall = lastSuccessWall
                    if ((monotonic == null || item.monotonicMillis > monotonic) && wall != item.timestampMillis) {
                        successes += 1
                        lastSuccessMonotonic = item.monotonicMillis
                        lastSuccessWall = item.timestampMillis
                    }
                }
            }
            if (!failed || successes < 2) return false
        }
        return true
    }

    private fun failurePath(event: DiagnosticEvent): DiagnosticPath? = when (event.kind) {
        DiagnosticEventKind.PROBE_FAILURE -> event.path
        DiagnosticEventKind.RUNTIME_FAILURE -> DiagnosticPath.CORE
        else -> null
    }

    private fun interruptCurrent(source: IncidentCheckpoint): IncidentCheckpoint {
        val archive = source.active?.archive?.copy(status = IncidentStatus.INTERRUPTED, recovered = false)
            ?: source.ring.takeIf { it.isNotEmpty() }?.let { interruptedArchive(it.first(), it) }
        return source.copy(
            ring = emptyList(),
            active = null,
            pendingArchives = source.pendingArchives + listOfNotNull(archive),
        )
    }

    private fun interruptedArchive(trigger: DiagnosticEvent, events: List<DiagnosticEvent>) = IncidentArchive(
        id = validNewId(),
        sessionId = trigger.sessionId,
        trigger = trigger,
        status = IncidentStatus.INTERRUPTED,
        recovered = false,
        truncated = events.size > policy.maxEventsPerSegment,
        events = boundedEvents(events, trigger),
    )

    private fun boundedEvents(events: List<DiagnosticEvent>, trigger: DiagnosticEvent): List<DiagnosticEvent> {
        val limit = policy.maxEventsPerSegment
        if (events.size <= limit) return events
        val before = events.filter { it.monotonicMillis < trigger.monotonicMillis }
        val after = events.filter { it.monotonicMillis > trigger.monotonicMillis }
        if (after.isEmpty()) return (before.takeLast(limit - 1) + trigger).sortedBy { it.monotonicMillis }
        val beforeLimit = if (limit <= 2) limit - 1 else minOf(policy.ringMaxEvents, (limit - 1) / 2)
        val retainedBefore = before.takeLast(beforeLimit)
        val retainedAfter = after.takeLast(limit - 1 - retainedBefore.size)
        return (retainedBefore + trigger + retainedAfter).sortedBy { it.monotonicMillis }
    }

    private fun sealActive(status: IncidentStatus, recovered: Boolean, truncated: Boolean) {
        val active = checkpoint.active ?: return
        checkpoint = checkpoint.copy(
            active = null,
            ring = emptyList(),
            pendingArchives = checkpoint.pendingArchives + active.archive.copy(
                status = status,
                recovered = recovered,
                truncated = truncated || active.archive.truncated,
            ),
        )
        checkpointDirty = true
        persistAndFlush()
    }

    private fun validNewId(): String = newId().also { id ->
        require(isSafeId(id)) { "newId must return 32..64 lowercase hex characters" }
        require(id != checkpoint.active?.archive?.id && checkpoint.pendingArchives.none { it.id == id }) {
            "newId must return a fresh identifier"
        }
        require(store.listArchives().none { it.id == id }) { "newId must return a fresh identifier" }
    }

    private fun retryWrites() {
        if (checkpointDirty) store.saveCheckpoint(checkpoint).also { checkpointDirty = false }
        flushArchive()
    }

    private fun persistAndFlush() {
        store.saveCheckpoint(checkpoint)
        checkpointDirty = false
        flushArchive()
    }

    private fun flushArchive() {
        while (checkpoint.pendingArchives.isNotEmpty()) {
            val archive = checkpoint.pendingArchives.first()
            store.saveArchive(archive)
            checkpoint = checkpoint.copy(pendingArchives = checkpoint.pendingArchives.drop(1))
            checkpointDirty = true
            store.saveCheckpoint(checkpoint)
            checkpointDirty = false
        }
    }

    private fun DiagnosticEvent.isTrigger() =
        kind == DiagnosticEventKind.PROBE_FAILURE || kind == DiagnosticEventKind.RUNTIME_FAILURE
}
