package com.yokhan.gates.windows.service

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class XrayProcessSidecarTest {

    @Test
    fun startsWritesAndRemovesItsOwnConfig() {
        withDirectory { directory ->
            val paths = paths(directory)
            writeFakeExecutable(directory)
            val handle = FakeHandle()
            val sidecar = XrayProcessSidecar(
                paths,
                processFactory = { _, configFile, _ ->
                    assertTrue(Files.isRegularFile(configFile))
                    handle
                },
                portIsFree = { true },
                readinessProbe = { true },
            )
            val spec = spec(sha256 = executableHash(directory))

            assertIs<SidecarStartResult.Started>(sidecar.start(spec))
            assertEquals(XhttpSidecarState.READY, sidecar.state())
            assertTrue(sidecar.isHealthy())
            assertTrue(Files.isRegularFile(paths.xhttpDirectory.resolve("active.json")))
            assertIs<SidecarStartResult.AlreadyRunning>(sidecar.start(spec))

            sidecar.stop()
            assertEquals(XhttpSidecarState.STOPPED, sidecar.state())
            assertFalse(Files.exists(paths.xhttpDirectory.resolve("active.json")))
            assertEquals(1, handle.stopCalls)
        }
    }

    @Test
    fun rejectsAnUnpinnedExecutableBeforeStartingAnything() {
        withDirectory { directory ->
            writeFakeExecutable(directory)
            var starts = 0
            val sidecar = XrayProcessSidecar(
                paths(directory),
                processFactory = { _, _, _ ->
                    starts += 1
                    FakeHandle()
                },
                portIsFree = { true },
                readinessProbe = { true },
            )

            assertEquals(SidecarFailure.HASH_MISMATCH, sidecar.preflight(spec(sha256 = "b".repeat(64))))
            val result = sidecar.start(spec(sha256 = "b".repeat(64)))
            assertIs<SidecarStartResult.Failed>(result)
            assertEquals(SidecarFailure.HASH_MISMATCH, result.reason)
            assertEquals(0, starts)
        }
    }

    @Test
    fun refusesToShareItsLoopbackPort() {
        withDirectory { directory ->
            writeFakeExecutable(directory)
            var starts = 0
            val sidecar = XrayProcessSidecar(
                paths(directory),
                processFactory = { _, _, _ ->
                    starts += 1
                    FakeHandle()
                },
                portIsFree = { false },
                readinessProbe = { true },
            )

            val result = sidecar.start(spec(sha256 = executableHash(directory)))
            assertIs<SidecarStartResult.Failed>(result)
            assertEquals(SidecarFailure.PORT_IN_USE, result.reason)
            assertEquals(0, starts)
        }
    }

    @Test
    fun stopsAChildThatNeverBecomesReady() {
        withDirectory { directory ->
            writeFakeExecutable(directory)
            val handle = FakeHandle()
            val sidecar = XrayProcessSidecar(
                paths(directory),
                processFactory = { _, _, _ -> handle },
                portIsFree = { true },
                readinessProbe = { false },
                readinessTimeoutMillis = 50,
            )

            val result = sidecar.start(spec(sha256 = executableHash(directory)))
            assertIs<SidecarStartResult.Failed>(result)
            assertEquals(SidecarFailure.NOT_READY, result.reason)
            assertFalse(handle.isAlive)
        }
    }

    private fun spec(sha256: String): XhttpSidecarSpec = XhttpSidecarSpec(
        outboundTag = "xhttp",
        executableName = "xray.exe",
        executableSha256 = sha256,
        listenPort = 2090,
        required = true,
        xrayConfigJson = """{"inbounds":[],"outbounds":[]}""",
    )

    private fun paths(directory: Path): WindowsServicePaths {
        val data = directory.resolve("data")
        return WindowsServicePaths(
            launcherExecutable = directory.resolve("GATES.exe"),
            runtimeExecutable = directory.resolve("sing-box.exe"),
            dataDirectory = data,
            configFile = data.resolve("active.json"),
            logFile = data.resolve("service.log"),
            controlTokenFile = data.resolve("control.token"),
        )
    }

    private fun writeFakeExecutable(directory: Path) {
        Files.writeString(directory.resolve("xray.exe"), "synthetic-xray-binary")
    }

    private fun executableHash(directory: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(directory.resolve("xray.exe")))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun withDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("gates-xhttp-sidecar-")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}

private class FakeHandle : SidecarProcessHandle {
    @Volatile var stopCalls = 0

    override val isAlive: Boolean
        get() = stopCalls == 0

    override fun stop(timeoutMillis: Long) {
        stopCalls += 1
    }
}
