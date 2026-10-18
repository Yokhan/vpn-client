package com.yokhan.gates.windows

internal fun windowsUiResultMessage(command: String, result: RuntimeCommandResult): String {
    if (result.exitCode != 0) {
        when (windowsUiFailureCode(command, result)) {
            "GATES_UAC_CANCELLED" -> return "Подготовка туннеля отменена. Разрешите установку службы GATES. Код: GATES_UAC_CANCELLED."
            "GATES_INSTALL_LOCATION" -> return "Запустите установленную GATES из меню «Пуск». Для туннеля нужна установка в Program Files. Код: GATES_INSTALL_LOCATION."
            "GATES_SERVICE_SETUP" -> return "Не удалось подготовить службу туннеля. Повторите запуск GATES. Код: GATES_SERVICE_SETUP."
            "GATES_BROKER_UNAVAILABLE" -> return "Служба туннеля не отвечает. Настройки не изменены. Код: GATES_BROKER_UNAVAILABLE."
            "GATES_STATE_MISMATCH" -> return "Настройки работающего туннеля отличаются от сохранённых. Сначала отключите GATES. Код: GATES_STATE_MISMATCH."
            "GATES_ROUTE_UNAVAILABLE" -> return "Этого маршрута нет в полученной конфигурации. Обновите доступ и выберите доступный маршрут."
            "GATES_INVALID_APP" -> return "Выберите файл .exe или укажите имя процесса, например browser.exe."
            "GATES_CONNECT_FAILED" -> return "Не удалось подключиться. Код: GATES_CONNECT_FAILED."
        }
    }
    if (result.exitCode != 0) return when (command) {
        "login", "import-app" -> if (result.exitCode == 401) "Сессия истекла. Войдите снова." else "Не удалось получить доступ."
        "import-sub" -> "Не удалось добавить подписку."
        "report-bug" -> "Не удалось отправить отчёт."
        "check-update" -> "Не удалось проверить обновления."
        "connect" -> "Не удалось подключиться."
        "stop" -> "Не удалось отключиться."
        else -> "Не удалось сохранить изменения."
    }
    return when (command) {
        "login", "import-app" -> "Доступ обновлён."
        "import-sub" -> "Подписка добавлена."
        "report-bug" -> "Отчёт отправлен."
        "check-update" -> when {
            result.output.contains("Update available: true") -> "Доступно обновление."
            result.output.contains("No update") || result.output.contains("Update available: false") -> "Установлена последняя версия."
            else -> "Проверка обновлений завершена."
        }
        "connect" -> "Подключено."
        "stop" -> "Отключено."
        else -> "Изменения сохранены."
    }
}

internal fun windowsUiFailureCode(command: String, result: RuntimeCommandResult): String? {
    if (result.exitCode == 0) return null
    val detail = result.output.lowercase()
    return when {
        result.exitCode == 1223 || "uac_cancelled" in detail || "uac_approval" in detail ||
            "operation was canceled by the user" in detail -> "GATES_UAC_CANCELLED"
        "per-machine program files" in detail -> "GATES_INSTALL_LOCATION"
        "runtime state could not be verified" in detail || "runtime configuration could not be verified" in detail -> "GATES_BROKER_UNAVAILABLE"
        "runtime configuration differs" in detail -> "GATES_STATE_MISMATCH"
        "selected route is not available" in detail -> "GATES_ROUTE_UNAVAILABLE"
        "expected .exe path or process name" in detail -> "GATES_INVALID_APP"
        command == "prepare-runtime" || "runtime-helper-connect exit=" in detail -> "GATES_SERVICE_SETUP"
        command == "connect" -> "GATES_CONNECT_FAILED"
        else -> "GATES_COMMAND_FAILED"
    }
}

internal fun windowsRuntimeSetupStage(result: RuntimeCommandResult): String? =
    Regex("GATES_SETUP_STAGE=([a-z_]+)").find(result.output)?.groupValues?.get(1)?.takeIf {
        it in setOf("prepare_directory", "query_service", "inspect_service", "prepare_token",
            "preserve_active_service", "remove_legacy_task", "install_service", "start_service",
            "service_health", "uac_approval", "elevated_launch", "setup", "unknown")
    }
