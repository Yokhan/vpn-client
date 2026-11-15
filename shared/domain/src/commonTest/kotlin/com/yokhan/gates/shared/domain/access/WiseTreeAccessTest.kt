package com.yokhan.gates.shared.domain.access

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WiseTreeAccessTest {
    @Test
    fun sha256MatchesStandardTestVectors() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            WiseTreeAccess.sha256Utf8Hex("abc"),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            WiseTreeAccess.sha256Utf8Hex(""),
        )
    }

    @Test
    fun verifySaltedCodeNormalizesCaseAndWhitespace() {
        val salt = "test-salt"
        val digest = WiseTreeAccess.sha256Utf8Hex("test-salt:open sesame")
        assertTrue(WiseTreeAccess.verifySaltedCode("  OPEN SESAME  ", salt, digest))
        assertFalse(WiseTreeAccess.verifySaltedCode("wrong", salt, digest))
        assertFalse(WiseTreeAccess.verifyCode("definitely-wrong-passphrase"))
    }

    @Test
    fun embeddedBootstrapProfileIsStrictlyScopedToYokhanDomains() {
        val json = WiseTreeAccess.buildEmbeddedBootstrapProfileJson()
        assertTrue(WiseTreeAccess.EMBEDDED_UUID in json)
        assertTrue(""""server":"46.226.160.43"""" in json)
        assertTrue(""""server_port":24443""" in json)
        assertTrue(""""domain_suffix":["yokhan.xyz","yokhanfitness.ru"]""" in json)
        assertTrue(""""final":"block"""" in json)
    }
}
