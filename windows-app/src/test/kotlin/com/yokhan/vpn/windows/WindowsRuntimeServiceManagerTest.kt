package com.yokhan.gates.windows

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsRuntimeServiceManagerTest {

    @Test
    fun servicePresenceDistinguishesMissingFromUnknownScmFailures() {
        fun presenceFor(exitCode: Int): Boolean? = WindowsRuntimeServiceManager(
            processRunner = { args, _, _ ->
                assertEquals(listOf("sc.exe", "query", "GatesVpn"), args)
                RuntimeCommandResult(exitCode, "mocked SCM result")
            },
        ).presence()

        assertEquals(true, presenceFor(0))
        assertEquals(false, presenceFor(1060))
        assertEquals(null, presenceFor(5))
        assertEquals(
            null,
            WindowsRuntimeServiceManager(processRunner = { _, _, _ -> error("SCM unavailable") }).presence(),
        )
    }

    @Test
    fun installReconcilesMatchingServiceWithoutUninstallingOrRotatingActiveToken() {
        val script = buildInstallScript(
            WindowsRuntimeServiceAssets(
                wrapper = File("C:/Program Files/GATES/app/resources/gates-service/Gates.Service.exe"),
                jar = File("C:/Program Files/GATES/app/resources/gates-service/Gates.Service.jar"),
                configuration = File("C:/Program Files/GATES/app/resources/gates-service/Gates.Service.xml"),
            ),
            "S-1-5-21-1-2-3-1001",
        )

        assertTrue(script.indexOf("Invoke-GatesNative 'sc.exe'") < script.indexOf("WriteAllText"))
        assertTrue(script.contains("service.PathName -notmatch"))
        assertTrue(script.contains("service.State -eq 'Running'"))
        assertTrue(script.contains("GATES_TOKEN_STATE=preserved"))
        assertTrue(script.contains("if (-not \$serviceInstalled)"))
        assertTrue(script.contains("function Invoke-GatesNative"))
        assertTrue(script.contains("PSNativeCommandUseErrorActionPreference = \$false"))
        assertTrue(script.contains("ErrorActionPreference = 'Continue'"))
        assertTrue(script.contains("ErrorActionPreference = \$previousErrorActionPreference"))
        assertTrue(script.contains("finally {"))
        assertTrue(script.contains("nativeExitCode = \$LASTEXITCODE"))
        assertTrue(script.contains("'start_service' { 68; break }"))
        assertTrue(script.contains("taskQueryExit = Invoke-GatesNative 'schtasks.exe'"))
        assertFalse(Regex("(?im)^\\s*(?:&|Invoke-GatesNative).*\\b(?:stop|uninstall)\\b").containsMatchIn(script))
        assertTrue(script.contains("exit \$failureCode"))
    }

    @Test
    fun preparationDefersPersistedTunnelBeforeStartingService() {
        val script = buildInstallScript(
            WindowsRuntimeServiceAssets(File("wrapper.exe"), File("service.jar"), File("service.xml")),
            "S-1-5-21-1-2-3-1001",
            allowSessionRestore = false,
        )
        val guard = script.indexOf("if (-not \$false -and")
        val start = script.indexOf("\$stage = 'start_service'")
        assertTrue(guard >= 0 && guard < start)
        assertTrue(script.contains("Join-Path \$serviceDir 'active.json'"))
        assertTrue(script.contains("exit 70"))
        val deferred = normalizeElevatedSetupResult(RuntimeCommandResult(70, "private output"))
        assertEquals(0, deferred.exitCode)
        assertEquals(RUNTIME_ACTIVATION_DEFERRED_MESSAGE, deferred.output)
    }

    @Test
    fun elevatedWrapperReportsUacCancellationWithoutForwardingExceptionText() {
        val wrapper = buildElevatedPowerShellWrapper("ZmFrZQ==")
        val result = normalizeElevatedSetupResult(
            RuntimeCommandResult(
                1223,
                "",
            ),
        )

        assertTrue(wrapper.contains("NativeErrorCode -eq 1223"))
        assertTrue(wrapper.contains("exit 1223"))
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("Administrator approval was canceled"))
    }

    @Test
    fun setupFailureIsReportedBySanitizedStage() {
        val result = normalizeElevatedSetupResult(
            RuntimeCommandResult(68, "private-token-value"),
        )

        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("start_service"))
        assertTrue(result.output.contains("could not be started"))
        assertFalse(result.output.contains("token"))
    }
}
