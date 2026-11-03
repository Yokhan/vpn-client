package com.yokhan.gates.windows.service

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SingBoxProcessLauncherLifecycleTest {
    @Test
    fun preflightRedirectsCombinedOutputToDiscard() {
        val builder = preflightProcessBuilder(Path.of("sing-box.exe"), Path.of("candidate.json"))

        assertTrue(builder.redirectErrorStream())
        assertSame(ProcessBuilder.Redirect.DISCARD, builder.redirectOutput())
    }

    @Test
    fun awaitStoppedReturnsImmediatelyWhenAlreadyExited() {
        val process = FakeProcess(alive = false)

        assertTrue(ProcessTunnelChild(process).awaitStopped(5000))
        assertTrue(process.waitTimeoutsNanos.isEmpty())
        assertEquals(0, process.forceCalls)
    }

    @Test
    fun gracefulAndForcedWaitsShareOneMonotonicBudget() {
        var now = 0L
        val process = FakeProcess(
            alive = true,
            timedWait = { process, index, _ ->
                if (index == 0) {
                    now += 60_000_000L
                    false
                } else {
                    process.processAlive = false
                    true
                }
            },
        )
        val child = ProcessTunnelChild(process) { now }

        assertTrue(child.awaitStopped(100))
        assertEquals(listOf(50_000_000L, 40_000_000L), process.waitTimeoutsNanos)
        assertEquals(1, process.forceCalls)
    }

    @Test
    fun zeroOrExpiredBudgetForcesWithoutClaimingUnconfirmedStop() {
        val zeroBudgetProcess = FakeProcess(alive = true)
        assertFalse(ProcessTunnelChild(zeroBudgetProcess).awaitStopped(0))
        assertEquals(0, zeroBudgetProcess.waitTimeoutsNanos.size)
        assertEquals(1, zeroBudgetProcess.forceCalls)

        var now = 0L
        val expiredBudgetProcess = FakeProcess(alive = true, timedWait = { _, _, _ ->
            now = 10_000_000L
            false
        })
        assertFalse(ProcessTunnelChild(expiredBudgetProcess) { now }.awaitStopped(10))
        assertEquals(listOf(5_000_000L), expiredBudgetProcess.waitTimeoutsNanos)
        assertEquals(1, expiredBudgetProcess.forceCalls)
    }

    @Test
    fun interruptionForcesChildAndRestoresInterruptStatus() {
        val interrupted = InterruptedException("test interruption")
        val process = FakeProcess(alive = true, timedWait = { _, _, _ -> throw interrupted })

        try {
            val thrown = kotlin.test.assertFailsWith<InterruptedException> {
                ProcessTunnelChild(process).awaitStopped(5000)
            }
            assertSame(interrupted, thrown)
            assertEquals(1, process.forceCalls)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun preflightTerminationForcesAndWaitsWithinItsCleanupBudget() {
        val process = FakeProcess(alive = true, timedWait = { process, _, _ ->
            process.processAlive = false
            true
        })

        assertTrue(terminatePreflightProcess(process, 250))
        assertEquals(1, process.forceCalls)
        assertEquals(1, process.waitTimeoutsNanos.size)
        assertTrue(process.waitTimeoutsNanos.single() in 1L..TimeUnit.MILLISECONDS.toNanos(250))
    }
}

private class FakeProcess(
    alive: Boolean,
    private val timedWait: (process: FakeProcess, index: Int, timeoutNanos: Long) -> Boolean = { _, _, _ -> false },
) : Process() {
    var processAlive: Boolean = alive
    var forceCalls: Int = 0
    val waitTimeoutsNanos = mutableListOf<Long>()

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun getInputStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())

    override fun waitFor(): Int = error("Untimed waits are not expected")

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        val timeoutNanos = unit.toNanos(timeout)
        waitTimeoutsNanos += timeoutNanos
        return timedWait(this, waitTimeoutsNanos.lastIndex, timeoutNanos)
    }

    override fun exitValue(): Int {
        check(!processAlive) { "process is still running" }
        return 0
    }

    override fun destroy() {
        processAlive = false
    }

    override fun destroyForcibly(): Process {
        forceCalls += 1
        return this
    }

    override fun isAlive(): Boolean = processAlive
}
