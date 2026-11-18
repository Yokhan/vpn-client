package com.yokhan.gates.windows

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsXhttpTransportTest {

    // Synthetic RFC 4122 documentation-style value, not a credential.
    private val syntheticUuid = "00000000-0000-4000-8000-000000000001"

    @Test
    fun convertsAnXhttpOutboundIntoALoopbackSocksEgress() {
        val root = JsonParser.parseString(profile()).asJsonObject
        val plan = applyWindowsXhttpTransport(
            root,
            WindowsAppConfig(),
            WindowsXhttpBind(interfaceName = "Wi-Fi", ipv4 = "192.0.2.10"),
            resolve = { listOf("203.0.113.10") },
        )

        assertNotNull(plan)
        assertEquals("xhttp", plan.outboundTag)
        assertEquals(2095, plan.listenPort)
        assertEquals("reality", plan.security)
        val outbound = root.getAsJsonArray("outbounds").first { it.asJsonObject.get("tag").asString == "xhttp" }.asJsonObject
        assertEquals("socks", outbound.get("type").asString)
        assertEquals("127.0.0.1", outbound.get("server").asString)
        assertEquals(2095, outbound.get("server_port").asInt)
        assertEquals("5", outbound.get("version").asString)
        assertFalse(outbound.has("network"))

        val tun = root.getAsJsonArray("inbounds").first { it.asJsonObject.get("type").asString == "tun" }.asJsonObject
        val excluded = tun.getAsJsonArray("route_exclude_address").map { it.asString }
        assertTrue("203.0.113.10/32" in excluded)

        val xray = JsonParser.parseString(plan.xrayConfigJson).asJsonObject
        assertFalse(xray.has("dns"))
        assertFalse(xray.has("routing"))
        assertEquals(2095, xray.getAsJsonArray("inbounds").first().asJsonObject.get("port").asInt)
        val stream = xray.getAsJsonArray("outbounds").first().asJsonObject.getAsJsonObject("streamSettings")
        assertEquals("xhttp", stream.get("network").asString)
        assertEquals("reality", stream.get("security").asString)
        assertEquals(
            "4",
            stream.getAsJsonObject("xhttpSettings").getAsJsonObject("extra").getAsJsonObject("xmux").get("maxConnections").asString,
        )
        assertEquals("203.0.113.10", xray.getAsJsonArray("outbounds").first().asJsonObject
            .getAsJsonObject("settings").getAsJsonArray("vnext").first().asJsonObject.get("address").asString)
        assertEquals("Wi-Fi", stream.getAsJsonObject("sockopt").get("interface").asString)
        assertFalse(xray.toString().contains("192.0.2.10"))
    }

    @Test
    fun pinsTheSidecarSpecWithoutEmbeddingAnAddressOrCredential() {
        val root = JsonParser.parseString(profile()).asJsonObject
        val plan = applyWindowsXhttpTransport(
            root,
            WindowsAppConfig(),
            WindowsXhttpBind(interfaceName = "Wi-Fi"),
            resolve = { listOf("203.0.113.10") },
        )!!
        val spec = JsonParser.parseString(
            windowsXhttpSpecJson(plan, required = true, executableSha256 = "a".repeat(64)),
        ).asJsonObject

        assertEquals(1, spec.get("schema").asInt)
        assertEquals("xhttp", spec.get("outbound_tag").asString)
        assertEquals("xray.exe", spec.get("executable").asString)
        assertEquals(2095, spec.get("listen_port").asInt)
        assertTrue(spec.get("required").asBoolean)
        assertTrue(spec.getAsJsonObject("xray_config").has("inbounds"))
        assertFails { windowsXhttpSpecJson(plan, required = true, executableSha256 = "not-pinned") }
    }

    @Test
    fun rejectsProfilesWithAnUnsupportedRouteIdOrTlsShape() {
        val bind = WindowsXhttpBind(interfaceName = "Wi-Fi")
        val wrongTag = profile().replace("\"tag\":\"xhttp\"", "\"tag\":\"xhttp-lab\"")
        assertFails {
            applyWindowsXhttpTransport(
                JsonParser.parseString(wrongTag).asJsonObject,
                WindowsAppConfig(),
                bind,
                resolve = { listOf("203.0.113.10") },
            )
        }
        val noTls = profile().replace("\"tls\":{", "\"tlsMissing\":{")
        assertFails {
            applyWindowsXhttpTransport(
                JsonParser.parseString(noTls).asJsonObject,
                WindowsAppConfig(),
                bind,
                resolve = { listOf("203.0.113.10") },
            )
        }
        assertFails {
            applyWindowsXhttpTransport(
                JsonParser.parseString(profile()).asJsonObject,
                WindowsAppConfig(),
                bind,
                resolve = { emptyList() },
            )
        }
    }

    @Test
    fun requiresAConcretePhysicalInterfaceForAnActiveXhttpRoute() {
        listOf(
            null,
            WindowsXhttpBind(),
            WindowsXhttpBind(interfaceName = " "),
            WindowsXhttpBind(interfaceName = WindowsAppConfig().tunInterfaceName),
        ).forEach { bind ->
            val failure = assertFails {
                applyWindowsXhttpTransport(
                    JsonParser.parseString(profile()).asJsonObject,
                    WindowsAppConfig(),
                    bind,
                    resolve = { listOf("203.0.113.10") },
                )
            }
            assertTrue(failure.message.orEmpty().contains("physical interface binding"))
        }
    }

    @Test
    fun removesAnUnselectedXhttpRouteAndItsReferences() {
        val root = JsonParser.parseString(
            """{"inbounds":[{"type":"tun","tag":"tun-in"}],"outbounds":[
                {"type":"selector","tag":"auto","outbounds":["xhttp","nl-tcp"],"default":"xhttp"},
                {"type":"vless","tag":"xhttp","server":"entry.example.com","server_port":443,
                 "uuid":"$syntheticUuid","transport":{"type":"xhttp","path":"/lab"}},
                {"type":"vless","tag":"nl-tcp","server":"fallback.example.com","server_port":443,
                 "uuid":"$syntheticUuid"}
            ],"route":{"final":"xhttp","rules":[{"process_name":["game.exe"],"outbound":"xhttp"}]}}""",
        ).asJsonObject

        assertTrue(stripWindowsXhttpOutbound(root))
        assertNull(windowsXhttpOutbound(root))
        assertEquals("direct", root.getAsJsonObject("route").get("final").asString)
        assertEquals(
            "direct",
            root.getAsJsonObject("route").getAsJsonArray("rules").first().asJsonObject.get("outbound").asString,
        )
        val selector = root.getAsJsonArray("outbounds").first { it.asJsonObject.get("tag").asString == "auto" }.asJsonObject
        assertEquals(listOf("nl-tcp"), selector.getAsJsonArray("outbounds").map { it.asString })
    }

    @Test
    fun allocatesTheFirstFreePortOutsideTheExistingClientListeners() {
        assertEquals(2095, chooseWindowsXhttpPort(2095) { true })
        assertEquals(2096, chooseWindowsXhttpPort(2095) { it != 2095 })
        assertNull(chooseWindowsXhttpPort(2095) { false })
    }

    @Test
    fun mergesTheSidecarSpecOnlyAtTheServiceBoundary() {
        val singBox = """{"inbounds":[{"type":"tun","tag":"tun-in"}],""" +
            """"outbounds":[{"type":"socks","tag":"xhttp","server":"127.0.0.1","server_port":2095}],""" +
            """"route":{"final":"xhttp"}}"""
        val spec = """{"schema":1,"outbound_tag":"xhttp","executable":"xray.exe",""" +
            """"executable_sha256":"${"a".repeat(64)}","listen_port":2095,"required":true,""" +
            """"xray_config":{"inbounds":[],"outbounds":[]}}"""
        val merged = JsonParser.parseString(
            windowsServiceTransportConfig(singBox, spec, WindowsAppConfig()),
        ).asJsonObject
        assertTrue(merged.has("gates_xhttp"))
        assertFalse(merged.has("gates_protection"))
        assertEquals("xhttp", merged.getAsJsonObject("route").get("final").asString)

        val plain = windowsServiceTransportConfig(singBox, null, WindowsAppConfig())
        assertEquals(singBox, plain)
    }

    @Test
    fun exposesTheXhttpRouteInTheProfileList() {
        val routes = windowsProfileRoutes(profile())
        assertEquals(listOf("xhttp"), routes.map { it.tag })
        assertEquals("XHTTP (Xray)", routes.single().label)
        assertEquals("VLESS / XHTTP", routes.single().protocol)
        assertFalse(routes.toString().contains(syntheticUuid))
    }

    private fun profile(): String = """{"inbounds":[{"type":"tun","tag":"tun-in"}],"outbounds":[
        {"type":"direct","tag":"direct"},
        {"type":"vless","tag":"xhttp","server":"entry.example.com","server_port":443,
         "uuid":"$syntheticUuid","flow":"",
         "tls":{"enabled":true,"server_name":"entry.example.com",
           "reality":{"enabled":true,"public_key":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","short_id":"abcd"},
           "utls":{"enabled":true,"fingerprint":"chrome"}},
         "transport":{"type":"xhttp","host":"entry.example.com","path":"/lab","mode":"auto"}}
    ]}"""
}
