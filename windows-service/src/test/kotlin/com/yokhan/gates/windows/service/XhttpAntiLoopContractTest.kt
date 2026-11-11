package com.yokhan.gates.windows.service

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XhttpAntiLoopContractTest {

    @Test
    fun exactTransportOwnedPathIsDirectRoutedBeforeUserRules() {
        val userRule = """{"process_path":["C:\\Games\\game.exe"],"outbound":"xhttp"}"""
        val conflictingNameRule = """{"process_name":["xray.exe"],"outbound":"xhttp"}"""
        val config = sidecarConfig(
            rules = "$conflictingNameRule,$userRule",
        )

        val runtime = JsonParser.parseString(
            runtimeConfigJson(config, null, listOf(XRAY_PATH)),
        ).asJsonObject

        val route = runtime.getAsJsonObject("route")
        val rules = route.getAsJsonArray("rules")
        val injected = rules.first().asJsonObject
        assertEquals(listOf(XRAY_PATH), injected.getAsJsonArray("process_path").map { it.asString })
        assertEquals("direct", injected.get("outbound").asString)
        assertFalse(injected.has("process_name"))
        assertTrue(route.get("find_process").asBoolean)
        assertEquals("xhttp", rules[1].asJsonObject.get("outbound").asString)
        assertEquals("C:\\Games\\game.exe", rules[2].asJsonObject.getAsJsonArray("process_path").first().asString)
    }

    @Test
    fun doesNotInventAGlobalXrayProcessNameRule() {
        val config = sidecarConfig(rules = """{"process_name":["game.exe"],"outbound":"xhttp"}""")

        val text = runtimeConfigJson(config, null, listOf(XRAY_PATH))

        assertTrue(text.contains("xray.exe"))
        assertFalse(text.contains("\"process_name\":[\"xray.exe\"]"))
    }

    @Test
    fun leavesPlainConfigsWithoutASidecarUnchanged() {
        val plain = ValidatedSingBoxConfig(
            """{"inbounds":[{"type":"tun","tag":"tun-in"}],"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}""",
        )

        val runtime = JsonParser.parseString(
            runtimeConfigJson(plain, null, listOf(XRAY_PATH)),
        ).asJsonObject

        assertFalse(runtime.getAsJsonObject("route").has("rules"))
        assertFalse(runtime.getAsJsonObject("route").has("find_process"))
    }

    @Test
    fun deduplicatesInjectedPathsAndKeepsExistingRules() {
        val config = sidecarConfig(rules = """{"process_name":["game.exe"],"outbound":"xhttp"}""")

        val once = JsonParser.parseString(runtimeConfigJson(config, null, listOf(XRAY_PATH))).asJsonObject
        val twice = JsonParser.parseString(runtimeConfigJson(config, null, listOf(XRAY_PATH, XRAY_PATH))).asJsonObject

        val onceRules = once.getAsJsonObject("route").getAsJsonArray("rules")
        val twiceRules = twice.getAsJsonObject("route").getAsJsonArray("rules")
        assertEquals(2, onceRules.size())
        assertEquals(2, twiceRules.size())
        assertEquals(listOf(XRAY_PATH), twiceRules.first().asJsonObject
            .getAsJsonArray("process_path").map { it.asString })
    }

    @Test
    fun prependHelperIsANoOpForBlankPaths() {
        val root = JsonParser.parseString(
            """{"inbounds":[{"type":"tun"}],"outbounds":[{"type":"direct","tag":"direct"}],"route":{}}""",
        ).asJsonObject

        prependDirectProcessRules(root, listOf("", "  "))

        assertNull(root.getAsJsonObject("route").get("rules"))
    }

    private fun sidecarConfig(rules: String): ValidatedSingBoxConfig {
        val canonical = """{"inbounds":[{"type":"tun","tag":"tun-in"}],
            "outbounds":[
              {"type":"socks","tag":"xhttp","server":"127.0.0.1","server_port":2095,"version":"5"},
              {"type":"direct","tag":"direct"}],
            "route":{"final":"xhttp","find_process":true,"rules":[$rules]}}""".trimIndent()
        return ValidatedSingBoxConfig(
            canonicalJson = canonical,
            sidecar = XhttpSidecarSpec(
                outboundTag = "xhttp",
                executableName = "xray.exe",
                executableSha256 = "a".repeat(64),
                listenPort = 2095,
                required = true,
                xrayConfigJson = """{"inbounds":[],"outbounds":[]}""",
            ),
        )
    }

    private companion object {
        const val XRAY_PATH = "C:\\Program Files\\GATES\\app\\resources\\yokhan-runtime\\windows\\xray.exe"
    }
}
