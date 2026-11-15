package io.nekohasekai.sagernet.fmt.xray

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XrayConfigBuilderTest {

    private fun spec() = XrayRouteSpec(
        routeId = "route-xhttp-test",
        sidecarPort = 20801,
        mappingPort = 20802,
        uuid = "00000000-0000-0000-0000-000000000000",
        serverName = "entry.example.invalid",
        realityPublicKey = "PUBLIC_KEY_PLACEHOLDER",
        realityShortId = "0123456789abcdef",
        path = "/xhttp-test",
        mode = "auto",
    )

    @Test
    fun documentIsLoopbackOnlyWithSingleDatapath() {
        val json = XrayConfigBuilder.build(spec())

        assertTrue(json.contains("\"listen\":\"127.0.0.1\""))
        assertTrue(json.contains("\"address\":\"127.0.0.1\""))
        assertTrue(json.contains("\"port\":20801"))
        assertTrue(json.contains("\"port\":20802"))
        assertTrue(json.contains("\"network\":\"xhttp\""))
        assertTrue(json.contains("\"protocol\":\"vless\""))

        assertFalse(json.contains("0.0.0.0"))
        assertFalse(json.contains("\"dns\""))
        assertFalse(json.contains("\"tun\""))
        assertFalse(json.contains("\"routing\""))
        assertFalse(json.contains("\"sockopt\""))
        assertFalse(json.contains("\"api\""))
        assertFalse(json.contains("dialerProxy"))
    }

    @Test
    fun udpIsDisabledByDefault() {
        val json = XrayConfigBuilder.build(spec())

        assertTrue(json.contains("\"udp\":false"))
    }

    @Test
    fun rejectsIdenticalSidecarAndMappingPorts() {
        val failure = assertFailsWith<XrayConfigException> {
            XrayConfigBuilder.build(spec().copy(mappingPort = 20801))
        }

        assertTrue(failure.message.orEmpty().contains("ports must differ"))
    }

    @Test
    fun rejectsBlankCredentialsWithoutEchoingValues() {
        val marker = "SECRET-MARKER"
        val failure = assertFailsWith<XrayConfigException> {
            XrayConfigBuilder.build(spec().copy(uuid = "", realityPublicKey = marker))
        }

        assertFalse(failure.message.orEmpty().contains(marker))
        assertTrue(failure.message.orEmpty().contains("uuid is blank"))
    }

    @Test
    fun rejectsRealityWithoutPublicKey() {
        val failure = assertFailsWith<XrayConfigException> {
            XrayConfigBuilder.build(spec().copy(realityPublicKey = ""))
        }

        assertTrue(failure.message.orEmpty().contains("reality public key is blank"))
    }

    @Test
    fun rejectsControlCharactersInCarriedValues() {
        val failure = assertFailsWith<XrayConfigException> {
            XrayConfigBuilder.build(spec().copy(path = "/xhttp\nnext"))
        }

        assertTrue(failure.message.orEmpty().contains("control characters"))
    }

    @Test
    fun guardRejectsForbiddenDocumentKeys() {
        assertFailsWith<XrayConfigException> {
            XrayConfigBuilder.assertSingleDatapath("{\"dns\":{},\"listen\":\"127.0.0.1\",\"address\":\"127.0.0.1\"}")
        }
        assertFailsWith<XrayConfigException> {
            XrayConfigBuilder.assertSingleDatapath("{\"listen\":\"0.0.0.0\",\"address\":\"127.0.0.1\"}")
        }
        assertFailsWith<XrayConfigException> {
            XrayConfigBuilder.assertSingleDatapath("{\"listen\":\"203.0.113.1\",\"address\":\"127.0.0.1\"}")
        }
        val withEntryHost = XrayConfigBuilder.build(spec().copy(entryHost = "203.0.113.1", entryPort = 24443))
        assertTrue(withEntryHost.contains("\"listen\":\"127.0.0.1\""))
        assertTrue(withEntryHost.contains("\"address\":\"203.0.113.1\""))
        assertTrue(withEntryHost.contains("\"port\":24443"))
    }

    @Test
    fun tlsVariantOmitsRealitySettings() {
        val json = XrayConfigBuilder.build(
            spec().copy(security = XrayStreamSecurity.Tls, realityPublicKey = "", realityShortId = ""),
        )

        assertTrue(json.contains("\"security\":\"tls\""))
        assertFalse(json.contains("realitySettings"))
        assertTrue(json.contains("tlsSettings"))
    }

    @Test
    fun specToStringRedactsSecrets() {
        val route = spec()
        val text = route.toString()

        assertFalse(text.contains(route.uuid))
        assertFalse(text.contains(route.serverName))
        assertFalse(text.contains(route.realityPublicKey))
        assertFalse(text.contains(route.realityShortId))
        assertFalse(text.contains(route.path))
        assertTrue(text.contains("route-xhttp-test"))
        assertTrue(text.contains("<redacted>"))
    }

    @Test
    fun mappingExpectationPointsAtSingBoxOwnedLoopbackInbound() {
        val expectation = spec().mappingExpectation("entry.example.invalid", 443)

        assertEquals("127.0.0.1", expectation.listenHost)
        assertEquals(20802, expectation.listenPort)
        assertEquals("entry.example.invalid", expectation.overrideAddress)
        assertEquals(443, expectation.overridePort)
    }
}
