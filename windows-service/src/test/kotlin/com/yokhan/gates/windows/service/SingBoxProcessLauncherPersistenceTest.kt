package com.yokhan.gates.windows.service

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingBoxProcessLauncherPersistenceTest {
    @Test
    fun preservesLegacyBytesAndRoundTripsGuardMetadataSeparatelyFromCoreJson() {
        val directory = Files.createTempDirectory("gates-service-persist-")
        try {
            val paths = WindowsServicePaths(
                launcherExecutable = directory.resolve("GATES.exe"),
                runtimeExecutable = directory.resolve("sing-box.exe"),
                dataDirectory = directory,
                configFile = directory.resolve("active.json"),
                logFile = directory.resolve("service.log"),
                controlTokenFile = directory.resolve("control.token"),
            )
            val launcher = SingBoxProcessLauncher(paths, serviceProcessExecutablePath = "C:\\Program Files\\GATES\\app\\resources\\gates-service\\java.exe")
            val legacy = ValidatedSingBoxConfig(
                """{"inbounds":[{"type":"tun"}],"outbounds":[{"type":"direct","tag":"default"}]}""",
            )
            launcher.persistConfig(legacy)
            assertEquals(legacy.canonicalJson, Files.readString(paths.configFile))
            assertEquals(legacy, launcher.loadPersistedConfig())

            val guarded = ValidatedSingBoxConfig(
                """{"inbounds":[{"type":"tun","address":["10.0.0.1/30"]}],"outbounds":[{"type":"direct","tag":"default"}]}""",
                SelectedAppProtection(listOf("C:\\Apps\\browser.exe")),
            )
            launcher.persistConfig(guarded)
            val persisted = Files.readString(paths.configFile)
            assertTrue(persisted.contains("gates_protection"))
            assertEquals(guarded, launcher.loadPersistedConfig())

            val runtime = runtimeConfigJson(guarded, "GATES-0123456789abcdef")
            assertFalse(runtime.contains("gates_protection"))
            assertTrue(runtime.contains("GATES-0123456789abcdef"))
            val supervisor = SerializedTunnelSupervisor { object : TunnelChild {
                override fun isAlive() = true
                override fun requestStop() = Unit
                override fun awaitStopped(timeoutMillis: Long) = true
            } }
            supervisor.start(guarded)
            val expectedDigest = MessageDigest.getInstance("SHA-256")
                .digest(guarded.canonicalJson.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertEquals(expectedDigest, supervisor.configurationDigest())
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
