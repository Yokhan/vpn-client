package io.nekohasekai.sagernet.plugin

import com.yokhan.vpn.app.routing.XraySidecarBinaryState
import java.io.File
import java.security.MessageDigest

sealed interface XrayExecutableResolution {
    data class Available(
        val file: File,
        /** True only when [expectedSha256] was supplied and matched. */
        val hashVerified: Boolean,
    ) : XrayExecutableResolution

    data class Unavailable(
        val state: XraySidecarBinaryState,
        /** Redacted reason: names and states only, never binary contents. */
        val detail: String,
    ) : XrayExecutableResolution
}

/**
 * Resolves the Xray child binary the same way `PluginManager` resolves `libnaive.so`.
 *
 * Supported form: `nativeLibraryDir/libxray.so` (extracted by `jniLibs.useLegacyPackaging`, so
 * `canExecute()` is true). An APK-asset copy is explicitly unsupported: with `targetSdk >= 29`
 * Android refuses to execute files from app data directories, so assets-based execution would fail
 * at runtime. The binary must be packaged as a `jniLibs` entry instead.
 *
 * Hash verification is optional here because the Android pin does not exist yet (see the spike
 * report §4.3). Release builds must pass the pinned SHA-256; a mismatch is a hard failure.
 */
object XrayExecutableLocator {

    const val BINARY_NAME = "libxray.so"

    fun resolve(
        nativeLibraryDir: File,
        expectedSha256: String? = null,
    ): XrayExecutableResolution {
        val file = File(nativeLibraryDir, BINARY_NAME)
        if (!file.exists()) {
            return XrayExecutableResolution.Unavailable(
                XraySidecarBinaryState.Missing,
                "libxray.so is not packaged in this build",
            )
        }
        if (!file.isFile || !file.canExecute()) {
            return XrayExecutableResolution.Unavailable(
                XraySidecarBinaryState.NotExecutable,
                "libxray.so exists but is not executable",
            )
        }
        if (expectedSha256 != null) {
            val actual = runCatching { sha256(file) }.getOrNull()
            if (actual == null || !actual.equals(expectedSha256, ignoreCase = true)) {
                return XrayExecutableResolution.Unavailable(
                    XraySidecarBinaryState.HashMismatch,
                    "libxray.so does not match the pinned hash",
                )
            }
            return XrayExecutableResolution.Available(file, hashVerified = true)
        }
        return XrayExecutableResolution.Available(file, hashVerified = false)
    }

    /** Asset-only packaging is rejected by design; kept as an explicit, testable outcome. */
    fun assetOnly(assetPath: String): XrayExecutableResolution = XrayExecutableResolution.Unavailable(
        XraySidecarBinaryState.AssetOnly,
        "asset $assetPath cannot be executed on modern Android; package it as jniLibs/$BINARY_NAME",
    )

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
