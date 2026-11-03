package com.yokhan.gates.windows.service

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SerializedTunnelSupervisorTest {

    @Test
    fun diagnosticSnapshotsAreReadOnlyAndNewConnectionsGetNewSessionIds() {
        val launcher = RecordingLauncher()
        val supervisor = SerializedTunnelSupervisor(launcher)
        assertNull(supervisor.diagnosticsSnapshot())
        supervisor.start(configA)
        val first = supervisor.diagnosticsSnapshot()!!
        assertTrue(first.runtimeAlive)
        assertTrue(first.sessionId.matches(Regex("[0-9a-f]{64}")))
        assertEquals(first.sessionId, supervisor.diagnosticsSnapshot()!!.sessionId)
        assertEquals(1, launcher.launches)
        launcher.children.single().isRunning = false
        assertFalse(supervisor.diagnosticsSnapshot()!!.runtimeAlive)
        assertEquals(first.sessionId, supervisor.diagnosticsSnapshot()!!.sessionId)
        supervisor.stop()
        assertNull(supervisor.diagnosticsSnapshot())
        supervisor.start(configA)
        assertFalse(first.sessionId == supervisor.diagnosticsSnapshot()!!.sessionId)
    }

    @Test
    fun tracksOnlyTheChildItStartedAndPreventsOverlappingLaunches() {
        val launcher = RecordingLauncher()
        val supervisor = SerializedTunnelSupervisor(launcher)

        assertEquals(TunnelStartResult.Started, supervisor.start(configA))
        assertEquals(TunnelStartResult.AlreadyRunning, supervisor.start(configA))
        assertEquals(1, launcher.launches)
        assertEquals(TunnelSupervisorState.RUNNING, supervisor.state())
        assertEquals(1, launcher.maximumLiveChildren)
    }

    @Test
    fun keepsItsOwnedHandleUntilTheChildHasExited() {
        val child = FakeTunnelChild(remainsAliveAfterStop = true)
        val supervisor = SerializedTunnelSupervisor(RecordingLauncher(fixedChild = child))
        supervisor.start(configA)

        assertEquals(TunnelStopResult.StopRequested, supervisor.stop())
        assertNull(supervisor.configurationDigest())
        assertEquals(TunnelStartResult.AlreadyRunning, supervisor.start(configA))
        assertEquals(configDigest(configA), supervisor.configurationDigest())
        child.isRunning = false
        assertEquals(TunnelSupervisorState.STOPPED, supervisor.state())
        assertEquals(configDigest(configA), supervisor.configurationDigest())
    }

    @Test
    fun clearsTheHandleAfterAStoppedChildExits() {
        val supervisor = SerializedTunnelSupervisor(RecordingLauncher())
        assertNull(supervisor.configurationDigest())
        supervisor.start(configA)

        assertEquals(TunnelStopResult.Stopped, supervisor.stop())
        assertEquals(TunnelStopResult.AlreadyStopped, supervisor.stop())
        assertNull(supervisor.configurationDigest())
    }

    @Test
    fun replacesTheOwnedChildOnlyAfterThePreviousChildStops() {
        val launcher = RecordingLauncher()
        val supervisor = SerializedTunnelSupervisor(launcher)

        assertEquals(TunnelStartResult.Started, supervisor.start(configA))
        assertEquals(TunnelStartResult.Restarted, supervisor.start(configB))
        assertEquals(2, launcher.launches)
        assertFalse(launcher.children.first().isRunning)
        assertTrue(launcher.children.last().isRunning)
        assertEquals(1, launcher.maximumLiveChildren)
        assertEquals(configB, launcher.persistedConfig)
        assertEquals(configDigest(configB), supervisor.configurationDigest())
    }

    @Test
    fun restoresPersistedConfigAfterServiceRestart() {
        val launcher = RecordingLauncher(initialConfig = configA)
        val supervisor = SerializedTunnelSupervisor(launcher)

        assertTrue(supervisor.restorePersisted())
        assertEquals(TunnelSupervisorState.RUNNING, supervisor.state())
        assertEquals(1, launcher.launches)
    }

    @Test
    fun delayedIdentityBecomesReadyForTheSameChildWithoutAStop() {
        var nowNanos = 0L
        val launcher = IdentityReadinessLauncher(
            outcomes = ArrayDeque(listOf(
                ProtectionPermitResult.IDENTITY_NOT_READY,
                ProtectionPermitResult.IDENTITY_NOT_READY,
                ProtectionPermitResult.PERMITTED,
            )),
        )
        val supervisor = SerializedTunnelSupervisor(
            launcher,
            nowMillis = { 0L },
            nowNanos = { nowNanos },
            sleepMillis = { nowNanos += TimeUnit.MILLISECONDS.toNanos(it) },
        )

        assertEquals(TunnelStartResult.Started, supervisor.start(guardedConfig))

        assertEquals(1, launcher.launches)
        assertEquals(3, launcher.probeCount)
        assertEquals(0, launcher.child.stopRequests)
        assertTrue(launcher.child.isAlive())
        assertEquals(1, launcher.confirmedPermits)
    }

    @Test
    fun readinessPhaseCanSpanMultipleBoundedIdentityQueries() {
        var nowNanos = 0L
        val queryDurations = mutableListOf<Long>()
        val launcher = IdentityReadinessLauncher(
            outcomes = ArrayDeque(listOf(
                ProtectionPermitResult.IDENTITY_NOT_READY,
                ProtectionPermitResult.IDENTITY_NOT_READY,
                ProtectionPermitResult.PERMITTED,
            )),
            afterProbe = {
                val duration = TimeUnit.MILLISECONDS.toNanos(1_100)
                queryDurations += duration
                nowNanos += duration
            },
        )
        val supervisor = SerializedTunnelSupervisor(
            launcher,
            nowMillis = { 0L },
            nowNanos = { nowNanos },
            sleepMillis = { nowNanos += TimeUnit.MILLISECONDS.toNanos(it) },
        )

        assertEquals(TunnelStartResult.Started, supervisor.start(guardedConfig))

        assertTrue(queryDurations.all { it <= TimeUnit.SECONDS.toNanos(2) })
        assertTrue(nowNanos > TimeUnit.SECONDS.toNanos(2))
        assertEquals(1, launcher.launches)
        assertEquals(3, launcher.probeCount)
        assertEquals(0, launcher.child.stopRequests)
        assertEquals(1, launcher.confirmedPermits)
    }

    @Test
    fun permanentIdentityAbsenceStopsChildAtMonotonicDeadlineAndRetainsBlocks() {
        var nowNanos = 0L
        val launcher = IdentityReadinessLauncher()
        val supervisor = SerializedTunnelSupervisor(
            launcher,
            nowMillis = { 0L },
            nowNanos = { nowNanos },
            sleepMillis = { nowNanos += TimeUnit.MILLISECONDS.toNanos(it) },
        )

        assertIs<IOException>(runCatching { supervisor.start(guardedConfig) }.exceptionOrNull())

        assertEquals(TimeUnit.SECONDS.toNanos(5), nowNanos)
        assertTrue(launcher.probeCount > 1)
        assertFalse(launcher.child.isAlive())
        assertEquals(1, launcher.child.stopRequests)
        assertTrue(launcher.blockCalls > 0)
        assertEquals(0, launcher.confirmedPermits)
        assertEquals(0, launcher.releaseCalls)
    }

    @Test
    fun earlyChildDeathStopsReadinessRetriesAndNeverPermits() {
        var nowNanos = 0L
        val launcher = IdentityReadinessLauncher(
            dieAfterProbe = 1,
        )
        val supervisor = SerializedTunnelSupervisor(
            launcher,
            nowMillis = { 0L },
            nowNanos = { nowNanos },
            sleepMillis = { nowNanos += TimeUnit.MILLISECONDS.toNanos(it) },
        )

        assertIs<IOException>(runCatching { supervisor.start(guardedConfig) }.exceptionOrNull())

        assertEquals(1, launcher.probeCount)
        assertEquals(0, launcher.confirmedPermits)
        assertEquals(1, launcher.child.stopRequests)
        assertFalse(launcher.child.isAlive())
        assertTrue(launcher.blockCalls > 0)
    }

    @Test
    fun fatalProtectionBackendFailureIsNotRetried() {
        val launcher = IdentityReadinessLauncher(fatalFailure = IOException("backend failure"))
        val supervisor = SerializedTunnelSupervisor(launcher)

        assertIs<IOException>(runCatching { supervisor.start(guardedConfig) }.exceptionOrNull())

        assertEquals(1, launcher.probeCount)
        assertEquals(0, launcher.confirmedPermits)
        assertEquals(1, launcher.child.stopRequests)
        assertTrue(launcher.blockCalls > 0)
        assertEquals(0, launcher.releaseCalls)
    }

    @Test
    fun interruptionPreservesInterruptAndStopsWithoutAnotherIdentityProbe() {
        val launcher = IdentityReadinessLauncher(outcomes = ArrayDeque(listOf(ProtectionPermitResult.IDENTITY_NOT_READY)))
        val supervisor = SerializedTunnelSupervisor(
            launcher,
            nowMillis = { 0L },
            nowNanos = { 0L },
            sleepMillis = {
                Thread.currentThread().interrupt()
                throw InterruptedException("interrupted")
            },
        )

        try {
            assertIs<IOException>(runCatching { supervisor.start(guardedConfig) }.exceptionOrNull())
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, launcher.probeCount)
            assertEquals(0, launcher.confirmedPermits)
            assertEquals(1, launcher.child.stopRequests)
            assertTrue(launcher.blockCalls > 0)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun failedRestoredLaunchRetainsIntentAndRetriesAfterBoundedBackoff() {
        var now = 0L
        val launcher = RecordingLauncher(initialConfig = configA).apply { failedLaunchesRemaining = 1 }
        val supervisor = SerializedTunnelSupervisor(launcher, nowMillis = { now })

        assertFalse(supervisor.restorePersisted())
        assertEquals(configA, launcher.persistedConfig)
        assertEquals(TunnelSupervisorState.STOPPED, supervisor.state())
        assertEquals(TunnelReconcileResult.WAITING, supervisor.reconcile())
        now += 1_000
        assertEquals(TunnelReconcileResult.RESTARTED, supervisor.reconcile())
        assertEquals(TunnelSupervisorState.RUNNING, supervisor.state())
    }

    @Test
    fun failedInitialExplicitLaunchKeepsAcceptedIntentAndRetries() {
        var now = 0L
        val launcher = RecordingLauncher().apply { failedLaunchesRemaining = 1 }
        val supervisor = SerializedTunnelSupervisor(launcher, nowMillis = { now })

        val failure = runCatching { supervisor.start(configA) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(failure!!.message.orEmpty().contains("credential", ignoreCase = true))
        assertEquals(configA, launcher.persistedConfig)
        assertEquals(TunnelReconcileResult.WAITING, supervisor.reconcile())
        now += 1_000
        assertEquals(TunnelReconcileResult.RESTARTED, supervisor.reconcile())
        assertEquals(TunnelSupervisorState.RUNNING, supervisor.state())
    }

    @Test
    fun failedPreflightCannotReplaceKnownGoodIntentOrChild() {
        val launcher = RecordingLauncher(initialConfig = configA).apply {
            failPreflightFor = configB
            preflightFailure = IllegalArgumentException("credential=must-not-escape")
        }
        val supervisor = SerializedTunnelSupervisor(launcher)
        supervisor.restorePersisted()

        val failure = runCatching { supervisor.start(configB) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(failure!!.message.orEmpty().contains("must-not-escape"))
        assertEquals(configA, launcher.persistedConfig)
        assertEquals(TunnelSupervisorState.RUNNING, supervisor.state())
        assertEquals(1, launcher.launches)
    }

    @Test
    fun failedLiveRouteReplacementRollsBackPersistenceAndRestartsPreviousConfig() {
        val launcher = RecordingLauncher().apply { failConfig = configB }
        val supervisor = SerializedTunnelSupervisor(launcher)
        supervisor.start(configA)

        val failure = runCatching { supervisor.start(configB) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse(failure!!.message.orEmpty().contains("must-not-expose"))
        assertEquals(configA, launcher.persistedConfig)
        assertEquals(configA, launcher.launchConfigs.last())
        assertEquals(TunnelSupervisorState.RUNNING, supervisor.state())
        assertEquals(1, launcher.maximumLiveChildren)
        assertEquals(configDigest(configA), supervisor.configurationDigest())
    }

    @Test
    fun failedReplacementAfterChildDeathRestoresPreviousIntentAndRestartsIt() {
        val launcher = RecordingLauncher().apply { failConfig = configB }
        val supervisor = SerializedTunnelSupervisor(launcher)
        supervisor.start(configA)
        launcher.children.single().isRunning = false

        assertTrue(runCatching { supervisor.start(configB) }.exceptionOrNull() is IOException)

        assertEquals(configA, launcher.persistedConfig)
        assertEquals(configA, launcher.launchConfigs.last())
        assertEquals(TunnelSupervisorState.RUNNING, supervisor.state())
        assertEquals(1, launcher.maximumLiveChildren)
    }

    @Test
    fun failedCandidatePersistenceLeavesKnownGoodChildAndIntentUntouched() {
        val launcher = RecordingLauncher().apply { failPersistConfig = configB }
        val supervisor = SerializedTunnelSupervisor(launcher)
        supervisor.start(configA)

        assertTrue(runCatching { supervisor.start(configB) }.exceptionOrNull() is IOException)

        assertEquals(configA, launcher.persistedConfig)
        assertEquals(TunnelSupervisorState.RUNNING, supervisor.state())
        assertEquals(1, launcher.launches)
    }

    @Test
    fun failedReplacementPersistenceRollbackIsReportedAndRetried() {
        var now = 0L
        val launcher = RecordingLauncher()
        val supervisor = SerializedTunnelSupervisor(launcher, nowMillis = { now })
        supervisor.start(configA)
        launcher.failConfig = configB
        launcher.launchHook = { config ->
            if (config == configB) launcher.failPersistConfig = configA
        }

        val failure = runCatching { supervisor.start(configB) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure!!.message.orEmpty().contains("pending"))
        assertEquals(configB, launcher.persistedConfig)
        assertEquals(configB, launcher.launchConfigs.last())
        assertEquals(TunnelSupervisorState.STOPPED, supervisor.state())
        assertEquals(configDigest(configA), supervisor.configurationDigest())

        launcher.failPersistConfig = null
        assertEquals(TunnelReconcileResult.WAITING, supervisor.reconcile())
        now = 1_000
        assertEquals(TunnelReconcileResult.RESTARTED, supervisor.reconcile())
        assertEquals(configA, launcher.launchConfigs.last())
        assertEquals(configA, launcher.persistedConfig)
        assertEquals(TunnelSupervisorState.RUNNING, supervisor.state())
    }

    @Test
    fun failedExplicitStopPersistenceStopsChildAndRetriesDurableClear() {
        var now = 0L
        val launcher = RecordingLauncher().apply { failClearPersistence = true }
        val supervisor = SerializedTunnelSupervisor(launcher, nowMillis = { now })
        supervisor.start(configA)

        assertTrue(runCatching { supervisor.stop() }.exceptionOrNull() is IOException)

        assertEquals(configA, launcher.persistedConfig)
        assertEquals(TunnelSupervisorState.STOPPED, supervisor.state())
        assertEquals(TunnelReconcileResult.WAITING, supervisor.reconcile())
        launcher.failClearPersistence = false
        now += 1_000
        assertEquals(TunnelReconcileResult.IDLE, supervisor.reconcile())
        assertNull(supervisor.configurationDigest())
        assertNull(launcher.persistedConfig)
    }

    @Test
    fun explicitStopClearsPersistedIntentEvenWhenOwnedChildAlreadyDied() {
        val launcher = RecordingLauncher(initialConfig = configA)
        val supervisor = SerializedTunnelSupervisor(launcher)
        supervisor.restorePersisted()
        launcher.children.single().isRunning = false

        assertEquals(TunnelStopResult.AlreadyStopped, supervisor.stop())
        assertNull(launcher.persistedConfig)
        assertEquals(TunnelReconcileResult.IDLE, supervisor.reconcile())
        assertNull(supervisor.configurationDigest())
    }

    @Test
    fun serviceShutdownStopsChildButPreservesIntentAndSuppressesLaterReconcile() {
        val launcher = RecordingLauncher(initialConfig = configA).apply {
            childrenRemainAliveOnStop = true
        }
        val supervisor = SerializedTunnelSupervisor(launcher)
        supervisor.restorePersisted()

        assertEquals(TunnelStopResult.StopRequested, supervisor.shutdown())
        assertEquals(configA, launcher.persistedConfig)
        assertEquals(TunnelReconcileResult.SHUTTING_DOWN, supervisor.reconcile())
        assertEquals(1, launcher.launches)
        assertEquals(TunnelSupervisorState.RUNNING, supervisor.state())
    }

    @Test
    fun stopRacingWithReconcileClearsIntentAndPreventsFurtherLaunches() {
        val enteredLaunch = CountDownLatch(1)
        val releaseLaunch = CountDownLatch(1)
        val launcher = RecordingLauncher().apply {
            launchHook = { config ->
                if (config == configA && launches > 2) {
                    enteredLaunch.countDown()
                    check(releaseLaunch.await(2, TimeUnit.SECONDS))
                }
            }
        }
        var now = 0L
        val supervisor = SerializedTunnelSupervisor(launcher, nowMillis = { now })
        supervisor.start(configA)
        launcher.children.single().isRunning = false
        launcher.failedLaunchesRemaining = 1
        assertEquals(TunnelReconcileResult.FAILED, supervisor.reconcile())
        now = 1_000

        var reconcileResult: TunnelReconcileResult? = null
        var stopResult: TunnelStopResult? = null
        val reconcileThread = thread { reconcileResult = supervisor.reconcile() }
        assertTrue(enteredLaunch.await(2, TimeUnit.SECONDS))
        val stopThread = thread { stopResult = supervisor.stop() }
        releaseLaunch.countDown()
        reconcileThread.join(2_000)
        stopThread.join(2_000)

        assertFalse(reconcileThread.isAlive)
        assertFalse(stopThread.isAlive)
        assertEquals(TunnelReconcileResult.RESTARTED, reconcileResult)
        assertEquals(TunnelStopResult.Stopped, stopResult)
        assertNull(launcher.persistedConfig)
        assertEquals(TunnelReconcileResult.IDLE, supervisor.reconcile())
        assertEquals(1, launcher.maximumLiveChildren)
    }

    @Test
    fun restartBackoffGrowsAndRemainsCapped() {
        var now = 0L
        val launcher = RecordingLauncher().apply { failedLaunchesRemaining = Int.MAX_VALUE }
        val supervisor = SerializedTunnelSupervisor(launcher, nowMillis = { now })
        assertTrue(runCatching { supervisor.start(configA) }.exceptionOrNull() is IOException)

        repeat(8) { retry ->
            assertEquals(TunnelReconcileResult.WAITING, supervisor.reconcile())
            now += when (retry) {
                0 -> 1_000
                1 -> 2_000
                2 -> 4_000
                3 -> 8_000
                4 -> 16_000
                else -> 30_000
            }
            assertEquals(TunnelReconcileResult.FAILED, supervisor.reconcile())
        }
    }

    @Test
    fun rapidCrashAfterMonitorRestartKeepsCooldown() {
        var now = 10_000L
        val launcher = RecordingLauncher()
        val supervisor = SerializedTunnelSupervisor(launcher, nowMillis = { now })
        supervisor.start(configA)
        launcher.children.single().isRunning = false

        assertEquals(TunnelReconcileResult.RESTARTED, supervisor.reconcile())
        launcher.children.last().isRunning = false
        assertEquals(TunnelReconcileResult.WAITING, supervisor.reconcile())
        now += 1_000
        assertEquals(TunnelReconcileResult.RESTARTED, supervisor.reconcile())
    }

    private class FakeTunnelChild(
        private val remainsAliveAfterStop: Boolean = false,
    ) : TunnelChild {
        var isRunning = true

        override fun isAlive(): Boolean = isRunning

        override fun requestStop() {
            if (!remainsAliveAfterStop) isRunning = false
        }

        override fun awaitStopped(timeoutMillis: Long): Boolean = !isRunning
    }

    private class RecordingLauncher(
        initialConfig: ValidatedSingBoxConfig? = null,
        private val fixedChild: FakeTunnelChild? = null,
    ) : TunnelLauncher {
        var persistedConfig: ValidatedSingBoxConfig? = initialConfig
            private set
        val children = mutableListOf<FakeTunnelChild>()
        val launchConfigs = mutableListOf<ValidatedSingBoxConfig>()
        var launches = 0
            private set
        var maximumLiveChildren = 0
            private set
        var failedLaunchesRemaining = 0
        var failConfig: ValidatedSingBoxConfig? = null
        var failPersistConfig: ValidatedSingBoxConfig? = null
        var failClearPersistence = false
        var failPreflightFor: ValidatedSingBoxConfig? = null
        var preflightFailure: Exception = IllegalArgumentException("preflight failed")
        var childrenRemainAliveOnStop = false
        var launchHook: (ValidatedSingBoxConfig) -> Unit = {}
        override fun preflight(config: ValidatedSingBoxConfig) {
            if (config == failPreflightFor) throw preflightFailure
        }

        override fun launch(config: ValidatedSingBoxConfig): TunnelChild {
            check(persistedConfig == config) { "launch must use the persisted desired config" }
            launches += 1
            launchConfigs += config
            launchHook(config)
            if (failedLaunchesRemaining > 0) {
                failedLaunchesRemaining -= 1
                throw IOException("credential=do-not-expose")
            }
            if (config == failConfig) throw IOException("credential=do-not-expose")
            val child = fixedChild ?: FakeTunnelChild(childrenRemainAliveOnStop)
            children += child
            maximumLiveChildren = maxOf(maximumLiveChildren, children.count { it.isRunning })
            return child
        }

        override fun loadPersistedConfig(): ValidatedSingBoxConfig? = persistedConfig

        override fun persistConfig(config: ValidatedSingBoxConfig) {
            if (config == failPersistConfig) throw IOException("credential=do-not-expose")
            persistedConfig = config
        }

        override fun clearPersistedConfig() {
            if (failClearPersistence) throw IOException("credential=do-not-expose")
            persistedConfig = null
        }
    }

    private class IdentityReadinessLauncher(
        private val outcomes: ArrayDeque<ProtectionPermitResult> = ArrayDeque(),
        private val fatalFailure: Exception? = null,
        private val afterProbe: (Int) -> Unit = {},
        private val dieAfterProbe: Int? = null,
    ) : TunnelLauncher {
        var persistedConfig: ValidatedSingBoxConfig? = null
        val child = ReadinessChild()
        var launches = 0
        var probeCount = 0
        var confirmedPermits = 0
        var blockCalls = 0
        var releaseCalls = 0

        override fun launch(config: ValidatedSingBoxConfig): TunnelChild {
            check(persistedConfig == config)
            launches++
            return child
        }

        override fun persistConfig(config: ValidatedSingBoxConfig) { persistedConfig = config }
        override fun clearPersistedConfig() { persistedConfig = null }
        override fun permitProtection(
            config: ValidatedSingBoxConfig,
            confirmChildAlive: () -> Unit,
        ): ProtectionPermitResult {
            probeCount++
            afterProbe(probeCount)
            fatalFailure?.let { throw it }
            val outcome = if (outcomes.isEmpty()) ProtectionPermitResult.IDENTITY_NOT_READY else outcomes.removeFirst()
            if (outcome == ProtectionPermitResult.IDENTITY_NOT_READY && probeCount == dieAfterProbe) child.die()
            if (outcome == ProtectionPermitResult.PERMITTED) {
                confirmChildAlive()
                confirmedPermits++
            }
            return outcome
        }
        override fun blockProtection() { blockCalls++ }
        override fun releaseProtection() { releaseCalls++ }
        override fun protectionStatus() = if (releaseCalls == 0) ProtectionStatus.BLOCKED else ProtectionStatus.DISABLED

        class ReadinessChild : TunnelChild {
            private var alive = true
            var stopRequests = 0
            override fun isAlive() = alive
            override fun requestStop() { stopRequests++; alive = false }
            override fun awaitStopped(timeoutMillis: Long) = !alive
            fun die() { alive = false }
        }
    }

    private companion object {
        val configA = ValidatedSingBoxConfig("{\"route\":1}")
        val configB = ValidatedSingBoxConfig("{\"route\":2}")
        val guardedConfig = ValidatedSingBoxConfig(
            """{"inbounds":[{"type":"tun","address":["10.0.0.1/30"]}],"outbounds":[{"type":"direct","tag":"default"}]}""",
            SelectedAppProtection(listOf("C:\\Apps\\browser.exe")),
        )

        fun configDigest(config: ValidatedSingBoxConfig): String =
            MessageDigest.getInstance("SHA-256")
                .digest(config.canonicalJson.toByteArray(UTF_8))
                .joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
    }
}
