package com.yokhan.gates.windows

import com.google.gson.JsonParser
import com.yokhan.gates.windows.service.ConfigValidationResult
import com.yokhan.gates.windows.service.SingBoxConfigValidator
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WindowsRuBypassTest {
    @Test
    fun ruBypassAndWholeTrafficModesAreMutuallyExclusive() {
        val whole = WindowsAppConfig().withWholeTrafficChanged(true)
        assertTrue(whole.wholeTrafficMode)
        assertFalse(whole.enableRuBypass)

        val ru = whole.withRuBypassChanged(true)
        assertTrue(ru.enableRuBypass)
        assertFalse(ru.wholeTrafficMode)
    }

    @Test
    fun explicitUserProxyRulesOverrideRuAutoBypass() {
        val config = WindowsAppConfig(
            enableRuBypass = true,
            wholeTrafficMode = false,
            appProxyProcessNames = listOf("YandexMusic.exe"),
            appProxyProcessPaths = listOf("C:\\Users\\user\\AppData\\Local\\Yandex\\YandexBrowser\\Application\\browser.exe"),
        )
        val rules = windowsRuBypassRouteRules(config)
        val processRule = rules.firstOrNull { it.has("process_name") }
        val processNames = processRule?.getAsJsonArray("process_name")?.map { it.asString } ?: emptyList()
        assertFalse("YandexMusic.exe" in processNames)
        assertTrue("gosuslugi.ru" in rules.last().getAsJsonArray("domain_suffix").map { it.asString })
        assertFalse(rules.any { it.has("process_path_regex") })
    }

    @Test
    fun lanAndLoopbackExclusionRulesRespectToggle() {
        val withLanBypass = effectiveTunExcludeAddresses(WindowsAppConfig(bypassLan = true))
        assertTrue("192.168.0.0/16" in withLanBypass)

        val withoutLanBypass = effectiveTunExcludeAddresses(WindowsAppConfig(bypassLan = false))
        assertFalse("192.168.0.0/16" in withoutLanBypass)
        assertTrue("127.0.0.0/8" in withoutLanBypass)

        val loopback = windowsLoopbackDirectRule()
        assertEquals("direct", loopback.get("outbound").asString)
        val quicReject = windowsQuicRejectRule()
        assertEquals("reject", quicReject.get("action").asString)
    }

    @Test
    fun generatedConfigWithRuBypassValidatesWithServiceAndSingBoxBinary() {
        val root = Files.createTempDirectory("gates-ru-bypass-test-").toFile()
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = false,
            appDir = root,
            validateGeneratedConfig = { RuntimeCommandResult(0, "ok") },
        )
        controller.writeConfig(
            WindowsAppConfig(
                activeRoute = "bridge",
                physicalBindEnabled = false,
                restoreSystemProxyOnStop = false,
                roomNetEnabled = false,
                enableRuBypass = true,
                wholeTrafficMode = false,
                bypassLan = true,
                blockQuic = true,
            ),
        )
        root.resolve("runtime").mkdirs()
        root.resolve("runtime/subscription.singbox.json").writeText(VLESS_PROFILE, StandardCharsets.UTF_8)

        val generated = kotlinx.coroutines.runBlocking { controller.run("generate") }
        assertEquals(0, generated.exitCode, generated.output)

        val configFile = root.resolve("runtime/config.json")
        val configBytes = configFile.readBytes()
        val validation = SingBoxConfigValidator.validate(configBytes)
        assertIs<ConfigValidationResult.Accepted>(validation)

        val parsed = JsonParser.parseString(configFile.readText(StandardCharsets.UTF_8)).asJsonObject
        val rules = parsed.getAsJsonObject("route").getAsJsonArray("rules")
        assertTrue(rules.any { it.isJsonObject && it.asJsonObject.get("action")?.asString == "reject" })
        assertTrue(rules.any {
            it.isJsonObject &&
                it.asJsonObject.get("outbound")?.asString == "direct" &&
                it.asJsonObject.getAsJsonArray("domain_suffix")?.any { s -> s.asString == "gosuslugi.ru" } == true
        })

        val singBoxExe = File("C:\\Users\\iohan\\Documents\\YokhanIndependentVPN\\.tmp\\sing-box\\sing-box-1.13.12-windows-amd64\\sing-box.exe")
        if (singBoxExe.isFile) {
            val process = ProcessBuilder(singBoxExe.absolutePath, "check", "-c", configFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(15, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), "sing-box check failed: $output")
        }
    }

    @Test
    fun disablesUpstreamUdp443BlockWhenBlockQuicIsFalseAndOptimizesLogAndDns() {
        val root = Files.createTempDirectory("gates-ru-quic-test-").toFile()
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = false,
            appDir = root,
            validateGeneratedConfig = { RuntimeCommandResult(0, "ok") },
        )
        controller.writeConfig(
            WindowsAppConfig(
                activeRoute = "bridge",
                physicalBindEnabled = false,
                restoreSystemProxyOnStop = false,
                roomNetEnabled = false,
                blockQuic = false,
            ),
        )
        root.resolve("runtime").mkdirs()
        root.resolve("runtime/subscription.singbox.json").writeText(
            """{"dns":{"servers":[{"type":"https","tag":"proxy-dns","server":"1.1.1.1","path":"/dns-query","detour":"bridge"},{"type":"udp","tag":"direct-dns","server":"77.88.8.8"}]},"inbounds":[{"type":"tun","tag":"tun-in"}],"outbounds":[{"type":"direct","tag":"direct"},{"type":"block","tag":"block"},{"type":"vless","tag":"bridge","server":"203.0.113.10","server_port":443,"uuid":"00000000-0000-4000-8000-000000000001"}],"route":{"rules":[{"network":["udp"],"port":[443],"outbound":"block"}]}}""",
            StandardCharsets.UTF_8,
        )

        val generated = kotlinx.coroutines.runBlocking { controller.run("generate") }
        assertEquals(0, generated.exitCode, generated.output)

        val parsed = JsonParser.parseString(root.resolve("runtime/config.json").readText(StandardCharsets.UTF_8)).asJsonObject
        assertEquals("warn", parsed.getAsJsonObject("log").get("level").asString)
        val dns = parsed.getAsJsonObject("dns")
        assertEquals(true, dns.get("independent_cache").asBoolean)
        val proxyDns = dns.getAsJsonArray("servers").first { it.asJsonObject.get("tag").asString == "proxy-dns" }.asJsonObject
        assertEquals("tcp", proxyDns.get("type").asString)
        assertEquals("1.1.1.1", proxyDns.get("server").asString)
        assertTrue(!proxyDns.has("path"))
        val rules = parsed.getAsJsonObject("route").getAsJsonArray("rules")
        assertTrue(rules.none {
            it.isJsonObject && it.asJsonObject.get("outbound")?.asString == "block" &&
                it.asJsonObject.getAsJsonArray("network")?.any { n -> n.asString == "udp" } == true
        })
    }

    private companion object {
        const val VLESS_PROFILE = """{"inbounds":[{"type":"tun","tag":"tun-in"}],"outbounds":[
            {"type":"direct","tag":"direct"},
            {"type":"vless","tag":"bridge","server":"203.0.113.10","server_port":443,"uuid":"00000000-0000-4000-8000-000000000001"}
        ]}"""
    }
}
