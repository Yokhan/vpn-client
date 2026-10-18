package com.yokhan.gates.windows

import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsAppRoutingTest {
    @Test
    fun malformedExecutablePathIsRejectedWithoutThrowing() {
        assertNull(parseWindowsAppRoutingTarget("C:\\Bad\u0000folder\\game.exe"))
        assertNull(parseWindowsAppRoutingTarget("C:\\bad?folder\\game.exe"))
        assertNull(parseWindowsAppRoutingTarget("\"C:\\Games\\game.exe"))
        assertNull(parseWindowsAppRoutingTarget("relative\\game.exe"))
        if (File.separatorChar == '\\') {
            assertNull(parseWindowsAppRoutingTarget("C:\\Trailing space \\game.exe"))
        }
    }

    @Test
    fun processRowsFallBackToNameWhenPathIsMissingOrInvalid() {
        assertEquals("game.exe", windowsProcessRoutingValue("game.exe", ""))
        assertEquals("game.exe", windowsProcessRoutingValue("game.exe", "relative\\game.exe"))
        val path = File("C:/Games/A/game.exe").absolutePath
        assertEquals(path, windowsProcessRoutingValue("game.exe", path))
    }

    @Test
    fun exactExecutableDoesNotAddNameWideRule() {
        val path = File("C:/Games/A/game.exe").absolutePath
        val target = parseWindowsAppRoutingTarget("\"$path\"")!!
        val result = updateWindowsAppRouting(WindowsAppConfig(), target, proxy = true, remove = false)
        assertEquals(listOf(path), result.appProxyProcessPaths)
        assertTrue(result.appProxyProcessNames.isEmpty())
    }

    @Test
    fun duplicateNamesKeepIndependentExactPathRules() {
        val a = parseWindowsAppRoutingTarget(File("C:/Games/A/game.exe").absolutePath)!!
        val b = parseWindowsAppRoutingTarget(File("C:/Games/B/game.exe").absolutePath)!!
        val proxy = updateWindowsAppRouting(WindowsAppConfig(), a, proxy = true, remove = false)
        val direct = updateWindowsAppRouting(proxy, b, proxy = false, remove = false)
        assertEquals(listOf(a.processPath), direct.appProxyProcessPaths)
        assertEquals(listOf(b.processPath), direct.appDirectProcessPaths)
        assertTrue(direct.appProxyProcessNames.isEmpty())
        assertFalse("game.exe" in direct.appDirectProcessNames)
    }

    @Test
    fun removingPathDoesNotDeleteExplicitNameRule() {
        val path = parseWindowsAppRoutingTarget(File("C:/Games/A/game.exe").absolutePath)!!
        val config = WindowsAppConfig(appProxyProcessNames = listOf("game.exe"), appProxyProcessPaths = listOf(path.processPath))
        val result = updateWindowsAppRouting(config, path, proxy = true, remove = true)
        assertEquals(listOf("game.exe"), result.appProxyProcessNames)
        assertTrue(result.appProxyProcessPaths.isEmpty())
    }

    @Test
    fun explicitNameMovesOnlyItsNameWideRule() {
        val target = parseWindowsAppRoutingTarget("game.exe")!!
        val config = WindowsAppConfig(appProxyProcessNames = listOf("GAME.exe"), appProxyProcessPaths = listOf("C:\\A\\game.exe"))
        val result = updateWindowsAppRouting(config, target, proxy = false, remove = false)
        assertEquals(listOf("C:\\A\\game.exe"), result.appProxyProcessPaths)
        assertTrue(result.appProxyProcessNames.isEmpty())
        assertTrue("game.exe" in result.appDirectProcessNames)
        assertNull(parseWindowsAppRoutingTarget("relative/game.exe"))
        assertNull(parseWindowsAppRoutingTarget("game.dll"))
    }

    @Test
    fun executableNameCanBeAddedToEitherRoutingTarget() {
        val proxy = updateWindowsAppRouting(
            WindowsAppConfig(),
            parseWindowsAppRoutingTarget("game.exe")!!,
            proxy = true,
            remove = false,
        )
        val direct = updateWindowsAppRouting(
            WindowsAppConfig(),
            parseWindowsAppRoutingTarget("game.exe")!!,
            proxy = false,
            remove = false,
        )
        assertEquals(listOf("game.exe"), proxy.appProxyProcessNames)
        assertEquals(WindowsAppConfig().appDirectProcessNames + "game.exe", direct.appDirectProcessNames)
        assertEquals(WindowsAppConfig().appDirectProcessNames, proxy.appDirectProcessNames)
        assertTrue(direct.appProxyProcessNames.isEmpty())
    }

    @Test
    fun exactRulesPrecedeNameWideRules() {
        val config = WindowsAppConfig(appProxyProcessPaths = listOf("C:\\A\\game.exe"), appDirectProcessNames = listOf("game.exe"))
        val rules = windowsAppProcessRules(config, "auto").map { it.asJsonObject }
        assertEquals("auto", rules.first().get("outbound").asString)
        assertTrue(rules.first().has("process_path"))
        assertTrue(rules.last().has("process_name"))
    }

    @Test
    fun activeServiceReceivesAddedAndRemovedRules() {
        val fixture = Fixture()
        fixture.controller.writeConfig(fixture.config)
        fixture.seedConfig()
        assertEquals(0, fixture.controller.addAppRouting("game.exe", proxy = true).exitCode)
        assertTrue(fixture.service.started.last().contains("game.exe"))
        assertEquals(0, fixture.controller.removeAppRouting("game.exe", proxy = true).exitCode)
        assertFalse(fixture.service.started.last().contains("game.exe"))
        assertEquals(2, fixture.service.started.size)
    }

    @Test
    fun rejectedApplyRestoresFilesAndServiceConfig() {
        val fixture = Fixture()
        fixture.controller.writeConfig(fixture.config)
        fixture.seedConfig()
        val oldSettings = fixture.root.resolve("app-config.json").readText()
        fixture.service.responses += RuntimeCommandResult(1, "Rejected")
        fixture.service.responses += RuntimeCommandResult(0, "RESTARTED")
        val result = fixture.controller.addAppRouting("game.exe", proxy = true)
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("previous settings restored"))
        assertEquals(oldSettings, fixture.root.resolve("app-config.json").readText())
        assertEquals(Fixture.OLD_CONFIG, fixture.root.resolve("runtime/config.json").readText())
        assertEquals(Fixture.OLD_CONFIG, fixture.service.started.last())
    }

    @Test
    fun validationFailureDoesNotTouchService() {
        val fixture = Fixture(validation = RuntimeCommandResult(1, "Invalid"))
        fixture.controller.writeConfig(fixture.config)
        fixture.seedConfig()
        val result = fixture.controller.addAppRouting("game.exe", proxy = true)
        assertEquals(1, result.exitCode)
        assertTrue(fixture.controller.readConfig().appProxyProcessNames.isEmpty())
        assertEquals(Fixture.OLD_CONFIG, fixture.root.resolve("runtime/config.json").readText())
        assertTrue(fixture.service.started.isEmpty())
    }

    @Test
    fun failedRollbackIsNotReportedAsRestored() {
        val fixture = Fixture()
        fixture.controller.writeConfig(fixture.config)
        fixture.seedConfig()
        repeat(2) { fixture.service.responses += RuntimeCommandResult(1, "Unavailable") }
        val result = fixture.controller.addAppRouting("game.exe", proxy = true)
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("rollback incomplete"))
    }

    @Test
    fun missingRollbackConfigRejectsActiveEdit() {
        val fixture = Fixture()
        fixture.controller.writeConfig(fixture.config)
        val result = fixture.controller.addAppRouting("game.exe", proxy = true)
        assertEquals(1, result.exitCode)
        assertTrue(fixture.controller.readConfig().appProxyProcessNames.isEmpty())
        assertTrue(fixture.service.started.isEmpty())
    }

    @Test
    fun unrelatedAcknowledgementTriggersRollback() {
        val fixture = Fixture()
        fixture.controller.writeConfig(fixture.config)
        fixture.seedConfig()
        fixture.service.responses += RuntimeCommandResult(0, "STOPPED")
        val result = fixture.controller.addAppRouting("game.exe", proxy = true)
        assertEquals(1, result.exitCode)
        assertEquals(Fixture.OLD_CONFIG, fixture.service.started.last())
    }

    @Test
    fun disconnectedEditIsSavedWithoutStartingService() {
        val fixture = Fixture()
        fixture.service.state = "STOPPED"
        fixture.service.liveConfig = null
        fixture.controller.writeConfig(fixture.config)
        val result = fixture.controller.addAppRouting("game.exe", proxy = true)
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("Saved for the next connection"))
        assertTrue(fixture.service.started.isEmpty())
    }

    @Test
    fun stopReachesServiceEvenWhenChildHasDied() {
        val fixture = Fixture()
        fixture.service.state = "STOPPED"
        fixture.controller.writeConfig(fixture.config)
        val result = runBlocking { fixture.controller.run("stop") }
        assertEquals(0, result.exitCode)
        assertEquals(1, fixture.service.stops)
    }

    @Test
    fun pendingStopIsNotReportedAsSuccess() {
        val fixture = Fixture()
        fixture.service.stopResult = RuntimeCommandResult(0, "STOP_REQUESTED")
        fixture.controller.writeConfig(fixture.config)
        assertEquals(1, runBlocking { fixture.controller.run("stop") }.exitCode)
    }

    @Test
    fun staleGeneratedCandidateCannotBecomeRollbackBaseline() {
        val fixture = Fixture()
        fixture.controller.writeConfig(fixture.config)
        fixture.seedConfig()
        fixture.root.resolve("runtime/config.json").writeText("{\"route\":{\"final\":\"direct\"}}")
        val result = fixture.controller.addAppRouting("game.exe", proxy = true)
        assertEquals(1, result.exitCode)
        assertTrue(fixture.service.started.isEmpty())
        assertEquals(Fixture.OLD_CONFIG, fixture.service.liveConfig)
    }

    @Test
    fun failedRoutingModeChangeRestoresSettingsAndRuntime() {
        val fixture = Fixture()
        fixture.controller.writeConfig(fixture.config)
        fixture.seedConfig()
        fixture.service.responses += RuntimeCommandResult(1, "Rejected")
        assertEquals(1, runBlocking { fixture.controller.run("set-tun-routing", "selected") }.exitCode)
        assertTrue(fixture.controller.readConfig().tunRouteAll)
        assertEquals(Fixture.OLD_CONFIG, fixture.service.liveConfig)
    }

    @Test
    fun recoveringServiceReceivesNewRulesInsteadOfResumingOldConfig() {
        val fixture = Fixture()
        fixture.controller.writeConfig(fixture.config)
        fixture.seedConfig()
        fixture.service.state = "STOPPED"
        assertEquals(0, fixture.controller.addAppRouting("game.exe", proxy = true).exitCode)
        assertTrue(fixture.service.started.last().contains("game.exe"))
    }

    @Test
    fun idleBrokerDoesNotSkipOwnedLocalRuntimeStop() {
        var localRunning = true
        val fixture = Fixture(localStop = { localRunning = false; RuntimeCommandResult(0, "Stopped local") })
        fixture.controller.writeConfig(fixture.config)
        fixture.service.state = "STOPPED"
        fixture.service.liveConfig = null
        fixture.service.stopResult = RuntimeCommandResult(0, "ALREADY_STOPPED")
        assertEquals(0, runBlocking { fixture.controller.run("stop") }.exitCode)
        assertFalse(localRunning)
        assertEquals(1, fixture.service.stops)
    }

    @Test
    fun concurrentStopRunsAfterFailedApplyRollbackAndKeepsTunnelStopped() {
        val fixture = Fixture()
        fixture.controller.writeConfig(fixture.config)
        fixture.seedConfig()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.service.responses += RuntimeCommandResult(1, "Timed out")
        fixture.service.startHook = {
            if (fixture.service.started.size == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        var editResult: RuntimeCommandResult? = null
        var stopResult: RuntimeCommandResult? = null
        val edit = thread { editResult = fixture.controller.addAppRouting("game.exe", proxy = true) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val stop = thread { stopResult = runBlocking { fixture.controller.run("stop") } }
        release.countDown()
        edit.join(5_000)
        stop.join(5_000)
        assertFalse(edit.isAlive)
        assertFalse(stop.isAlive)
        assertEquals(1, editResult?.exitCode)
        assertEquals(0, stopResult?.exitCode)
        assertNull(fixture.service.liveConfig)
        assertEquals(2, fixture.service.started.size)
    }

    @Test
    fun unknownDigestCannotBeTreatedAsIdleService() {
        val fixture = Fixture()
        fixture.controller.writeConfig(fixture.config)
        fixture.seedConfig()
        fixture.service.state = "STOPPED"
        fixture.service.digestResult = RuntimeCommandResult(1, "Timed out")
        assertEquals(1, fixture.controller.addAppRouting("game.exe", proxy = true).exitCode)
        assertTrue(fixture.controller.readConfig().appProxyProcessNames.isEmpty())
        assertEquals(Fixture.OLD_CONFIG, fixture.root.resolve("runtime/config.json").readText())
        assertTrue(fixture.service.started.isEmpty())
    }

    @Test
    fun unknownHealthRejectsRoutingEditWithoutChangingFiles() {
        val fixture = Fixture()
        fixture.controller.writeConfig(fixture.config)
        fixture.seedConfig()
        fixture.service.healthResult = RuntimeCommandResult(1, "Unavailable")
        assertEquals(1, fixture.controller.addAppRouting("game.exe", proxy = true).exitCode)
        assertTrue(fixture.controller.readConfig().appProxyProcessNames.isEmpty())
        assertTrue(fixture.service.started.isEmpty())
    }

    @Test
    fun stopStillContactsBrokerAndStopsLocalRuntimeWhenHealthFails() {
        var localRunning = true
        val fixture = Fixture(localStop = { localRunning = false; RuntimeCommandResult(0, "Stopped") })
        fixture.controller.writeConfig(fixture.config)
        fixture.service.healthResult = RuntimeCommandResult(1, "Timed out")
        assertEquals(0, runBlocking { fixture.controller.run("stop") }.exitCode)
        assertEquals(1, fixture.service.stops)
        assertFalse(localRunning)
    }

    @Test
    fun failedBrokerStopDoesNotSkipLocalStopOrClaimCompleteSuccess() {
        var localRunning = true
        val fixture = Fixture(localStop = { localRunning = false; RuntimeCommandResult(0, "Stopped") })
        fixture.controller.writeConfig(fixture.config)
        fixture.service.stopResult = RuntimeCommandResult(1, "Unavailable")
        assertEquals(1, runBlocking { fixture.controller.run("stop") }.exitCode)
        assertEquals(1, fixture.service.stops)
        assertFalse(localRunning)
        assertEquals(Fixture.OLD_CONFIG, fixture.service.liveConfig)
    }

    private class Fixture(
        validation: RuntimeCommandResult = RuntimeCommandResult(0, "Valid"),
        localStop: ((WindowsAppConfig) -> RuntimeCommandResult)? = null,
    ) {
        val root: File = Files.createTempDirectory("gates-routing-test-").toFile()
        val service = FakeService()
        val config = WindowsAppConfig(activeRoute = "bridge", physicalBindEnabled = false, restoreSystemProxyOnStop = false, roomNetEnabled = false)
        val controller = WindowsRuntimeController(
            appDir = root,
            runtimeService = service,
            runtimeServiceInstalled = { true },
            prepareRuntimeService = { RuntimeCommandResult(0, "") },
            validateGeneratedConfig = { JsonParser.parseString(it.readText()); validation },
            stopLocalRuntime = localStop,
        )

        fun seedConfig() {
            root.resolve("runtime").mkdirs()
            root.resolve("runtime/subscription.singbox.json").writeText(OLD_CONFIG)
            root.resolve("runtime/config.json").writeText(OLD_CONFIG)
            root.resolve("runtime/generated-mode.txt").writeText("tun")
        }

        companion object {
            const val OLD_CONFIG = "{\"outbounds\":[{\"tag\":\"bridge\",\"type\":\"vless\"}],\"route\":{}}"
        }
    }

    private class FakeService : WindowsRuntimeControl {
        var state = "RUNNING"
        var healthResult: RuntimeCommandResult? = null
        var digestResult: RuntimeCommandResult? = null
        var liveConfig: String? = Fixture.OLD_CONFIG
        var startHook: (String) -> Unit = {}
        var stopResult = RuntimeCommandResult(0, "STOPPED")
        val started = mutableListOf<String>()
        val responses = mutableListOf<RuntimeCommandResult>()
        var stops = 0
        override fun health() = healthResult ?: RuntimeCommandResult(0, state)
        override fun configurationDigest() = digestResult ?: RuntimeCommandResult(0, liveConfig?.let(::runtimeConfigurationDigest) ?: "NONE")
        override fun startTunnel(configJson: String): RuntimeCommandResult {
            started += configJson
            startHook(configJson)
            val result = if (responses.isEmpty()) RuntimeCommandResult(0, "RESTARTED") else responses.removeAt(0)
            if (result.exitCode == 0 && result.output in setOf("STARTED", "RESTARTED", "ALREADY_RUNNING")) liveConfig = configJson
            return result
        }
        override fun stop(): RuntimeCommandResult {
            stops += 1
            if (stopResult.output in setOf("STOPPED", "ALREADY_STOPPED")) liveConfig = null
            return stopResult
        }
    }
}
