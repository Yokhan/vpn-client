package com.yokhan.gates.windows.service

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XhttpTunnelChildTest {

    @Test
    fun stopsSingBoxBeforeTheSidecarAndNeverTwice() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val tunnel = FakeTunnelChild(events = events)
        val sidecar = FakeSidecar(events = events).apply { healthy = true }
        val child = XhttpTunnelChild(tunnel, sidecar, spec(required = true))

        child.requestStop()
        assertTrue(child.awaitStopped(1_000))

        assertEquals(listOf("tunnel-stop", "tunnel-await", "sidecar-stop"), events.toList())
        child.awaitStopped(1_000)
        assertEquals(1, sidecar.stopCalls)
    }

    @Test
    fun failClosedWhenARequiredSidecarCannotRestart() {
        val stopped = CountDownLatch(1)
        val tunnel = FakeTunnelChild(onStop = { stopped.countDown() })
        val sidecar = FakeSidecar().apply { healthy = false }
        val child = XhttpTunnelChild(
            tunnel,
            sidecar,
            spec(required = true),
            watchdogIntervalMillis = 1,
            maxRestartAttempts = 2,
            restartBaseDelayMillis = 5,
        )

        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (child.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        assertFalse(child.isAlive())
    }

    @Test
    fun autoModeKeepsTheTunnelWhenTheSidecarIsUnavailable() {
        val tunnel = FakeTunnelChild()
        val sidecar = FakeSidecar().apply { healthy = false }
        val child = XhttpTunnelChild(
            tunnel,
            sidecar,
            spec(required = false),
            watchdogIntervalMillis = 1,
            maxRestartAttempts = 2,
            restartBaseDelayMillis = 5,
        )

        Thread.sleep(200)
        assertTrue(tunnel.isAlive())
        assertTrue(tunnel.stopCalls == 0)
        sidecar.healthy = true
        Thread.sleep(50)
        assertTrue(sidecar.startCalls > 0)
        child.requestStop()
        child.awaitStopped(1_000)
    }

    @Test
    fun restartsACrashedSidecarAndResetsItsBackoff() {
        val tunnel = FakeTunnelChild()
        val sidecar = FakeSidecar().apply { healthy = false }
        val child = XhttpTunnelChild(
            tunnel,
            sidecar,
            spec(required = true),
            watchdogIntervalMillis = 5,
            maxRestartAttempts = 10,
            restartBaseDelayMillis = 5,
        )

        Thread.sleep(100)
        val attemptsBefore = sidecar.startCalls
        assertTrue(attemptsBefore > 0)
        sidecar.healthy = true
        Thread.sleep(100)
        child.requestStop()
        assertTrue(child.awaitStopped(1_000))
    }

    private fun spec(required: Boolean): XhttpSidecarSpec = XhttpSidecarSpec(
        outboundTag = "xhttp",
        executableName = "xray.exe",
        executableSha256 = "a".repeat(64),
        listenPort = 2090,
        required = required,
        xrayConfigJson = "{}",
    )
}

private class FakeTunnelChild(
    private val events: MutableList<String>? = null,
    private val onStop: (() -> Unit)? = null,
) : TunnelChild {
    @Volatile var alive = true
    @Volatile var stopCalls = 0

    override fun isAlive(): Boolean = alive

    override fun requestStop() {
        stopCalls += 1
        events?.add("tunnel-stop")
        onStop?.invoke()
        alive = false
    }

    override fun awaitStopped(timeoutMillis: Long): Boolean {
        events?.add("tunnel-await")
        alive = false
        return true
    }
}

private class FakeSidecar(
    private val events: MutableList<String>? = null,
) : XraySidecar {
    @Volatile var healthy = false
    @Volatile var startCalls = 0
    @Volatile var stopCalls = 0

    override fun preflight(spec: XhttpSidecarSpec?): SidecarFailure? = null

    override fun start(spec: XhttpSidecarSpec): SidecarStartResult {
        startCalls += 1
        return if (healthy) SidecarStartResult.Started else SidecarStartResult.Failed(SidecarFailure.PROCESS_EXITED)
    }

    override fun isHealthy(): Boolean = healthy

    override fun stop() {
        stopCalls += 1
        events?.add("sidecar-stop")
        healthy = false
    }

    override fun state(): XhttpSidecarState =
        if (healthy) XhttpSidecarState.READY else XhttpSidecarState.FAILED

    override fun lastFailure(): SidecarFailure? = null
}
