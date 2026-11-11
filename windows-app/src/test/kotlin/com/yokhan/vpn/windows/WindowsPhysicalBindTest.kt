package com.yokhan.vpn.windows

import com.yokhan.gates.windows.decodeWindowsInterfaceAlias
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowsPhysicalBindTest {
    @Test fun windowsInterfaceAliasSurvivesNonAsciiCodePage() {
        val alias = "Беспроводная сеть"
        val encoded = Base64.getEncoder().encodeToString(alias.toByteArray(StandardCharsets.UTF_8))
        assertEquals(alias, decodeWindowsInterfaceAlias(encoded))
        assertEquals(alias, decodeWindowsInterfaceAlias("  $encoded  "))
    }

    @Test fun invalidAliasPayloadIsRejected() {
        assertNull(decodeWindowsInterfaceAlias(""))
        assertNull(decodeWindowsInterfaceAlias("not-base64!"))
    }
}
