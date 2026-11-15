package com.yokhan.vpn.app.routing

/**
 * Device/build capability gate for transports that the sing-box core cannot execute on its own.
 *
 * The gate is intentionally pure: callers inject the build flag, the primary ABI and the state of
 * the sidecar binary. That keeps it JVM-testable and keeps Android globals
 * (`Build.SUPPORTED_ABIS`, `nativeLibraryDir`, `BuildConfig`) in one small platform adapter
 * (`io.nekohasekai.sagernet.bg.proto.XraySidecarPlatform`).
 *
 * Rule: a route may only be published to the user when its capability is [YokhanTransportAvailability.Available].
 * "Unsupported" must be visible, never silently imported as a different protocol.
 */
enum class YokhanTransportAvailability {
    Available,
    /** Sidecar support is compiled out; the default for every build until a device proof exists. */
    DisabledInBuild,
    /** No official Xray binary exists for this ABI (official assets: arm64-v8a, x86_64). */
    UnsupportedAbi,
    BinaryMissing,
    BinaryNotExecutable,
    BinaryHashMismatch,
    /**
     * A binary shipped only as an APK asset cannot be executed: Android 10+ (targetSdk >= 29)
     * refuses exec from app data directories. It must be packaged as a `jniLibs` entry instead.
     */
    AssetOnlyUnsupported,
}

data class YokhanTransportCapability(
    val transport: String,
    val availability: YokhanTransportAvailability,
    /** Short redacted explanation for diagnostics; never contains credentials or endpoints. */
    val summary: String,
) {
    val available: Boolean get() = availability == YokhanTransportAvailability.Available
}

object YokhanTransportCapabilities {

    const val TRANSPORT_XHTTP = "xhttp"

    /** ABIs for which XTLS publishes an official Android Xray binary. */
    val XHTTP_SUPPORTED_ABIS: Set<String> = setOf("arm64-v8a", "x86_64")

    fun xhttp(
        primaryAbi: String,
        enabledInBuild: Boolean,
        binaryState: XraySidecarBinaryState,
    ): YokhanTransportCapability = when {
        !enabledInBuild -> YokhanTransportCapability(
            TRANSPORT_XHTTP,
            YokhanTransportAvailability.DisabledInBuild,
            "xhttp sidecar is not enabled in this build",
        )

        primaryAbi !in XHTTP_SUPPORTED_ABIS -> YokhanTransportCapability(
            TRANSPORT_XHTTP,
            YokhanTransportAvailability.UnsupportedAbi,
            "no official Xray binary for this ABI",
        )

        else -> when (binaryState) {
            XraySidecarBinaryState.Executable -> YokhanTransportCapability(
                TRANSPORT_XHTTP,
                YokhanTransportAvailability.Available,
                "xhttp sidecar available",
            )

            XraySidecarBinaryState.Missing -> YokhanTransportCapability(
                TRANSPORT_XHTTP,
                YokhanTransportAvailability.BinaryMissing,
                "xhttp sidecar binary is missing",
            )

            XraySidecarBinaryState.NotExecutable -> YokhanTransportCapability(
                TRANSPORT_XHTTP,
                YokhanTransportAvailability.BinaryNotExecutable,
                "xhttp sidecar binary is not executable",
            )

            XraySidecarBinaryState.HashMismatch -> YokhanTransportCapability(
                TRANSPORT_XHTTP,
                YokhanTransportAvailability.BinaryHashMismatch,
                "xhttp sidecar binary does not match the pinned hash",
            )

            XraySidecarBinaryState.AssetOnly -> YokhanTransportCapability(
                TRANSPORT_XHTTP,
                YokhanTransportAvailability.AssetOnlyUnsupported,
                "xhttp sidecar is packaged as an asset and cannot be executed",
            )
        }
    }
}

/** Binary state as observed by the platform layer; part of the pure gate contract. */
enum class XraySidecarBinaryState {
    Executable,
    Missing,
    NotExecutable,
    HashMismatch,
    AssetOnly,
}
