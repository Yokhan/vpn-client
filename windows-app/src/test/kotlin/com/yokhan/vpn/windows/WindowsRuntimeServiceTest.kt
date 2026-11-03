package com.yokhan.gates.windows

import java.io.File
import com.yokhan.gates.windows.service.ConfigValidationResult
import com.yokhan.gates.windows.service.SingBoxConfigValidator
import com.yokhan.gates.windows.service.SerializedTunnelSupervisor
import com.yokhan.gates.windows.service.TunnelChild
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class WindowsRuntimeServiceTest {

    @Test
    fun parsesVersionedRuntimeServiceResponses() {
        assertEquals(0, parseRuntimeServiceResponse("2|OK|RUNNING").exitCode)
        assertEquals(1, parseRuntimeServiceResponse("2|ERROR|INVALID_FRAME").exitCode)
        assertEquals(1, parseRuntimeServiceResponse("invalid").exitCode)
    }

    @Test
    fun requiresOperationSpecificAcknowledgement() {
        assertEquals(0, requireRuntimeStartAcknowledgement(RuntimeCommandResult(0, "RESTARTED")).exitCode)
        assertEquals(1, requireRuntimeStartAcknowledgement(RuntimeCommandResult(0, "STOPPED")).exitCode)
        assertEquals(1, requireRuntimeStopAcknowledgement(RuntimeCommandResult(0, "STOP_REQUESTED")).exitCode)
        assertEquals(0, requireRuntimeStopAcknowledgement(RuntimeCommandResult(0, "ALREADY_STOPPED")).exitCode)
        assertEquals(1, requireRuntimeStopAcknowledgement(RuntimeCommandResult(0, "UNKNOWN")).exitCode)
    }

    @Test
    fun clientDigestMatchesValidatedServiceConfig() {
        val json = """{ "inbounds": [{"type":"tun"}], "outbounds": [{"type":"direct", "tag":"default"}] }"""
        val accepted = assertIs<ConfigValidationResult.Accepted>(SingBoxConfigValidator.validate(json.toByteArray()))
        val supervisor = SerializedTunnelSupervisor { object : TunnelChild {
            override fun isAlive() = true
            override fun requestStop() = Unit
            override fun awaitStopped(timeoutMillis: Long) = false
        } }
        supervisor.start(accepted.config)
        assertEquals(runtimeConfigurationDigest(json), supervisor.configurationDigest())
    }

    @Test
    fun refusesToInstallServiceFromUserWritableLauncher() {
        val result = WindowsRuntimeServiceManager(
            launcherPathProvider = { "C:\\Users\\user\\AppData\\Local\\GATES\\GATES.exe" },
            programFilesPath = "C:\\Program Files",
        ).install()

        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("Program Files"))
    }

    @Test
    fun resolvesServiceAssetsOnlyUnderProgramFiles() {
        val root = createTempDir(prefix = "gates-program-files")
        val launcher = root.resolve("GATES/GATES.exe").apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(1))
        }

        val assets = resolveWindowsRuntimeServiceAssets(launcher.path, root.path)

        assertEquals("Gates.Service.exe", assets?.wrapper?.name)
        assertTrue(assets?.wrapper?.path?.contains("app${File.separator}resources") == true)
        assertEquals("GATES.exe", assets?.launcher?.name)
    }
}
