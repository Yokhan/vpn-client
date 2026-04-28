package com.yokhan.vpn.shared.ui.form

fun yokhanSampleProfileFormState(): YokhanFormState {
    return YokhanFormState(
        title = "Профиль VLESS",
        subtitle = "Общий Compose form-kit: поля, селекторы, валидация, dirty-state и save/cancel контракт.",
        dirty = false,
        sections = listOf(
            YokhanFormSection(
                id = "profile",
                title = "ОСНОВНОЕ",
                description = "Этот экран использует те же shared-ui примитивы, которые дальше пойдут в Android формы.",
                fields = listOf(
                    YokhanFormField.Text(
                        id = "name",
                        label = "Название",
                        value = "Bridge-igor",
                        placeholder = "Название профиля",
                        required = true,
                    ),
                    YokhanFormField.Text(
                        id = "server",
                        label = "Сервер",
                        value = "edge.example.net",
                        placeholder = "example.com",
                        required = true,
                    ),
                    YokhanFormField.Port(
                        id = "port",
                        label = "Порт",
                        value = "443",
                        validation = YokhanFormValidation(
                            state = YokhanFormValidationState.Success,
                            message = "Порт в допустимом диапазоне.",
                        ),
                    ),
                    YokhanFormField.Password(
                        id = "uuid",
                        label = "UUID / пароль",
                        value = "17761155-5414-1000-9000-yokhanvpn",
                        required = true,
                    ),
                ),
            ),
            YokhanFormSection(
                id = "transport",
                title = "ТРАНСПОРТ",
                fields = listOf(
                    YokhanFormField.Select(
                        id = "security",
                        label = "Безопасность",
                        selectedOptionId = "reality",
                        options = listOf(
                            YokhanFormOption("reality", "REALITY", "Современный режим маскировки TLS."),
                            YokhanFormOption("tls", "TLS", "Классический TLS-транспорт."),
                            YokhanFormOption("none", "NONE", "Без шифрования транспорта."),
                        ),
                    ),
                    YokhanFormField.Switch(
                        id = "udp",
                        label = "UDP relay",
                        checked = true,
                        helper = "Переключатель сразу отдаёт событие наружу, без внутреннего состояния в UI.",
                    ),
                    YokhanFormField.Info(
                        id = "subscription",
                        label = "Источник",
                        value = "Subscription #1776115554141",
                        helper = "Информационные строки не редактируются и не создают dirty-state.",
                    ),
                ),
            ),
        ),
    )
}
