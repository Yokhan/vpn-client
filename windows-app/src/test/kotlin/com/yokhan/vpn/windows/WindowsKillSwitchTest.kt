package com.yokhan.gates.windows

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

class WindowsKillSwitchTest {
    private val config = WindowsAppConfig(killSwitchEnabled = true,
        appProxyProcessPaths = listOf("C:\\Apps\\Browser\\browser.exe"))

    @Test fun disabledProtectionDoesNotRewriteConfig() {
        assertEquals("{  }", windowsProtectionConfig("{  }", config.copy(killSwitchEnabled = false)))
    }

    @Test fun emitsOnlyExplicitSelectedExeMetadata() {
        val root = JsonParser.parseString(windowsProtectionConfig("{\"outbounds\":[]}", config)).asJsonObject
        assertEquals(1, root.getAsJsonObject("gates_protection").get("schema").asInt)
        assertEquals(config.appProxyProcessPaths.single(), root.getAsJsonObject("gates_protection")
            .getAsJsonArray("protected_executables")[0].asString)
        assertTrue(root.has("outbounds"))
    }

    @Test fun rejectsProxyModeAndMissingOrUnresolvedNames() {
        listOf(config.copy(runtimeMode = "proxy"), config.copy(appProxyProcessPaths = emptyList()),
            config.copy(appProxyProcessNames = listOf("helper.exe"))).forEach {
            assertFailsWith<IllegalArgumentException> { windowsProtectionConfig("{}", it) }
        }
    }

    @Test fun rejectsAmbiguousAndNetworkExecutablePaths() {
        listOf("browser.exe", "\\\\server\\share\\browser.exe", "C:\\Apps\\..\\browser.exe",
            "C:\\Apps\\browser.exe:stream", "C:\\Apps\\browser.exe\n").forEach {
            assertFailsWith<IllegalArgumentException> {
                windowsProtectionConfig("{}", config.copy(appProxyProcessPaths = listOf(it)))
            }
        }
    }

    @Test fun rejectsDirectConflictAndUntrustedMetadata() {
        assertFailsWith<IllegalArgumentException> {
            windowsProtectionConfig("{}", config.copy(appDirectProcessPaths = config.appProxyProcessPaths))
        }
        assertFailsWith<IllegalArgumentException> {
            windowsProtectionConfig("{\"gates_protection\":{}}", config)
        }
    }

    @Test fun protectionDoesNotFollowProcessLiveness() {
        assertEquals(WindowsProtectionState.UNAVAILABLE, parseWindowsProtectionState(RuntimeCommandResult(0, "RUNNING")))
        assertEquals(WindowsProtectionState.BLOCKED, parseWindowsProtectionState(RuntimeCommandResult(0, "BLOCKED")))
        assertEquals(WindowsProtectionState.UNAVAILABLE, parseWindowsProtectionState(RuntimeCommandResult(1, "ARMED")))
    }

    @Test fun onlyConfirmedBlockingIsLabelledBlocked() {
        assertEquals("ТРАФИК ЗАБЛОКИРОВАН",
            windowsProtectionDashboardLabels(WindowsProtectionState.BLOCKED, true, false).disconnected)
        listOf(WindowsProtectionState.ERROR, WindowsProtectionState.UNAVAILABLE).forEach {
            assertEquals("ЗАЩИТА НЕ ПОДТВЕРЖДЕНА", windowsProtectionDashboardLabels(it, true, false).disconnected)
        }
    }

    @Test fun preferenceSaveNeverStartsAnOfflineTunnel() = runBlocking {
        val controller = offlineController()
        controller.writeConfig(config.copy(physicalBindEnabled = false))
        assertEquals(0, controller.run("set-kill-switch", "true").exitCode)
        assertTrue(controller.readConfig().killSwitchEnabled)
    }

    @Test fun invalidPreferenceDoesNotRewriteSavedState() = runBlocking {
        val controller = offlineController()
        controller.writeConfig(config.copy(killSwitchEnabled = false, appProxyProcessPaths = emptyList()))
        assertEquals(1, controller.run("set-kill-switch", "true").exitCode)
        assertFalse(controller.readConfig().killSwitchEnabled)
    }

    @Test fun enabledProtectionRejectsProxyModeWithoutChangingSettings() = runBlocking {
        val controller = offlineController()
        controller.writeConfig(config)
        assertEquals(1, controller.run("set-runtime", "proxy").exitCode)
        assertEquals("tun", controller.readConfig().runtimeMode)
        assertTrue(controller.readConfig().killSwitchEnabled)
    }

    @Test fun enabledProtectionCannotRemoveItsLastExactExecutable() {
        val controller = offlineController()
        controller.writeConfig(config)
        assertEquals(1, controller.removeAppRouting(config.appProxyProcessPaths.single(), true).exitCode)
        assertEquals(config.appProxyProcessPaths, controller.readConfig().appProxyProcessPaths)
    }

    @Test fun runningUnguardedServiceIsNotAcceptedAsProtectedConnection() = runBlocking {
        val controller = WindowsRuntimeController(
            appDir = Files.createTempDirectory("gates-protection-running").toFile(),
            runtimeService = object : WindowsRuntimeControl {
                override fun health() = RuntimeCommandResult(0, "RUNNING")
                override fun protectionStatus() = RuntimeCommandResult(0, "DISABLED")
                override fun startTunnel(configJson: String): RuntimeCommandResult = error("Must not start")
                override fun stop(): RuntimeCommandResult = error("Must not stop")
            },
            prepareRuntimeService = { error("Must not install") },
        )
        controller.writeConfig(config)
        assertEquals(1, controller.run("connect").exitCode)
    }

    @Test fun disconnectedKillSwitchArmsAndDisarmsStandingWfpProtection() = runBlocking {
        var armedPayloads = mutableListOf<String>()
        var protectionStatus = "DISABLED"
        val controller = WindowsRuntimeController(
            appDir = Files.createTempDirectory("gates-protection-standing").toFile(),
            runtimeServiceInstalled = { true },
            runtimeService = object : WindowsRuntimeControl {
                override fun health() = RuntimeCommandResult(0, "STOPPED")
                override fun configurationDigest() = RuntimeCommandResult(0, "NONE")
                override fun protectionStatus() = RuntimeCommandResult(0, protectionStatus)
                override fun armProtection(protectionJson: String): RuntimeCommandResult {
                    armedPayloads += protectionJson
                    protectionStatus = "BLOCKED"
                    return RuntimeCommandResult(0, "BLOCKED")
                }
                override fun disarmProtection(): RuntimeCommandResult {
                    protectionStatus = "DISABLED"
                    return RuntimeCommandResult(0, "DISABLED")
                }
                override fun startTunnel(configJson: String): RuntimeCommandResult = error("Must not start tunnel")
                override fun stop(): RuntimeCommandResult = error("Must not stop tunnel")
            },
            prepareRuntimeService = { error("Already running") },
        )
        controller.writeConfig(config.copy(killSwitchEnabled = false))

        assertEquals(0, controller.run("set-kill-switch", "true").exitCode)
        assertTrue(controller.readConfig().killSwitchEnabled)
        assertEquals(1, armedPayloads.size)
        assertEquals("BLOCKED", controller.queryLiveRuntimeStatus().protectionState.name)

        // Adding another exe while disconnected and Kill Switch ON updates standing WFP policy.
        assertEquals(0, controller.addAppRouting("C:\\Apps\\Chat\\chat.exe", proxy = true).exitCode)
        assertEquals(2, armedPayloads.size)
        assertTrue("chat.exe" in armedPayloads.last())

        // Turning Kill Switch OFF while BLOCKED succeeds and disarms WFP.
        assertEquals(0, controller.run("set-kill-switch", "false").exitCode)
        assertFalse(controller.readConfig().killSwitchEnabled)
        assertEquals("DISABLED", controller.queryLiveRuntimeStatus().protectionState.name)
    }

    @Test fun armFailureRollsBackKillSwitchConfigCleanly() = runBlocking {
        val controller = WindowsRuntimeController(
            appDir = Files.createTempDirectory("gates-protection-rollback").toFile(),
            runtimeServiceInstalled = { true },
            runtimeService = object : WindowsRuntimeControl {
                override fun health() = RuntimeCommandResult(0, "STOPPED")
                override fun configurationDigest() = RuntimeCommandResult(0, "NONE")
                override fun protectionStatus() = RuntimeCommandResult(0, "DISABLED")
                override fun armProtection(protectionJson: String) =
                    RuntimeCommandResult(1, "GATES Runtime rejected the request: UNSUPPORTED_COMMAND")
                override fun startTunnel(configJson: String): RuntimeCommandResult = error("Must not start")
                override fun stop(): RuntimeCommandResult = error("Must not stop")
            },
            prepareRuntimeService = { RuntimeCommandResult(0, "ok") },
            repairRuntimeService = { RuntimeCommandResult(0, "ok") },
        )
        controller.writeConfig(config.copy(killSwitchEnabled = false))

        val result = controller.run("set-kill-switch", "true")
        assertEquals(1, result.exitCode)
        assertTrue("GATES_PROTECTION_SERVICE_UNAVAILABLE" in result.output)
        assertFalse(controller.readConfig().killSwitchEnabled)
    }

    @Test fun outdatedRunningServiceIsRepairedOnUnsupportedCommandAndMissingExeIsReported() = runBlocking {
        var repaired = 0
        var protectionStatus = "DISABLED"
        var armMode = "unsupported"
        val controller = WindowsRuntimeController(
            appDir = Files.createTempDirectory("gates-protection-repair").toFile(),
            runtimeServiceInstalled = { true },
            runtimeService = object : WindowsRuntimeControl {
                override fun health() = RuntimeCommandResult(0, "STOPPED")
                override fun configurationDigest() = RuntimeCommandResult(0, "NONE")
                override fun protectionStatus() = RuntimeCommandResult(0, protectionStatus)
                override fun armProtection(protectionJson: String): RuntimeCommandResult = when (armMode) {
                    "unsupported" -> RuntimeCommandResult(1, "GATES Runtime rejected the request: UNSUPPORTED_COMMAND")
                    "missing" -> RuntimeCommandResult(1, "GATES Runtime rejected the request: MISSING_EXE")
                    else -> {
                        protectionStatus = "BLOCKED"
                        RuntimeCommandResult(0, "BLOCKED")
                    }
                }
                override fun startTunnel(configJson: String): RuntimeCommandResult = error("Must not start")
                override fun stop(): RuntimeCommandResult = error("Must not stop")
            },
            prepareRuntimeService = { RuntimeCommandResult(0, "ok") },
            repairRuntimeService = {
                repaired++
                armMode = "ok"
                RuntimeCommandResult(0, "ok")
            },
        )
        controller.writeConfig(config.copy(killSwitchEnabled = false))

        val upgraded = controller.run("set-kill-switch", "true")
        assertEquals(0, upgraded.exitCode, upgraded.output)
        assertEquals(1, repaired)
        assertTrue(controller.readConfig().killSwitchEnabled)

        controller.writeConfig(config.copy(killSwitchEnabled = false))
        protectionStatus = "DISABLED"
        armMode = "missing"
        val missingExe = controller.run("set-kill-switch", "true")
        assertEquals(1, missingExe.exitCode)
        assertTrue("GATES_PROTECTION_MISSING_EXE" in missingExe.output)
        assertFalse(controller.readConfig().killSwitchEnabled)
    }

    @Test fun disconnectedKillSwitchToggleSucceedsEvenWhenConfigGenerationFailsOffline() = runBlocking {
        var protectionStatus = "DISABLED"
        val controller = WindowsRuntimeController(
            appDir = Files.createTempDirectory("gates-protection-offline-toggle").toFile(),
            runtimeServiceInstalled = { true },
            runtimeService = object : WindowsRuntimeControl {
                override fun health() = RuntimeCommandResult(0, "STOPPED")
                override fun configurationDigest() = RuntimeCommandResult(0, "NONE")
                override fun protectionStatus() = RuntimeCommandResult(0, protectionStatus)
                override fun armProtection(protectionJson: String): RuntimeCommandResult {
                    protectionStatus = "BLOCKED"
                    return RuntimeCommandResult(0, "BLOCKED")
                }
                override fun disarmProtection(): RuntimeCommandResult {
                    protectionStatus = "DISABLED"
                    return RuntimeCommandResult(0, "DISABLED")
                }
                override fun startTunnel(configJson: String): RuntimeCommandResult = error("Must not start")
                override fun stop(): RuntimeCommandResult = error("Must not stop")
            },
            validateGeneratedConfig = { RuntimeCommandResult(1, "offline NIC unavailable") },
            prepareRuntimeService = { RuntimeCommandResult(0, "ok") },
        )
        val profile = """{"inbounds":[{"type":"tun","tag":"tun-in"}],"outbounds":[{"type":"direct","tag":"direct"},{"type":"vless","tag":"bridge","server":"203.0.113.10","server_port":443,"uuid":"00000000-0000-4000-8000-000000000001"}]}"""
        controller.writeConfig(config.copy(killSwitchEnabled = false, activeRoute = "bridge"))
        assertEquals(0, controller.storeImportedProfile("manual", profile).exitCode)

        val on = controller.run("set-kill-switch", "true")
        assertEquals(0, on.exitCode, on.output)
        assertTrue(controller.readConfig().killSwitchEnabled)
        assertEquals("BLOCKED", protectionStatus)

        val off = controller.run("set-kill-switch", "false")
        assertEquals(0, off.exitCode, off.output)
        assertFalse(controller.readConfig().killSwitchEnabled)
        assertEquals("DISABLED", protectionStatus)
    }

    @Test fun connectedKillSwitchToggleStopsDisarmsAndRestartsTunnel() = runBlocking {
        val events = mutableListOf<String>()
        var running = true
        var protectionStatus = "ARMED"
        val controller = WindowsRuntimeController(
            appDir = Files.createTempDirectory("gates-protection-connected-").toFile(),
            runtimeServiceInstalled = { true },
            runtimeService = object : WindowsRuntimeControl {
                override fun health() = RuntimeCommandResult(0, if (running) "RUNNING" else "STOPPED")
                override fun configurationDigest() = RuntimeCommandResult(0, if (running) "a".repeat(64) else "NONE")
                override fun protectionStatus() = RuntimeCommandResult(0, protectionStatus)
                override fun armProtection(protectionJson: String): RuntimeCommandResult {
                    events += "arm"
                    protectionStatus = "BLOCKED"
                    return RuntimeCommandResult(0, "BLOCKED")
                }
                override fun disarmProtection(): RuntimeCommandResult {
                    events += "disarm"
                    protectionStatus = "DISABLED"
                    return RuntimeCommandResult(0, "DISABLED")
                }
                override fun startTunnel(configJson: String): RuntimeCommandResult {
                    events += if ("gates_protection" in configJson) "start-guarded" else "start-unguarded"
                    running = true
                    protectionStatus = if ("gates_protection" in configJson) "ARMED" else "DISABLED"
                    return RuntimeCommandResult(0, "STARTED")
                }
                override fun stop(): RuntimeCommandResult {
                    events += "stop"
                    running = false
                    if (protectionStatus == "ARMED") protectionStatus = "BLOCKED"
                    return RuntimeCommandResult(0, "STOPPED")
                }
            },
            validateGeneratedConfig = { RuntimeCommandResult(0, "ok") },
            prepareRuntimeService = { RuntimeCommandResult(0, "ok") },
        )
        val profile = """{"inbounds":[{"type":"tun","tag":"tun-in"}],"outbounds":[{"type":"direct","tag":"direct"},{"type":"vless","tag":"bridge","server":"203.0.113.10","server_port":443,"uuid":"00000000-0000-4000-8000-000000000001"}]}"""
        controller.writeConfig(config.copy(killSwitchEnabled = true, activeRoute = "bridge", physicalBindInterface = "Wi-Fi", physicalBindIpv4 = "192.0.2.10"))
        assertEquals(0, controller.storeImportedProfile("manual", profile).exitCode)
        assertEquals(0, controller.run("generate").exitCode)

        val off = controller.run("set-kill-switch", "false")
        assertEquals(0, off.exitCode, off.output)
        assertFalse(controller.readConfig().killSwitchEnabled)
        assertEquals("DISABLED", controller.queryLiveRuntimeStatus().protectionState.name)

        val on = controller.run("set-kill-switch", "true")
        assertEquals(0, on.exitCode, on.output)
        assertTrue(controller.readConfig().killSwitchEnabled)
        assertEquals("ARMED", controller.queryLiveRuntimeStatus().protectionState.name)
        assertEquals(listOf("stop", "disarm", "start-unguarded", "stop", "arm", "start-guarded"), events)
    }

    private fun offlineController() = WindowsRuntimeController(
        appDir = Files.createTempDirectory("gates-protection-preference").toFile(),
        runtimeServiceInstalled = { false },
        runtimeService = object : WindowsRuntimeControl {
            override fun health() = RuntimeCommandResult(1, "unavailable")
            override fun startTunnel(configJson: String): RuntimeCommandResult = error("Must not start")
            override fun stop(): RuntimeCommandResult = error("Must not stop")
        },
        prepareRuntimeService = { error("Must not install") },
    )
}
