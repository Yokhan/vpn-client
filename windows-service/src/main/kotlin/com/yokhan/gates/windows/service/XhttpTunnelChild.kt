package com.yokhan.gates.windows.service

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tunnel child that owns sing-box plus the optional Xray XHTTP sidecar.
 *
 * Stop order is deliberate: sing-box (the TUN/DNS owner) is stopped first so
 * no new traffic is forwarded into a dying SOCKS listener; the sidecar is
 * stopped only after sing-box has exited.
 *
 * A daemon watchdog restarts a crashed sidecar with bounded backoff. When the
 * user explicitly selected the XHTTP route, an unrecoverable sidecar failure
 * also stops sing-box (fail closed) instead of leaving a connected-looking
 * tunnel that blackholes traffic. A profile that carries XHTTP but does not
 * select it never starts this child, so plain sing-box routes are unaffected.
 */
class XhttpTunnelChild(
    private val tunnel: TunnelChild,
    private val sidecar: XraySidecar?,
    private val spec: XhttpSidecarSpec?,
    private val watchdogIntervalMillis: Long = WATCHDOG_INTERVAL_MILLIS,
    private val maxRestartAttempts: Int = MAX_RESTART_ATTEMPTS,
    private val restartBaseDelayMillis: Long = RESTART_BASE_DELAY_MILLIS,
) : TunnelChild {
    private val stopping = AtomicBoolean(false)
    private val sidecarStopped = AtomicBoolean(false)
    private val watchdog: Thread?

    init {
        watchdog = if (sidecar != null && spec != null) {
            Thread(Runnable { watchSidecar() }, "gates-xhttp-watchdog").apply {
                isDaemon = true
                start()
            }
        } else {
            null
        }
    }

    override fun isAlive(): Boolean = tunnel.isAlive()

    override fun requestStop() {
        stopping.set(true)
        watchdog?.interrupt()
        tunnel.requestStop()
    }

    override fun awaitStopped(timeoutMillis: Long): Boolean {
        stopping.set(true)
        watchdog?.interrupt()
        val stopped = tunnel.awaitStopped(timeoutMillis)
        stopSidecarOnce()
        return stopped
    }

    private fun watchSidecar() {
        val childSidecar = sidecar ?: return
        val childSpec = spec ?: return
        var attempts = 0
        while (!stopping.get() && tunnel.isAlive()) {
            if (childSidecar.isHealthy()) {
                attempts = 0
                if (!sleep(watchdogIntervalMillis)) return
                continue
            }
            attempts += 1
            if (!sleep(restartDelayMillis(attempts))) return
            if (stopping.get()) return
            val result = childSidecar.start(childSpec)
            if (result is SidecarStartResult.Started || result is SidecarStartResult.AlreadyRunning) {
                attempts = 0
                continue
            }
            if (childSpec.required && attempts >= maxRestartAttempts) {
                // Fail closed: a required XHTTP route must not pretend to work.
                tunnel.requestStop()
                return
            }
            if (!childSpec.required && attempts >= maxRestartAttempts) {
                attempts = 0
                if (!sleep(MAX_RESTART_DELAY_MILLIS)) return
            }
        }
    }

    private fun stopSidecarOnce() {
        if (!sidecarStopped.compareAndSet(false, true)) return
        sidecar?.stop()
    }

    private fun sleep(millis: Long): Boolean = try {
        Thread.sleep(millis)
        true
    } catch (_: InterruptedException) {
        false
    }

    private fun restartDelayMillis(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, MAX_RESTART_EXPONENT)
        return (restartBaseDelayMillis shl exponent).coerceAtMost(MAX_RESTART_DELAY_MILLIS)
    }
}

private const val WATCHDOG_INTERVAL_MILLIS = 1_000L
private const val MAX_RESTART_ATTEMPTS = 3
private const val RESTART_BASE_DELAY_MILLIS = 1_000L
private const val MAX_RESTART_DELAY_MILLIS = 30_000L
private const val MAX_RESTART_EXPONENT = 4
