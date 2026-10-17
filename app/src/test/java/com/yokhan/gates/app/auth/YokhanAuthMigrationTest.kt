package com.yokhan.gates.app.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YokhanAuthMigrationTest {
    @Test
    fun acceptsOnlyTheConfiguredIssuer() {
        assertTrue(authIssuerMatches("https://auth.yokhan.xyz/realms/yokhan", "https://auth.yokhan.xyz/realms/yokhan"))
        assertTrue(authIssuerMatches("https://auth.yokhan.xyz/realms/yokhan/", "https://auth.yokhan.xyz/realms/yokhan"))
        assertFalse(authIssuerMatches("https://auth.yokhanfitness.ru/realms/yokhan", "https://auth.yokhan.xyz/realms/yokhan"))
        assertFalse(authIssuerMatches(null, "https://auth.yokhan.xyz/realms/yokhan"))
    }
}
