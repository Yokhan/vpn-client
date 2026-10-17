package com.yokhan.gates.windows

import java.io.File
import java.net.URI

private val WINDOWS_UPDATE_SIGNER_PATTERN = Regex("[0-9A-F]{40}")

internal fun deliverWindowsUpdate(
    expectedSigner: String?,
    downloadPageUrl: String,
    releaseUrl: String,
    assetName: String,
    assetUrl: String,
    openPage: (String) -> Boolean,
    downloadAsset: (String, String) -> File,
    verifySignature: (File) -> RuntimeCommandResult,
    openInstaller: (File) -> Boolean,
    latestVersion: String,
): RuntimeCommandResult {
    if (expectedSigner.isNullOrBlank()) {
        if (!isSecureWindowsDownloadPage(downloadPageUrl)) {
            return RuntimeCommandResult(1, "The configured Windows download page must use HTTPS; no installer was downloaded or opened.")
        }
        val opened = openPage(downloadPageUrl)
        return RuntimeCommandResult(
            0,
            "No trusted Windows installer signer is configured. No installer was downloaded or opened. " +
                if (opened) "Download and install it manually from: $downloadPageUrl"
                else "Open the configured download page and install manually: $downloadPageUrl",
        )
    }

    val signer = expectedSigner.replace(" ", "").uppercase()
    if (!WINDOWS_UPDATE_SIGNER_PATTERN.matches(signer)) {
        return RuntimeCommandResult(1, "Configured Windows installer signer is invalid; no installer was downloaded or opened.")
    }

    if (assetName.isBlank() || assetUrl.isBlank()) {
        if (!isSecureWindowsDownloadPage(releaseUrl)) {
            return RuntimeCommandResult(1, "The release page must use HTTPS; no installer was downloaded or opened.")
        }
        val opened = openPage(releaseUrl)
        return RuntimeCommandResult(
            0,
            if (opened) "Update found, but no Windows asset was selected. Opened download page: $releaseUrl"
            else "Update found, but no Windows asset was selected. Open the download page: $releaseUrl",
        )
    }

    val extension = assetName.substringAfterLast('.', "").lowercase()
    if (extension !in setOf("exe", "msi")) {
        return RuntimeCommandResult(1, "Update asset must be an EXE or MSI installer.")
    }
    val uri = runCatching { URI.create(assetUrl) }.getOrNull()
    if (uri?.scheme?.equals("https", ignoreCase = true) != true || uri.host.isNullOrBlank()) {
        return RuntimeCommandResult(1, "Update asset URL must use HTTPS.")
    }

    val target = downloadAsset(assetName, assetUrl)
    val verified = verifySignature(target)
    if (verified.exitCode != 0) {
        target.delete()
        return verified
    }
    val opened = openInstaller(target)
    return RuntimeCommandResult(
        0,
        buildString {
            appendLine("Downloaded update: ${target.absolutePath}")
            appendLine("Latest version: ${latestVersion.ifBlank { "unknown" }}")
            append(if (opened) "Installer opened." else "Open the downloaded file manually.")
        }.trim(),
    )
}

private fun isSecureWindowsDownloadPage(url: String): Boolean {
    val uri = runCatching { URI.create(url) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
}
