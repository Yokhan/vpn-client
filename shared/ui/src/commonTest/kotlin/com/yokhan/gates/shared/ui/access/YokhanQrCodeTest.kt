package com.yokhan.gates.shared.ui.access

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YokhanQrCodeTest {
    @Test
    fun `encodes non-empty auth verification uri into square QR matrix`() {
        val qr = encodeYokhanQrMatrix("https://auth.yokhan.xyz/realms/yokhan/device?user_code=ABCD-EFGH")
        assertNotNull(qr)
        assertTrue(qr.size >= 21)
        assertTrue(qr.modules.size == qr.size * qr.size)
        assertTrue(qr.modules.any { it })
        assertTrue(qr.modules.any { !it })
    }

    @Test
    fun `returns null for blank qr input`() {
        assertNull(encodeYokhanQrMatrix("   "))
    }
}
