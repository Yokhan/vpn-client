package com.yokhan.gates.windows.service

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.UUID

data class TunnelDiagnosticSnapshot(
    val sessionId: String,
    val runtimeAlive: Boolean,
    val config: ValidatedSingBoxConfig,
)

interface TunnelChild {
    fun isAlive(): Boolean
    fun requestStop()
    fun awaitStopped(timeoutMillis: Long): Boolean
}

fun interface TunnelLauncher {
    fun launch(config: ValidatedSingBoxConfig): TunnelChild

    fun preflight(config: ValidatedSingBoxConfig) = Unit

    fun loadPersistedConfig(): ValidatedSingBoxConfig? = null

    fun persistConfig(config: ValidatedSingBoxConfig) = Unit

    fun clearPersistedConfig() = Unit
}

enum class TunnelSupervisorState {
    RUNNING,
    STOPPED,
}

enum class TunnelReconcileResult {
    HEALTHY,
    IDLE,
    WAITING,
    RESTARTED,
    FAILED,
    SHUTTING_DOWN,
}

sealed interface TunnelStartResult {
    data object Started : TunnelStartResult
    data object Restarted : TunnelStartResult
    data object AlreadyRunning : TunnelStartResult
}

sealed interface TunnelStopResult {
    data object Stopped : TunnelStopResult
    data object StopRequested : TunnelStopResult
    data object AlreadyStopped : TunnelStopResult
}

class SerializedTunnelSupervisor(
    private val launcher: TunnelLauncher,
) {
    private sealed interface PersistenceRepair {
        data class Persist(val config: ValidatedSingBoxConfig) : PersistenceRepair
        data object Clear : PersistenceRepair
    }

    private var nowMillis: () -> Long = System::currentTimeMillis
    private val lock = Any()
    private var ownedChild: TunnelChild? = null
    private var ownedChildConfig: ValidatedSingBoxConfig? = null
    private var activeConfig: ValidatedSingBoxConfig? = null
    private var pendingPersistenceRepair: PersistenceRepair? = null
    private var restartAttempts = 0
    private var nextRestartAtMillis = 0L
    private var lastRestartLaunchAtMillis: Long? = null
    private var shuttingDown = false
    private var diagnosticSessionId: String? = null
    private var diagnosticConfig: ValidatedSingBoxConfig? = null

    internal constructor(
        launcher: TunnelLauncher,
        nowMillis: () -> Long,
    ) : this(launcher) {
        this.nowMillis = nowMillis
    }

    fun start(config: ValidatedSingBoxConfig): TunnelStartResult = synchronized(lock) {
        if (shuttingDown) throw IOException("Tunnel service is shutting down")
        try {
            launcher.preflight(config)
        } catch (_: Exception) {
            throw IOException("Tunnel configuration was rejected")
        }

        val previousConfig = activeConfig
        val child = liveOwnedChild()
        if (child != null && ownedChildConfig == config) {
            if (previousConfig == config) {
                resetRestartBackoff()
                return@synchronized TunnelStartResult.AlreadyRunning
            }
            persistCandidate(config, previousConfig)
            activeConfig = config
            resetRestartBackoff()
            return@synchronized TunnelStartResult.AlreadyRunning
        }

        persistCandidate(config, previousConfig)

        if (child == null) {
            if (previousConfig != null && previousConfig != config) {
                return@synchronized launchReplacement(config, previousConfig)
            }
            activeConfig = config
            return@synchronized launchAcceptedConfig(config, TunnelStartResult.Started)
        }

        val stopped = try {
            child.requestStop()
            child.awaitStopped(RESTART_STOP_TIMEOUT_MILLIS)
        } catch (_: Exception) {
            false
        }
        if (!stopped) {
            val persistenceRestored = restorePersistence(previousConfig)
            if (persistenceRestored) throw IOException("Existing tunnel could not be stopped")
            throw IOException("Existing tunnel could not be stopped; prior desired state restoration is pending")
        }
        ownedChild = null
        ownedChildConfig = null

        return@synchronized launchReplacement(config, previousConfig)
    }

    private fun launchReplacement(
        config: ValidatedSingBoxConfig,
        previousConfig: ValidatedSingBoxConfig?,
    ): TunnelStartResult = try {
        ownedChild = launcher.launch(config)
        ownedChildConfig = config
        activeConfig = config
        resetRestartBackoff()
        TunnelStartResult.Restarted
    } catch (_: Exception) {
        ownedChild = null
        ownedChildConfig = null
        if (previousConfig == null) {
            activeConfig = config
            scheduleNextRestart()
            throw IOException("Tunnel launch failed; accepted configuration will be retried")
        }
        activeConfig = previousConfig
        val persistenceRestored = rollbackReplacement(previousConfig)
        if (persistenceRestored) {
            throw IOException("Route replacement failed; previous tunnel recovery was attempted")
        }
        throw IOException("Route replacement failed; previous desired state restoration is pending")
    }

    fun stop(): TunnelStopResult = synchronized(lock) {
        diagnosticSessionId = null
        try {
            launcher.clearPersistedConfig()
        } catch (_: Exception) {
            activeConfig = null
            pendingPersistenceRepair = PersistenceRepair.Clear
            scheduleNextRestart()
            stopOwnedChild()
            throw IOException("Tunnel desired state could not be cleared; retry is scheduled")
        }
        activeConfig = null
        pendingPersistenceRepair = null
        resetRestartBackoff()
        stopOwnedChild()
    }

    fun shutdown(): TunnelStopResult = synchronized(lock) {
        shuttingDown = true
        stopOwnedChild()
    }

    fun restorePersisted(): Boolean = synchronized(lock) {
        if (shuttingDown) return@synchronized false
        if (activeConfig != null || liveOwnedChild() != null) return@synchronized false
        val config = launcher.loadPersistedConfig() ?: return@synchronized false
        activeConfig = config
        return@synchronized try {
            launcher.preflight(config)
            ownedChild = launcher.launch(config)
            ownedChildConfig = config
            resetRestartBackoff()
            true
        } catch (_: Exception) {
            ownedChild = null
            scheduleNextRestart()
            false
        }
    }

    fun reconcile(): TunnelReconcileResult = synchronized(lock) {
        if (shuttingDown) return@synchronized TunnelReconcileResult.SHUTTING_DOWN
        if (pendingPersistenceRepair != null) {
            if (nowMillis() < nextRestartAtMillis) return@synchronized TunnelReconcileResult.WAITING
            try {
                when (val repair = pendingPersistenceRepair) {
                    is PersistenceRepair.Persist -> launcher.persistConfig(repair.config)
                    PersistenceRepair.Clear -> launcher.clearPersistedConfig()
                    null -> Unit
                }
                pendingPersistenceRepair = null
            } catch (_: Exception) {
                scheduleNextRestart()
                return@synchronized TunnelReconcileResult.FAILED
            }
        }
        val config = activeConfig ?: return@synchronized TunnelReconcileResult.IDLE
        if (liveOwnedChild() != null) {
            val lastRestart = lastRestartLaunchAtMillis
            if (lastRestart != null && nowMillis() - lastRestart >= RESTART_BACKOFF_RESET_MILLIS) {
                resetRestartBackoff()
            }
            return@synchronized TunnelReconcileResult.HEALTHY
        }
        if (nowMillis() < nextRestartAtMillis) return@synchronized TunnelReconcileResult.WAITING
        return@synchronized runCatching {
            ownedChild = launcher.launch(config)
            ownedChildConfig = config
            lastRestartLaunchAtMillis = nowMillis()
            scheduleNextRestart()
            TunnelReconcileResult.RESTARTED
        }.getOrElse {
            ownedChild = null
            ownedChildConfig = null
            scheduleNextRestart()
            TunnelReconcileResult.FAILED
        }
    }

    fun state(): TunnelSupervisorState = synchronized(lock) {
        if (liveOwnedChild() == null) TunnelSupervisorState.STOPPED else TunnelSupervisorState.RUNNING
    }

    fun diagnosticsSnapshot(): TunnelDiagnosticSnapshot? = synchronized(lock) {
        val desired = activeConfig ?: return@synchronized null
        val child = liveOwnedChild()
        val config = if (child != null) ownedChildConfig ?: desired else desired
        if (diagnosticSessionId == null || diagnosticConfig != config) {
            diagnosticConfig = config
            diagnosticSessionId = MessageDigest.getInstance("SHA-256")
                .digest(UUID.randomUUID().toString().toByteArray(UTF_8))
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        }
        TunnelDiagnosticSnapshot(diagnosticSessionId!!, child != null, config)
    }

    fun configurationDigest(): String? = synchronized(lock) {
        val desiredConfig = activeConfig ?: return@synchronized null
        val config = if (liveOwnedChild() != null) ownedChildConfig else desiredConfig
        config?.canonicalJson?.toByteArray(UTF_8)?.let { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        }
    }

    private fun persistCandidate(
        config: ValidatedSingBoxConfig,
        previousConfig: ValidatedSingBoxConfig?,
    ) {
        try {
            launcher.persistConfig(config)
            pendingPersistenceRepair = null
        } catch (_: Exception) {
            val repaired = runCatching {
                if (previousConfig == null) launcher.clearPersistedConfig()
                else launcher.persistConfig(previousConfig)
            }.isSuccess
            if (repaired) {
                pendingPersistenceRepair = null
            } else {
                pendingPersistenceRepair = previousConfig?.let { PersistenceRepair.Persist(it) } ?: PersistenceRepair.Clear
                scheduleNextRestart()
                throw IOException("Tunnel desired state could not be saved; prior state restoration is pending")
            }
            throw IOException("Tunnel desired state could not be saved")
        }
    }

    private fun launchAcceptedConfig(
        config: ValidatedSingBoxConfig,
        success: TunnelStartResult,
    ): TunnelStartResult = try {
        ownedChild = launcher.launch(config)
        ownedChildConfig = config
        resetRestartBackoff()
        success
    } catch (_: Exception) {
        ownedChild = null
        ownedChildConfig = null
        activeConfig = config
        scheduleNextRestart()
        throw IOException("Tunnel launch failed; accepted configuration will be retried")
    }

    private fun rollbackReplacement(previousConfig: ValidatedSingBoxConfig): Boolean {
        activeConfig = previousConfig
        val persistenceRestored = runCatching { launcher.persistConfig(previousConfig) }.isSuccess
        pendingPersistenceRepair = if (persistenceRestored) null else PersistenceRepair.Persist(previousConfig)
        if (!persistenceRestored) {
            ownedChild = null
            ownedChildConfig = null
            scheduleNextRestart()
            return false
        }

        val childRestored = runCatching { launcher.launch(previousConfig) }.getOrNull()
        ownedChild = childRestored
        ownedChildConfig = childRestored?.let { previousConfig }
        if (childRestored != null) lastRestartLaunchAtMillis = nowMillis()
        scheduleNextRestart()
        return true
    }

    private fun restorePersistence(config: ValidatedSingBoxConfig?): Boolean {
        val restored = runCatching {
            if (config == null) launcher.clearPersistedConfig() else launcher.persistConfig(config)
        }.isSuccess
        pendingPersistenceRepair = if (restored) null else config?.let { PersistenceRepair.Persist(it) } ?: PersistenceRepair.Clear
        if (!restored) scheduleNextRestart()
        return restored
    }

    private fun stopOwnedChild(): TunnelStopResult {
        val child = liveOwnedChild() ?: return TunnelStopResult.AlreadyStopped
        return try {
            child.requestStop()
            if (child.awaitStopped(RESTART_STOP_TIMEOUT_MILLIS)) {
                ownedChild = null
                ownedChildConfig = null
                TunnelStopResult.Stopped
            } else {
                TunnelStopResult.StopRequested
            }
        } catch (_: Exception) {
            TunnelStopResult.StopRequested
        }
    }

    private fun liveOwnedChild(): TunnelChild? {
        val child = ownedChild ?: return null
        if (child.isAlive()) return child
        ownedChild = null
        ownedChildConfig = null
        return null
    }

    private fun resetRestartBackoff() {
        restartAttempts = 0
        nextRestartAtMillis = 0L
        lastRestartLaunchAtMillis = null
    }

    private fun scheduleNextRestart() {
        val exponent = restartAttempts.coerceAtMost(MAX_RESTART_EXPONENT)
        val delay = (RESTART_BASE_DELAY_MILLIS shl exponent).coerceAtMost(RESTART_MAX_DELAY_MILLIS)
        restartAttempts += 1
        nextRestartAtMillis = nowMillis() + delay
    }
}

private const val RESTART_STOP_TIMEOUT_MILLIS = 5_000L
private const val RESTART_BASE_DELAY_MILLIS = 1_000L
private const val RESTART_MAX_DELAY_MILLIS = 30_000L
private const val MAX_RESTART_EXPONENT = 5
private const val RESTART_BACKOFF_RESET_MILLIS = 60_000L
