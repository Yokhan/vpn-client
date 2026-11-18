package io.nekohasekai.sagernet.fmt.xray

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class XrayConfigBuilderXhttpSettingsTest {

    @Test
    fun xhttpSettingsIncludesHostAndConservativeXmuxPacing() {
        val spec = XrayRouteSpec(
            routeId = "test-route",
            sidecarPort = 28080,
            mappingPort = 28081,
            entryHost = "127.0.0.1",
            entryPort = 28082,
            uuid = "11111111-2222-3333-4444-555555555555",
            serverName = "dutchnews.nl",
            path = "/gates-xhttp",
            mode = "auto",
            security = XrayStreamSecurity.Reality,
            realityPublicKey = "pubKeyExample1234567890",
            realityShortId = "abcd1234",
            fingerprint = "chrome",
            alpn = listOf("h2", "http/1.1"),
        )

        val json = JsonParser.parseString(XrayConfigBuilder.build(spec)).asJsonObject
        val xhttpSettings = json.getAsJsonArray("outbounds")
            .get(0).asJsonObject
            .getAsJsonObject("streamSettings")
            .getAsJsonObject("xhttpSettings")

        assertEquals("/gates-xhttp", xhttpSettings.get("path").asString)
        assertEquals("auto", xhttpSettings.get("mode").asString)
        assertEquals("dutchnews.nl", xhttpSettings.get("host").asString)

        val xmux = xhttpSettings
            .getAsJsonObject("extra")
            .getAsJsonObject("xmux")
        assertEquals("4", xmux.get("maxConnections").asString)
        assertEquals(30, xmux.get("hKeepAlivePeriod").asInt)
    }
}
