package com.yokhan.gates.windows

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsOfflineSettingsTest {
    @Test fun bothRuleListsCanBeEditedBeforeTheServiceIsInstalled() {
        val fixture = fixture(false)
        assertEquals(0, fixture.controller.addAppRouting("game.exe", proxy = true).exitCode)
        assertEquals(0, fixture.controller.addAppRouting("browser.exe", proxy = false).exitCode)
        assertEquals(listOf("game.exe"), fixture.controller.readConfig().appProxyProcessNames)
        assertTrue("browser.exe" in fixture.controller.readConfig().appDirectProcessNames)
        assertEquals(0, fixture.starts)
    }

    @Test fun anInstalledOrUnknownUnreachableBrokerStillRejectsLiveEdits() {
        for (presence in listOf(true, null)) {
            val fixture = fixture(presence)
            val before = fixture.controller.readConfig()
            assertEquals(1, fixture.controller.addAppRouting("game.exe", proxy = true).exitCode)
            assertEquals(before, fixture.controller.readConfig())
        }
    }

    @Test fun importedNlAndHyRoutesAreAvailableAndPhantomBridgeIsRejected() = runBlocking {
        val fixture = fixture(false)
        assertEquals(listOf("nl-tcp", "hy2"), fixture.controller.availableRoutes().map { it.tag })
        assertEquals(0, fixture.controller.run("set-route", "nl-tcp").exitCode)
        assertEquals("nl-tcp", fixture.controller.readConfig().activeRoute)
        assertEquals(1, fixture.controller.run("set-route", "bridge").exitCode)
        assertEquals("nl-tcp", fixture.controller.readConfig().activeRoute)
    }

    @Test fun preparationDoesNotConnectOrRequireAnImportedProfile() = runBlocking {
        val fixture = fixture(false)
        assertEquals(0, fixture.controller.run("prepare-runtime").exitCode)
        assertEquals(1, fixture.preparations)
        assertEquals(0, fixture.starts)
    }

    @Test fun proxyModeAndIsolatedAccessNeverRequestElevation() = runBlocking {
        val fixture = fixture(false)
        fixture.controller.writeConfig(fixture.controller.readConfig().copy(runtimeMode = "proxy"))
        assertEquals(0, fixture.controller.run("prepare-runtime").exitCode)
        assertEquals(0, fixture.preparations)
        val controller = WindowsRuntimeController(appDir = Files.createTempDirectory("gates-access-prep").toFile(),
            accessOnly = true, prepareRuntimeService = { error("Must not elevate") },
            prepareRuntimeWithoutTunnel = { error("Must not elevate") })
        assertEquals(403, controller.run("prepare-runtime").exitCode)
    }

    private fun fixture(presence: Boolean?): Fixture = Fixture(presence)

    private class Fixture(presence: Boolean?) {
        val root = Files.createTempDirectory("gates-offline-settings").toFile()
        var starts = 0
        var preparations = 0
        val controller = WindowsRuntimeController(
            appDir = root,
            runtimeService = object : WindowsRuntimeControl {
                override fun health() = RuntimeCommandResult(1, "Unavailable")
                override fun startTunnel(configJson: String): RuntimeCommandResult {
                    starts++
                    error("Offline settings must not start a tunnel")
                }
                override fun stop() = error("Must not stop anything")
            },
            runtimeServiceInstalled = { presence },
            prepareRuntimeService = { error("Preparation must not restore a saved tunnel") },
            prepareRuntimeWithoutTunnel = { preparations++; RuntimeCommandResult(0, "Ready") },
            validateGeneratedConfig = { RuntimeCommandResult(0, "Valid") },
        )
        init {
            controller.writeConfig(WindowsAppConfig(profileSource = "account", physicalBindEnabled = false))
            root.resolve("runtime/account.singbox.json").apply {
                parentFile.mkdirs()
                writeText("""{"outbounds":[{"type":"vless","tag":"nl-tcp","server":"example.test","server_port":443},{"type":"hysteria2","tag":"hy2","server":"example.test","server_port":443}],"route":{}}""")
            }
        }
    }
}
