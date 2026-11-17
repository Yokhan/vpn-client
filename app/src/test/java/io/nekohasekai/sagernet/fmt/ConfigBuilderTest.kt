package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.bg.resolveEffectiveTunMtu
import io.nekohasekai.sagernet.group.extractFullXhttpConfigName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigBuilderTest {
    @Test
    fun routeOptionsTrackTheActiveAndroidInterface() {
        val route = buildRouteOptions()

        assertTrue(route.auto_detect_interface)
    }

    @Test
    fun extractJsonProxyStatsTagsFindsLeafProxyOutboundsAndIgnoresGroupsOrDirect() {
        val rawConfig = """
            {
              "outbounds": [
                {"type": "urltest", "tag": "auto", "outbounds": ["bridge", "xhttp"]},
                {"type": "vless", "tag": "bridge"},
                {"type": "hysteria2", "tag": "hy2"},
                {"type": "vless", "tag": "xhttp"},
                {"type": "direct", "tag": "direct"},
                {"type": "block", "tag": "block"},
                {"type": "dns", "tag": "dns-out"}
              ]
            }
        """.trimIndent()

        assertEquals(listOf("bridge", "hy2", "xhttp"), extractJsonProxyStatsTags(rawConfig))
    }

    @Test
    fun extractJsonProxyStatsTagsFallsBackToProxyTagWhenJsonHasNoLeafOutbounds() {
        assertEquals(listOf(TAG_PROXY), extractJsonProxyStatsTags("{}"))
        assertEquals(listOf(TAG_PROXY), extractJsonProxyStatsTags("not-json"))
    }

    @Test
    fun normalizeRawConfigTunMtuAlignsTunInboundMtuWithAndroidBuffer() {
        val raw = """{"inbounds":[{"type":"tun","tag":"tun-in","mtu":1500},{"type":"mixed","listen":"127.0.0.1"}],"outbounds":[]}"""
        val normalized = normalizeRawConfigTunMtu(raw, 9000)

        assertTrue(normalized.contains("\"mtu\":9000"))
        assertTrue(!normalized.contains("\"mtu\":1500"))
    }

    @Test
    fun resolveEffectiveTunMtuNeverExceedsSingTunBufferMtu() {
        assertEquals(1500, resolveEffectiveTunMtu("""{"MTU":1500}""", 9000))
        assertEquals(9000, resolveEffectiveTunMtu("""{"MTU":9000}""", 9000))
        assertEquals(1400, resolveEffectiveTunMtu("""{"MTU":1500}""", 1400))
        assertEquals(9000, resolveEffectiveTunMtu("{}", 9000))
    }

    @Test
    fun extractFullXhttpConfigNameDetectsXhttpSingBoxConfigAndSkipsPlainOutbounds() {
        val xhttpJson = """
            {
              "inbounds": [{"type": "tun", "tag": "tun-in", "mtu": 1500}],
              "outbounds": [
                {"type": "vless", "tag": "xhttp", "transport": {"type": "xhttp", "path": "/assets/x"}},
                {"type": "direct", "tag": "direct"}
              ]
            }
        """.trimIndent()
        val regularJson = """
            {
              "outbounds": [
                {"type": "vless", "tag": "reality", "transport": {"type": "tcp"}}
              ]
            }
        """.trimIndent()

        assertEquals("xhttp", extractFullXhttpConfigName(xhttpJson))
        assertNull(extractFullXhttpConfigName(regularJson))
    }
}
