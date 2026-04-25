# Rewrite Strategy

The current app should be treated as a legacy transition base. The goal is a faster, cleaner Yokhan-owned Android client with a controlled bridge to the existing VPN core while GPL-derived app code is replaced.

## Decision

Use Kotlin Multiplatform for shared product logic, platform-native VPN runtimes for each operating system, and a Rust-owned performance core where it provides measurable benefit. Do not rewrite the VPN product as a Flutter or React Native app.

Rationale:

- Kotlin Multiplatform lets the product share domain logic across Android, iOS, Windows, and later macOS/Linux without forcing one runtime model onto all platforms.
- Compose Multiplatform is viable for shared UI on Android and desktop. iOS can use either Compose Multiplatform for product screens or SwiftUI where native feel and NetworkExtension integration are more important.
- Flutter can build attractive UI quickly, but the VPN/service/native boundary would move complexity into platform channels and make Android/iOS/Windows tunnel behavior harder to debug.
- React Native is a poor fit for a VPN-heavy app with persistent services, native networking, and strict lifecycle behavior.
- Rust is the right candidate for owned hot-path code: config parsing, policy evaluation, routing rules, diagnostics, crypto-adjacent utilities, and cross-platform controller logic. Bind it into Kotlin/Swift/Windows through generated FFI instead of hand-written JNI everywhere.
- Go should remain only where it is already the practical boundary for sing-box/libcore integration during the migration.

Official references used for this decision:

- Kotlin Multiplatform official docs: https://kotlinlang.org/docs/multiplatform/ios-android-app-development.html
- Android Developers KMP guidance: https://developer.android.com/kotlin/multiplatform
- Compose Multiplatform FAQ: https://kotlinlang.org/docs/multiplatform/faq.html
- UniFFI Swift bindings: https://mozilla.github.io/uniffi-rs/0.28/swift/overview.html

## Target Architecture

Create a clean multiplatform product shell and keep the existing fork behind explicit adapters until it is replaced.

Modules or package boundaries:

- `shared-domain`: Kotlin Multiplatform models, use cases, state machines, validation, profile selection, connection state, telemetry contracts, and diagnostics contracts.
- `shared-ui`: Compose Multiplatform design system, tokens, typography, animation primitives, dashboard components, and reusable controls.
- `core-rust`: owned high-performance Rust library for config parsing, policy/routing evaluation, diagnostics, and deterministic state transitions.
- `android-app`: Android entry points, `VpnService`, notification/service lifecycle, permissions, and Android-specific storage.
- `ios-app`: Swift/SwiftUI shell, NetworkExtension integration, keychain/app group storage, and iOS lifecycle.
- `windows-app`: Windows shell, installer/update path, tray/service integration, TUN/Wintun adapter, and OS-specific permissions.
- `runtime-adapters`: Android/iOS/Windows implementations over sing-box or the chosen tunnel core.
- `profiles-data`: multiplatform repositories, migrations, subscription import/export, and profile persistence interfaces.
- `legacy-bridge`: temporary wrappers around existing GPL-derived classes. New code must depend on interfaces, not directly on legacy classes.

Dependency rule:

`platform app -> shared-ui -> shared-domain -> interfaces`

Implementations such as `runtime-adapters`, `profiles-data`, `core-rust`, and `legacy-bridge` bind at composition/root level. UI code must not import legacy service/database classes directly.

## Platform Targets

Android:

- Keep current app releasable while the new shell grows.
- Replace XML/Fragment dashboard with Compose.
- Keep `VpnService` native to Android; do not abstract it into a fake cross-platform layer.

iOS:

- Build a separate iOS shell around shared domain/UI where possible.
- VPN runtime must use Apple's NetworkExtension model. Treat this as a first-class platform adapter, not a port of Android service code.
- Keychain and app group storage are platform-specific.

Windows:

- Build a desktop shell from shared UI/domain where it is stable enough.
- Tunnel/runtime adapter must own Windows service/tray integration and TUN/Wintun lifecycle.
- Installer, auto-update, and logs are Windows-specific product surfaces.

Shared:

- Profile model, subscription parsing, policy evaluation, diagnostics, telemetry formatting, connection state machine, and dashboard state mapping.
- No Android classes, SwiftUI classes, Windows APIs, or GPL-derived classes in shared domain.

## Migration Plan

1. Freeze the current app as `legacy`.

- Stop broad refactors inside legacy fragments unless they unblock a release bug.
- Keep CI/release working for current users.
- Do not delete attribution or license files while legacy code ships.

2. Start a new owned multiplatform shell.

- Use Yokhan-owned namespaces for new code.
- Add Kotlin Multiplatform modules for shared domain and shared UI.
- Add Compose with a small dashboard route first on Android and desktop.
- Keep the current service and profile store behind adapters.

3. Rebuild the dashboard cleanly.

- Recreate the verified web/mobile visual system in Compose.
- Replace XML layout, custom view motion, and fragment glue with one screen state model.
- Acceptance gate: rocket/flames/stars/speed/timer/profile actions must match device screenshots before release.

4. Extract domain interfaces and Rust FFI seams.

- Define `VpnController`, `ProfileRepository`, `TrafficStatsSource`, `ConnectionTester`, and `ReleaseDiagnostics`.
- Define which APIs are Kotlin-only and which move to Rust.
- Generate bindings instead of maintaining platform FFI manually.
- Add test fixtures for disconnected, connecting, connected, error, and no-profile states.
- Make selectors and menu actions testable without Android view IDs.

5. Replace settings and profile flows.

- Move high-use flows first: profile list, active profile switch, import, edit, diagnostics, app bypass.
- Keep low-use upstream settings behind a legacy route until there is product need to rewrite them.

6. Remove legacy code by feature area.

- Track each legacy package as `kept`, `wrapped`, `rewritten`, or `removed`.
- Only after a feature has no copied GPL-derived implementation should it be considered proprietary-owned.

## Performance Plan

Baseline before each migration step:

- cold start time to dashboard
- time from connect tap to service state transition
- frame time and jank on dashboard animation
- memory after 60 seconds connected
- APK size by ABI
- database calls on main thread
- CPU wakeups and polling intervals while connected
- battery drain during 30 minutes idle connected
- tunnel restart latency
- Rust/Kotlin/adapter boundary overhead for hot paths

Primary fixes:

- Replace Fragment/View churn with stable Compose state.
- Move profile and stats reads off the main thread.
- Avoid native core initialization before it is needed.
- Use one connection state stream rather than scattered observers.
- Keep animation assets pre-sized and avoid scaling transparent canvases at runtime.
- Remove duplicate settings/profile transformations.
- Move deterministic hot-path parsing/policy work into Rust only after benchmarks prove it beats simple Kotlin.
- Prefer push/state-stream updates over timers and polling.
- Keep background services quiet: no unnecessary wake locks, no busy loops, no repeated DB reads for unchanged state.

## Release Gates

No release from the rewrite branch unless:

- local debug build passes
- GitHub CI passes
- installable signed artifact is produced by CI/CD
- dashboard screenshot is checked on a real Android device
- profile select, edit, menu, connect, disconnect, test connection, timer, speed, stars, rocket, and flames are verified
- GPL/compliance notes are updated for any removed or replaced legacy code
- performance baseline is captured and compared against the previous release
- iOS/Windows plans are not allowed to import Android-only assumptions into shared code

## Immediate Next Step

The first shared-domain KMP module now exists at `shared/domain/`, is checked in CI before Android APK builds, and is consumed by the Android dashboard through `DashboardStateAdapter`.

Speed and latency now flow through shared value contracts while Android keeps localized string rendering.

The first Android renderer split is also in place: `DashboardViewRenderer` owns connection-state theme application, while `DashboardFragment` keeps lifecycle, data loading, timers, and user actions.

Profile list rendering now consumes explicit presentation items instead of database entities.

Active profile header rendering is now owned by `DashboardViewRenderer` through an explicit presentation model.

Dashboard click wiring is now owned by `DashboardViewRenderer.Actions`.

Dashboard host interactions now go through `DashboardHost`, including connection actions, configuration navigation, stats-bar protocol updates, and dashboard messages.

The current XML dashboard visual regressions have an interim fix: rocket/flame dimensions are restored closer to the verified mobile mock, the bottom green glow is reduced, starfield invalidation is made explicit while connected, profile-row ripple no longer paints a solid row background, and the bottom stats bar uses the bundled mono font with corrected runtime icons.

The first Compose dashboard renderer now exists under `app/src/main/java/io/nekohasekai/sagernet/ui/dashboard/compose/`. It is intentionally isolated from `MainActivity`, database classes, fragments, and service bindings: it accepts `DashboardState`, explicit profile presentation items, and callbacks. The production route still uses XML until the Compose screen passes device and screenshot parity checks. Build a test variant with `YOKHAN_COMPOSE_DASHBOARD=1` or `-PyokhanComposeDashboard=true` to route the dashboard through Compose.

The Android Compose route is now wired behind `BuildConfig.ENABLE_COMPOSE_DASHBOARD`. Normal APKs still use the legacy XML route. A test APK can enable Compose with `-PyokhanComposeDashboard=true` or `YOKHAN_COMPOSE_DASHBOARD=1`.

A Windows-runnable Compose Desktop preview now exists at `preview/desktop/`. It uses shared-domain dashboard state and the shared dashboard Compose screen from `shared/ui`, so layout, transparency, stars, profile rows, status controls, and animation direction are no longer duplicated between Android and desktop preview.

The Android Compose adapter now only supplies Android strings and callbacks. The desktop preview and Android Compose route both consume shared Compose UI resources for the bundled fonts, rocket PNG, and flame PNG.

Next step: use the desktop preview for fast visual iteration, then validate the same Compose route on a real Android device before making it the default. Keep current `main` releasable while the rewrite grows behind explicit adapters.
