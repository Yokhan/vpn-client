package io.nekohasekai.sagernet.plugin

import com.yokhan.vpn.app.routing.XraySidecarBinaryState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class XrayExecutableLocatorTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { dir -> dir.walkBottomUp().forEach { it.delete() } }
    }

    private fun tempDir(): File = Files.createTempDirectory("xray-locator-test").toFile()
        .also { tempDirs += it }

    @Test
    fun missingBinaryIsReportedAsMissing() {
        val resolution = XrayExecutableLocator.resolve(tempDir())

        val unavailable = assertIs<XrayExecutableResolution.Unavailable>(resolution)
        assertEquals(XraySidecarBinaryState.Missing, unavailable.state)
    }

    @Test
    fun aDirectoryCannotBeExecutedAsTheSidecar() {
        val dir = tempDir()
        File(dir, XrayExecutableLocator.BINARY_NAME).mkdirs()

        val resolution = XrayExecutableLocator.resolve(dir)

        val unavailable = assertIs<XrayExecutableResolution.Unavailable>(resolution)
        assertEquals(XraySidecarBinaryState.NotExecutable, unavailable.state)
    }

    @Test
    fun pinnedHashMismatchFailsClosed() {
        val dir = tempDir()
        File(dir, XrayExecutableLocator.BINARY_NAME).writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))

        val resolution = XrayExecutableLocator.resolve(dir, expectedSha256 = "00".repeat(32))

        val unavailable = assertIs<XrayExecutableResolution.Unavailable>(resolution)
        assertEquals(XraySidecarBinaryState.HashMismatch, unavailable.state)
    }

    @Test
    fun matchingPinnedHashIsVerified() {
        val dir = tempDir()
        val binary = File(dir, XrayExecutableLocator.BINARY_NAME)
        binary.writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
        val digest = XrayExecutableLocator.sha256(binary)

        val resolution = XrayExecutableLocator.resolve(dir, expectedSha256 = digest)

        if (binary.canExecute()) {
            val available = assertIs<XrayExecutableResolution.Available>(resolution)
            assertTrue(available.hashVerified)
            assertEquals(binary.absolutePath, available.file.absolutePath)
        } else {
            val unavailable = assertIs<XrayExecutableResolution.Unavailable>(resolution)
            assertEquals(XraySidecarBinaryState.NotExecutable, unavailable.state)
        }
    }

    @Test
    fun assetOnlyPackagingIsRejectedWithAnActionableReason() {
        val resolution = XrayExecutableLocator.assetOnly("xray/libxray.so")

        val unavailable = assertIs<XrayExecutableResolution.Unavailable>(resolution)
        assertEquals(XraySidecarBinaryState.AssetOnly, unavailable.state)
        assertTrue(unavailable.detail.contains("jniLibs"))
    }

    @Test
    fun pinnedDigestsExistForAllSupportedAndroidAbis() {
        val platformClass = io.nekohasekai.sagernet.bg.proto.XraySidecarPlatform
        assertEquals(
            "ce8f18c44eb40cbd4563433109e0ede36c38b89c2fe71d1e583b4332ebefb622",
            platformClass.pinnedSha256ForAbi("arm64-v8a"),
        )
        assertEquals(
            "894651c1de4e2b9cb51775c13ed168e8c8148be7220d2b5db15a42362c5b3263",
            platformClass.pinnedSha256ForAbi("x86_64"),
        )
        assertEquals(null, platformClass.pinnedSha256ForAbi("armeabi-v7a"))
    }
}
