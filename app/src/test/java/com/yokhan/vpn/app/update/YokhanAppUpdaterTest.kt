package com.yokhan.gates.app.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YokhanAppUpdaterTest {

    private val apiBase = "https://app.yokhan.xyz/gates"
    private val canonicalDownload = "https://fitness.yokhan.xyz/download"
    private val legacyHub = "https://app.yokhan.xyz/hub/gates"
    private val legacyCloak = "https://cloak.yokhanfitness.ru"

    @Test
    fun acceptsOnlyExpectedPackageAndOwnedSigner() {
        assertTrue(
            isTrustedApkIdentity(
                expectedPackage = "com.yokhan.gates",
                actualPackage = "com.yokhan.gates",
                expectedSignerSha256 = OWNED_SIGNER,
                actualSignerSha256 = listOf(OWNED_SIGNER.uppercase()),
            ),
        )
    }

    @Test
    fun rejectsLegacyPackageEvenWithOwnedSigner() {
        assertFalse(
            isTrustedApkIdentity(
                expectedPackage = "com.yokhan.gates",
                actualPackage = "com.buffit.gates",
                expectedSignerSha256 = OWNED_SIGNER,
                actualSignerSha256 = listOf(OWNED_SIGNER),
            ),
        )
    }

    @Test
    fun rejectsUnknownSigner() {
        assertFalse(
            isTrustedApkIdentity(
                expectedPackage = "com.yokhan.gates",
                actualPackage = "com.yokhan.gates",
                expectedSignerSha256 = OWNED_SIGNER,
                actualSignerSha256 = listOf("00".repeat(32)),
            ),
        )
    }

    @Test
    fun releaseApiUrlIncludesChannelVersionAndPackage() {
        val url = buildReleaseApiUrl(apiBase, "stable", "1.7.9", "com.yokhan.gates")
        assertTrue(url.contains("platform=android"), url)
        assertTrue(url.contains("channel=stable"), url)
        assertTrue(url.contains("version=1.7.9"), url)
        assertTrue(url.contains("package_id=com.yokhan.gates"), url)
        assertTrue(url.contains("protocol=2"), url)
    }

    @Test
    fun releaseApiUrlDefaultsToStableChannelWhenBlank() {
        val url = buildReleaseApiUrl(apiBase, "   ", "1.7.9", "com.yokhan.gates")
        assertTrue(url.contains("channel=stable"), url)
    }

    @Test
    fun releaseApiUrlFailsClearlyWhenBaseBlank() {
        assertFailsWith<IllegalArgumentException> {
            buildReleaseApiUrl("   ", "stable", "1.7.9", "com.yokhan.gates")
        }
    }

    @Test
    fun releaseApiUrlNeverUsesLegacyHubOrCloakBase() {
        val url = buildReleaseApiUrl(apiBase, "stable", "1.7.9", "com.yokhan.gates")
        assertFalse(url.contains("hub/gates"), url)
        assertFalse(url.contains(legacyHub), url)
        assertFalse(url.contains(legacyCloak), url)
    }

    @Test
    fun downloadResolutionFallsBackToCanonicalUrlNeverHub() {
        val resolved = resolveReleaseDownloadUrl(null, canonicalDownload)
        assertEquals(canonicalDownload, resolved)
        assertFalse(resolved.contains("hub/gates"), resolved)
    }

    @Test
    fun downloadResolutionPrefersApiProvidedReleaseUrl() {
        val apiUrl = "https://fitness.yokhan.xyz/download/1.7.9.apk"
        assertEquals(apiUrl, resolveReleaseDownloadUrl(apiUrl, canonicalDownload))
    }

    private companion object {
        const val OWNED_SIGNER =
            "b174e202d24f73eb703265f3270c2784eba0ae80d6a1ad0979ad9b87f7d4a504"
    }
}
