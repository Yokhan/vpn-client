# План доработки Windows-клиента GATES до паритета с Android (и выше)

Дата создания: 2026-11-13
Статус: `BUILT_VERIFIED_READY_TO_SWITCH (GATES 1.7.14 Windows + 1.8.15 DEBUG Android)`
Активный worktree сборки: `c:\Users\iohan\Documents\.worktrees\gates-windows-xray-build` (Android/shared-ui: `c:\Users\iohan\Documents\.worktrees\gates-android-xray-sidecar`)
Связанная продуктовая спецификация: [windows-product-client-spec.md](file:///C:/Users/iohan/Documents/YokhanVPN/docs/windows-product-client-spec.md)

## Сводка реализации и проверки GATES 1.7.14 + Android 1.8.15 DEBUG (2026-11-13)

- **Что добавлено в 1.7.14 / 1.8.15 сверх волны паритета 1.7.13:**
  - **Десктопная двухколоночная компоновка без бургера:** привычный мобильный столбец `460.dp` сохранён слева, справа на ширине `>= 840.dp` раскрывается companion-колонка (живая телеметрия скорости RX/TX и uptime, активные процессы в сети, маршруты, горячие клавиши, drop/rule-панели); боковой Drawer и кнопка меню убраны на обеих платформах, на Windows Desktop нижняя панель `GatesBottomNavigation` расширена сразу до 6 вкладок (`01 Главная` .. `06 Настройки`).
  - **Монолитный заголовок окна Windows:** `WindowsWindowChrome.kt` через `dwmapi` окрашивает системный caption/border/text в цвета `YokhanPalette` с сохранением нативного Aero Snap/resize + `ui-window.json` (`1080×760` по умолчанию) и single-instance lock (`WindowsSingleInstance.kt`).
  - **Drag-and-Drop + хоткеи + контекстное меню + производительность Windows:** `NativeFileDropTarget` (`ACTION_COPY_OR_MOVE or ACTION_LINK`, рекурсивное навешивание на `SkiaLayer` + HiDPI `mapAwtPointToComposeRootPx`), быстрый бинарный `[MS-SHLLINK]` `.lnk`-парсер и Squirrel `Update.exe --processStart` резолвер (`WindowsShortcut.kt`), обход папок глубины 4 (`WindowsDropIntake.kt`), нативный JNA-реестр установленных приложений (`WindowsInstalledAppsScanner.kt`), ленивый кэш иконок (`WindowsAppIcons.kt`), JNA телеметрия `GetIfTable` и «Кто сейчас в сети» `GetExtendedTcpTable`/`UdpTable`, глобальный drop overlay, перетаскивание и ПКМ-меню карточек приложений, горячие клавиши `Ctrl+1..6`/`Ctrl+F`/`Ctrl+R`/`Ctrl+V`/`Esc`, расширенный русский трей.
  - **Оптимизация Android `1.8.15 DEBUG` (`VERSION_CODE=536`):** устранены per-frame переаллокации палитры и `.alpha()` в `YokhanDashboardScreen` / `YokhanRouteKit` / `YokhanMainShell`, убрана искусственная задержка отключения, `killProcesses()` / DNS-резолв / записи статистики вынесены на `Dispatchers.IO`, кэшируются иконки и `PackageCache`, навигация приведена к вкладкам без Drawer.
- **Проверки Windows (`YOKHAN_SING_BOX_DIR` -> `sing-box-1.13.12-windows-amd64`):** `.\gradlew.bat :shared-ui:desktopTest :windows-service:test :windows-app:check --no-daemon` -> `BUILD SUCCESSFUL`:
  - `:shared-ui:desktopTest`: `9/9`;
  - `:windows-service:test`: `152/152`;
  - `:windows-app:test`: `228/228`;
  - `:windows-app:checkWindowsNoHubDownloadChannel`, `checkWindowsReleaseMetadata`, `checkWindowsTextEncoding`, `checkWindowsUiUsesDesignTokens`, `verifyPinnedWindowsRuntime`: PASSED.
- **Собранные пакеты Windows (`.\gradlew.bat :windows-app:packageMsi :windows-app:packageExe --no-daemon`):**
  - MSI `1.7.14`: `windows-app\build\compose\binaries\main\msi\GATES-1.7.14.msi` (`bytes=161374208`, `sha256=AC2C6D0D799C698120A23DBFC60E99FF0764F25BD0DF66DA359D6FA9FD6F3CAE`);
  - EXE `1.7.14`: `windows-app\build\compose\binaries\main\exe\GATES-1.7.14.exe` (`bytes=162097664`, `sha256=458544447F8E3DF7B0BD512078E33FBA0C247B1EC0ABDB4A40D`);
  - Откат `1.7.12`: `_archive\rollback-1.7.12\GATES-1.7.12.msi` (`bytes=160739328`, `sha256=F2BC7FE4D525238DDBAFF9DB85903A02EE961CB2FB9B676ECCE86479269F3093`).
- **Контракт установщика и Pester:**
  - `Test-GatesInstallerContract.ps1 -ExpectedProductVersion 1.7.14` -> exit `0` (`ProductName: GATES`, `ProductVersion: 1.7.14`, `ProductCode: {434F539C-34AD-3E5A-9B86-92211B1655AC}`, `UpgradeCode: {33D7B058-0DA8-4ADA-9108-D4511DA47B21}`);
  - `Invoke-Pester -Path .\windows-app\tools\Test-GatesInstallerContract.Tests.ps1` -> `4/4` passed (`0` failed, `0` skipped).
- **Проверки Android & `shared/ui` (`gates-android-xray-sidecar`):**
  - `.\gradlew.bat :shared-ui:testDebugUnitTest :app:testOssDebugUnitTest :app:assembleOssDebug --no-daemon` -> `BUILD SUCCESSFUL`: `:shared-ui:testDebugUnitTest` (`9/9`), `:app:testOssDebugUnitTest` (`108/108`);
  - APK `1.8.15 DEBUG`: `app\build\outputs\apk\oss\debug\GATES-1.8.15-arm64-v8a-debug.apk` (`bytes=55174248`, `sha256=312AFB48251728DBBD3A5F67363196E0FC98DCD2B547750632DC07036AB097DC`);
  - Пофайловый SHA-256 паритет `shared/ui/src` между двумя worktree: `22/22` файлов идентичны (`0` расхождений).
- **Переключение на хосте пользователем:** живой сервис `GatesVpn` (`1.7.12`, `Running`) не прерывался; локальный скрипт `windows-app\tools\GATES-switch-local.cmd` обновлён на `GATES-1.7.14.msi`.

## 1. Контекст и цель

На Android (`1.8.14 DEBUG`) доведены до рабочего состояния единый Storybook-shell ([YokhanMainShell.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/app/src/main/java/com/yokhan/vpn/app/ui/shell/YokhanMainShell.kt)), 3-полосный хедер меню (`☰`), нижняя навигация поверх контента ([GatesBottomNavigation.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/shared/ui/src/commonMain/kotlin/com/yokhan/gates/shared/ui/navigation/GatesBottomNavigation.kt)), примитивы [YokhanRouteKit.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/shared/ui/src/commonMain/kotlin/com/yokhan/gates/shared/ui/route/YokhanRouteKit.kt)), а также многослойный автообход РФ-приложений и доменов ([RuCompatibilityActivity.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/app/src/main/java/com/yokhan/vpn/app/ui/RuCompatibilityActivity.kt), [RuCompatibilityClassifier.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/app/src/main/java/com/yokhan/vpn/app/routing/RuCompatibilityClassifier.kt)).

Windows-клиент (`windows-app` + `windows-service`) уже имеет рабочий CLI/runtime-движок ([WindowsRuntimeController.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/WindowsRuntimeController.kt)), сервис и WFP kill-switch ([SelectedExecutableProtection.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-service/src/main/kotlin/com/yokhan/gates/windows/service/SelectedExecutableProtection.kt)), однако в UI сейчас урезана навигация, скрыты часть действий и настроек, drag-and-drop реализован как базовый перехват одного `.exe`, а автообход РФ ограничен двумя системными процессами.

Цель плана: довести `windows-app` (Compose Desktop) до UX/функционального паритета с Android с сохранением защищённых сервисных/WFP-контрактов и добавлением десктопных возможностей (полноценный drag-and-drop, сканер установленных приложений, расширенные сетевые настройки, логи, диагностика и локализованный трей).

---

## 2. Текущие разрывы (подтверждённый аудит кода)

| Область | Текущее состояние в Windows-клиенте | Где в коде | Целевой результат |
| :--- | :--- | :--- | :--- |
| Оболочка и навигация | `ProductionDashboardVisibility` скрывает меню (`showMenu = false`), проверку связи (`showConnectionTest = false`), добавление и действия профиля (`showAddProfile = false`, `showProfileActions = false`). Всего 4 вкладки, нет бокового Drawer (`☰`), маршрутов, логов и инструментов. `GatesBottomNavigation` зажат под `Column(weight(1f))` вместе со сдвигающими вёрстку баннерами `RoomNetPanel` / `WindowsStatusBar`. | [Main.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/Main.kt#L100-L140), [Main.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/Main.kt#L1153-L1172) | Storybook-shell по стандарту Android: 3-полосный хедер (`☰`), боковой Drawer с 5 разделами (`01..05`), парящая нижняя навигация поверх скролла (`Box`-overlay без непрозрачной подложки), баннеры без скачков высоты. |
| Элементы форм | Экран настроек, маршрутизации приложений и баг-репортов использует стандартные Material `Checkbox`, `SegmentedButton`, `IconButton` вместо общих с Android компонентов. | [WindowsProductScreens.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/WindowsProductScreens.kt) | Единые примитивы из [YokhanRouteKit.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/shared/ui/src/commonMain/kotlin/com/yokhan/gates/shared/ui/route/YokhanRouteKit.kt): `YokhanToggleRow`, `YokhanActionRow`, `YokhanRouteCard`, `YokhanButtonGrid`, `YokhanStatusPill`. |
| Drag-and-Drop | `NativeFileDropTarget` висит на всём окне, фильтрует только одиночный `.exe` (`files.firstOrNull()`), игнорирует `.lnk`-ярлыки, папки и конфиги `.json`/`.txt`, всегда отправляет в `add-app-proxy` без выбора зоны и визуальной подсветки. | [NativeFileDropTarget.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/NativeFileDropTarget.kt), [Main.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/Main.kt#L836-L845) | Мульти-drop нескольких файлов за раз, разрешение `.lnk` до реального `.exe`, рекурсивный поиск `.exe` в папках, импорт `.json`/`.txt` на дашборде, две явные зоны (`Через GATES` и `Напрямую`) + перетаскивание/перенос внутри UI. |
| Автообход РФ | В `WindowsAppConfig` зашито только `appDirectProcessNames = listOf("GATES.exe", "sing-box.exe")`. Нет правил для доменов `.ru`/`.su`/`.рф`, РФ-DNS, базы десктопных РФ-клиентов (банки, маркетплейсы, VK/Yandex/Mail/RuTube/Sber/Госуслуги и др.) и простого переключателя `enableRuBypass` <-> `wholeTrafficMode`. | [WindowsRuntimeController.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/WindowsRuntimeController.kt#L75-L155), [WindowsAppRouting.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/WindowsAppRouting.kt) | Единый свитчер «Умный автообход РФ» + взаимное отключение с режимом всего трафика (1 тап), РФ-домены/DNS-маршруты в `sing-box` и локальный список десктопных РФ-процессов с пересборкой правил на лету. |
| Скрытые настройки и CLI | В `WindowsAppConfig` и `run(...)` уже есть `autoStartOnLogin`, `systemProxyEnabled`, `restoreSystemProxyOnStop`, `listenHost`, `socksPort=2088`, `httpPort=2089`, `tunMtu=9000`, `tunStack="mixed"`, `tunStrictRoute`, `physicalBindEnabled`, `physicalBindInterface`, `routeOrder=listOf("bridge","relay","naive","hy2")` и команды `probe-routes`, `logs`, `support-bundle`, `install-service`, `uninstall-service`, `service-status`, но в UI они не выведены. | [WindowsRuntimeController.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/WindowsRuntimeController.kt#L75-L155), [WindowsRuntimeController.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/WindowsRuntimeController.kt#L1120-L1215) | Вкладка `04 Маршруты`, расширенные настройки (Прокси/TUN/Автозапуск/Сетевой адаптер/Сервис WFP), живой просмотр логов и экспорт support-bundle из UI. |
| Системный трей | Только английские пункты `"Open GATES"` и `"Quit"`. | [NativeTray.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/NativeTray.kt) | Локализованное меню на русском (Подключить/Отключить, текущий маршрут, Открыть окно, Проверка связи, Выход). |

---

## 3. Архитектурные ограничения и инварианты

1. **Продуктовая и сервисная безопасность:**
   - Сохранять разделение режимов и откат (`proxy` fallback, снимок маршрутов/прокси/DNS до старта TUN) согласно [windows-product-client-spec.md](file:///C:/Users/iohan/Documents/YokhanVPN/docs/windows-product-client-spec.md).
   - Не ломать контракт WFP/сервиса ([SelectedExecutableProtection.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-service/src/main/kotlin/com/yokhan/gates/windows/service/SelectedExecutableProtection.kt)) и маршрутных тегов ([WindowsProfileRoutes.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/WindowsProfileRoutes.kt)).
   - Тесты, preview и QA-сессии не должны мутировать живые директории `ProgramData\GATES`, рабочий профиль пользователя или системный сервис без явного изолированного флага/песочницы.
2. **Единая дизайн-система:**
   - Максимум UI-примитивов переиспользуется из `shared-ui` ([YokhanRouteKit.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/shared/ui/src/commonMain/kotlin/com/yokhan/gates/shared/ui/route/YokhanRouteKit.kt), [GatesBottomNavigation.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/shared/ui/src/commonMain/kotlin/com/yokhan/gates/shared/ui/navigation/GatesBottomNavigation.kt)).
   - Все интерактивные элементы (переключатели, строки, кнопки маршрутов) должны иметь реальное состояние и немедленную реакцию (никаких фиктивных кнопок-заглушек).
3. **Запрет изменений серверного контура:**
   - Вся доработка строго локальна для `windows-app`, `windows-service` и общих UI-компонентов (`shared/ui`); серверные транспорты (HY/Reality/XHTTP/Relay) не меняются.

---

## 4. План реализации по волнам

### Волна 1: Единая Storybook-оболочка, Drawer и базовые 5 экранов

**Цель волны:** визуально и структурно выровнять десктопный интерфейс с Android `1.8.14`, убрать скрытые/мертвые действия и зафиксировать нижнюю навигацию.

- **Задачи:**
  1. Пересмотреть `ProductionDashboardVisibility` в [Main.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/Main.kt): включить меню хедера (`showMenu = true`), кнопку проверки связи (`showConnectionTest = true`) и действия с профилями (`showAddProfile`, `showProfileActions`), не возвращая отладочный технический шум.
  2. Сделать единый `Box`-overlay каркас окна: контент скроллится на всю высоту с корректным нижним отступом, а `GatesBottomNavigation` парит поверх без непрозрачной/градиентной плашки, сдвигающей макет. Стабилизировать `RoomNetPanel` и `WindowsStatusBar` внутри общего слота уведомлений.
  3. Подключить боковой Drawer по нажатию на 3-полосную иконку меню (`☰`) с разделами `01 Главная`, `02 Профили`, `03 Настройки`, `04 Маршруты`, `05 Приложения` и быстрыми переходами в `Логи` и `Инструменты / Диагностика`.
  4. Перевести экраны в [WindowsProductScreens.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/WindowsProductScreens.kt) с Material `Checkbox` / `SegmentedButton` / сырых `IconButton` на примитивы `YokhanRouteKit` (`YokhanToggleRow`, `YokhanActionRow`, `YokhanRouteCard`, `YokhanButtonGrid`, `YokhanStatusPill`).
- **Критерий готовности (Acceptance):**
  - В окне Windows отображается 3-полосный хедер (`☰`), нижнее меню парит поверх скролла без перекрывающих артефактов, работают все 5 основных экранов и возврат назад без сбоев компоновки при ресайзе.

### Волна 2: Полноценный Drag-and-Drop и каталог установленных приложений Windows

**Цель волны:** превратить добавление приложений и конфигов на Windows в удобный десктопный сценарий без ручного копирования путей.

- **Задачи:**
  1. Расширить [NativeFileDropTarget.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/NativeFileDropTarget.kt) и обработчик в [Main.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/Main.kt):
     - приём списка файлов (пакетное перетаскивание нескольких `.exe` за раз);
     - парсинг `.lnk`-ярлыков Windows (извлечение целевого `.exe` пути через Shell Link / PowerShell/JNI-helper с безопасной валидацией существования файла);
     - раскрытие перетащенной папки программы с выбором найденного основного `.exe` (или списка кандидатов);
     - распознавание конфигов `.json` / `.txt` / ссылок доступа при перетаскивании на экран дашборда/профилей;
     - передача координат/hover-событий (`dragEnter`, `dragOver`, `dragExit`) в Compose для подсветки целевой зоны.
  2. Переработать UX экрана [WindowsAppRoutingScreen](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/WindowsProductScreens.kt):
     - две явные drop-зоны: **«Через GATES»** (`proxy`) и **«Напрямую (мимо VPN)»** (`direct`), подсвечивающиеся при наведении файла из Проводника;
     - кнопка переноса/свайпа элемента между списками в 1 клик (а также перетаскивание карточки между колонками/зонами внутри окна).
  3. Добавить сканер установленных программ Windows:
     - обход ярлыков меню «Пуск» (`ProgramData\Microsoft\Windows\Start Menu`, `AppData\Roaming\Microsoft\Windows\Start Menu`), веток реестра `Uninstall` и активных процессов;
     - извлечение отображаемого имени, пути к `.exe`, фильтрация системного мусора (деинсталляторы, системные службы) и опциональное кэширование иконки `.exe`;
     - экран выбора из найденных программ с поиском (аналог Android `RuCompatibilityActivity` / списка приложений).
  4. Подключить правила в [WindowsAppRouting.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/WindowsAppRouting.kt) и передачу выбранных executable в `SelectedExecutableProtection` / конфиг `sing-box` с проверкой дедупликации.
- **Критерий готовности (Acceptance):**
  - Перетаскивание 1+ `.exe`, `.lnk` или папки из Проводника в выбранную зону («Через GATES» или «Напрямую») добавляет все валидные программы с отображением имени и пути, а сканер установленных программ позволяет добавить приложение без поиска `.exe` вручную. Юнит-тесты в `WindowsAppRoutingTest.kt` покрывают разрешение `.lnk`, дедупликацию и мульти-drop.

### Волна 3: Единый автообход РФ и экран `04 Маршруты`

**Цель волны:** выровнять логику и UX маршрутизации с Android: простой переключатель автообхода РФ, умная классификация и ручное управление порядком/проверкой маршрутов.

- **Задачи:**
  1. Реализовать модуль классификации РФ-приложений и доменов для Windows (по аналогии с [RuCompatibilityClassifier.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/app/src/main/java/com/yokhan/vpn/app/routing/RuCompatibilityClassifier.kt)):
     - правила `sing-box`: домены `.ru`, `.su`, `.рф`, ключевые российские сервисы/банки/госсектор и отечественный DNS для direct-маршрута;
     - словарь известных десктопных РФ-процессов (Yandex Browser, VK/Telegram/RuTube/Sber/T-Bank/Ozon/Wildberries/1C и др.) + пользовательские исключения;
     - единый свитчер `enableRuBypass` («Автообход РФ-приложений и сайтов») со строгим взаимным исключением `enableRuBypass <-> wholeTrafficMode` в 1 клик с понятным пояснением под строкой (как в обновлённом Android UI).
  2. Добавить экран `04 Маршруты`:
     - отображение приоритета маршрутов (`routeOrder`: `bridge`, `relay`, `naive`, `hy2`) с использованием [WindowsProfileRoutes.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/WindowsProfileRoutes.kt);
     - ручной запуск `probe-routes` и просмотр задержки/здоровья каждого маршрута прямо на карточке;
     - кнопки изменения приоритета маршрута вверх/вниз и быстрые переключатели политики маршрутизации (обход LAN, блокировка рекламы/трекеров, политика QUIC, привязка direct/proxy для доменов).
- **Критерий готовности (Acceptance):**
  - При включении «Автообход РФ» `wholeTrafficMode` автоматически отключается (и наоборот), пересчитывается конфиг `sing-box` (РФ-домены + РФ-процессы идут напрямую, остальной трафик через GATES), а экран `04 Маршруты` показывает живые результаты `probe-routes` и позволяет менять `routeOrder`.

### Волна 4: Десктопные расширенные настройки, логи, инструменты и трей

**Цель волны:** открыть в UI уже существующие в [WindowsRuntimeController.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/WindowsRuntimeController.kt) механизмы без костылей через CLI и локализовать трей.

- **Задачи:**
  1. Расширить экран `03 Настройки` (сгруппировано по секциям `YokhanRouteCard` без перегруза):
     - **Автозапуск и окно:** `autoStartOnLogin` (вызов существующих `enable-autostart` / `disable-autostart`), сворачивание в трей при закрытии;
     - **Системный прокси и порты:** `systemProxyEnabled`, `restoreSystemProxyOnStop`, редактирование локальных портов (`socksPort = 2088`, `httpPort = 2089`, `listenHost`) с валидацией диапазона и занятости;
     - **Движок TUN и сеть:** выбор `tunStack` (`mixed` / `system` / `gvisor`), `tunMtu` (по умолчанию `9000`), `tunStrictRoute`, привязка к физическому интерфейсу (`physicalBindEnabled` + список адаптеров для `physicalBindInterface`);
     - **Системный сервис и Kill-Switch:** отображение статуса `Gates.Service` (`service-status`), кнопки `install-service` / `uninstall-service`, управление WFP kill-switch ([SelectedExecutableProtection.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-service/src/main/kotlin/com/yokhan/gates/windows/service/SelectedExecutableProtection.kt)) с понятным предупреждением о правах администратора.
  2. Добавить экран **Логи**:
     - стриминг/обновление логов на базе команды `logs` с фильтром по уровню (`INFO` / `WARN` / `ERROR`), поиском и кнопкой копирования/сохранения в файл.
  3. Добавить экран **Инструменты и поддержка**:
     - кнопка диагностики сети (`test` / `probe-routes`), очистка зависших системных прокси/состояний, сбор и экспорт `support-bundle` в выбранную папку + связь с [WindowsBugReportScreen](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/WindowsProductScreens.kt).
  4. Локализовать [NativeTray.kt](file:///C:/Users/iohan/Documents/.worktrees/gates-android-xray-sidecar/windows-app/src/main/kotlin/com/yokhan/vpn/windows/NativeTray.kt):
     - русские пункты меню: статус подключения, «Подключить» / «Отключить», выбор активного маршрута, «Открыть GATES», «Проверка связи», «Выход» (с корректной остановкой runtime и откатом системного прокси).
- **Критерий готовности (Acceptance):**
  - Все ключевые поля `WindowsAppConfig` настраиваются через UI и сохраняются с дебаунсом/валидацией, экраны логов и support-bundle работают без консоли, системный трей отражает текущий статус и умеет безопасно останавливать туннель.

---

## 5. План верификации и безопасного запуска

Для каждой волны перед переходом к следующей:
1. **Модульные тесты (без мутации системных путей Windows):**
   - `:shared-ui:allTests` (или общий check при изменениях в `YokhanRouteKit` / `GatesBottomNavigation`),
   - `:windows-app:test`,
   - `:windows-service:test`.
2. **Визуальная и поведенческая проверка UI:**
   - запуск `windows-app` в изолированном QA/offline-профиле без записи в `ProgramData\GATES` и без изменения реальных системных настроек прокси/TUN;
   - проверка drag-and-drop (файлы, ярлыки, папки, отмена наведения), переключений в Drawer/нижнем меню, ресайза окна.
3. **Защита от регрессий Android:**
   - если волна затрагивает `shared/ui`, обязательно прогонять `:app:testOssDebugUnitTest` и `:app:assembleOssDebug`.
