package com.yokhan.gates.shared.domain.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncidentRecorderTest {
    @Test
    fun triggerIncludesRecentEventsAndPostWindowIsSealed() {
        val store = MemoryStore()
        val recorder = recorder(store)
        recorder.record(event(1_000, DiagnosticEventKind.SESSION_STARTED))
        recorder.record(event(2_000, DiagnosticEventKind.NETWORK_CHANGED))
        recorder.record(event(3_000, DiagnosticEventKind.PROBE_FAILURE, DiagnosticTarget.GSTATIC))

        val active = assertNotNull(store.checkpoint?.active)
        assertEquals(listOf(DiagnosticEventKind.SESSION_STARTED, DiagnosticEventKind.NETWORK_CHANGED,
            DiagnosticEventKind.PROBE_FAILURE), active.archive.events.map { it.kind })
        recorder.tick(183_000, 183_000)
        assertTrue(store.archives.isEmpty())
        assertNotNull(store.checkpoint?.active)
        recorder.tick(303_000, 303_000)
        assertEquals(IncidentStatus.SUSPECTED, store.archives.single().status)
        assertEquals(3, store.archives.single().events.size)
        assertNull(store.checkpoint?.active)
    }

    @Test
    fun distinctTargetFailureConfirmsAndTwoTimedSuccessesRecover() {
        val store = MemoryStore()
        val recorder = recorder(store)
        recorder.record(event(10, DiagnosticEventKind.PROBE_FAILURE, DiagnosticTarget.GSTATIC, path = DiagnosticPath.SYSTEM))
        recorder.record(event(20, DiagnosticEventKind.PROBE_FAILURE, DiagnosticTarget.YOUTUBE, path = DiagnosticPath.SYSTEM))
        assertEquals(IncidentStatus.CONFIRMED, store.checkpoint?.active?.archive?.status)

        recorder.record(event(30, DiagnosticEventKind.PROBE_SUCCESS, DiagnosticTarget.GSTATIC, path = DiagnosticPath.SYSTEM))
        recorder.record(event(30, DiagnosticEventKind.PROBE_SUCCESS, DiagnosticTarget.YOUTUBE, path = DiagnosticPath.SYSTEM))
        recorder.record(event(31, DiagnosticEventKind.PROBE_SUCCESS, DiagnosticTarget.YOUTUBE,
            path = DiagnosticPath.SYSTEM).copy(timestampMillis = 30))
        assertFalse(store.checkpoint?.active?.archive?.recovered == true)
        recorder.record(event(32, DiagnosticEventKind.PROBE_SUCCESS, DiagnosticTarget.GSTATIC, path = DiagnosticPath.SYSTEM))
        assertEquals(IncidentStatus.RECOVERED, store.checkpoint?.active?.archive?.status)
        assertTrue(store.checkpoint?.active?.archive?.recovered == true)
    }

    @Test
    fun unavailableDoesNotCountAsFailureOrRecovery() {
        val store = MemoryStore()
        val recorder = recorder(store)
        recorder.record(event(10, DiagnosticEventKind.PROBE_FAILURE, DiagnosticTarget.GSTATIC, path = DiagnosticPath.SYSTEM))
        recorder.record(event(20, DiagnosticEventKind.PROBE_UNAVAILABLE, DiagnosticTarget.YOUTUBE, path = DiagnosticPath.UNKNOWN))
        recorder.record(event(30, DiagnosticEventKind.PROBE_SUCCESS, DiagnosticTarget.GSTATIC, path = DiagnosticPath.SYSTEM))

        assertEquals(IncidentStatus.SUSPECTED, store.checkpoint?.active?.archive?.status)
        assertFalse(store.checkpoint?.active?.archive?.recovered == true)
    }

    @Test
    fun successOnSystemPathCannotRecoverCoreProxyOutage() {
        val store = MemoryStore()
        val recorder = recorder(store)
        recorder.record(event(1, DiagnosticEventKind.PROBE_FAILURE, DiagnosticTarget.GSTATIC, path = DiagnosticPath.CORE))
        recorder.record(event(2, DiagnosticEventKind.PROBE_SUCCESS, DiagnosticTarget.GSTATIC, path = DiagnosticPath.SYSTEM))
        recorder.record(event(3, DiagnosticEventKind.PROBE_SUCCESS, DiagnosticTarget.GSTATIC, path = DiagnosticPath.CORE))

        assertFalse(store.checkpoint?.active?.archive?.recovered == true)
        recorder.record(event(4, DiagnosticEventKind.PROBE_SUCCESS, DiagnosticTarget.GSTATIC, path = DiagnosticPath.CORE))
        assertTrue(store.checkpoint?.active?.archive?.recovered == true)
    }

    @Test
    fun mixedPathFailuresRequireRecoveryOnEveryPathAndDoNotCrossConfirm() {
        val store = MemoryStore()
        val recorder = recorder(store)
        recorder.record(event(1, DiagnosticEventKind.PROBE_FAILURE, DiagnosticTarget.GSTATIC, path = DiagnosticPath.SYSTEM))
        recorder.record(event(2, DiagnosticEventKind.PROBE_FAILURE, DiagnosticTarget.YOUTUBE, path = DiagnosticPath.CORE))
        assertEquals(IncidentStatus.SUSPECTED, store.checkpoint?.active?.archive?.status)

        recorder.record(event(3, DiagnosticEventKind.PROBE_SUCCESS, DiagnosticTarget.GSTATIC, path = DiagnosticPath.SYSTEM))
        recorder.record(event(4, DiagnosticEventKind.PROBE_SUCCESS, DiagnosticTarget.GSTATIC, path = DiagnosticPath.SYSTEM))
        assertFalse(store.checkpoint?.active?.archive?.recovered == true)

        recorder.record(event(5, DiagnosticEventKind.PROBE_SUCCESS, DiagnosticTarget.GSTATIC, path = DiagnosticPath.CORE))
        assertFalse(store.checkpoint?.active?.archive?.recovered == true)
        recorder.record(event(6, DiagnosticEventKind.PROBE_SUCCESS, DiagnosticTarget.GSTATIC, path = DiagnosticPath.CORE))
        assertTrue(store.checkpoint?.active?.archive?.recovered == true)
    }

    @Test
    fun unavailableOnTriggerPathBreaksSuccessStreakAndRuntimeUsesCoreRecovery() {
        val store = MemoryStore()
        val recorder = recorder(store)
        recorder.record(event(1, DiagnosticEventKind.PROBE_FAILURE, DiagnosticTarget.GSTATIC, path = DiagnosticPath.SYSTEM))
        recorder.record(event(2, DiagnosticEventKind.PROBE_SUCCESS, DiagnosticTarget.GSTATIC, path = DiagnosticPath.SYSTEM))
        recorder.record(event(3, DiagnosticEventKind.PROBE_UNAVAILABLE, DiagnosticTarget.GSTATIC, path = DiagnosticPath.SYSTEM))
        recorder.record(event(4, DiagnosticEventKind.PROBE_SUCCESS, DiagnosticTarget.GSTATIC, path = DiagnosticPath.SYSTEM))
        assertFalse(store.checkpoint?.active?.archive?.recovered == true)
        recorder.record(event(5, DiagnosticEventKind.PROBE_SUCCESS, DiagnosticTarget.GSTATIC, path = DiagnosticPath.SYSTEM))
        assertTrue(store.checkpoint?.active?.archive?.recovered == true)

        val runtimeStore = MemoryStore()
        val runtimeRecorder = recorder(runtimeStore)
        runtimeRecorder.record(event(10, DiagnosticEventKind.RUNTIME_FAILURE))
        runtimeRecorder.record(event(11, DiagnosticEventKind.PROBE_SUCCESS, path = DiagnosticPath.CORE))
        runtimeRecorder.record(event(12, DiagnosticEventKind.PROBE_SUCCESS, path = DiagnosticPath.CORE))
        assertTrue(runtimeStore.checkpoint?.active?.archive?.recovered == true)
    }

    @Test
    fun phaseTimingsRejectNegativeValues() {
        val base = event(1, DiagnosticEventKind.PROBE_SUCCESS)
        assertFailsWith<IllegalArgumentException> { base.copy(dnsMillis = -1) }
        assertFailsWith<IllegalArgumentException> { base.copy(connectMillis = -1) }
        assertFailsWith<IllegalArgumentException> { base.copy(tlsMillis = -1) }
    }

    @Test
    fun sessionSwitchAndMonotonicResetSealInterruptedNotRecovered() {
        val store = MemoryStore()
        val recorder = recorder(store)
        recorder.record(event(1_000, DiagnosticEventKind.PROBE_FAILURE, DiagnosticTarget.GSTATIC))
        recorder.record(event(2_000, DiagnosticEventKind.SESSION_STARTED, session = SESSION_2))
        assertEquals(IncidentStatus.INTERRUPTED, store.archives.single().status)
        assertFalse(store.archives.single().recovered)

        recorder.record(event(3_000, DiagnosticEventKind.PROBE_FAILURE, DiagnosticTarget.YOUTUBE, session = SESSION_2))
        recorder.record(event(1, DiagnosticEventKind.OBSERVER_GAP, session = SESSION_2))
        assertEquals(IncidentStatus.INTERRUPTED, store.archives.last().status)
        assertFalse(store.archives.last().recovered)
    }

    @Test
    fun restartSealsActiveAndRingEvidenceAsInterrupted() {
        val activeStore = MemoryStore()
        val oldRecorder = recorder(activeStore)
        oldRecorder.record(event(10, DiagnosticEventKind.RUNTIME_FAILURE))
        val resumedStore = MemoryStore().also { it.checkpoint = activeStore.checkpoint }

        recorder(resumedStore)
        assertEquals(IncidentStatus.INTERRUPTED, resumedStore.archives.single().status)
        assertFalse(resumedStore.archives.single().recovered)

        val ringStore = MemoryStore().also {
            it.checkpoint = IncidentCheckpoint(sessionId = SESSION_1, ring = listOf(
                event(1, DiagnosticEventKind.SESSION_STARTED),
            ))
        }
        recorder(ringStore)
        assertEquals(IncidentStatus.INTERRUPTED, ringStore.archives.single().status)
    }

    @Test
    fun startupDrainsFullOutboxAndDoesNotDuplicateOverlappingRing() {
        val store = MemoryStore()
        val trigger = event(10, DiagnosticEventKind.RUNTIME_FAILURE)
        val activeArchive = IncidentArchive(
            id = id(20), sessionId = SESSION_1, trigger = trigger,
            status = IncidentStatus.CONFIRMED, recovered = false, truncated = false,
            events = listOf(trigger),
        )
        val queued = (1L..4L).map { sequence ->
            val queuedTrigger = event(sequence, DiagnosticEventKind.PROBE_FAILURE,
                DiagnosticTarget.GSTATIC, session = SESSION_2)
            IncidentArchive(id(sequence), SESSION_2, queuedTrigger, IncidentStatus.SUSPECTED,
                false, false, listOf(queuedTrigger))
        }
        store.checkpoint = IncidentCheckpoint(
            sessionId = SESSION_1,
            ring = listOf(trigger),
            active = ActiveIncident(activeArchive, trigger.monotonicMillis),
            pendingArchives = queued,
        )

        recorder(store)

        assertEquals(5, store.archives.size)
        val recovered = store.archives.single { it.id == activeArchive.id }
        assertEquals(IncidentStatus.INTERRUPTED, recovered.status)
        assertEquals(listOf(trigger), recovered.events)
        assertTrue(store.checkpoint?.pendingArchives?.isEmpty() == true)
    }

    @Test
    fun quotaLikeEventCapTruncatesWithoutGrowingActiveArchive() {
        val store = MemoryStore()
        val recorder = IncidentRecorder(
            policy = IncidentPolicy(ringMaxEvents = 4, maxEventsPerSegment = 2),
            store = store,
            newId = ids(),
        )
        recorder.record(event(0, DiagnosticEventKind.SESSION_STARTED))
        val trigger = event(1, DiagnosticEventKind.RUNTIME_FAILURE)
        recorder.record(trigger)
        recorder.record(event(2, DiagnosticEventKind.NETWORK_CHANGED))
        recorder.record(event(3, DiagnosticEventKind.OBSERVER_GAP))

        val archive = assertNotNull(store.checkpoint?.active?.archive)
        assertEquals(2, archive.events.size)
        assertTrue(archive.truncated)
        assertTrue(trigger in archive.events)
        assertTrue(archive.events.any { it.kind == DiagnosticEventKind.SESSION_STARTED })
    }

    @Test
    fun checkpointAndArchiveFailuresAreRetriedIdempotently() {
        val store = MemoryStore()
        val recorder = recorder(store)
        val failure = event(1, DiagnosticEventKind.RUNTIME_FAILURE)
        store.failNextCheckpoint = true
        assertFailsWith<StoreFailure> { recorder.record(failure) }
        recorder.record(failure)
        assertNotNull(store.checkpoint?.active)

        store.failNextArchive = true
        assertFailsWith<StoreFailure> { recorder.endSession(event(2, DiagnosticEventKind.SESSION_ENDED)) }
        recorder.endSession(event(2, DiagnosticEventKind.SESSION_ENDED))
        assertEquals(1, store.archives.size)
        assertEquals(IncidentStatus.ENDED, store.archives.single().status)
    }

    @Test
    fun invalidSessionAndGeneratedIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            DiagnosticEvent(0, 0, "unsafe-session", DiagnosticEventKind.SESSION_STARTED)
        }
        val store = MemoryStore()
        val recorder = IncidentRecorder(IncidentPolicy(), store) { "../unsafe" }
        assertFailsWith<IllegalArgumentException> {
            recorder.record(event(1, DiagnosticEventKind.PROBE_FAILURE, DiagnosticTarget.GSTATIC))
        }
    }

    @Test
    fun sealedArchiveCanBeListedAndLoadedBySelectedId() {
        val store = MemoryStore()
        val recorder = recorder(store)
        recorder.record(event(1, DiagnosticEventKind.RUNTIME_FAILURE))
        recorder.endSession(event(2, DiagnosticEventKind.SESSION_ENDED))

        val listed = recorder.listArchives().single()
        assertEquals(listed, recorder.loadArchive(listed.id))
        assertNull(recorder.loadArchive("000000000000000000000000000000ff"))
    }

    @Test
    fun endingSessionWithoutIncidentClearsRingAndDoesNotRecoverAsInterrupted() {
        val store = MemoryStore()
        val recorder = recorder(store)
        recorder.record(event(1, DiagnosticEventKind.SESSION_STARTED))
        recorder.endSession(event(2, DiagnosticEventKind.SESSION_ENDED))

        assertTrue(store.archives.isEmpty())
        assertNull(store.checkpoint?.sessionId)
        assertTrue(store.checkpoint?.ring?.isEmpty() == true)
        recorder(store)
        assertTrue(store.archives.isEmpty())
    }

    private fun recorder(store: MemoryStore) = IncidentRecorder(IncidentPolicy(), store, ids())

    private fun ids(): () -> String {
        var value = 0L
        return {
            val current = value++
            id(current)
        }
    }

    private fun id(value: Long) = value.toString(16).padStart(32, '0')

    private fun event(
        monotonic: Long,
        kind: DiagnosticEventKind,
        target: DiagnosticTarget = DiagnosticTarget.NONE,
        session: String = SESSION_1,
        path: DiagnosticPath = DiagnosticPath.UNKNOWN,
    ) = DiagnosticEvent(monotonic, monotonic, session, kind, target, path = path)

    private class MemoryStore : IncidentStore {
        var checkpoint: IncidentCheckpoint? = null
        val archives = mutableListOf<IncidentArchive>()
        var failNextCheckpoint = false
        var failNextArchive = false

        override fun loadCheckpoint(): IncidentCheckpoint? = checkpoint

        override fun saveCheckpoint(checkpoint: IncidentCheckpoint) {
            if (failNextCheckpoint) {
                failNextCheckpoint = false
                throw StoreFailure()
            }
            this.checkpoint = checkpoint
        }

        override fun saveArchive(archive: IncidentArchive) {
            if (failNextArchive) {
                failNextArchive = false
                throw StoreFailure()
            }
            val existing = archives.firstOrNull { it.id == archive.id }
            if (existing == null) archives += archive else assertEquals(existing, archive)
        }

        override fun listArchives(): List<IncidentArchive> = archives.toList()

        override fun loadArchive(id: String): IncidentArchive? = archives.firstOrNull { it.id == id }
    }

    private class StoreFailure : RuntimeException()

    private companion object {
        const val SESSION_1 = "00000000000000000000000000000001"
        const val SESSION_2 = "00000000000000000000000000000002"
    }
}
