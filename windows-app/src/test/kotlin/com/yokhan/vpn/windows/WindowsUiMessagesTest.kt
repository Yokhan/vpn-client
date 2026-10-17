package com.yokhan.gates.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import com.yokhan.gates.shared.domain.ConnectionStatus

class WindowsUiMessagesTest {
    @Test fun errorsDoNotExposeRawTransportOrAccountOutput() {
        val message = windowsUiResultMessage("login", RuntimeCommandResult(1, "token=private; IOException C:\\user"))
        assertEquals("Не удалось получить доступ.", message)
        assertFalse(message.contains("private"))
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
