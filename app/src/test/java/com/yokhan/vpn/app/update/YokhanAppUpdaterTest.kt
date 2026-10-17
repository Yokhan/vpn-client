package com.yokhan.gates.app.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YokhanAppUpdaterTest {
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

    private companion object {
        const val OWNED_SIGNER =
            "b174e202d24f73eb703265f3270c2784eba0ae80d6a1ad0979ad9b87f7d4a504"
    }
}
