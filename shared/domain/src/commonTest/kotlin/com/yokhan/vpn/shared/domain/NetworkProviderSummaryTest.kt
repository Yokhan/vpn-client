package com.yokhan.vpn.shared.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkProviderSummaryTest {
    @Test
    fun allowsCurrentMonolithProvider() {
        val summary = NetworkProviderSummary(
            state = NetworkProviderState.CurrentMonolith,
            packageName = "com.example.shell",
            versionName = "1.0",
            versionCode = 1,
            certificateSha256 = null,
        )

        assertTrue(summary.canUseProvider)
    }

    @Test
    fun allowsTrustedExternalProvider() {
        val summary = NetworkProviderSummary(
            state = NetworkProviderState.ExternalReady,
            packageName = "com.example.provider",
            versionName = "1.0",
            versionCode = 1,
            certificateSha256 = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
        )

        assertTrue(summary.canUseProvider)
    }

    @Test
    fun blocksMissingProvider() {
        assertFalse(emptySummary(NetworkProviderState.Missing).canUseProvider)
    }

    @Test
    fun blocksUntrustedProvider() {
        assertFalse(emptySummary(NetworkProviderState.Untrusted).canUseProvider)
    }

    @Test
    fun blocksOutdatedProvider() {
        assertFalse(emptySummary(NetworkProviderState.Outdated).canUseProvider)
    }

    private fun emptySummary(state: NetworkProviderState): NetworkProviderSummary =
        NetworkProviderSummary(
            state = state,
            packageName = null,
            versionName = null,
            versionCode = null,
            certificateSha256 = null,
        )
}
