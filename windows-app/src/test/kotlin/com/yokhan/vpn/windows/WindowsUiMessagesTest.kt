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
    @Test fun accessFailuresAreActionableAndSecretFree() {
        val unlinked = windowsUiResultMessage(
            "login",
            RuntimeCommandResult(403, "SECRET_DETAIL", accessFailure =
                WindowsAccessFailure(WindowsAccessStage.PROFILE, WindowsAccessCode.ACCESS_NOT_LINKED, 403)),
        )
        assertTrue(unlinked.contains("не привязан"))
        assertTrue(unlinked.contains("запросите доступ", ignoreCase = true))
        assertFalse(unlinked.contains("SECRET_DETAIL"))
        val pending = windowsUiResultMessage(
            "import-app",
            RuntimeCommandResult(403, "SECRET_DETAIL", accessFailure =
                WindowsAccessFailure(WindowsAccessStage.PROFILE, WindowsAccessCode.ACCESS_PENDING, 403)),
        )
        assertTrue(pending.contains("ожидает одобрения"))
        assertFalse(pending.contains("SECRET_DETAIL"))
        val limited = windowsUiResultMessage(
            "login",
            RuntimeCommandResult(429, "SECRET_DETAIL", accessFailure =
                WindowsAccessFailure(WindowsAccessStage.AUTHORIZATION, WindowsAccessCode.RATE_LIMITED, 429)),
        )
        assertTrue(limited.contains("Подождите"))
        assertFalse(limited.contains("SECRET_DETAIL"))
    }
    @Test fun routingAndRuleCommandsReturnRussianMessagesWithoutEnglishLeakage() {
        assertEquals(
            "Настройки автообхода РФ сохранены.",
            windowsUiResultMessage("set-ru-bypass", RuntimeCommandResult(0, "RU bypass enabled; token=secret")),
        )
        assertEquals(
            "Приложение добавлено в маршрут «Через GATES».",
            windowsUiResultMessage("add-app-proxy", RuntimeCommandResult(0, "Added C:\\game.exe")),
        )
        assertEquals(
            "Активный маршрут обновлён.",
            windowsUiResultMessage("set-route", RuntimeCommandResult(0, "route switched")),
        )
        assertEquals(
            "Не удалось добавить правило для приложения.",
            windowsUiResultMessage("add-app-proxy", RuntimeCommandResult(1, "failed C:\\foo.exe")),
        )
    }
    @Test fun protectionFailuresSurfaceSpecificFailureCodesAndActionableMessages() {
        val missingExe = RuntimeCommandResult(1, "GATES_PROTECTION_MISSING_EXE: MISSING_EXE")
        assertEquals("GATES_PROTECTION_MISSING_EXE", windowsUiFailureCode("set-kill-switch", missingExe))
        assertTrue(windowsUiResultMessage("set-kill-switch", missingExe).contains(".exe"))

        val staleService = RuntimeCommandResult(1, "GATES_PROTECTION_SERVICE_UNAVAILABLE")
        assertEquals("GATES_PROTECTION_SERVICE_UNAVAILABLE", windowsUiFailureCode("set-kill-switch", staleService))
        assertTrue(windowsUiResultMessage("set-kill-switch", staleService).contains("служба"))
    }
    @Test fun autostartSuccessAndFailureMessagesAreLocalized() {
        assertEquals("Автозапуск включён.", windowsUiResultMessage("enable-autostart", RuntimeCommandResult(0, "ok")))
        assertEquals("Автозапуск выключен.", windowsUiResultMessage("disable-autostart", RuntimeCommandResult(0, "ok")))
        assertEquals("Не удалось включить автозапуск.", windowsUiResultMessage("enable-autostart", RuntimeCommandResult(5, "Access denied")))
        assertEquals("Не удалось отключить автозапуск.", windowsUiResultMessage("disable-autostart", RuntimeCommandResult(5, "Access denied")))
    }

    @Test fun updateAndPrepareRuntimeMessagesAreLocalized() {
        assertEquals("Обновление загружено и запущено.", windowsUiResultMessage("update", RuntimeCommandResult(0, "ok")))
        assertEquals("Не удалось загрузить обновление.", windowsUiResultMessage("update", RuntimeCommandResult(1, "HTTP 502")))
        assertEquals("Служба GATES готова.", windowsUiResultMessage("prepare-runtime", RuntimeCommandResult(0, "ok")))
        assertTrue(windowsUiResultMessage("prepare-runtime", RuntimeCommandResult(1, "install failed")).contains("служб"))
    }
}
