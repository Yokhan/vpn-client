# YokhanVPN Production Roadmap

This is the working plan for moving YokhanVPN from a partially migrated Android app to a production-grade Compose-first product. The plan has exactly 20 top-level tracks and stays below the requested 509 total plan items.

## 01. Product Contract

- [ ] Define the supported product platforms: Android first, desktop preview for UI validation, later iOS and Windows production targets.
- [ ] Document the non-goals for the current phase: no new XML product UI, no View-tree recoloring, no duplicate screen implementations.
- [ ] Define the minimum supported Android versions and device classes for UI validation.
- [ ] Define the acceptance bar for a test release: installable signed APK, deterministic CI artifact, dashboard parity, no legacy dashboard fallback.
- [ ] Define the acceptance bar for a production release: full Compose shell, migrated core screens, release notes, reproducible build, rollback path.
- [ ] Keep product naming consistent as YokhanVPN / BUFF IT GATES only where intentionally exposed.
- [ ] Track every remaining Matsuri/Neko reference and classify it as core dependency, inherited implementation detail, or removable UI debt.
- [ ] Keep the Russian commercial, invite-only, and compliance boundary plan current in `docs/commercial-readiness-and-risk-plan.md`.
- [ ] Keep the shell/provider split plan current in `docs/rocket-shell-provider-plan.md`.

## 02. Architecture Boundaries

- [ ] Keep platform-neutral state models in `shared-domain`.
- [ ] Keep shared visual components and tokens in `shared-ui`.
- [ ] Keep Android service, database, permissions, and intents in `app`.
- [ ] Introduce Android presentation controllers/viewmodels for screen state loading.
- [ ] Prevent composables from directly reading Android singletons such as `DataStore`.
- [ ] Prevent UI components from starting services directly; actions should flow through host interfaces.
- [ ] Replace mixed Fragment/View/Compose ownership with a single Compose shell owner.
- [ ] Split the Android product into a Compose shell and a signed network provider only after the contract, signature checks, and migration fallback are stable.
- [ ] Keep the current monolith as the temporary trusted provider until the external provider APK passes device QA.

## 03. Shared Design System

- [ ] Expand `com.yokhan.vpn.shared.ui.design` beyond palette, typography, and motion.
- [ ] Add reusable cards, section headers, dividers, icon buttons, status dots, and bottom bars.
- [ ] Add role-based colors for connected, disconnected, connecting, warning, error, and disabled states.
- [ ] Add spacing, radius, border, and elevation tokens instead of local hardcoded values.
- [ ] Add shared touch target rules for mobile and desktop preview.
- [ ] Add explicit typography roles for title, mono label, mono value, body, caption, and action.
- [ ] Add component previews for all reusable primitives.
- [ ] Keep dashboard-specific tuning separate from global design tokens.

## 04. Compose Main Shell

- [x] Replace `layout_main.xml` with an Android `setContent` entry point.
- [x] Replace `DrawerLayout` and `NavigationView` with a Compose navigation surface.
- [x] Remove legacy `StatsBar` from the main shell.
- [x] Remove legacy `ServiceButton` and `fabProgress` from the main shell.
- [ ] Move snackbar ownership into Compose with a single snackbar host.
- [ ] Handle system insets in Compose, not in XML appbar listeners.
- [x] Make connected/disconnected theme transitions global to the shell.
- [x] Keep back navigation behavior equivalent to current Fragment logic until the old fragments are gone.
- [ ] Replace the temporary fragment host bridge after each route is migrated.

## 05. Dashboard Completion

- [ ] Move dashboard data loading out of `DashboardFragment`.
- [ ] Render `YokhanDashboardScreen` directly from the Compose shell.
- [ ] Delete `DashboardFragment` once the shell can own dashboard state.
- [ ] Verify rocket sizing and flame placement against desktop reference screenshots.
- [ ] Keep rocket and flame animation as one scene that flies away together.
- [ ] Keep starfield mounted until disconnect animation finishes.
- [ ] Verify connection test action is clickable and returns latency visibly.
- [ ] Verify profile selector, edit, and menu actions are wired to real operations.

## 06. Navigation Model

- [ ] Replace menu item ids with a typed route model.
- [ ] Define routes for dashboard, configurations, groups, routes, settings, tools, logcat, about, scanner, and profile edit flows.
- [ ] Define route arguments as typed data instead of raw bundle keys where possible.
- [ ] Preserve deep link import behavior for subscriptions and profiles.
- [ ] Preserve VPN permission flow behavior.
- [ ] Preserve external browser/custom tab actions.
- [ ] Add route-level analytics hooks only after the UI migration is stable.

## 07. Configuration Screens

- [x] Port configuration hub to Compose cards/lists.
- [x] Port active group/profile list to Compose.
- [x] Port profile selection interactions to Compose.
- [x] Port profile add/import/export actions to Compose entry points.
- [x] Replace the main configuration RecyclerView item layouts with Compose list rows.
- [x] Verify selector profiles change and reload service when needed at compile/build level; device smoke test is still required.
- [x] Add empty/loading/search states for configuration data.
- [x] Keep QR/export menus functional during migration.

## 08. Group Screens

- [x] Port group hub to Compose.
- [x] Port group list to Compose.
- [ ] Port group create/edit/delete to typed Compose forms.
- [x] Port subscription refresh controls.
- [ ] Preserve subscription import confirmation.
- [ ] Preserve group selection persistence.
- [x] Add visible progress state for subscription update.
- [x] Remove group list XML layouts after route parity.

## 09. Route Screens

- [x] Port route hub to Compose.
- [x] Port route list to Compose.
- [ ] Port route edit forms to Compose.
- [ ] Preserve route import/export behavior.
- [ ] Preserve unsaved changes confirmation.
- [ ] Add validation messages inline.
- [x] Preserve route deletion confirmation.
- [x] Remove route XML layouts after parity.

## 10. Settings Screens

- [ ] Replace `PreferenceFragmentCompat` settings with typed Compose screens.
- [ ] Port core settings first: service mode, proxy apps, bypass mode, individual apps, stealth mode.
- [ ] Port advanced settings after the main settings route is stable.
- [ ] Preserve settings that require service reload and show Compose apply action.
- [x] Remove custom preference dialog layout XML and replace it with programmatic preference dialogs/classes.
- [ ] Add searchable settings only after full parity.
- [ ] Remove preference XML resources after migration.
- [ ] Make theme/state changes immediate across settings screens.

## 11. Utility Screens

- [x] Port tools hub to Compose.
- [x] Port logcat screen to Compose with stable large-text performance.
- [x] Port about screen to Compose and remove inherited branding noise.
- [x] Port backup/import screens to Compose.
- [x] Port backup import/progress dialogs to Compose.
- [x] Port network/debug screens to Compose if still product-relevant.
- [x] Port route assets management to Compose.
- [x] Port webview/dashboard panel wrapper to Compose.
- [x] Port scanner host shell while keeping camera/scanner implementation stable.
- [x] Port STUN test utility to Compose.
- [x] Port app list/app manager UI to Compose.
- [x] Delete remaining utility XML layouts after profile select parity.

## 12. Forms And Validation

- [ ] Create shared form primitives: text field, select, switch, segmented control, chip, validation row.
- [ ] Define validation result models in domain or app presentation layer.
- [ ] Make invalid states visible without relying on Toast/Snackbar only.
- [ ] Preserve keyboard navigation and IME actions.
- [ ] Preserve unsaved changes guards.
- [ ] Add loading/disabled states for async form operations.
- [ ] Avoid reflection or raw preference keys in new UI forms.

## 13. State Management

- [ ] Introduce screen state holders for dashboard, configurations, groups, routes, and settings.
- [ ] Use flows for database/service state observation.
- [ ] Avoid repeated refresh calls caused by fragment lifecycle churn.
- [ ] Centralize connection state mapping from Android service to shared domain.
- [ ] Centralize current profile/group summary mapping.
- [ ] Add cancellation-safe async operations for imports, updates, tests, and reloads.
- [ ] Add deterministic error models for user-facing errors.

## 14. Performance

- [ ] Measure dashboard recomposition count during speed updates.
- [ ] Avoid full dashboard model replacement when only speed/timer changes.
- [ ] Keep animated starfield and rocket cheap on low-end Android devices.
- [ ] Use stable immutable UI models.
- [ ] Avoid unnecessary bitmap reloads for rocket/flame assets.
- [ ] Verify R8 release output after Compose migration.
- [ ] Keep desktop preview startup fast enough for visual iteration.
- [ ] Add baseline profiles only after major navigation is stable.

## 15. Animation Quality

- [ ] Keep motion timings sourced from `YokhanMotion`.
- [ ] Match web reference timings before inventing Android-specific alternatives.
- [ ] Make connect and disconnect animations interrupt-safe.
- [ ] Make rocket, flames, space theme, and starfield transitions coordinated.
- [ ] Keep disabled/button animations consistent with the design system.
- [ ] Add desktop preview controls for key animation states.
- [ ] Capture reference screenshots for connected and disconnected states.
- [ ] Avoid abrupt alpha cuts before movement finishes.

## 16. Visual QA

- [ ] Keep reference screenshots under `preview/desktop/references`.
- [ ] Keep generated screenshots under `preview/desktop/build/screenshots`.
- [ ] Compare dashboard against the known web reference before Android release.
- [ ] Validate Android screenshots on at least one narrow and one tall device.
- [ ] Validate typography assets in APK and desktop preview.
- [ ] Validate transparent cards, divider opacity, and bottom safe-area spacing.
- [ ] Validate text truncation for long profile/group names.
- [ ] Add automated screenshot tests only after layout stabilizes.

## 17. CI/CD

- [x] Fix branch push CI trigger by making the `push.branches` filter explicit in `.github/workflows/ci.yml`.
- [ ] Keep manual CI dispatch available for signed and unsigned APK builds.
- [ ] Keep release workflow tag-driven and workflow-dispatch capable.
- [ ] Publish deterministic artifacts with clear architecture names.
- [ ] Verify signed artifacts with `apksigner` before release publication.
- [ ] Add a lightweight UI build check for desktop preview.
- [ ] Cache libcore and Gradle dependencies without hiding source changes.
- [x] Update GitHub Actions from Node 20-backed actions before runner removal becomes blocking.

## 18. Release Engineering

- [ ] Define version bump rules for test releases and production releases.
- [ ] Keep release notes tied to user-visible changes and migration risk.
- [ ] Keep unsigned local artifacts separate from signed GitHub release artifacts.
- [ ] Preserve rollback path to previous release tag.
- [ ] Verify install/update behavior on Android after every signed release.
- [ ] Keep artifacts downloadable from GitHub Releases, not only Actions.
- [ ] Document exact commands for local verification.
- [ ] Ensure private/closed-source transition tasks do not break reproducible internal builds.
- [ ] Publish Shell APK and Provider APK as separate, clearly labeled private artifacts once the split is enabled.

## 19. Codebase Cleanup

- [x] Delete app `res/layout` XML after replacing remaining chain rows, dialogs, dropdown rows, and hosts.
- [ ] Delete old widgets after shell removal: `StatsBar`, `ServiceButton`, old appbar styling.
- [ ] Remove unused drawables/dimens tied only to deleted XML UI.
- [ ] Remove stale imports and inherited comments that no longer describe YokhanVPN.
- [ ] Rename packages only after functional migration is stable.
- [ ] Keep core VPN/service code changes separate from UI migration commits.
- [ ] Run dependency cleanup after screens are migrated.
- [ ] Track remaining legacy files with owner and removal condition.

## 20. Production Readiness

- [ ] Define release-blocker bug categories.
- [ ] Add smoke test checklist for connect, disconnect, reload, import, selector, edit, route, settings, and backup.
- [ ] Add crash/log review before publishing a test APK.
- [ ] Verify battery and thermal behavior during dashboard animation.
- [ ] Verify accessibility labels for primary actions.
- [ ] Verify offline/error states.
- [ ] Verify localization strings and mojibake risks.
- [ ] Ship only after CI, local build, and manual visual QA agree.
