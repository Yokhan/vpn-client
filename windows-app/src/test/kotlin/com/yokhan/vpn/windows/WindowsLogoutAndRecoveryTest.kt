package com.yokhan.gates.windows

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class WindowsLogoutAndRecoveryTest {
    @Test
    fun `logout clears saved auth state cleanly even in access-only mode`() = runBlocking {
        val tempDir = Files.createTempDirectory("gates-logout-test").toFile()
        try {
            val store = WindowsEncryptedJsonStore(tempDir.resolve("auth-state.json"))
            store.write("""{"issuer":"https://auth.yokhan.xyz/realms/yokhan","clientId":"gates-windows","tokenEndpoint":"https://auth.yokhan.xyz/realms/yokhan/protocol/openid-connect/token","accessToken":"tok","refreshToken":"ref","expiresAtEpochSecond":9999999999}""")

            val controller = windowsAccessAcceptanceController(tempDir)
            assertTrue(controller.sessionState().signedIn)

            val result = controller.run("logout")
            assertEquals(0, result.exitCode)
            assertFalse(controller.sessionState().signedIn)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `bootstrap guidance appears for network blocked login or profile sync`() {
        val startFailed = windowsUiAccessFailureMessage(
            "login",
            WindowsAccessFailure(WindowsAccessStage.AUTHORIZATION, WindowsAccessCode.AUTH_START_FAILED),
        ).orEmpty()
        assertTrue(startFailed.contains("Мудрое Древо"))

        val transportFailed = windowsUiAccessFailureMessage(
            "import-app",
            WindowsAccessFailure(WindowsAccessStage.PROFILE, WindowsAccessCode.TRANSPORT_ERROR),
        ).orEmpty()
        assertTrue(transportFailed.contains("Мудрое Древо"))
    }
}
