package com.yokhan.gates.shared.domain.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryDeliveryTest {
    private val session = "a".repeat(32)
    private fun report(id: Int = 1): RecoveryReport {
        val event = DiagnosticEvent(10, 10, session, DiagnosticEventKind.RUNTIME_FAILURE,
            path = DiagnosticPath.CORE, transport = DiagnosticTransport.HYSTERIA2)
        return assertNotNull(compactRecoveryReport(IncidentArchive(id.toString(16).padStart(32, '0'),
            session, event, IncidentStatus.CONFIRMED, false, false, listOf(event))))
    }

    @Test fun waitsForNetworkAndUserSettingThenAcknowledgesPersistently() {
        val store = Store()
        val queue = RecoveryDeliveryQueue(store)
        assertEquals(RecoveryEnqueueResult.QUEUED, queue.enqueue(report()))
        assertNull(queue.nextDue(100, false, true))
        assertNull(queue.nextDue(100, true, false))
        assertNotNull(queue.nextDue(100, true, true))
        queue.complete(report().id, 100, true)
        val restarted = RecoveryDeliveryQueue(store)
        assertEquals(RecoveryEnqueueResult.ALREADY_KNOWN, restarted.enqueue(report()))
        assertNull(restarted.nextDue(1000, true, true))
    }

    @Test fun retrySurvivesRestartBacksOffAndHandlesClockGoingBack() {
        val store = Store()
        val queue = RecoveryDeliveryQueue(store)
        queue.enqueue(report())
        queue.complete(report().id, 1000, false)
        assertNull(RecoveryDeliveryQueue(store).nextDue(60_999, true, true))
        assertNotNull(RecoveryDeliveryQueue(store).nextDue(61_000, true, true))
        assertNotNull(RecoveryDeliveryQueue(store).nextDue(999, true, true))
        queue.complete(report().id, 61_000, false, 180_000)
        assertNull(queue.nextDue(240_999, true, true))
        assertNotNull(queue.nextDue(241_000, true, true))
    }

    @Test fun fullQueueNeverEvictsUnsentReportsAndDuplicateDoesNotGrowIt() {
        val queue = RecoveryDeliveryQueue(Store())
        repeat(8) { queue.enqueue(report(it + 1)) }
        assertEquals(RecoveryEnqueueResult.FULL, queue.enqueue(report(9)))
        assertTrue(queue.snapshot().capacityReached)
        assertEquals(RecoveryEnqueueResult.ALREADY_KNOWN, queue.enqueue(report()))
        assertEquals(8, queue.snapshot().pending.size)
        queue.complete(report().id, 1, true)
        assertFalse(queue.snapshot().capacityReached)
        assertEquals(RecoveryEnqueueResult.QUEUED, queue.enqueue(report(9)))
    }

    @Test fun failedPersistenceDoesNotConsumeOrAcknowledgeReport() {
        val store = Store()
        val queue = RecoveryDeliveryQueue(store)
        queue.enqueue(report())
        store.fail = true
        assertFailsWith<IllegalStateException> { queue.complete(report().id, 10, true) }
        assertEquals(1, queue.snapshot().pending.size)
    }

    @Test fun compactEvidenceKeepsTriggerTailAndNoSuccessOnlyIncident() {
        val sample = report()
        val events = (0..100).map { sample.events.single().copy(timestampMillis = it.toLong(),
            monotonicMillis = it.toLong(), kind = DiagnosticEventKind.PROBE_SUCCESS) }
        val failure = sample.events.single().copy(timestampMillis = 30, monotonicMillis = 30)
        val archive = IncidentArchive(sample.id, session, failure, IncidentStatus.RECOVERED, true,
            false, events + failure)
        val compact = assertNotNull(compactRecoveryReport(archive))
        assertTrue(compact.truncated && compact.recovered)
        assertTrue(failure in compact.events)
        assertTrue(compact.events.size <= 24)
        assertEquals(100L, compact.events.last().monotonicMillis)
        assertNull(compactRecoveryReport(archive.copy(trigger = events.first())))
    }

    private class Store : RecoveryDeliveryStore {
        var state: RecoveryDeliveryState? = null
        var fail = false
        override fun load() = state
        override fun save(state: RecoveryDeliveryState) {
            check(!fail)
            this.state = state
        }
    }
}
