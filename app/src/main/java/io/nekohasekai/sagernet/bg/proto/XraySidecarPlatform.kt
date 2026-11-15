package io.nekohasekai.sagernet.bg.proto

import android.os.Build
import com.yokhan.vpn.app.routing.XraySidecarBinaryState
import com.yokhan.vpn.app.routing.YokhanTransportCapability
import com.yokhan.vpn.app.routing.YokhanTransportCapabilities
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.plugin.XrayExecutableLocator
import io.nekohasekai.sagernet.plugin.XrayExecutableResolution
import java.io.File

/**
 * Single Android-facing seam for the Xray sidecar. Nothing else in the app should read
 * `BuildConfig`, `Build.SUPPORTED_ABIS` or `nativeLibraryDir` to decide whether XHTTP is possible.
 *
 * The build enables XHTTP only when the shipped `jniLibs/<abi>/libxray.so` binaries match the
 * pinned SHA-256 digests enforced here and verified by Gradle before packaging.
 */
object XraySidecarPlatform {

    internal val PINNED_SHA256_BY_ABI: Map<String, String> = mapOf(
        "arm64-v8a" to "ce8f18c44eb40cbd4563433109e0ede36c38b89c2fe71d1e583b4332ebefb622",
        "x86_64" to "894651c1de4e2b9cb51775c13ed168e8c8148be7220d2b5db15a42362c5b3263",
    )

    fun pinnedSha256ForAbi(
        primaryAbi: String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
    ): String? = PINNED_SHA256_BY_ABI[primaryAbi]

    fun nativeLibraryDir(): File = File(SagerNet.application.applicationInfo.nativeLibraryDir)

    fun resolveExecutable(
        expectedSha256: String? = pinnedSha256ForAbi(),
    ): XrayExecutableResolution =
        XrayExecutableLocator.resolve(nativeLibraryDir(), expectedSha256)

    fun binaryState(): XraySidecarBinaryState = when (val resolution = resolveExecutable()) {
        is XrayExecutableResolution.Available -> XraySidecarBinaryState.Executable
        is XrayExecutableResolution.Unavailable -> resolution.state
    }

    fun capability(): YokhanTransportCapability = YokhanTransportCapabilities.xhttp(
        primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        enabledInBuild = BuildConfig.YOKHAN_XHTTP_SIDECAR_ENABLED,
        binaryState = binaryState(),
    )

    /**
     * Returns a supervisor only when the capability is genuinely available. Callers should treat
     * `null` as "route unsupported", not as "try anyway".
     */
    fun createSupervisor(
        logger: (String) -> Unit,
        expectedSha256: String? = pinnedSha256ForAbi(),
    ): XraySidecarSupervisor? {
        if (!capability().available) return null
        val binary = when (val resolution = resolveExecutable(expectedSha256)) {
            is XrayExecutableResolution.Available -> resolution.file
            is XrayExecutableResolution.Unavailable -> return null
        }
        return XraySidecarSupervisor(
            executable = binary,
            engineVersion = BuildConfig.YOKHAN_XRAY_VERSION,
            binarySha256 = expectedSha256,
            logger = logger,
        )
    }
}
