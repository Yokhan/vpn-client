package com.yokhan.gates.app.diagnostics

import kotlinx.coroutines.sync.Mutex

internal class DiagnosticsLifetimeGate {
    private val mutex = Mutex()

    suspend fun <T> serialize(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    suspend fun consumeObserver(
        stopped: () -> Boolean,
        startupFailed: () -> Boolean,
        consume: suspend () -> Unit,
        recordFailedStart: () -> Unit,
    ) = serialize {
        if (!stopped()) consume() else if (startupFailed()) recordFailedStart()
    }
}

internal fun isProbeResultCurrent(startGeneration: Long, currentGeneration: Long, stopped: Boolean): Boolean =
    !stopped && startGeneration == currentGeneration
