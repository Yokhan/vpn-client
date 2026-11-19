package com.yokhan.gates.windows

import kotlinx.coroutines.runBlocking
import java.awt.AWTException
import java.awt.TrayIcon
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsAutoStartAndTrayTest {
    @Test
    fun enableAndDisableAutostartManageAutostartArgAndStartupApprovedOverride() = runBlocking {
        val root = Files.createTempDirectory("gates-autostart-").toFile()
        val registry = mutableMapOf<Pair<String, String>, Pair<String, String>>()
        val runKey = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        val approvedKey = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"
        registry[approvedKey to "GATES"] = "REG_BINARY" to "0300000011223344"

        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = false,
            appDir = root,
            autoStartLauncherProvider = { "C:\\Program Files\\GATES\\GATES.exe" },
            registryCommandRunner = { args -> fakeRegExe(registry, args) },
        )

        val enabled = controller.run("enable-autostart")
        assertEquals(0, enabled.exitCode, enabled.output)
        assertTrue(controller.readConfig().autoStartOnLogin)
        assertEquals(
            "\"C:\\Program Files\\GATES\\GATES.exe\" --autostart",
            registry[runKey to "GATES"]?.second,
        )
        assertFalse(approvedKey to "GATES" in registry)

        val disabled = controller.run("disable-autostart")
        assertEquals(0, disabled.exitCode, disabled.output)
        assertFalse(controller.readConfig().autoStartOnLogin)
        assertFalse(runKey to "GATES" in registry)
    }

    @Test
    fun enableAutostartFailureLeavesConfigFlagFalse() = runBlocking {
        val root = Files.createTempDirectory("gates-autostart-fail-").toFile()
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = false,
            appDir = root,
            autoStartLauncherProvider = { "C:\\Program Files\\GATES\\GATES.exe" },
            registryCommandRunner = { args ->
                if (args.getOrNull(1) == "add") RuntimeCommandResult(5, "ERROR: Access is denied.")
                else RuntimeCommandResult(1, "not found")
            },
        )

        val res = controller.run("enable-autostart")
        assertEquals(5, res.exitCode)
        assertFalse(controller.readConfig().autoStartOnLogin)
    }

    @Test
    fun reconcileAutostartSyncsMissingOrDisabledRegistryAndUpgradesLegacyRunValue() {
        val root = Files.createTempDirectory("gates-autostart-reconcile-").toFile()
        val registry = mutableMapOf<Pair<String, String>, Pair<String, String>>()
        val runKey = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        val approvedKey = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"

        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = false,
            appDir = root,
            autoStartLauncherProvider = { "C:\\Program Files\\GATES\\GATES.exe" },
            registryCommandRunner = { args -> fakeRegExe(registry, args) },
        )

        // 1. Stale config says true, but registry entry is missing -> syncs false.
        controller.writeConfig(controller.readConfig().copy(autoStartOnLogin = true))
        assertFalse(controller.reconcileAutoStartWithRegistry().autoStartOnLogin)

        // 2. Legacy Run entry exists without --autostart -> upgrades command and syncs true.
        registry[runKey to "GATES"] = "REG_SZ" to "\"C:\\Program Files\\GATES\\GATES.exe\""
        assertTrue(controller.reconcileAutoStartWithRegistry().autoStartOnLogin)
        assertEquals(
            "\"C:\\Program Files\\GATES\\GATES.exe\" --autostart",
            registry[runKey to "GATES"]?.second,
        )

        // 3. Disabled in Task Manager StartupApproved -> syncs false.
        registry[approvedKey to "GATES"] = "REG_BINARY" to "03000000AABBCCDD"
        assertFalse(controller.reconcileAutoStartWithRegistry().autoStartOnLogin)
    }

    @Test
    fun nativeTrayRetriesAfterEarlyShellFailureAppliesLatestStateAndRecoversAfterExplorerRestart() {
        val backend = FakeTrayBackend(supported = false, failAdd = true)
        val tray = NativeTray(
            onOpen = {},
            onQuit = {},
            backend = backend,
            runOnUiThread = { it() },
            startWatchdog = false,
        )

        // Early boot: SystemTray not ready yet
        tray.install()
        assertFalse(tray.isInstalled())

        // Explorer up slightly later, but first NIM_ADD still throws AWTException
        backend.supported = true
        tray.attemptInstallNowForTest()
        assertFalse(tray.isInstalled())

        // Meanwhile tunnel connects -> state is cached
        tray.updateState(
            connected = true,
            activeRouteLabel = "XHTTP (Xray)",
            activeRouteTag = "xhttp",
            routes = listOf(WindowsProfileRoute("xhttp", "XHTTP Primary", "VLESS", "r1.yokhan.xyz")),
            enableRuBypass = false,
        )
        assertFalse(tray.isInstalled())

        // Next retry succeeds and immediately applies the queued connected state
        backend.failAdd = false
        tray.attemptInstallNowForTest()
        assertTrue(tray.isInstalled())
        assertEquals(1, backend.icons.size)
        assertEquals("GATES — Подключено (XHTTP (Xray))", backend.icons.single().toolTip)

        // Simulate explorer.exe restart dropping shell icons -> next check re-installs cleanly
        backend.icons.clear()
        assertFalse(tray.isInstalled())
        tray.attemptInstallNowForTest()
        assertTrue(tray.isInstalled())
        assertEquals("GATES — Подключено (XHTTP (Xray))", backend.icons.single().toolTip)

        tray.uninstall()
        assertFalse(tray.isInstalled())
        assertTrue(backend.icons.isEmpty())
    }

    @Test
    fun serviceManagerWaitsForStartingInstalledServiceWithoutPromptingElevation() {
        var healthChecks = 0
        var powerShellCalled = false
        val fakeClient = object : WindowsRuntimeControl {
            override fun health(): RuntimeCommandResult {
                healthChecks += 1
                return if (healthChecks >= 3) RuntimeCommandResult(0, "STOPPED")
                else RuntimeCommandResult(1, "connection refused")
            }

            override fun startTunnel(configJson: String) = RuntimeCommandResult(0, "STARTED")
            override fun stop() = RuntimeCommandResult(0, "STOPPED")
        }
        val manager = WindowsRuntimeServiceManager(
            client = fakeClient,
            processRunner = { args, _, _ ->
                if (args.firstOrNull() == "sc.exe") {
                    RuntimeCommandResult(0, "SERVICE_NAME: GatesVpn\r\n        STATE              : 2  START_PENDING")
                } else {
                    powerShellCalled = true
                    RuntimeCommandResult(1, "unexpected elevation")
                }
            },
        )

        val result = manager.prepareWithoutConnecting()
        assertEquals(0, result.exitCode)
        assertFalse(powerShellCalled)
        assertTrue(healthChecks >= 3)
    }

    private class FakeTrayBackend(
        var supported: Boolean,
        var failAdd: Boolean,
    ) : TraySystemBackend {
        val icons = mutableListOf<TrayIcon>()

        override fun isSupported(): Boolean = supported

        override fun add(icon: TrayIcon) {
            if (failAdd) throw AWTException("Shell_TrayWnd not initialized")
            icons += icon
        }

        override fun remove(icon: TrayIcon) {
            icons.remove(icon)
        }

        override fun contains(icon: TrayIcon): Boolean = icon in icons

        override fun iconSize(): Int = 16
    }

    private fun fakeRegExe(
        store: MutableMap<Pair<String, String>, Pair<String, String>>,
        args: List<String>,
    ): RuntimeCommandResult {
        val op = args.getOrNull(1)
        val key = args.getOrNull(2).orEmpty()
        val vIdx = args.indexOf("/v")
        val name = if (vIdx >= 0) args.getOrNull(vIdx + 1).orEmpty() else ""
        return when (op) {
            "query" -> {
                val entry = store[key to name]
                    ?: return RuntimeCommandResult(1, "ERROR: The system was unable to find the specified registry key or value.")
                RuntimeCommandResult(0, "\r\n$key\r\n    $name    ${entry.first}    ${entry.second}\r\n")
            }
            "add" -> {
                val tIdx = args.indexOf("/t")
                val dIdx = args.indexOf("/d")
                val type = if (tIdx >= 0) args.getOrNull(tIdx + 1).orEmpty() else "REG_SZ"
                val value = if (dIdx >= 0) args.getOrNull(dIdx + 1).orEmpty() else ""
                store[key to name] = type to value
                RuntimeCommandResult(0, "The operation completed successfully.")
            }
            "delete" -> {
                val removed = store.remove(key to name)
                if (removed != null) RuntimeCommandResult(0, "The operation completed successfully.")
                else RuntimeCommandResult(1, "ERROR: The system was unable to find the specified registry key or value.")
            }
            else -> RuntimeCommandResult(1, "unsupported reg op")
        }
    }
}
