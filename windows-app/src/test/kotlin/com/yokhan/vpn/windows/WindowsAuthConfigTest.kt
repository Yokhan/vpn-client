package com.yokhan.gates.windows

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsAuthConfigTest {

    @Test
    fun usesGatesClientForBlankConfig() {
        assertEquals("gates-windows", normalizeWindowsAuthClientId(""))
        assertEquals("gates-windows", normalizeWindowsAuthClientId(null))
    }

    @Test
    fun migratesLegacyVpnClientIdToGatesClient() {
        assertEquals(
            "gates-windows",
            normalizeWindowsAuthClientId("yokhan-vpn-windows"),
        )
    }

    @Test
    fun keepsExplicitCustomClientId() {
        assertEquals("custom-client", normalizeWindowsAuthClientId("custom-client"))
    }
}
