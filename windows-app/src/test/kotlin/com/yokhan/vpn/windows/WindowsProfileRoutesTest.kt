package com.yokhan.gates.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse

class WindowsProfileRoutesTest {
    @Test fun routeListComesFromTheImportedProfileNotTheLegacyCatalog() {
        val routes = windowsProfileRoutes("""{"outbounds":[{"type":"direct","tag":"direct"},{"type":"vless","tag":"nl-tcp","server":"test.example","server_port":443,"uuid":"private-value"},{"type":"hysteria2","tag":"hy2","server":"test.example","server_port":443,"password":"private-value"}]}""")
        assertEquals(listOf("nl-tcp", "hy2"), routes.map { it.tag })
        assertEquals("NL VLESS", routes.first().label)
        assertFalse(routes.toString().contains("private-value"))
        assertEquals("auto", windowsRouteTagForProfileId(1, routes))
        assertEquals("nl-tcp", windowsRouteTagForProfileId(2, routes))
        assertEquals("hy2", windowsRouteTagForProfileId(3, routes))
        assertNull(windowsRouteTagForProfileId(4, routes))
        assertNull(windowsRouteTagForProfileId(Long.MAX_VALUE, routes))
    }

    @Test fun missingOrInvalidProfilesDoNotFabricateServers() {
        assertEquals(emptyList(), windowsProfileRoutes("invalid"))
        assertEquals(emptyList(), windowsProfileRoutes("{}"))
        assertEquals(emptyList(), windowsProfileRoutes("""{"outbounds":[{"type":"vless","tag":"nl-tcp","server":"test.example","server_port":0}]}"""))
    }
}
