package com.yokhan.gates.windows.service

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SelectedProtectionLifecycleTest {
    @Test
    fun armsBeforeLaunchAndPermitsOnlyAfterLiveChildLaunch() {
        val launcher = RecordingProtectedLauncher()
        val supervisor = SerializedTunnelSupervisor(launcher)

        assertEquals(TunnelStartResult.Started, supervisor.start(guardedConfig))

        assertEquals(listOf("arm", "persist", "launch", "permit"), launcher.events)
        assertTrue(launcher.children.single().alive)
    }

    @Test
    fun armFailurePreventsPersistenceAndChildLaunch() {
        val launcher = RecordingProtectedLauncher().apply { failArm = true }
        val supervisor = SerializedTunnelSupervisor(launcher)

        assertIs<IOException>(runCatching { supervisor.start(guardedConfig) }.exceptionOrNull())

        assertEquals(listOf("arm"), launcher.events)
        assertEquals(0, launcher.launches)
    }

    @Test
    fun persistFailureAfterArmWithoutChildBlocksUntilExplicitStopReleases() {
        val launcher = RecordingProtectedLauncher().apply { failPersist = true }
        val supervisor = SerializedTunnelSupervisor(launcher)

        assertIs<IOException>(runCatching { supervisor.start(guardedConfig) }.exceptionOrNull())

        assertTrue(launcher.events.indexOf("arm") in 0 until launcher.events.indexOf("persist"))
        assertFalse("launch" in launcher.events)
        assertFalse("permit" in launcher.events)
        assertEquals(ProtectionStatus.BLOCKED, supervisor.protectionStatus())

        assertEquals(TunnelStopResult.AlreadyStopped, supervisor.stop())
        assertTrue("release" in launcher.events)
        assertEquals(ProtectionStatus.DISABLED, supervisor.protectionStatus())
    }

    @Test
    fun persistFailureAfterArmWithFailedClearKeepsBlocksUntilLaterStopSucceeds() {
        val launcher = RecordingProtectedLauncher().apply {
            failPersist = true
            failClear = true
        }
        val supervisor = SerializedTunnelSupervisor(launcher)

        assertIs<IOException>(runCatching { supervisor.start(guardedConfig) }.exceptionOrNull())

        assertTrue(launcher.events.indexOf("arm") in 0 until launcher.events.indexOf("persist"))
        assertFalse("launch" in launcher.events)
        assertFalse("permit" in launcher.events)
        assertEquals(ProtectionStatus.BLOCKED, supervisor.protectionStatus())

        assertIs<IOException>(runCatching { supervisor.stop() }.exceptionOrNull())
        assertFalse("release" in launcher.events)
        assertEquals(ProtectionStatus.BLOCKED, supervisor.protectionStatus())

        launcher.failClear = false
        assertEquals(TunnelStopResult.AlreadyStopped, supervisor.stop())
        assertTrue("release" in launcher.events)
        assertEquals(ProtectionStatus.DISABLED, supervisor.protectionStatus())
    }

    @Test
    fun detectedDeathBlocksBeforeRecoveryAndShutdownNeverReleases() {
        val launcher = RecordingProtectedLauncher()
        val supervisor = SerializedTunnelSupervisor(launcher)
        supervisor.start(guardedConfig)
        launcher.children.single().alive = false
        launcher.events.clear()

        assertEquals(TunnelReconcileResult.RESTARTED, supervisor.reconcile())
        assertTrue(launcher.events.indexOf("block") in 0 until launcher.events.indexOf("launch"))
        launcher.events.clear()

        supervisor.shutdown()

        assertTrue("block" in launcher.events)
        assertTrue("closePermits" in launcher.events)
        assertFalse("release" in launcher.events)
        assertEquals(guardedConfig, launcher.persisted)
    }

    @Test
    fun explicitStopReleasesOnlyAfterChildExitAndDesiredConfigClear() {
        val launcher = RecordingProtectedLauncher()
        val supervisor = SerializedTunnelSupervisor(launcher)
        supervisor.start(guardedConfig)
        launcher.events.clear()

        assertEquals(TunnelStopResult.Stopped, supervisor.stop())

        assertTrue(launcher.events.indexOf("block") < launcher.events.indexOf("childStopped"))
        assertTrue(launcher.events.indexOf("clear") < launcher.events.indexOf("childStopped"))
        assertTrue(launcher.events.indexOf("childStopped") < launcher.events.indexOf("release"))
        assertEquals(ProtectionStatus.DISABLED, supervisor.protectionStatus())
    }

    @Test
    fun failedStopOrDesiredClearRetainsBlocksAndDoesNotRelease() {
        val stopLauncher = RecordingProtectedLauncher().apply { keepChildAliveOnStop = true }
        val stopSupervisor = SerializedTunnelSupervisor(stopLauncher)
        stopSupervisor.start(guardedConfig)
        stopLauncher.events.clear()

        assertIs<IOException>(runCatching { stopSupervisor.stop() }.exceptionOrNull())
        assertTrue("block" in stopLauncher.events)
        assertTrue(stopLauncher.events.indexOf("clear") < stopLauncher.events.indexOf("requestStop"))
        assertFalse("release" in stopLauncher.events)
        assertEquals(null, stopLauncher.persisted)
        assertEquals(ProtectionStatus.BLOCKED, stopSupervisor.protectionStatus())

        val clearLauncher = RecordingProtectedLauncher().apply { failClear = true }
        val clearSupervisor = SerializedTunnelSupervisor(clearLauncher)
        clearSupervisor.start(guardedConfig)
        clearLauncher.events.clear()

        assertIs<IOException>(runCatching { clearSupervisor.stop() }.exceptionOrNull())
        assertFalse("requestStop" in clearLauncher.events)
        assertFalse("release" in clearLauncher.events)
        assertEquals(guardedConfig, clearLauncher.persisted)
        assertEquals(ProtectionStatus.BLOCKED, clearSupervisor.protectionStatus())
        assertEquals(TunnelReconcileResult.IDLE, clearSupervisor.reconcile())

        clearLauncher.failClear = false
        clearLauncher.keepChildAliveOnStop = false
        assertEquals(TunnelStopResult.Stopped, clearSupervisor.stop())
        assertEquals(null, clearLauncher.persisted)
        assertEquals(ProtectionStatus.DISABLED, clearSupervisor.protectionStatus())
    }

    @Test
    fun failedProtectedStopClearsRestartIntentBeforeWaitingAndLaterStopReleasesBlocks() {
        val launcher = RecordingProtectedLauncher().apply { keepChildAliveOnStop = true }
        val supervisor = SerializedTunnelSupervisor(launcher)
        supervisor.start(guardedConfig)

        assertIs<IOException>(runCatching { supervisor.stop() }.exceptionOrNull())
        assertEquals(null, launcher.persisted)
        assertEquals(ProtectionStatus.BLOCKED, supervisor.protectionStatus())

        val replacementSupervisor = SerializedTunnelSupervisor(launcher)
        assertFalse(replacementSupervisor.restorePersisted())
        assertEquals(1, launcher.launches)
        assertEquals(ProtectionStatus.BLOCKED, replacementSupervisor.protectionStatus())

        launcher.keepChildAliveOnStop = false
        assertEquals(TunnelStopResult.Stopped, supervisor.stop())
        assertEquals(ProtectionStatus.DISABLED, supervisor.protectionStatus())
    }

    @Test
    fun unguardedStartCannotReplaceAnActiveGuardWithoutExplicitStop() {
        val launcher = RecordingProtectedLauncher()
        val supervisor = SerializedTunnelSupervisor(launcher)
        supervisor.start(guardedConfig)
        launcher.events.clear()

        assertIs<IOException>(runCatching { supervisor.start(unguardedConfig) }.exceptionOrNull())

        assertEquals(emptyList(), launcher.events)
        assertEquals(guardedConfig, launcher.persisted)
    }

    @Test
    fun rollbackRetainsOwnershipWhenRestoredChildCannotBeStoppedAfterIdentityFailure() {
        val launcher = RecordingProtectedLauncher()
        val supervisor = SerializedTunnelSupervisor(launcher)
        supervisor.start(guardedConfig)
        launcher.failLaunchAt = 2
        launcher.failPermitAt = 3
        launcher.refuseStopAt = 3

        assertIs<IOException>(runCatching { supervisor.start(replacementGuardedConfig) }.exceptionOrNull())

        assertEquals(TunnelSupervisorState.RUNNING, supervisor.state())
        assertEquals(guardedConfig, launcher.persisted)
        assertTrue(launcher.children.last().alive)
        assertEquals(ProtectionStatus.BLOCKED, supervisor.protectionStatus())
    }

    @Test
    fun standingProtectionPersistsAcrossStopAndServiceRestoreUntilExplicitlyDisarmed() {
        val launcher = RecordingProtectedLauncher()
        val supervisor = SerializedTunnelSupervisor(launcher)

        supervisor.armStandingProtection(guardedConfig.protection!!)
        assertEquals(guardedConfig.protection, launcher.persistedStanding)
        assertEquals(ProtectionStatus.BLOCKED, supervisor.protectionStatus())

        assertEquals(TunnelStartResult.Started, supervisor.start(guardedConfig))
        assertIs<IOException>(runCatching { supervisor.disarmStandingProtection() }.exceptionOrNull())
        assertEquals(TunnelStopResult.Stopped, supervisor.stop())
        assertFalse("release" in launcher.events)
        assertEquals(ProtectionStatus.BLOCKED, supervisor.protectionStatus())

        val restarted = SerializedTunnelSupervisor(launcher)
        assertFalse(restarted.restorePersisted())
        assertEquals(ProtectionStatus.BLOCKED, restarted.protectionStatus())

        restarted.disarmStandingProtection()
        assertEquals(null, launcher.persistedStanding)
        assertEquals(ProtectionStatus.DISABLED, restarted.protectionStatus())
    }

    private class RecordingProtectedLauncher : TunnelLauncher {
        val events = mutableListOf<String>()
        val children = mutableListOf<Child>()
        var launches = 0
        var persisted: ValidatedSingBoxConfig? = null
        var persistedStanding: SelectedAppProtection? = null
        var failArm = false
        var failClear = false
        var failPersist = false
        var keepChildAliveOnStop = false
        var failLaunchAt: Int? = null
        var failPermitAt: Int? = null
        var refuseStopAt: Int? = null
        var released = false

        override fun launch(config: ValidatedSingBoxConfig): TunnelChild {
            check(persisted == config)
            events += "launch"
            launches++
            if (failLaunchAt == launches) throw IOException("launch failed")
            return Child(this, launches).also(children::add)
        }

        override fun persistConfig(config: ValidatedSingBoxConfig) {
            events += "persist"
            if (failPersist) throw IOException("persist failed")
            persisted = config
        }

        override fun loadPersistedConfig(): ValidatedSingBoxConfig? = persisted

        override fun clearPersistedConfig() {
            events += "clear"
            if (failClear) throw IOException("clear failed")
            persisted = null
        }

        override fun loadStandingProtection(): SelectedAppProtection? = persistedStanding

        override fun persistStandingProtection(protection: SelectedAppProtection) {
            events += "persistStanding"
            persistedStanding = protection
        }

        override fun clearStandingProtection() {
            events += "clearStanding"
            persistedStanding = null
        }

        override fun armProtection(config: ValidatedSingBoxConfig) {
            events += "arm"
            if (failArm) throw IOException("arm failed")
            released = false
        }

        override fun permitProtection(config: ValidatedSingBoxConfig, confirmChildAlive: () -> Unit): ProtectionPermitResult {
            confirmChildAlive()
            check(children.last().alive)
            events += "permit"
            if (failPermitAt == launches) throw IOException("identity failed")
            return ProtectionPermitResult.PERMITTED
        }

        override fun blockProtection() { events += "block" }
        override fun releaseProtection() { events += "release"; released = true }
        override fun closeProtectionPermitsOnly() { events += "closePermits" }
        override fun protectionStatus() = if (released) ProtectionStatus.DISABLED else ProtectionStatus.BLOCKED

        class Child(private val owner: RecordingProtectedLauncher, private val launchNumber: Int) : TunnelChild {
            var alive = true
            override fun isAlive() = alive
            override fun requestStop() {
                owner.events += "requestStop"
                if (!owner.keepChildAliveOnStop && owner.refuseStopAt != launchNumber) alive = false
            }
            override fun awaitStopped(timeoutMillis: Long): Boolean {
                if (!alive) owner.events += "childStopped"
                return !alive
            }
        }
    }

    private companion object {
        val guardedConfig = ValidatedSingBoxConfig(
            """{"inbounds":[{"type":"tun","address":["10.0.0.1/30"]}],"outbounds":[{"type":"direct","tag":"default"}]}""",
            SelectedAppProtection(listOf("C:\\Apps\\browser.exe")),
        )
        val unguardedConfig = ValidatedSingBoxConfig("""{"inbounds":[{"type":"tun"}],"outbounds":[{"type":"direct","tag":"default"}]}""")
        val replacementGuardedConfig = ValidatedSingBoxConfig(
            """{"inbounds":[{"type":"tun","address":["10.0.0.1/30"]}],"outbounds":[{"type":"direct","tag":"default"}]}""",
            SelectedAppProtection(listOf("C:\\Apps\\other.exe")),
        )
    }
}
