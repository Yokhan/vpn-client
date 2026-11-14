## Актуальное состояние (2026-11-13): Windows GATES 1.7.14 и Android GATES 1.8.15 DEBUG собраны и проверены, готовы к переключению

- **Worktree сборки:** Windows/shared-ui — `c:\Users\iohan\Documents\.worktrees\gates-windows-xray-build` (`1.7.14`), Android/shared-ui — `c:\Users\iohan\Documents\.worktrees\gates-android-xray-sidecar` (`1.8.15`, `VERSION_CODE=536`).
- **Что выполнено:** [windows-client-parity-plan.md](file:///C:/Users/iohan/Documents/.worktrees/gates-windows-xray-build/docs/windows-client-parity-plan.md) дополнен и реализован: двухколоночный desktop companion (`>= 840.dp`), 6-вкладочный `GatesBottomNavigation` без бургера/Drawer на обеих платформах, монолитный заголовок окна (`WindowsWindowChrome`), `WindowsSingleInstance`, обновлённый DnD (`NativeFileDropTarget` + бинарный `.lnk` + Squirrel + глубина папок 4 + HiDPI + `.url` подсказка), горячие клавиши и ПКМ-меню приложений, JNA-реестр установленных программ и ленивые иконки, JNA живая телеметрия и список активных сетевых процессов («Кто сейчас в сети»), локальный 3s polling, расширенный трей на русском и устранение лага запуска/выключения на Android.
- **Проверки Windows:**
  - `.\gradlew.bat :shared-ui:desktopTest :windows-service:test :windows-app:check --no-daemon` -> `BUILD SUCCESSFUL`: `:shared-ui:desktopTest` (`9/9`), `:windows-service:test` (`152/152`), `:windows-app:test` (`228/228`), release/encoding/design/runtime checks PASSED.
  - `.\gradlew.bat :windows-app:packageMsi :windows-app:packageExe --no-daemon` -> `BUILD SUCCESSFUL`:
    - `windows-app\build\compose\binaries\main\msi\GATES-1.7.14.msi` (`bytes=161374208`, `sha256=AC2C6D0D799C698120A23DBFC60E99FF0764F25BD0DF66DA359D6FA9FD6F3CAE`)
    - `windows-app\build\compose\binaries\main\exe\GATES-1.7.14.exe` (`bytes=162097664`, `sha256=458544447F8E3DF7B0BD512078E33FBA0C247B1EC0ABDB4A40D`)
    - Откат: `_archive\rollback-1.7.12\GATES-1.7.12.msi` (`bytes=160739328`, `sha256=F2BC7FE4D525238DDBAFF9DB85903A02EE961CB2FB9B676ECCE86479269F3093`)
  - `Test-GatesInstallerContract.ps1` (`ExpectedProductVersion 1.7.14`) -> exit `0`; `Invoke-Pester` (`Test-GatesInstallerContract.Tests.ps1`) -> `4/4` passed.
- **Проверки Android и паритет `shared/ui/src`:**
  - `.\gradlew.bat :shared-ui:testDebugUnitTest :app:testOssDebugUnitTest :app:assembleOssDebug --no-daemon` -> `BUILD SUCCESSFUL`: `:shared-ui:testDebugUnitTest` (`9/9`), `:app:testOssDebugUnitTest` (`108/108`), APK `GATES-1.8.15-arm64-v8a-debug.apk` (`bytes=55174248`, `sha256=312AFB48251728DBBD3A5F67363196E0FC98DCD2B547750632DC07036AB097DC`).
  - SHA-256 паритет `shared/ui/src`: `22/22` файлов идентичны (`0` расхождений).
- **Живой хост и переключение:** служба `GatesVpn` (`1.7.12`, `Running`) не останавливалась; для переключения пользователем обновлён [GATES-switch-local.cmd](file:///C:/Users/iohan/Documents/.worktrees/gates-windows-xray-build/windows-app/tools/GATES-switch-local.cmd) (указывает на `GATES-1.7.14.msi`).

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
