package com.yokhan.gates.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsRouteRuleTest {

    @Test
    fun routesLocalhostDomainsDirectly() {
        val rule = windowsLocalDirectRouteRule()
        val domains = rule.getAsJsonArray("domain").map { it.asString }
        val suffixes = rule.getAsJsonArray("domain_suffix").map { it.asString }

        assertEquals("direct", rule.get("outbound").asString)
        assertTrue("localhost" in domains)
        assertTrue("localhost" in suffixes)
        assertTrue("local" in suffixes)
    }

    @Test
    fun detectsTunPermissionFailure() {
        val output = "configure tun interface: Access is denied."

        assertTrue(isTunPermissionFailure(output))
        assertFalse(isTunPermissionFailure("dial tcp: i/o timeout"))
    }

    @Test
    fun extractsTunAddressIpsForWindowsCleanup() {
        assertEquals(
            listOf("172.19.0.1", "fd00::1"),
            windowsTunAddressIps(listOf("172.19.0.1/30", "fd00::1/64", "172.19.0.1/30")),
        )
    }

    @Test
    fun migratesLegacySingBoxTunAddressAwayFromNekoDefault() {
        assertEquals(
            listOf("172.30.248.1/30"),
            normalizeTunAddress(listOf("172.19.0.1/30")),
        )
    }

    @Test
    fun tunCleanupScriptTreatsMissingAddressAsSuccess() {
        val script = buildWindowsTunAddressCleanupScript("GATES", listOf("172.19.0.1"))

        assertTrue(script.contains("Get-NetIPAddress"))
        assertTrue(script.contains("exit 0"))
        assertTrue(script.contains("cleanup_error="))
    }

    @Test
    fun recoversRuntimePidFromProcessStateWhenPidFileIsMissing() {
        assertEquals(
            21044L,
            managedRuntimePid(null, mapOf("pid" to "21044")),
        )
    }

    @Test
    fun autoDnsUsesStableRouteInsteadOfUrltest() {
        assertEquals(
            "naive",
            selectWindowsDnsProxyOutbound("auto", setOf("auto", "hy2", "bridge", "relay", "naive")),
        )
    }

    @Test
    fun manualDnsRouteIsPreserved() {
        assertEquals(
            "bridge",
            selectWindowsDnsProxyOutbound("bridge", setOf("auto", "bridge", "naive")),
        )
    }

    @Test
    fun autoDnsCanSelectNlTcpAndManualSelectionIsPreserved() {
        assertEquals("nl-tcp", selectWindowsDnsProxyOutbound("auto", setOf("auto", "nl-tcp", "hy2")))
        assertEquals("nl-tcp", selectWindowsDnsProxyOutbound("nl-tcp", setOf("auto", "nl-tcp", "hy2")))
    }
}
