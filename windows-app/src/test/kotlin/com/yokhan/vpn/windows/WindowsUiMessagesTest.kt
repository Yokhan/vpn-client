package com.yokhan.gates.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.yokhan.gates.shared.domain.ConnectionStatus

class WindowsUiMessagesTest {
    @Test fun setupAndOfflineRuleFailuresHaveActionableSecretFreeMessages() {
        val cases = listOf(
            "prepare-runtime" to RuntimeCommandResult(1223, "password=private"),
            "prepare-runtime" to RuntimeCommandResult(1, "setup failed token=private"),
            "add-app-direct" to RuntimeCommandResult(1, "Runtime state could not be verified; private"),
            "set-route" to RuntimeCommandResult(1, "Selected route is not available in the imported profile; private"),
        )
        for ((command, result) in cases) {
            val message = windowsUiResultMessage(command, result)
            assertFalse(message.contains("private"))
            assertFalse(message == "Не удалось сохранить изменения.")
        }
    }
    @Test fun errorsDoNotExposeRawTransportOrAccountOutput() {
        val message = windowsUiResultMessage("login", RuntimeCommandResult(1, "token=private; IOException C:\\user"))
        assertEquals("Не удалось получить доступ.", message)
        assertFalse(message.contains("private"))
    }
    @Test fun preparationTimeoutExplainsThatElevatedWorkMayStillBeRunning() {
        val message = windowsUiResultMessage(
            "prepare-runtime",
            RuntimeCommandResult(1, "GATES_SETUP_STAGE=elevated_timeout; private details"),
        )

        assertTrue(message.contains("может ещё выполняться"))
        assertTrue(message.contains("проверьте статус службы"))
        assertFalse(message.contains("private details"))
    }
    @Test fun updateAvailabilityIsNotInstalledSuccess() {
        assertEquals("Доступно обновление.", windowsUiResultMessage("check-update", RuntimeCommandResult(0, "Update available: true")))
        assertEquals("Установлена последняя версия.", windowsUiResultMessage("check-update", RuntimeCommandResult(0, "No update. Current version: 1.7.0")))
        assertEquals("Не удалось проверить обновления.", windowsUiResultMessage("check-update", RuntimeCommandResult(1, "No update")))
    }
    @Test fun backgroundWorkIsNotAConnectionOrConnectionTest() {
        val state = dashboardState(ConnectionStatus.Disconnected, "auto", true)
        assertFalse(state.shouldAnimateSpace)
        assertFalse(state.isConnectionTestRunning)
    }
}
