package com.yokhan.gates.windows

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsConfigRefreshTest {
    @Test
    fun importedProfileIsNextSessionOnlyWhileRuntimeServiceIsRunning() {
        val root = Files.createTempDirectory("gates-config-refresh-test").toFile()
        val service = RunningRuntimeService()
        val controller = WindowsRuntimeController(appDir = root, runtimeService = service)
        val activeSnapshot = """{"inbounds":[{"type":"tun"}],"outbounds":[{"type":"vless","tag":"old-route"}]}"""
        val rawConfig = root.resolve("runtime/subscription.singbox.json")
        val generatedConfig = root.resolve("runtime/config.json")
        rawConfig.parentFile.mkdirs()
        rawConfig.writeText("""{"outbounds":[{"type":"vless","tag":"old-route","server":"old.example","server_port":443}]}""")
        generatedConfig.writeText(activeSnapshot)

        val imported = controller.storeImportedProfile(
            "account",
            """{"outbounds":[{"type":"vless","tag":"new-route","server":"new.example","server_port":443}]}""",
        )
        val connect = kotlinx.coroutines.runBlocking { controller.run("connect") }

        assertEquals(0, imported.exitCode)
        assertEquals(0, connect.exitCode)
        assertTrue(connect.output.contains("next connection"))
        assertEquals(activeSnapshot, generatedConfig.readText())
        assertEquals("new-route", root.resolve("runtime/account.singbox.json").readText().let {
            com.google.gson.JsonParser.parseString(it).asJsonObject
                .getAsJsonArray("outbounds")[0].asJsonObject.get("tag").asString
        })
        assertEquals(0, service.startCalls)
    }

    private class RunningRuntimeService : WindowsRuntimeControl {
        var startCalls = 0

        override fun health() = RuntimeCommandResult(0, "RUNNING")

        override fun startTunnel(configJson: String): RuntimeCommandResult {
            startCalls += 1
            return RuntimeCommandResult(0, "RESTARTED")
        }

        override fun stop() = RuntimeCommandResult(0, "STOPPED")
    }
}
