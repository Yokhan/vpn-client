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
