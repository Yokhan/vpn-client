package com.yokhan.gates.app.auth

import com.yokhan.gates.shared.domain.access.WiseTreeAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun candidateBootstrapSubscriptionUrls_includesCloakFallbackForSubPath() {
        val urls = candidateBootstrapSubscriptionUrls(
            subscriptionUrl = "https://app.yokhan.xyz/gates/api/bootstrap/sub/tok123",
            configuredBase = "https://app.yokhan.xyz/gates",
        )
        assertEquals(
            listOf(
                "https://app.yokhan.xyz/gates/api/bootstrap/sub/tok123",
                "https://cloak.yokhan.xyz/gates/api/bootstrap/sub/tok123",
            ),
            urls,
        )
    }

    @Test
    fun claimWithDeps_rejectsWrongPasswordOfflineWithoutNetworkCall() = runBlocking {
        var requests = 0
        var tunnelStarted = false
        val result = YokhanBootstrapAccess.claimWithDeps(
            code = "wrong-passphrase",
            deviceId = "android-test-1",
            ensureEmbeddedTunnelConnected = { tunnelStarted = true; true },
            claimRequester = { _, _, _ -> requests += 1; 200 to "{}" },
            subFetcher = { "{}" },
            profileUpdater = { _, _ -> 1 },
            profileCleaner = {},
        )
        assertTrue(result.isFailure)
        assertEquals(0, requests)
        assertFalse(tunnelStarted)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("пароль"))
    }

    @Test
    fun claimWithDeps_startsEmbeddedTunnelWhenDirectBlockedAndStopsOn429Cooldown() = runBlocking {
        var tunnelUp = false
        var stopped = false
        var cleaned = false
        val importedPayloads = mutableListOf<String>()
        val result = YokhanBootstrapAccess.claimWithDeps(
            code = "tree-secret",
            codeVerifier = { it == "tree-secret" },
            deviceId = "android-test-2",
            ensureEmbeddedTunnelConnected = { tunnelUp = true; true },
            stopEmbeddedTunnel = { tunnelUp = false; stopped = true },
            claimRequester = { _, _, _ ->
                if (!tunnelUp) error("blocked without VPN")
                429 to """{"error":"cooldown"}"""
            },
            subFetcher = { "{}" },
            profileUpdater = { payload, _ -> importedPayloads += payload; 1 },
            profileCleaner = { cleaned = true },
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("24 часа"))
        assertEquals(1, importedPayloads.size)
        assertTrue(WiseTreeAccess.EMBEDDED_UUID in importedPayloads.first())
        assertTrue(stopped)
        assertTrue(cleaned)
    }

    @Test
    fun claimWithDeps_startsEmbeddedTunnelWhenDirectBlockedAndUpgradesOn200() = runBlocking {
        var tunnelUp = false
        val importedPayloads = mutableListOf<String>()
        val exp = (System.currentTimeMillis() / 1000L) + 900L
        val result = YokhanBootstrapAccess.claimWithDeps(
            code = "tree-secret",
            codeVerifier = { it == "tree-secret" },
            deviceId = "android-test-3",
            ensureEmbeddedTunnelConnected = { tunnelUp = true; true },
            stopEmbeddedTunnel = { tunnelUp = false },
            claimRequester = { _, _, _ ->
                if (!tunnelUp) error("blocked without VPN")
                200 to """{"subscription_url":"https://cloak.yokhan.xyz/gates/api/bootstrap/sub/tok99","expires_at":$exp}"""
            },
            subFetcher = { """{"outbounds":[{"type":"vless","tag":"xhttp","uuid":"per-device-uuid"}]}""" },
            profileUpdater = { payload, _ -> importedPayloads += payload; 1 },
            profileCleaner = {},
        )
        assertTrue(result.isSuccess)
        assertEquals(2, importedPayloads.size)
        assertTrue(WiseTreeAccess.EMBEDDED_UUID in importedPayloads[0])
        assertTrue("per-device-uuid" in importedPayloads[1])
    }

    @Test
    fun claimWithDeps_cancellationDuringClaimCleansUpEmbeddedTunnelAndSeedProfile() = runBlocking {
        var tunnelUp = false
        var stopped = false
        var cleaned = false
        var cancelledCaught = false
        try {
            YokhanBootstrapAccess.claimWithDeps(
                code = "tree-secret",
                codeVerifier = { it == "tree-secret" },
                deviceId = "android-test-cancel",
                ensureEmbeddedTunnelConnected = { tunnelUp = true; true },
                stopEmbeddedTunnel = { tunnelUp = false; stopped = true },
                claimRequester = { _, _, _ ->
                    if (!tunnelUp) error("blocked without VPN")
                    throw CancellationException("user cancelled")
                },
                subFetcher = { "{}" },
                profileUpdater = { _, _ -> 1 },
                profileCleaner = { cleaned = true },
            )
        } catch (_: CancellationException) {
            cancelledCaught = true
        }
        assertTrue(cancelledCaught)
        assertFalse(tunnelUp)
        assertTrue(stopped)
        assertTrue(cleaned)
    }
}
