# Текущая задача: Хардкорный форк NekoBox → YokhanVPN

## Цель

Модифицировать NekoBox для защиты от детекции VPN российскими приложениями (методичка Минцифры, апрель 2026).

## Контекст

- habr.com/ru/articles/1020080/ — уязвимость SOCKS5 в VLESS-клиентах
- Российские приложения (Сбер, Тинькофф, Яндекс, Ozon, VK и весь реестр ИТ) могут сканировать localhost, найти SOCKS5 прокси и узнать exit IP VPN-сервера
- Shelter/Island НЕ помогает — loopback не изолирован между профилями

## Фичи для реализации

### 1. SOCKS5 inbound OFF по умолчанию

- **Где:** `Constants.kt` или default SharedPreferences
- **Что:** по умолчанию не поднимать SOCKS/HTTP inbound при старте VPN
- **Зачем:** нечего сканировать на localhost

### 2. exclude_package из sing-box JSON конфига

- **Где:** `bg/` — VpnService builder, парсинг sing-box конфига
- **Что:** при импорте sing-box JSON, если в `inbounds[].exclude_package` есть список — передать его в Android VpnService.Builder.addDisallowedApplication()
- **Зачем:** российские приложения автоматически обходят VPN без ручной настройки в UI

### 3. Auto-update подписки

- **Где:** `bg/` — добавить WorkManager PeriodicWorkRequest
- **Что:** раз в N часов (настраиваемо, дефолт 12ч) обновлять подписку по URL
- **Зачем:** когда мы обновляем список доменов/пакетов на сервере — юзеры получают автоматически

### 4. Встроенный список ру-приложений

- **Где:** `assets/ru_packages.json` + UI чекбокс "Обходить российские приложения"
- **Что:** при включении чекбокса — добавить все пакеты из списка в exclude per-app
- **Зачем:** один клик вместо ручного выбора 50+ приложений

## Статус реализации

| Фича               | Статус                                  | Ветка                           |
| ------------------ | --------------------------------------- | ------------------------------- |
| 1. SOCKS5 OFF      | DONE                                    | `feat/anti-detection-hardening` |
| 2. exclude_package | DONE (парсинг), нужен v2 fix VpnService | `feat/anti-detection-hardening` |
| 3. Auto-update     | Уже в upstream (SubscriptionUpdater.kt) | —                               |
| 4. RU bypass list  | DONE (UI), нужен v2 fix runtime merge   | `feat/anti-detection-hardening` |

## Plan — v2 fixes (zero-config UX)

### Goal

Устранить три UX-бага: RU bypass и server exclude не применяются без ручного захода в AppManager.

### Complexity: S (3 файла, ~30 строк)

### Что менять

1. **DataStore.kt** — `enableRuBypass` default → `true`
2. **VpnService.kt** — redesign per-app routing:
   - Entry condition: `+ || hasRuBypass || hasServerExclude`
   - RU packages: `NGUtil.readTextFromAssets` + merge в individual
   - Server packages: merge вне `if(proxyApps)` блока
   - Force `bypass = true` когда auto-sources активны
3. **AppManagerActivity.kt** — `initProxiedUids` показывает RU packages если флаг включён

### Порядок

1. DataStore default → сразу
2. VpnService redesign → ключевое изменение
3. AppManager UI consistency → после VpnService

### Risk: LOW

Изменения в runtime routing — но логика additive (мерджим дополнительные пакеты в существующий set).

## Связанный проект

Сервер и бот: `../YokhanIndependentVPN/`
Бот генерирует sing-box JSON конфиги с exclude_package и keepalive — этот клиент их потребляет.
