# Stabilization And Refactor Plan

## Goal

Bring the app from a working Compose migration into a production-clean state: stable lifecycle behavior, predictable dashboard animations, clear first-run setup, shared UI primitives, and less legacy coupling.

## Current Bug Themes

1. Dashboard and route surfaces must show the light OFF palette as a deliberate disconnected signal, without uncontrolled beige flashes on resume.
2. Rocket/flame alignment is still too hard to tune because the preview does not expose enough measurable controls.
3. First install and empty profile state do not explain the next action clearly enough.
4. Some UI state transitions are lifecycle-sensitive and need explicit contracts instead of incidental recomposition.
5. Legacy Android resources and inherited namespaces still create maintenance noise.

## Workstreams

1. Dashboard lifecycle stability: keep OFF/ON theme changes explicit across resume, foreground/background, connect, disconnect, and timer updates.
2. Rocket scene tuning: expose flame width, X/Y offsets, and debug overlay in desktop preview; only commit tuned constants after visual comparison.
3. First-run setup: make empty profile state actionable from the dashboard, then improve import/manual setup screens.
4. UI state contracts: route/window colors follow the service-state palette, while dialogs and platform-only surfaces stay on a stable dark palette unless they receive explicit state.
5. Route UI cleanup: continue replacing local dimensions, shapes, and card behaviors with shared primitives.
6. Navigation cleanup: keep all new flows on typed routes and remove remaining legacy fragment assumptions where safe.
7. Performance pass: avoid remapping lists or reloading resources during timer/speed hot updates.
8. Preview and QA: desktop preview must reproduce connecting/disconnecting/resume states and export screenshots with visible tuning values.
9. Release process: keep every stage buildable with `:shared-domain:check`, `:desktop-preview:classes`, and `:app:assembleOssRelease`.
10. Source cleanup: remove product-owned references to inherited package names only after confirming they are not core/data compatibility points.

## Immediate Patch Scope

1. Restore the light OFF palette as a deliberate disconnected state while avoiding uncontrolled resume flashes.
2. Add first-run dashboard copy and CTA when there are no profiles.
3. Add desktop preview controls for flame width and a rocket/flame debug overlay.
4. Document the new tuning workflow so offsets can be reported as exact values, not screenshots alone.
5. Remove light `dashboard_bg_off` usage from app UI hosts so legacy screens cannot flash beige.

## Current Execution Pass

1. Restore service-state palette switching for dashboard, shell, and route frames; keep standalone dialogs/forms on stable dark surfaces until they have an explicit state owner.
2. Make the dashboard connection-test control clickable in idle states so the user gets an explicit "not connected" result instead of a dead button.
3. Remove remaining product UI class references from inherited namespaces where the change is route/resource-safe.
4. Verify shared UI/domain and Android compilation before release work.
5. Keep rocket/flame launch timing and z-order aligned with the web source so the flame is not hidden during arrival.

## Rocket Tuning Workflow

1. Run the desktop preview and keep the phone viewport in Dashboard mode.
2. Enable `Rocket overlay: on`; cyan marks the rocket bounds and center, orange marks both flame bounds.
3. Adjust `Flame width`, `Flame height`, `Flame Y`, `Left flame X`, and `Right flame X` until the flame visually sits under the nozzles.
4. Press `Save phone screenshot` and compare it with the reference screenshot.
5. Send or commit the displayed `DashboardUiTuning(...)` line; the production default constants should be updated only from that exact line.

## Acceptance

1. Returning to the app shows either the deliberate light OFF state or the dark ON state, with no uncontrolled beige flash.
2. The preview panel shows exact rocket/flame tuning values and can render an overlay for alignment.
3. Empty install tells the user how to add/import a profile.
4. App shell and route frames follow OFF/ON palette state; standalone settings hosts and dialogs keep a dark design-system surface until explicit state is wired in.
5. Build verification passes before every push.
