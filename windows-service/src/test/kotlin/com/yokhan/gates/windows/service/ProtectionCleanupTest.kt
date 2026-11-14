package com.yokhan.gates.windows.service

import com.yokhan.gates.windows.service.wfp.InstalledWfpRule
import com.yokhan.gates.windows.service.wfp.WfpAction
import com.yokhan.gates.windows.service.wfp.WfpAleLayer
import com.yokhan.gates.windows.service.wfp.WfpGuardBackend
import com.yokhan.gates.windows.service.wfp.WfpInspection
import com.yokhan.gates.windows.service.wfp.WfpPolicy
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtectionCleanupTest {
    @Test
    fun refusesCleanupWhileServiceIsRunningBeforeOpeningBackend() = withPaths { paths ->
        var backendOpened = false

        assertIs<IOException>(runCatching {
            cleanupSelectedProtection(paths, { true }, { backendOpened = true; FakeBackend() })
        }.exceptionOrNull())

        assertFalse(backendOpened)
    }

    @Test
    fun removesOwnedRulesAndArchivesGuardedConfigWithoutDroppingProfile() = withPaths { paths ->
        val original = guardedConfig.toByteArray()
        Files.write(paths.configFile, original)
        Files.writeString(paths.standingProtectionFile, """{"schema":1,"protected_executables":["C:\\Apps\\browser.exe"]}""")
        val backend = FakeBackend()

        cleanupSelectedProtection(paths, { false }, { backend }, { 1_797_000_000_000L })

        assertTrue(backend.disarmed)
        assertTrue(backend.closed)
        assertEquals(0, backend.inspect().rules.size)
        val archives = Files.list(paths.dataDirectory.resolve("protection-archive"))
        val archived = archives.use { it.findFirst().orElseThrow() }
        assertTrue(original.contentEquals(Files.readAllBytes(archived)))
        assertFalse(Files.exists(paths.configFile))
        assertFalse(Files.exists(paths.standingProtectionFile))
        assertEquals(null, SingBoxProcessLauncher(paths).loadPersistedConfig())
        val archivedJson = Files.readString(archived)
        assertTrue(archivedJson.contains("gates_protection"))
        assertTrue(archivedJson.contains("\"route\""))
        assertTrue(archivedJson.contains("\"profile_marker\":\"preserved\""))
    }

    @Test
    fun verificationFailureKeepsBlocksAndArchivedGuardIntentWithoutActiveConfig() = withPaths { paths ->
        val original = guardedConfig.toByteArray()
        Files.write(paths.configFile, original)
        val backend = FakeBackend().apply { retainRulesAfterDisarm = true }

        assertIs<IOException>(runCatching {
            cleanupSelectedProtection(paths, { false }, { backend })
        }.exceptionOrNull())

        assertFalse(Files.exists(paths.configFile))
        assertEquals(null, SingBoxProcessLauncher(paths).loadPersistedConfig())
        val archived = Files.list(paths.dataDirectory.resolve("protection-archive")).use { it.findFirst().orElseThrow() }
        assertTrue(original.contentEquals(Files.readAllBytes(archived)))
        assertTrue(backend.inspect().rules.any { it.persistent && it.action == WfpAction.BLOCK })
        assertTrue(backend.closed)
    }

    private fun withPaths(block: (WindowsServicePaths) -> Unit) {
        val directory = Files.createTempDirectory("gates-protection-cleanup-")
        try {
            block(
                WindowsServicePaths(
                    launcherExecutable = directory.resolve("GATES.exe"),
                    runtimeExecutable = directory.resolve("sing-box.exe"),
                    dataDirectory = directory,
                    configFile = directory.resolve("active.json"),
                    logFile = directory.resolve("service.log"),
                    controlTokenFile = directory.resolve("control.token"),
                ),
            )
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private class FakeBackend : WfpGuardBackend {
        private var rules = listOf(
            InstalledWfpRule(UUID.randomUUID(), WfpAction.BLOCK, true, WfpAleLayer.AUTH_CONNECT_V4, "selected-app-block"),
        )
        var disarmed = false
        var closed = false
        var retainRulesAfterDisarm = false

        override fun armBlocked(policy: WfpPolicy): WfpInspection = error("not used")
        override fun permitTunnel(verifiedTunLuid: Long) = error("not used")
        override fun block() = error("not used")
        override fun disarm(): WfpInspection {
            disarmed = true
            if (!retainRulesAfterDisarm) rules = emptyList()
            return inspect()
        }
        override fun inspect() = WfpInspection(rules)
        override fun closePermitsOnly() = Unit
        override fun close() { closed = true }
    }

    private companion object {
        const val guardedConfig = """{"inbounds":[{"type":"tun"}],"outbounds":[{"type":"direct","tag":"default"}],"route":{"profile_marker":"preserved"},"gates_protection":{"schema":1,"protected_executables":["C:\\Apps\\browser.exe"]}}"""
    }
}
