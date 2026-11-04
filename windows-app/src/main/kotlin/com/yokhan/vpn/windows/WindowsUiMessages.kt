package com.yokhan.gates.windows

internal fun windowsUiResultMessage(command: String, result: RuntimeCommandResult): String {
    if (result.exitCode != 0) {
        when {
            "GATES_PROTECTION_REQUIRES_EXE_PATHS" in result.output ->
                return "Добавьте полный путь к EXE в список «Через VPN». Защита по одному имени процесса недоступна."
            "GATES_PROTECTION_STOP_FIRST" in result.output ->
                return "Сначала отключите VPN. Защита работающего соединения не изменена."
            "GATES_PROTECTION_REQUIRES_TUN" in result.output || "GATES_PROTECTION_REQUIRES_SERVICE" in result.output ->
                return "Защита при обрыве работает только через службу TUN. Прямой запуск прокси запрещён."
            "GATES_PROTECTION_SERVICE_UNAVAILABLE" in result.output ->
                return "Эта служба GATES не поддерживает защиту при обрыве. Подключение без защиты не запущено."
            "GATES_PROTECTION" in result.output ->
                return "Защита при обрыве не подтверждена. Трафик может оставаться заблокированным."
        }
        when (windowsUiFailureCode(command, result)) {
            "GATES_UAC_CANCELLED" -> return "Подготовка туннеля отменена. Разрешите установку службы GATES. Код: GATES_UAC_CANCELLED."
            "GATES_INSTALL_LOCATION" -> return "Запустите установленную GATES из меню «Пуск». Для туннеля нужна установка в Program Files. Код: GATES_INSTALL_LOCATION."
            "GATES_SERVICE_SETUP" -> return when (windowsRuntimeSetupStage(result)) {
                "elevated_timeout" -> "Ожидание подготовки GATES превысило лимит. Запрос администратора может ещё выполняться; проверьте статус службы перед повтором. Код: GATES_SETUP_TIMEOUT."
                "install_service" -> "Не удалось зарегистрировать службу GATES. Проверьте установку приложения в Program Files и журнал службы. Код: GATES_SETUP_INSTALL."
                "start_service" -> "Не удалось запустить службу GATES. Проверьте журнал службы Windows. Код: GATES_SETUP_START."
                "service_health" -> "Служба GATES не ответила за отведённое время. Проверьте её статус и журнал. Код: GATES_SETUP_HEALTH."
                else -> "Не удалось подготовить службу GATES. Проверьте её статус и журнал перед повтором. Код: GATES_SERVICE_SETUP."
            }
            "GATES_BROKER_UNAVAILABLE" -> return "Служба туннеля не отвечает. Настройки не изменены. Код: GATES_BROKER_UNAVAILABLE."
            "GATES_STATE_MISMATCH" -> return "Настройки работающего туннеля отличаются от сохранённых. Сначала отключите GATES. Код: GATES_STATE_MISMATCH."
            "GATES_ROUTE_UNAVAILABLE" -> return "Этого маршрута нет в полученной конфигурации. Обновите доступ и выберите доступный маршрут."
            "GATES_INVALID_APP" -> return "Выберите файл .exe или укажите имя процесса, например browser.exe."
            "GATES_CONNECT_FAILED" -> return "Не удалось подключиться. Код: GATES_CONNECT_FAILED."
        }
        result.accessFailure?.let { failure ->
            windowsUiAccessFailureMessage(command, failure)?.let { return it }
        }
    }
    if (result.exitCode != 0) return when (command) {
        "login", "import-app" -> if (result.exitCode == 401) "Сессия истекла. Войдите снова." else "Не удалось получить доступ."
        "access-request" -> "Не удалось отправить заявку на доступ."
        "import-sub" -> "Не удалось добавить подписку."
        "report-bug" -> "Не удалось отправить отчёт."
        "check-update" -> "Не удалось проверить обновления."
        "connect" -> "Не удалось подключиться."
        "stop" -> "Не удалось отключиться."
        else -> "Не удалось сохранить изменения."
    }
    return when (command) {
        "login", "import-app" -> "Доступ обновлён."
        "access-request" -> "Заявка на доступ отправлена. Ожидайте одобрения."
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
            "service_health", "uac_approval", "elevated_launch", "elevated_timeout", "setup", "unknown")
    }

/**
 * Actionable access message from bounded metadata.
 * Returns null for unknown codes so the existing command fallback still applies.
 */
internal fun windowsUiAccessFailureMessage(command: String, failure: WindowsAccessFailure): String? {
    val completedSignIn = command == "login" && failure.stage == WindowsAccessStage.PROFILE
    return when (failure.code) {
        WindowsAccessCode.ACCESS_NOT_LINKED ->
            if (completedSignIn) "Вход выполнен, но доступ GATES не привязан к аккаунту. Войдите под другим аккаунтом или запросите доступ."
            else "Доступ GATES не привязан к аккаунту. Войдите под другим аккаунтом или запросите доступ."
        WindowsAccessCode.ACCESS_PENDING ->
            "Заявка на доступ ожидает одобрения. Нажмите «Проверить статус» после одобрения."
        WindowsAccessCode.USER_NOT_FOUND ->
            "Аккаунт не найден на сервере GATES. Проверьте, что вход выполнен под нужным аккаунтом, или запросите доступ."
        WindowsAccessCode.DEVICE_NOT_FOUND ->
            "Устройство не найдено в аккаунте. Проверьте имя устройства или обратитесь в поддержку."
        WindowsAccessCode.ROOM_FORBIDDEN ->
            "Для аккаунта недоступна выбранная комната. Проверьте настройки комнаты."
        WindowsAccessCode.APP_ROUTE_UNAVAILABLE ->
            "Сервер не смог подготовить маршруты. Сохранённая конфигурация не изменена. Повторите позже."
        WindowsAccessCode.RATE_LIMITED ->
            "Слишком много попыток. Подождите и повторите."
        WindowsAccessCode.SERVER_ERROR ->
            "Сервер временно недоступен. Повторите позже."
        WindowsAccessCode.TRANSPORT_ERROR ->
            "Нет связи с сервером. Проверьте интернет и повторите."
        WindowsAccessCode.NOT_FOUND ->
            "Аккаунт или устройство не найдено на сервере GATES."
        WindowsAccessCode.CONFLICT ->
            "Конфликт настроек комнаты или маршрута. Повторите позже."
        WindowsAccessCode.AUTH_REQUIRED ->
            "Сессия входа истекла. Войдите снова."
        WindowsAccessCode.AUTH_UNAVAILABLE ->
            "Сервер входа временно недоступен. Проверьте соединение и повторите."
        WindowsAccessCode.AUTH_FORBIDDEN ->
            "Сервер отклонил вход для этого аккаунта."
        WindowsAccessCode.AUTH_START_FAILED ->
            "Не удалось начать вход. Проверьте соединение и повторите."
        WindowsAccessCode.AUTH_DENIED ->
            "Вход отклонён в браузере. Попробуйте снова."
        WindowsAccessCode.AUTH_EXPIRED ->
            "Код входа устарел. Начните вход заново."
        WindowsAccessCode.AUTH_TIMEOUT ->
            "Время ожидания входа истекло. Начните вход заново."
        WindowsAccessCode.PROFILE_INVALID ->
            "Сервер вернул конфигурацию без поддерживаемого профиля GATES. Сохранённая конфигурация не изменена."
        WindowsAccessCode.PROFILE_STORE_FAILED ->
            "Не удалось сохранить конфигурацию. Сохранённая конфигурация не изменена."
        WindowsAccessCode.REQUEST_OPEN ->
            "Заявка на доступ уже ожидает одобрения."
        WindowsAccessCode.REQUEST_FAILED ->
            "Не удалось отправить заявку на доступ. Повторите позже."
        WindowsAccessCode.UNKNOWN -> null
    }
}
