package com.yokhan.gates.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YokhanBootstrapAccessTest {

    @Test
    fun candidateBootstrapApiBases_prioritizesConfiguredCloakAndPrimaryOnce() {
        val candidates = candidateBootstrapApiBases("https://app.yokhan.xyz/gates/")
        assertEquals(
            listOf(
                "https://app.yokhan.xyz/gates",
                "https://cloak.yokhan.xyz/gates",
            ),
            candidates,
        )
    }

    @Test
    fun candidateBootstrapApiBases_filtersInvalidOrBlankEntries() {
        val candidates = candidateBootstrapApiBases("http://insecure.example.com")
        assertEquals(
            listOf(
                "https://cloak.yokhan.xyz/gates",
                "https://app.yokhan.xyz/gates",
            ),
            candidates,
        )
    }

    @Test
    fun parseBootstrapClaimPayload_parsesExpiresAtAndSubscriptionUrl() {
        val now = 1_700_000_000_000L
        val futureEpochSec = (now / 1000L) + 900L
        val parsed = parseBootstrapClaimPayload(
            """{"subscription_url":"https://cloak.yokhan.xyz/gates/api/bootstrap/sub/tok123","expires_at":$futureEpochSec}""",
            nowMillis = now,
        )
        assertEquals("https://cloak.yokhan.xyz/gates/api/bootstrap/sub/tok123", parsed.subscriptionUrl)
        assertEquals(futureEpochSec * 1000L, parsed.expiresAtMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseBootstrapClaimPayload_rejectsNonHttpsSubscriptionUrl() {
        parseBootstrapClaimPayload("""{"subscription_url":"http://example.org/sub"}""", nowMillis = 1_000L)
    }

    @Test
    fun remainingBootstrapMinutes_returnsNullWhenExpiredAndRoundsUpWhenActive() {
        val now = 1_000_000L
        assertNull(remainingBootstrapMinutes(now, now))
        assertNull(remainingBootstrapMinutes(now - 1L, now))
        assertEquals(1, remainingBootstrapMinutes(now + 10_000L, now))
        assertEquals(15, remainingBootstrapMinutes(now + 15L * 60_000L, now))
    }

    @Test
    fun bootstrapClaimErrorMessage_mapsKnownStatusCodes() {
        assertTrue(bootstrapClaimErrorMessage(403).contains("пароль"))
        assertTrue(bootstrapClaimErrorMessage(429).contains("24"))
        assertTrue(bootstrapClaimErrorMessage(503).contains("недоступен"))
        assertTrue(bootstrapClaimErrorMessage(502).contains("502"))
    }
}
