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
 * The build flag defaults to `false`; it may only be turned on for a build that actually contains a
 * hash-verified `libxray.so`, after the device loop proof (E3 Stage 1a) has passed.
 */
object XraySidecarPlatform {

    fun nativeLibraryDir(): File = File(SagerNet.application.applicationInfo.nativeLibraryDir)

    fun resolveExecutable(expectedSha256: String? = null): XrayExecutableResolution =
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
        expectedSha256: String? = null,
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
