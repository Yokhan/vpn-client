# Production Hardening Roadmap

## Назначение

Этот документ фиксирует рабочий порядок доведения YokhanVPN до продукта, который можно спокойно давать первым клиентам из доверенного круга. План не заменяет юридическую проверку и не обещает безопасный публичный запуск в любой юрисдикции.

## Главный принцип

Не копим новый долг поверх старого. Каждая следующая итерация должна либо:

- удалять legacy-поведение;
- делать пользовательский путь понятнее;
- улучшать проверяемость сборки;
- отделять новый Yokhan-код от fork-derived монолита;
- снижать риск случайной установки, неверного APK или неподдерживаемого состояния.

## Зависимости

```text
R0 План и границы
  -> R1 Единая дизайн-система и rocket scene
  -> R2 Клиентский landing с маршрутами
  -> R3 Onboarding и provider status в приложении
  -> R4 Support/diagnostics
  -> R5 Network Provider split
  -> R6 Private releases and updates
  -> R7 Legacy UI cleanup
  -> R8 Performance and battery pass
  -> R9 Source closure / rewrite path
  -> R10 Release candidate discipline
```

## R0. План и границы

Status: in progress.

Tasks:

1. Держать этот roadmap рядом с `docs/product-landing-provider-execution-plan.md`.
2. Не маскировать сетевой продукт под игру.
3. Не обещать обход, анонимность, универсальный доступ или юридическую безопасность.
4. Сохранять private invite-only как операционную модель, а не как юридическую защиту.
5. Не добавлять скрытую загрузку исполняемых модулей.
6. Разделять client copy, internal docs, release docs и legal/source notes.

Acceptance:

- Документы не противоречат друг другу.
- Public copy не содержит запрещенных обещаний.
- Установочный путь явно говорит про приложение, сетевой компонент, подпись и профиль.

## R1. Единая дизайн-система и rocket scene

Status: in progress.

Tasks:

1. Зафиксировать один набор размеров ракеты и пламени.
2. Держать CSS landing tokens и Compose tuning синхронно.
3. Оставить debug overlay в desktop preview.
4. Добавить видимую формулу позиционирования flame относительно rocket center.
5. Проверять, что flame не обрезается при disconnect.
6. Проверять, что rocket/flame улетают вместе.
7. Перенести движущиеся звезды в landing.
8. Оставить `prefers-reduced-motion`.

Acceptance:

- Landing и Compose используют одинаковые числа: `rocketWidth=132`, `rocketHeight=228`, `flameWidth=22`, `flameHeight=75`, `flameY=176`, `leftFlameX=-11`, `rightFlameX=11`.
- В desktop preview можно включить overlay и сделать screenshot.
- В landing rocket/flame используют app PNG assets.

## R2. Клиентский landing с маршрутами

Status: in progress.

Tasks:

1. Построить маршруты: себе, семье, другу, команде.
2. Для каждого маршрута показать 4 шага.
3. Для каждого маршрута показать риск, если шаг пропустить.
4. Для каждого маршрута дать support template.
5. Плашки привести к app-like cards: тонкая рамка, прозрачный фон, marker, без тяжелых заливок.
6. Проверить все интерактивные элементы.
7. Проверить 390px mobile width.
8. Проверить copy cleanliness.

Acceptance:

- Человек понимает, что делать именно в своем сценарии.
- Нет owner-facing copy.
- Нет assistant meta-copy.
- HTML открывается напрямую без сборки.

## R3. Onboarding и provider status в приложении

Status: in progress.

Tasks:

1. Завершить отображение states: monolith, external ready, missing, untrusted, outdated.
2. Сделать отдельный onboarding/status screen на Compose.
3. Добавить понятный first-run path.
4. Объяснить, зачем нужен сетевой компонент.
5. Не давать внешние публичные ссылки в runtime UI.
6. Блокировать untrusted provider.
7. Проверить no-profile state.
8. Добавить device smoke checklist.

Acceptance:

- Пользователь на первом запуске не попадает в пустоту.
- Bad signature показывает понятную причину.
- Missing provider не ломает текущие тестовые релизы.

## R4. Support/diagnostics

Status: pending.

Tasks:

1. Добавить support checklist в docs.
2. Добавить privacy-minimal diagnostics export plan.
3. Добавить copyable support message в app.
4. Разделить ошибки: no config, no provider, untrusted provider, no permission, connection failed.
5. Не собирать лишние персональные данные.

Acceptance:

- Тестер может прислать скрин/status/model/action без технической расшифровки.

## R5. Network Provider split

Status: pending.

Tasks:

1. Создать `android-network-provider` application module.
2. Перенести `VpnService`, service lifecycle, native bridge.
3. Оставить shell как controller/onboarding UI.
4. Подписывать provider стабильным ключом.
5. Проверять package name и certificate SHA-256.
6. Сохранить monolith fallback на один-два тестовых релиза.
7. Потом отключить fallback в production flavor.

Acceptance:

- Shell APK и Provider APK собираются отдельно.
- Fresh install, missing provider, untrusted provider, connect/disconnect проходят device QA.

## R6. Private releases and updates

Status: pending.

Tasks:

1. Разделить release artifacts: Shell, Provider.
2. Публиковать SHA-256 APK.
3. Публиковать signing certificate SHA-256.
4. Добавить private update manifest.
5. Не делать silent install.
6. Документировать rollback.

Acceptance:

- Пользователь может сверить файл.
- Поддержка понимает, какой APK у человека установлен.

## R7. Legacy UI cleanup

Status: pending.

Tasks:

1. Удалять старые пользовательские экраны по одному маршруту.
2. Переводить оставшиеся панели на Compose/design system.
3. Удалять старые package mentions из user-facing UI.
4. Оставлять юридические notices там, где они обязательны.
5. Не ломать existing profiles.

Acceptance:

- Нет задвоенных нижних меню.
- Нет старых экранов поверх нового shell.
- Навигация предсказуема.

## R8. Performance and battery pass

Status: pending.

Tasks:

1. Замерить jank dashboard animations.
2. Проверить battery impact starfield/rocket.
3. Уменьшить overdraw.
4. Добавить baseline profile только после стабилизации навигации.
5. Проверить app resume и scroll-to-top.

Acceptance:

- Dashboard не дергается на тестовом телефоне.
- Resume не дает beige screen/old UI flash.

## R9. Source closure / rewrite path

Status: pending.

Tasks:

1. Составить карту GPL-derived частей.
2. Разделить новый Yokhan-owned код и inherited code.
3. Подготовить rewrite plan для closed-source будущего.
4. Не заявлять закрытый source для текущих GPL-derived binaries.

Acceptance:

- Понятно, что можно закрывать, а что пока нет.

## R10. Release candidate discipline

Status: pending.

Tasks:

1. Любой тестовый release идет через CI.
2. Перед release: local check, CI, artifact checksum, smoke checklist.
3. После release: ссылка, SHA-256, known limitations.
4. Не выкладывать артефакт без понятного install path.

Acceptance:

- Тестер получает installable signed APK и знает, что проверять.
