package io.nekohasekai.sagernet.bg.proto

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XhttpSidecarBridgeTest {

    private val raw = """
        {
          "inbounds": [{"type": "tun", "tag": "tun-in"}],
          "outbounds": [
            {
              "type": "vless",
              "tag": "xhttp",
              "server": "46.226.160.43",
              "server_port": 24443,
              "uuid": "11111111-1111-1111-1111-111111111111",
              "transport": {"type": "xhttp", "path": "/assets/demo", "mode": "auto"},
              "tls": {
                "enabled": true,
                "server_name": "dutchnews.nl",
                "reality": {"enabled": true, "public_key": "PUBLIC_KEY", "short_id": "SHORT"},
                "utls": {"enabled": true, "fingerprint": "chrome"}
              }
            },
            {"type": "direct", "tag": "direct"}
          ],
          "route": {"final": "xhttp"}
        }
    """.trimIndent()

    @Test
    fun rewritesXhttpRouteToSidecarAndMapping() {
        val plan = assertNotNull(XhttpSidecarBridge.build(raw, 28080, 28081))
        assertEquals("46.226.160.43", plan.entryHost)
        assertEquals(24443, plan.entryPort)
        assertEquals(28080, plan.spec.sidecarPort)
        assertEquals("46.226.160.43", plan.spec.entryHost)
        assertEquals(24443, plan.spec.entryPort)
        assertEquals("dutchnews.nl", plan.spec.serverName)

        val root = JsonParser.parseString(plan.configJson).asJsonObject
        val outbound = root.getAsJsonArray("outbounds").first { it.asJsonObject.get("tag").asString == "xhttp" }.asJsonObject
        assertEquals("socks", outbound.get("type").asString)
        assertEquals(28080, outbound.get("server_port").asInt)

        val bypass = root.getAsJsonObject("route").getAsJsonArray("rules").first().asJsonObject
        assertEquals("46.226.160.43/32", bypass.getAsJsonArray("ip_cidr").first().asString)
        assertEquals("direct", bypass.get("outbound").asString)
        assertEquals(
            false,
            root.getAsJsonArray("inbounds").any { it.asJsonObject.get("tag")?.asString == "xhttp-entry" },
        )
    }

    @Test
    fun ignoresProfilesWithoutXhttp() {
        val plain = """{"outbounds":[{"type":"vless","tag":"nl-tcp","server":"r1.yokhan.xyz","server_port":24158}]}"""
        assertNull(XhttpSidecarBridge.build(plain, 28080, 28081))
    }

    @Test
    fun rejectsIncompleteReality() {
        val broken = raw.replace("\"public_key\": \"PUBLIC_KEY\", ", "")
        assertNull(XhttpSidecarBridge.build(broken, 28080, 28081))
        assertTrue(broken.contains("short_id"))
    }
}
