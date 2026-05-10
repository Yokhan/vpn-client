package com.yokhan.vpn.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CertificateFingerprintTest {
    @Test
    fun normalizesColonSeparatedUppercaseSha256() {
        val input = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"

        val normalized = CertificateFingerprint.normalizeSha256(input)

        assertEquals(
            "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
            normalized,
        )
    }

    @Test
    fun normalizesSpaceSeparatedSha256() {
        val input = "aa bb cc dd ee ff 00 11 22 33 44 55 66 77 88 99 aa bb cc dd ee ff 00 11 22 33 44 55 66 77 88 99"

        val normalized = CertificateFingerprint.normalizeSha256(input)

        assertEquals(
            "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
            normalized,
        )
    }

    @Test
    fun rejectsWrongLength() {
        assertNull(CertificateFingerprint.normalizeSha256("aabbcc"))
    }

    @Test
    fun rejectsNonHexCharacters() {
        assertNull(
            CertificateFingerprint.normalizeSha256(
                "ggbbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
            ),
        )
    }
}
