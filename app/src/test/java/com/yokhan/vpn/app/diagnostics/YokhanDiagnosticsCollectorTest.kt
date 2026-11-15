package com.yokhan.gates.app.diagnostics

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class YokhanDiagnosticsCollectorTest {

    @Test
    fun mapBoundedParallel_preservesOrderRespectsLimitAndSpeedsUp() = runBlocking {
        val items = (1..12).toList()
        val inFlight = AtomicInteger(0)
        val peakInFlight = AtomicInteger(0)

        val started = System.nanoTime()
        val result = mapBoundedParallel(items, concurrency = 4) { value ->
            val current = inFlight.incrementAndGet()
            peakInFlight.accumulateAndGet(current, ::maxOf)
            try {
                delay(40)
                value * 10
            } finally {
                inFlight.decrementAndGet()
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertEquals(items.map { it * 10 }, result)
        assertTrue("Expected concurrency <= 4 but saw ${peakInFlight.get()}", peakInFlight.get() <= 4)
        assertTrue("Expected parallel execution (>1 in flight), peak was ${peakInFlight.get()}", peakInFlight.get() >= 2)
        // 12 * 40ms sequential would take >= 480ms; concurrency 4 completes in ~120-250ms.
        assertTrue("Expected parallel runtime < 420ms but took ${elapsedMs}ms", elapsedMs < 420L)
    }
}
