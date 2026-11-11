package com.yokhan.gates.windows

import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class WindowsXhttpBindContractTest {

    @Test
    fun activeXhttpWithoutPhysicalBindFailsClosed() = runBlocking {
        listOf(
            WindowsAppConfig(physicalBindEnabled = false),
            WindowsAppConfig(physicalBindEnabled = true, physicalBindInterface = "", physicalBindIpv4 = "192.0.2.10"),
            WindowsAppConfig(physicalBindEnabled = true, physicalBindInterface = "  ", physicalBindIpv4 = "192.0.2.10"),
            WindowsAppConfig(physicalBindEnabled = true, physicalBindInterface = "GATES", physicalBindIpv4 = "192.0.2.10"),
        ).forEach { overrides ->
            val fixture = Fixture()
            fixture.controller.writeConfig(xhttpConfig(overrides))
            fixture.seedXhttpProfile()

            val result = fixture.controller.run("generate")

            assertEquals(1, result.exitCode)
            assertTrue(result.output.contains("Physical bind"), result.output)
            assertFalse(fixture.root.resolve("runtime/xhttp-spec.json").exists())
            assertFalse(fixture.root.resolve("runtime/config.json").exists())
        }
    }

    @Test
    fun activeXhttpWithPhysicalBindEmitsSidecarInterface() = runBlocking {
        val fixture = Fixture()
        fixture.controller.writeConfig(
            xhttpConfig(WindowsAppConfig(physicalBindEnabled = true, physicalBindInterface = "Wi-Fi", physicalBindIpv4 = "192.0.2.10")),
        )
        fixture.seedXhttpProfile()

        val result = fixture.controller.run("generate")

        assertEquals(0, result.exitCode, result.output)
        val spec = JsonParser.parseString(
            fixture.root.resolve("runtime/xhttp-spec.json").readText(),
        ).asJsonObject
        val stream = spec.getAsJsonObject("xray_config")
            .getAsJsonArray("outbounds").first().asJsonObject
            .getAsJsonObject("streamSettings")
        assertEquals("Wi-Fi", stream.getAsJsonObject("sockopt").get("interface").asString)
    }

    @Test
    fun proxyOnlyConfigDoesNotNeedPhysicalBind() = runBlocking {
        val fixture = Fixture()
        fixture.controller.writeConfig(
            xhttpConfig(WindowsAppConfig(runtimeMode = "proxy", physicalBindEnabled = false)),
        )
        fixture.seedPlainProfile()

        val result = fixture.controller.run("generate")

        assertEquals(0, result.exitCode, result.output)
        assertFalse(fixture.root.resolve("runtime/xhttp-spec.json").exists())
    }

    private fun xhttpConfig(overrides: WindowsAppConfig): WindowsAppConfig = WindowsAppConfig(
        profileSource = "legacy",
        runtimeMode = "tun",
        activeRoute = "xhttp",
        restoreSystemProxyOnStop = false,
        roomNetEnabled = false,
    ).let { defaults ->
        defaults.copy(
            runtimeMode = overrides.runtimeMode,
            physicalBindEnabled = overrides.physicalBindEnabled,
            physicalBindInterface = overrides.physicalBindInterface,
            physicalBindIpv4 = overrides.physicalBindIpv4,
        )
    }

    private class Fixture {
        val root: File = Files.createTempDirectory("gates-xhttp-bind-").toFile()
        val controller = WindowsRuntimeController(
            appDir = root,
            runtimeService = object : WindowsRuntimeControl {
                override fun health() = RuntimeCommandResult(1, "unavailable")
                override fun startTunnel(configJson: String): RuntimeCommandResult = error("Must not start")
                override fun stop(): RuntimeCommandResult = error("Must not stop")
            },
            prepareRuntimeService = { error("Must not install") },
            validateGeneratedConfig = { RuntimeCommandResult(0, "Valid") },
            xrayPinProvider = { "a".repeat(64) },
        )

        fun seedXhttpProfile() {
            root.resolve("runtime").mkdirs()
            root.resolve("runtime/subscription.singbox.json").writeText(XHTTP_PROFILE)
        }

        fun seedPlainProfile() {
            root.resolve("runtime").mkdirs()
            root.resolve("runtime/subscription.singbox.json").writeText(PLAIN_PROFILE)
        }
    }

    private companion object {
        // Synthetic RFC 4122 documentation-style value, not a credential.
        const val UUID = "00000000-0000-4000-8000-000000000001"
        const val XHTTP_PROFILE = """{"inbounds":[{"type":"tun","tag":"tun-in"}],"outbounds":[
            {"type":"direct","tag":"direct"},
            {"type":"vless","tag":"xhttp","server":"203.0.113.10","server_port":443,
             "uuid":"$UUID","flow":"",
             "tls":{"enabled":true,"server_name":"entry.example.com",
               "reality":{"enabled":true,"public_key":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","short_id":"abcd"},
               "utls":{"enabled":true,"fingerprint":"chrome"}},
             "transport":{"type":"xhttp","host":"entry.example.com","path":"/lab","mode":"auto"}}
        ]}"""
        const val PLAIN_PROFILE = """{"inbounds":[{"type":"tun","tag":"tun-in"}],"outbounds":[
            {"type":"direct","tag":"direct"},
            {"type":"vless","tag":"bridge","server":"203.0.113.10","server_port":443,"uuid":"$UUID"}
        ]}"""
    }
}
