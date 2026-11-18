package com.yokhan.gates.windows.service

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class TunnelDiagnosticSnapshot(
    val sessionId: String,
    val runtimeAlive: Boolean,
    val config: ValidatedSingBoxConfig,
)

interface TunnelChild {
    fun isAlive(): Boolean
    fun requestStop()
    fun awaitStopped(timeoutMillis: Long): Boolean
    fun stopPreservingSidecar(timeoutMillis: Long): Boolean {
        requestStop()
        return awaitStopped(timeoutMillis)
    }
}

fun interface TunnelLauncher {
    fun launch(config: ValidatedSingBoxConfig): TunnelChild

    fun preflight(config: ValidatedSingBoxConfig) = Unit

    fun loadPersistedConfig(): ValidatedSingBoxConfig? = null

    fun persistConfig(config: ValidatedSingBoxConfig) = Unit

    fun clearPersistedConfig() = Unit

    fun loadStandingProtection(): SelectedAppProtection? = null

    fun persistStandingProtection(protection: SelectedAppProtection) = Unit

    fun clearStandingProtection() = Unit

    fun coreExecutablePath(): String? = null
    fun reservedProtectionPaths(): Set<String>? = emptySet()
    fun armProtection(config: ValidatedSingBoxConfig) = Unit
    fun permitProtection(
        config: ValidatedSingBoxConfig,
        confirmChildAlive: () -> Unit = {},
    ): ProtectionPermitResult {
        confirmChildAlive()
        return ProtectionPermitResult.PERMITTED
    }
    fun blockProtection() = Unit
    fun releaseProtection() = Unit
    fun closeProtectionPermitsOnly() = Unit
    fun protectionStatus(): ProtectionStatus = ProtectionStatus.DISABLED
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
    private var nowNanos: () -> Long = System::nanoTime
    private var sleepMillis: (Long) -> Unit = Thread::sleep
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
    private var protectionStopPending = false
    private var protectionFailurePending = false
    private var standingProtection: SelectedAppProtection? = null

    internal constructor(
        launcher: TunnelLauncher,
        nowMillis: () -> Long,
        nowNanos: () -> Long = System::nanoTime,
        sleepMillis: (Long) -> Unit = Thread::sleep,
    ) : this(launcher) {
        this.nowMillis = nowMillis
        this.nowNanos = nowNanos
        this.sleepMillis = sleepMillis
    }

    fun start(config: ValidatedSingBoxConfig): TunnelStartResult = synchronized(lock) {
        if (shuttingDown) throw IOException("Tunnel service is shutting down")
        try {
            launcher.preflight(config)
        } catch (_: Exception) {
            throw IOException("Tunnel configuration was rejected")
        }

        val previousConfig = activeConfig
        if (protectionStopPending || protectionFailurePending) {
            throw IOException("Selected-app protection must be explicitly stopped before another start")
        }
        if ((previousConfig?.protection != null || ownedChildConfig?.protection != null) && config.protection == null) {
            throw IOException("Selected-app protection must be explicitly stopped before unguarded start")
        }
        if (standingProtection != null && config.protection == null) {
            throw IOException("Standing selected-app protection is armed; guarded start required")
        }
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

        if (child != null && (ownedChildConfig?.protection != null || config.protection != null)) {
            try {
                launcher.blockProtection()
            } catch (_: Exception) {
                throw IOException("Selected-app protection could not be blocked before replacement")
            }
        }

        if (child == null) {
            armProtection(config)
            persistCandidate(config, previousConfig)
            if (previousConfig != null && previousConfig != config) {
                return@synchronized launchReplacement(config, previousConfig)
            }
            activeConfig = config
            return@synchronized launchAcceptedConfig(config, TunnelStartResult.Started)
        }

        persistCandidate(config, previousConfig)
        val preserveSidecar = config.sidecar != null && ownedChildConfig?.sidecar == config.sidecar
        val stopped = try {
            if (preserveSidecar) {
                child.requestStop()
                child.stopPreservingSidecar(RESTART_STOP_TIMEOUT_MILLIS)
            } else {
                child.requestStop()
                child.awaitStopped(RESTART_STOP_TIMEOUT_MILLIS)
            }
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

        try {
            launcher.armProtection(config)
        } catch (_: Exception) {
            if (previousConfig != null) rollbackReplacement(previousConfig)
            throw IOException("Selected-app protection could not be armed")
        }

        return@synchronized launchReplacement(config, previousConfig)
    }

    private fun launchReplacement(
        config: ValidatedSingBoxConfig,
        previousConfig: ValidatedSingBoxConfig?,
    ): TunnelStartResult = try {
        ownedChild = launchWithProtection(config)
        ownedChildConfig = config
        activeConfig = config
        resetRestartBackoff()
        TunnelStartResult.Restarted
    } catch (_: Exception) {
        discardExitedOwnedChild()
        if (ownedChild != null) {
            activeConfig = config
            scheduleNextRestart()
            throw IOException("Tunnel identity verification failed; child stop remains pending")
        }
        if (previousConfig == null) {
            activeConfig = config
            scheduleNextRestart()
            throw IOException("Tunnel launch failed; accepted configuration will be retried")
        }
        if (previousConfig.protection == null && config.protection != null) {
            activeConfig = config
            scheduleNextRestart()
            throw IOException("Guarded route launch failed; selected-app blocks remain active")
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
        val protectedStop = activeConfig?.protection != null || ownedChildConfig?.protection != null || protectionStopPending ||
            launcher.protectionStatus() != ProtectionStatus.DISABLED
        if (protectedStop) {
            runCatching(launcher::blockProtection).getOrElse { throw IOException("Selected-app protection could not be blocked") }
            activeConfig = null
            pendingPersistenceRepair = null
            protectionStopPending = true
            try {
                launcher.clearPersistedConfig()
            } catch (_: Exception) {
                throw IOException("Selected-app protection remains blocked because desired state could not be cleared")
            }
            val result = stopOwnedChild()
            if (result == TunnelStopResult.StopRequested) {
                throw IOException("Selected-app protection remains blocked because the tunnel did not stop")
            }
            val standing = standingProtection
            if (standing != null) {
                try {
                    launcher.armProtection(ValidatedSingBoxConfig("{}", standing))
                    protectionStopPending = false
                    protectionFailurePending = false
                } catch (_: Exception) {
                    protectionStopPending = true
                    throw IOException("Selected-app protection remains blocked because standing protection could not be re-armed")
                }
            } else {
                try {
                    launcher.releaseProtection()
                    protectionStopPending = false
                    protectionFailurePending = false
                } catch (_: Exception) {
                    protectionStopPending = true
                    throw IOException("Selected-app protection remains blocked because stop cleanup failed")
                }
            }
            resetRestartBackoff()
            return@synchronized result
        }
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
        if (activeConfig?.protection != null || ownedChildConfig?.protection != null ||
            protectionStopPending || protectionFailurePending) {
            runCatching(launcher::blockProtection)
            runCatching(launcher::closeProtectionPermitsOnly)
        }
        stopOwnedChild()
    }

    fun protectionStatus(): ProtectionStatus = synchronized(lock) { launcher.protectionStatus() }

    fun armStandingProtection(protection: SelectedAppProtection) = synchronized(lock) {
        if (shuttingDown) throw IOException("Tunnel service is shutting down")
        if (activeConfig != null || liveOwnedChild() != null || protectionStopPending || protectionFailurePending) {
            throw IOException("Tunnel must be stopped before updating standing protection")
        }
        val previous = standingProtection
        try {
            launcher.armProtection(ValidatedSingBoxConfig("{}", protection))
        } catch (_: Exception) {
            throw IOException("Selected-app protection could not be armed")
        }
        try {
            launcher.persistStandingProtection(protection)
        } catch (_: Exception) {
            if (previous != null) {
                runCatching { launcher.armProtection(ValidatedSingBoxConfig("{}", previous)) }
            } else {
                runCatching(launcher::releaseProtection)
            }
            throw IOException("Standing selected-app protection could not be saved")
        }
        standingProtection = protection
    }

    fun disarmStandingProtection() = synchronized(lock) {
        if (shuttingDown) throw IOException("Tunnel service is shutting down")
        if (activeConfig != null || liveOwnedChild() != null || protectionStopPending || protectionFailurePending) {
            throw IOException("Tunnel must be stopped before disarming protection")
        }
        val previous = standingProtection
        try {
            launcher.clearStandingProtection()
        } catch (_: Exception) {
            throw IOException("Standing selected-app protection could not be cleared")
        }
        try {
            launcher.releaseProtection()
        } catch (_: Exception) {
            if (previous != null) runCatching { launcher.persistStandingProtection(previous) }
            throw IOException("Selected-app protection could not be released")
        }
        standingProtection = null
    }

    fun coreExecutablePath(): String? = synchronized(lock) { launcher.coreExecutablePath() }

    fun reservedProtectionPaths(): Set<String>? = synchronized(lock) { launcher.reservedProtectionPaths() }

    fun restorePersisted(): Boolean = synchronized(lock) {
        if (shuttingDown) return@synchronized false
        if (activeConfig != null || liveOwnedChild() != null) return@synchronized false
        val standing = runCatching { launcher.loadStandingProtection() }.getOrNull()
        if (standing != null) {
            standingProtection = standing
            runCatching { launcher.armProtection(ValidatedSingBoxConfig("{}", standing)) }
        }
        val config = launcher.loadPersistedConfig() ?: return@synchronized false
        if (standing != null && config.protection == null) return@synchronized false
        activeConfig = config
        return@synchronized try {
            launcher.armProtection(config)
            launcher.preflight(config)
            ownedChild = launchWithProtection(config)
            ownedChildConfig = config
            resetRestartBackoff()
            true
        } catch (_: Exception) {
            discardExitedOwnedChild()
            scheduleNextRestart()
            false
        }
    }

    fun reconcile(): TunnelReconcileResult = synchronized(lock) {
        if (shuttingDown) return@synchronized TunnelReconcileResult.SHUTTING_DOWN
        if (protectionFailurePending) {
            val child = ownedChild
            if (child != null && runCatching(child::isAlive).getOrDefault(true)) {
                val stopped = runCatching {
                    child.requestStop()
                    child.awaitStopped(RESTART_STOP_TIMEOUT_MILLIS)
                }.getOrDefault(false)
                if (!stopped) return@synchronized TunnelReconcileResult.FAILED
            }
            ownedChild = null
            ownedChildConfig = null
            protectionFailurePending = false
        }
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
            if (config.protection != null) launcher.armProtection(config)
            ownedChild = launchWithProtection(config)
            ownedChildConfig = config
            lastRestartLaunchAtMillis = nowMillis()
            scheduleNextRestart()
            TunnelReconcileResult.RESTARTED
        }.getOrElse {
            discardExitedOwnedChild()
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
        ownedChild = launchWithProtection(config)
        ownedChildConfig = config
        resetRestartBackoff()
        success
    } catch (_: Exception) {
        discardExitedOwnedChild()
        activeConfig = config
        scheduleNextRestart()
        throw IOException(if (ownedChild != null) "Tunnel identity verification failed; child stop remains pending" else "Tunnel launch failed; accepted configuration will be retried")
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

        val childRestored = runCatching {
            launcher.armProtection(previousConfig)
            launchWithProtection(previousConfig)
        }.getOrNull()
        if (childRestored != null) {
            ownedChild = childRestored
            ownedChildConfig = previousConfig
        } else if (ownedChild?.let { runCatching(it::isAlive).getOrDefault(true) } != true) {
            ownedChild = null
            ownedChildConfig = null
        }
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
                protectionFailurePending = false
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
        val wasProtected = ownedChildConfig?.protection != null
        if (wasProtected) launcher.blockProtection()
        ownedChild = null
        ownedChildConfig = null
        protectionFailurePending = false
        return null
    }

    private fun launchWithProtection(config: ValidatedSingBoxConfig): TunnelChild {
        val child = launcher.launch(config)
        ownedChild = child
        ownedChildConfig = config
        if (config.protection != null) {
            try {
                awaitIdentityAndPermit(config, child)
                check(child.isAlive()) { "Tunnel child exited during identity verification" }
            } catch (failure: Exception) {
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                val stopped = runCatching {
                    child.requestStop()
                    child.awaitStopped(RESTART_STOP_TIMEOUT_MILLIS)
                }.getOrDefault(false)
                runCatching(launcher::blockProtection)
                if (stopped || !runCatching(child::isAlive).getOrDefault(true)) {
                    ownedChild = null
                    ownedChildConfig = null
                    protectionFailurePending = false
                } else {
                    protectionFailurePending = true
                }
                throw IOException("Selected-app TUN identity could not be verified")
            }
        }
        return child
    }

    private fun awaitIdentityAndPermit(config: ValidatedSingBoxConfig, child: TunnelChild) {
        val deadlineNanos = nowNanos() + IDENTITY_READINESS_TIMEOUT_NANOS
        var firstAttempt = true
        var retryDelayMillis = IDENTITY_RETRY_INITIAL_DELAY_MILLIS
        while (firstAttempt || nowNanos() < deadlineNanos) {
            firstAttempt = false
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Identity readiness was interrupted")
            check(child.isAlive()) { "Tunnel child exited before identity verification" }
            val outcome = launcher.permitProtection(config) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Identity readiness was interrupted")
                }
                check(child.isAlive()) { "Tunnel child exited during identity verification" }
            }
            when (outcome) {
                ProtectionPermitResult.PERMITTED -> return
                ProtectionPermitResult.IDENTITY_NOT_READY -> {
                    val remainingNanos = deadlineNanos - nowNanos()
                    if (remainingNanos <= 0L) break
                    val delayNanos = minOf(remainingNanos, TimeUnit.MILLISECONDS.toNanos(retryDelayMillis))
                    val delayMillis = TimeUnit.NANOSECONDS.toMillis(delayNanos).coerceAtLeast(1L)
                    sleepMillis(delayMillis)
                    retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(IDENTITY_RETRY_MAX_DELAY_MILLIS)
                }
            }
        }
        throw IOException("Selected-app TUN identity did not become ready before the bounded deadline")
    }

    private fun discardExitedOwnedChild() {
        val child = ownedChild ?: return
        if (!runCatching(child::isAlive).getOrDefault(true)) {
            ownedChild = null
            ownedChildConfig = null
            protectionFailurePending = false
        }
    }

    private fun armProtection(config: ValidatedSingBoxConfig) {
        try {
            launcher.armProtection(config)
        } catch (_: Exception) {
            throw IOException("Selected-app protection could not be armed")
        }
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
private const val IDENTITY_READINESS_TIMEOUT_NANOS = 5_000_000_000L
private const val IDENTITY_RETRY_INITIAL_DELAY_MILLIS = 50L
private const val IDENTITY_RETRY_MAX_DELAY_MILLIS = 250L
private const val RESTART_BASE_DELAY_MILLIS = 1_000L
private const val RESTART_MAX_DELAY_MILLIS = 30_000L
private const val MAX_RESTART_EXPONENT = 5
private const val RESTART_BACKOFF_RESET_MILLIS = 60_000L
