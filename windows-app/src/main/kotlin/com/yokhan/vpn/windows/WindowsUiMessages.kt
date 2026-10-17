package com.yokhan.gates.windows

internal fun windowsUiResultMessage(command: String, result: RuntimeCommandResult): String {
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
