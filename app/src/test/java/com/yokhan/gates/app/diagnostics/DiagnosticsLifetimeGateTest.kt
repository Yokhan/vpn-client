package com.yokhan.gates.app.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicBoolean

class DiagnosticsLifetimeGateTest {
    @Test
    fun fastFailedStartIsRecordedAfterPreviousOwnerExits() = runBlocking {
        val gate = DiagnosticsLifetimeGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val stopped = AtomicBoolean()
        val failed = AtomicBoolean()
        var consumed = false
        var recorded = false
        val first = async { gate.serialize { entered.complete(Unit); release.await() } }
        entered.await()
        val second = async { gate.consumeObserver(stopped::get, failed::get,
            consume = { consumed = true }, recordFailedStart = { recorded = true }) }
        yield()
        failed.set(true)
        stopped.set(true)
        assertFalse(recorded)
        release.complete(Unit)
        first.await()
        second.await()
        assertFalse(consumed)
        assertTrue(recorded)
    }

    @Test
    fun cancelledStartWithoutFailureCreatesNoIncident() = runBlocking {
        DiagnosticsLifetimeGate().consumeObserver({ true }, { false },
            consume = { error("Stopped observer must not reopen a session") },
            recordFailedStart = { error("Cancellation is not a failed connection") })
    }

    @Test
    fun observerLifetimesSerializeUntilCheckpointOwnerFinishes() = runBlocking {
        val gate = DiagnosticsLifetimeGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = async {
            gate.serialize {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = async {
            gate.serialize { secondEntered.complete(Unit) }
        }
        yield()

        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertTrue(secondEntered.isCompleted)
    }

    @Test
    fun staleOrStoppedProbeResultsAreRejected() {
        assertTrue(isProbeResultCurrent(4, 4, stopped = false))
        assertFalse(isProbeResultCurrent(4, 5, stopped = false))
        assertFalse(isProbeResultCurrent(4, 4, stopped = true))
    }
}
