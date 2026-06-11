package com.yokhan.gates.shared.ui.form

fun yokhanSampleProfileFormState(): YokhanFormState {
    return YokhanFormState(
        title = "РџСЂРѕС„РёР»СЊ VLESS",
        subtitle = "РћР±С‰РёР№ Compose form-kit: РїРѕР»СЏ, СЃРµР»РµРєС‚РѕСЂС‹, РІР°Р»РёРґР°С†РёСЏ, dirty-state Рё save/cancel РєРѕРЅС‚СЂР°РєС‚.",
        dirty = false,
        sections = listOf(
            YokhanFormSection(
                id = "profile",
                title = "РћРЎРќРћР’РќРћР•",
                description = "Р­С‚РѕС‚ СЌРєСЂР°РЅ РёСЃРїРѕР»СЊР·СѓРµС‚ С‚Рµ Р¶Рµ shared-ui РїСЂРёРјРёС‚РёРІС‹, РєРѕС‚РѕСЂС‹Рµ РґР°Р»СЊС€Рµ РїРѕР№РґСѓС‚ РІ Android С„РѕСЂРјС‹.",
                fields = listOf(
                    YokhanFormField.Text(
                        id = "name",
                        label = "РќР°Р·РІР°РЅРёРµ",
                        value = "Bridge-igor",
                        placeholder = "РќР°Р·РІР°РЅРёРµ РїСЂРѕС„РёР»СЏ",
                        required = true,
                    ),
                    YokhanFormField.Text(
                        id = "server",
                        label = "РЎРµСЂРІРµСЂ",
                        value = "edge.example.net",
                        placeholder = "example.com",
                        required = true,
                    ),
                    YokhanFormField.Port(
                        id = "port",
                        label = "РџРѕСЂС‚",
                        value = "443",
                        validation = YokhanFormValidation(
                            state = YokhanFormValidationState.Success,
                            message = "РџРѕСЂС‚ РІ РґРѕРїСѓСЃС‚РёРјРѕРј РґРёР°РїР°Р·РѕРЅРµ.",
                        ),
                    ),
                    YokhanFormField.Password(
                        id = "uuid",
                        label = "UUID / РїР°СЂРѕР»СЊ",
                        value = "17761155-5414-1000-9000-gates",
                        required = true,
                    ),
                ),
            ),
            YokhanFormSection(
                id = "transport",
                title = "РўР РђРќРЎРџРћР Рў",
                fields = listOf(
                    YokhanFormField.Select(
                        id = "security",
                        label = "Р‘РµР·РѕРїР°СЃРЅРѕСЃС‚СЊ",
                        selectedOptionId = "reality",
                        options = listOf(
                            YokhanFormOption("reality", "REALITY", "РЎРѕРІСЂРµРјРµРЅРЅС‹Р№ СЂРµР¶РёРј РјР°СЃРєРёСЂРѕРІРєРё TLS."),
                            YokhanFormOption("tls", "TLS", "РљР»Р°СЃСЃРёС‡РµСЃРєРёР№ TLS-С‚СЂР°РЅСЃРїРѕСЂС‚."),
                            YokhanFormOption("none", "NONE", "Р‘РµР· С€РёС„СЂРѕРІР°РЅРёСЏ С‚СЂР°РЅСЃРїРѕСЂС‚Р°."),
                        ),
                    ),
                    YokhanFormField.Switch(
                        id = "udp",
                        label = "UDP relay",
                        checked = true,
                        helper = "РџРµСЂРµРєР»СЋС‡Р°С‚РµР»СЊ СЃСЂР°Р·Сѓ РѕС‚РґР°С‘С‚ СЃРѕР±С‹С‚РёРµ РЅР°СЂСѓР¶Сѓ, Р±РµР· РІРЅСѓС‚СЂРµРЅРЅРµРіРѕ СЃРѕСЃС‚РѕСЏРЅРёСЏ РІ UI.",
                    ),
                    YokhanFormField.Info(
                        id = "subscription",
                        label = "РСЃС‚РѕС‡РЅРёРє",
                        value = "Subscription #1776115554141",
                        helper = "РРЅС„РѕСЂРјР°С†РёРѕРЅРЅС‹Рµ СЃС‚СЂРѕРєРё РЅРµ СЂРµРґР°РєС‚РёСЂСѓСЋС‚СЃСЏ Рё РЅРµ СЃРѕР·РґР°СЋС‚ dirty-state.",
                    ),
                ),
            ),
        ),
    )
}
