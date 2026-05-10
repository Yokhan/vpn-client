# Rocket Shell / Network Provider Plan

## Назначение

Этот документ фиксирует рабочую архитектуру разделения продукта на две части:

1. `Rocket Shell` - пользовательский интерфейс, onboarding, настройки, обновления, диагностика и поддержка.
2. `Network Provider` - отдельный Android-пакет, который владеет сетевым runtime, `VpnService`, native core и подключением.

Разделение делается ради поддержки, безопасности, обновляемости и постепенного выхода из fork-derived монолита. Это не механизм маскировки продукта и не попытка обходить требования площадок, законов или согласия пользователя.

## Жесткая граница

- Shell не должен скрытно скачивать, включать или запускать сетевой модуль.
- Shell не должен называться игрой, если пользовательский сценарий фактически включает защищенный сетевой туннель.
- Shell может использовать ракету, запуск, орбиту и прогресс как визуальную метафору бренда.
- Provider должен быть подписан, версионирован и проверяем по package name и SHA-256 сертификата.
- Пользователь должен понимать, что устанавливает отдельный сетевой компонент и зачем он нужен.
- Private distribution снижает публичную экспозицию, но не превращает GPL-derived или VPN-функциональность в закрытый unrelated-продукт.

## Текущее состояние

Сейчас Android APK остается монолитом: UI, настройки, `VpnService`, native core и profile storage живут в одном приложении.

Добавленный scaffold:

- `YokhanNetworkProviderContract` задает будущий package/action/extra contract.
- `YokhanNetworkProviderDetector` умеет найти внешний provider APK или текущий монолит.
- Внешний provider считается trusted только после настройки SHA-256 сертификата сборки.
- Текущий монолит временно считается trusted provider, чтобы не ломать существующие релизы.

## Целевая Android-схема

1. Shell APK:
   - Compose UI;
   - onboarding;
   - профиль пользователя;
   - проверка установленного provider;
   - проверка подписи provider;
   - запуск permission flow;
   - отправка команд provider через стабильный contract;
   - отображение статуса, скорости, ошибок и диагностики.

2. Provider APK:
   - `VpnService`;
   - foreground service;
   - native core;
   - protocol adapters;
   - local config import/validation;
   - connection state stream;
   - minimal UI только для системных permission/diagnostic случаев.

3. Shared modules:
   - `shared-domain` хранит платформенно-нейтральные state models;
   - `shared-ui` хранит дизайн-систему и общие Compose-компоненты;
   - provider API должен быть описан отдельно от экранов и Android singletons.

## Pipeline миграции

1. Зафиксировать contract и detector в монолите.
2. Добавить экран onboarding/provider state: `готов`, `не установлен`, `не доверен`, `устарел`.
3. Настроить release signing fingerprint для будущего provider APK через `YOKHAN_PROVIDER_CERT_SHA256`.
4. Вынести `VpnService`, `ProxyService`, foreground notification и native core bridge в новый Android module/application.
5. Оставить в Shell только controller layer и UI.
6. Перевести команды connect/disconnect/test на contract вместо прямого вызова `SagerNet.startServiceAsync()`.
7. Сделать совместимость с текущим монолитом на один-два тестовых релиза.
8. После device QA отключить monolith fallback в production flavor.
9. Удалить устаревшие plugin/download UI хвосты, которые ведут пользователя в неподконтрольные публичные источники.
10. Задокументировать install/update flow для закрытого круга пользователей.

## Минимальный UX для первого запуска

1. Пользователь устанавливает Shell APK.
2. Shell показывает состояние сетевого компонента.
3. Если provider отсутствует, Shell объясняет, что нужен отдельный сетевой модуль, и предлагает инструкцию установки из trusted private channel.
4. После установки Shell проверяет package name, version и certificate SHA-256.
5. Если provider не доверен, Shell блокирует запуск и показывает короткую диагностическую причину.
6. Если provider готов, пользователь импортирует или получает профиль доступа и запускает подключение.

## Release требования

- Shell и Provider подписываются стабильными release keys.
- Для каждого артефакта публикуются version, SHA-256 APK и SHA-256 signing certificate.
- Shell не доверяет внешнему provider без совпадения signing certificate.
- GitHub Release или private channel должны явно различать Shell APK и Provider APK.
- Перед релизом проверяются fresh install, update, provider missing, provider untrusted, connect, disconnect, reconnect after app resume.

## Следующие инженерные задачи

1. Подключить detector к onboarding/settings diagnostic UI.
2. Добавить provider status в shared-domain.
3. Добавить Compose экран установки/проверки provider без внешних публичных ссылок.
4. Создать отдельный `android-network-provider` application module.
5. Перенести service code в provider module без изменения пользовательского поведения.
6. Сделать contract tests для package/signature detection.
7. Прогнать device QA до отключения monolith fallback.
