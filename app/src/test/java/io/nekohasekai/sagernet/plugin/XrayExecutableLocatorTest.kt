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
}
