# Rewrite Strategy

The current app should be treated as a legacy transition base. The goal is a faster, cleaner Yokhan-owned Android client with a controlled bridge to the existing VPN core while GPL-derived app code is replaced.

## Decision

Use Kotlin as the primary Android app language and move the new UI to Jetpack Compose. Do not rewrite the Android shell in Flutter or React Native for this product.

Rationale:

- Kotlin is the native Android path and fits VPN service, notification, background, storage, and permission work with the least integration overhead.
- Compose gives a clean way to replace the XML/Fragment dashboard and settings flows without carrying the current view hierarchy debt forward.
- Flutter can build attractive UI quickly, but the VPN/service/native boundary would move complexity into platform channels and make Android-specific behavior harder to debug.
- React Native is a poor fit for a VPN-heavy app with persistent services, native networking, and strict lifecycle behavior.
- Rust is useful for new owned core libraries, parsing, policy, or crypto-adjacent utilities, but not as the main Android UI layer.
- Go should remain only where it is already the practical boundary for sing-box/libcore integration.

## Target Architecture

Create a clean app shell and keep the existing fork behind explicit adapters until it is replaced.

Modules or package boundaries:

- `app-shell`: Android entry points, Compose navigation, permissions, notifications, and top-level lifecycle.
- `design-system`: Yokhan tokens, typography, animation primitives, dashboard components, and reusable controls.
- `vpn-domain`: pure Kotlin models, use cases, state machines, and interfaces for profile selection, connection state, traffic stats, and diagnostics.
- `vpn-runtime`: implementation adapter over the current service/libcore stack.
- `profiles-data`: repositories, migrations, subscription import, export, and profile persistence.
- `legacy-bridge`: temporary wrappers around existing GPL-derived classes. New code must depend on interfaces, not directly on legacy classes.

Dependency rule:

`app-shell -> design-system -> vpn-domain -> interfaces`

Implementations such as `vpn-runtime`, `profiles-data`, and `legacy-bridge` bind at composition/root level. UI code must not import legacy service/database classes directly.

## Migration Plan

1. Freeze the current app as `legacy`.

- Stop broad refactors inside legacy fragments unless they unblock a release bug.
- Keep CI/release working for current users.
- Do not delete attribution or license files while legacy code ships.

2. Start a new owned app shell.

- Use a Yokhan-owned package namespace for new code.
- Add Compose with a small dashboard route first.
- Keep the current service and profile store behind adapters.

3. Rebuild the dashboard cleanly.

- Recreate the verified web/mobile visual system in Compose.
- Replace XML layout, custom view motion, and fragment glue with one screen state model.
- Acceptance gate: rocket/flames/stars/speed/timer/profile actions must match device screenshots before release.

4. Extract domain interfaces.

- Define `VpnController`, `ProfileRepository`, `TrafficStatsSource`, `ConnectionTester`, and `ReleaseDiagnostics`.
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

Primary fixes:

- Replace Fragment/View churn with stable Compose state.
- Move profile and stats reads off the main thread.
- Avoid native core initialization before it is needed.
- Use one connection state stream rather than scattered observers.
- Keep animation assets pre-sized and avoid scaling transparent canvases at runtime.
- Remove duplicate settings/profile transformations.

## Release Gates

No release from the rewrite branch unless:

- local debug build passes
- GitHub CI passes
- installable signed artifact is produced by CI/CD
- dashboard screenshot is checked on a real Android device
- profile select, edit, menu, connect, disconnect, test connection, timer, speed, stars, rocket, and flames are verified
- GPL/compliance notes are updated for any removed or replaced legacy code

## Immediate Next Step

Create a rewrite branch with the new package boundaries and a Compose dashboard prototype that reads from adapters. Keep current `main` releasable while the rewrite grows behind a separate branch.
