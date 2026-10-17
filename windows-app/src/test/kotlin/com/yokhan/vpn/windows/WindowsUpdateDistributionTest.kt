package com.yokhan.gates.windows

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsUpdateDistributionTest {
    @Test
    fun missingSignerOpensCanonicalPageWithoutDownloadingOrOpeningInstaller() {
        var pageOpens = 0
        var downloads = 0
        var verificationCalls = 0
        var installerOpens = 0

        val result = deliverWindowsUpdate(
            expectedSigner = null,
            downloadPageUrl = DOWNLOAD_PAGE,
            releaseUrl = "https://releases.example.test/latest",
            assetName = "GATES-1.7.0.msi",
            assetUrl = "https://releases.example.test/GATES-1.7.0.msi",
            openPage = { url ->
                assertEquals(DOWNLOAD_PAGE, url)
                pageOpens++
                true
            },
            downloadAsset = { _, _ -> downloads++; error("unsigned path must not download") },
            verifySignature = { verificationCalls++; error("unsigned path must not verify a downloaded file") },
            openInstaller = { installerOpens++; error("unsigned path must not open an installer") },
            latestVersion = "1.7.0",
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("No installer was downloaded or opened"))
        assertEquals(1, pageOpens)
        assertEquals(0, downloads)
        assertEquals(0, verificationCalls)
        assertEquals(0, installerOpens)
    }

    @Test
    fun invalidConfiguredSignerDoesNotDowngradeToManualPage() {
        var pageOpens = 0
        var downloads = 0

        val result = deliverWindowsUpdate(
            expectedSigner = "not-a-thumbprint",
            downloadPageUrl = DOWNLOAD_PAGE,
            releaseUrl = "https://releases.example.test/latest",
            assetName = "GATES-1.7.0.msi",
            assetUrl = "https://releases.example.test/GATES-1.7.0.msi",
            openPage = { pageOpens++; true },
            downloadAsset = { _, _ -> downloads++; error("invalid signer must not download") },
            verifySignature = { error("invalid signer must not verify") },
            openInstaller = { error("invalid signer must not open installer") },
            latestVersion = "1.7.0",
        )

        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("signer is invalid"))
        assertEquals(0, pageOpens)
        assertEquals(0, downloads)
    }

    @Test
    fun signatureMismatchDeletesDownloadAndDoesNotDowngradeToManualPage() {
        val target = Files.createTempFile("gates-update-test", ".msi").toFile()
        var pageOpens = 0
        var installerOpens = 0

        val result = deliverWindowsUpdate(
            expectedSigner = "A".repeat(40),
            downloadPageUrl = DOWNLOAD_PAGE,
            releaseUrl = "https://releases.example.test/latest",
            assetName = target.name,
            assetUrl = "https://releases.example.test/${target.name}",
            openPage = { pageOpens++; true },
            downloadAsset = { _, _ -> target },
            verifySignature = { RuntimeCommandResult(1, "signature mismatch") },
            openInstaller = { installerOpens++; true },
            latestVersion = "1.7.0",
        )

        assertEquals(1, result.exitCode)
        assertFalse(target.exists())
        assertEquals(0, pageOpens)
        assertEquals(0, installerOpens)
    }

    @Test
    fun configuredSignerDoesNotOpenAnUnsafeFallbackPage() {
        val result = deliverWindowsUpdate(
            expectedSigner = "A".repeat(40),
            downloadPageUrl = DOWNLOAD_PAGE,
            releaseUrl = "javascript:alert(1)",
            assetName = "",
            assetUrl = "",
            openPage = { error("unsafe URL must not open") },
            downloadAsset = { _, _ -> error("no asset") },
            verifySignature = { error("no asset") },
            openInstaller = { error("no asset") },
            latestVersion = "1.7.0",
        )
        assertEquals(1, result.exitCode)
    }

    companion object {
        private const val DOWNLOAD_PAGE = "https://app.yokhan.xyz/hub/gates"
    }
}
