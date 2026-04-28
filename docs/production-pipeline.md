# YokhanVPN Production Pipeline

This is the execution pipeline for turning the current transition codebase into a production-grade YokhanVPN app. Use it as the working cursor: move top-to-bottom, update the current cursor after meaningful progress, and do not open unrelated refactor fronts unless a blocker forces it.

## Execution Rules

- Keep `main` buildable after every commit.
- Do not reuse tags after app code changes.
- Do not add product UI XML or View-tree visual fixes.
- Do not move database/entity/core namespaces until a data migration plan exists.
- Keep UI refactors separate from VPN core/service changes.
- Verify with local build before push.
- Use GitHub Release for downloadable APKs; workflow artifacts are not reliable while repository artifact quota is exhausted.
- Device screenshots decide visual parity; desktop preview is a fast iteration tool, not final proof.

## Current Cursor

`P08-navigation-ownership`: replace legacy menu/fragment routing with typed route ownership.

## Pipeline

### P01. CI And Release Baseline

- Remove CI artifact upload paths that are blocked by GitHub artifact quota.
- Run official GitHub actions on Node 24-backed major versions.
- Keep release APK publishing direct to GitHub Releases.
- Keep libcore build/cache verification inside jobs that need Gradle.
- Document that testable APKs come from release tags or local builds until artifact quota is fixed.
- Add a lightweight CI path for compile/check without attempting artifact storage.
- Acceptance: local build passes, GitHub CI passes without quota annotations, release workflow remains tag-driven.

### P02. Compose Form Kit

- Create shared Compose form primitives for text, password, number, port, switch, select, category, help, and validation rows.
- Define form state, dirty state, validation state, disabled/loading state, and save/cancel contracts.
- Keep Android-specific persistence adapters in `app`, not `shared-ui`.
- Avoid reflection and raw preference key access in new forms.
- Acceptance: one migrated form can use the kit without `PreferenceFragmentCompat`.

### P03. First Profile Form Migration

- Migrate a small representative profile form first, preferably custom config or Shadowsocks.
- Preserve existing `ProfileSettingsActivity` save/load behavior.
- Preserve dirty-state and subscription edit restrictions.
- Preserve password summaries, port validation, and IME behavior.
- Acceptance: migrated screen builds, opens from add/edit flows, saves data, and does not use preference XML.
- Completed: `ConfigSettingActivity` now renders with the shared Compose form kit and `config_preferences.xml` is deleted.

### P04. Profile Form Scaling

- Migrate WireGuard, AnyTLS, ShadowTLS, SOCKS, HTTP, Trojan, Trojan-Go, TUIC, Naive, Mieru, Hysteria, VMess, and VLESS forms using the same form kit.
- Remove migrated `*_preferences.xml` resources one by one.
- Keep protocol-specific logic in small adapters.
- Acceptance: no migrated profile screen uses `PreferenceFragmentCompat` or preference XML.
- Completed: profile edit screens now render with Compose form primitives; profile-specific preference XML files are removed.

### P05. Group And Route Forms

- Migrate group create/edit forms to Compose.
- Migrate route edit forms to Compose.
- Preserve import/export, delete confirmation, unsaved changes guard, and service reload rules.
- Acceptance: group/route settings no longer depend on preference XML.
- Completed: group and route edit screens now render with the shared Compose form kit; route keeps app/profile/config editor actions without preference XML.

### P06. App Settings Forms

- Migrate main settings and advanced settings out of `PreferenceFragmentCompat`.
- Preserve service mode, proxy apps, bypass, individual app selection, stealth mode, DNS/routing toggles, and reload-required feedback.
- Make connected/disconnected theme changes immediate.
- Acceptance: settings screens follow Compose design system and no longer use preference XML.
- Completed: advanced app settings now render through the shared Compose form kit; `SettingsPreferenceFragment` and `global_preferences.xml` are removed.

### P07. Dashboard Ownership

- Move dashboard state loading/actions out of `DashboardFragment`.
- Render dashboard directly from `YokhanMainShell`.
- Delete `DashboardFragment` and the temporary dashboard fragment bridge.
- Preserve connect/disconnect, selector, edit, profile menu, connection test, timer, speed, stars, rocket, flames, and scroll-to-top.
- Acceptance: dashboard is shell-owned Compose and device smoke test passes.
- Completed: dashboard state/actions are owned by `DashboardController`, `YokhanMainShell` renders dashboard directly for home, and `DashboardFragment` is removed.

### P08. Navigation Ownership

- Replace menu item IDs and fragment bridge with typed routes.
- Progress: shell drawer selection and migrated hub cross-links now use `YokhanShellRoute`; old `nav_*` ID resources are removed.
- Progress: child list/settings transitions now use `YokhanShellSubRoute`; hub screens no longer instantiate sub-fragments directly.
- Define route args for dashboard, configurations, groups, routes, settings, tools, logcat, about, scanner, and profile edit.
- Preserve back navigation and deep-link/import flows.
- Acceptance: main shell navigation no longer depends on legacy fragment routing except platform-only wrappers if any remain.

### P09. Visual System Consolidation

- Move repeated colors, spacing, radius, typography, dividers, and card styles into shared tokens/components.
- Normalize transparent cards, bottom safe-area spacing, status rows, and action buttons.
- Keep dashboard tuning separate from global tokens.
- Acceptance: new screens compose from shared components with minimal local styling.

### P10. Animation And Performance Pass

- Make connect/disconnect animation interrupt-safe.
- Keep rocket and flames as one coordinated scene.
- Measure dashboard recomposition during speed/timer updates.
- Avoid repeated bitmap loads and full model replacement for hot updates.
- Acceptance: dashboard remains smooth on Android device and desktop preview.

### P11. Legacy Resource Cleanup

- Delete preference XML after each migrated form.
- Delete drawables/dimens/styles that only supported removed XML UI.
- Delete old widgets and bridge classes after shell ownership is complete.
- Acceptance: resource tree has no dead product UI assets.

### P12. Namespace And Source Cleanup

- Continue moving product-owned UI code under `com.yokhan.vpn.app.*`.
- Classify remaining `io.nekohasekai` and `moe.matsuri` references as core dependency, inherited data model, or removable debt.
- Do not remove legal attribution while GPL-derived code remains.
- Acceptance: UI layer is Yokhan-owned; core/data namespace migration has a separate safe plan.

### P13. Native Core Ownership

- Replace `MatsuriDayo/*` fallback source defaults with Yokhan-owned forks or explicit CI variables.
- Pin native core inputs.
- Keep reproducible libcore build validation.
- Acceptance: CI no longer silently falls back to upstream-owned native repos.

### P14. Cross-Platform Preparation

- Keep shared UI/domain code platform-neutral where realistic.
- Keep desktop preview as the fast visual harness.
- Document what must be rewritten or abstracted before iOS/Windows production targets.
- Acceptance: Android work does not block future desktop/iOS work with unnecessary Android-only UI coupling.

### P15. Test And QA Harness

- Maintain a manual smoke checklist for install, launch, connect, disconnect, selector, edit, connection test, settings, import, route, backup, and resume.
- Add screenshot references for dashboard states.
- Add automated tests only after UI contracts stabilize.
- Acceptance: release candidate has a repeatable QA path.

### P16. Release Hardening

- Keep version bump rules explicit.
- Verify signed artifacts before publication when signing secrets are available.
- Keep release notes tied to user-visible changes and migration risk.
- Maintain rollback path to last known good tag.
- Acceptance: test release can be produced without hand-fixing CI every time.

### P17. Production Readiness

- Define release-blocker bug categories.
- Verify battery/thermal behavior of animated dashboard.
- Verify accessibility labels for primary actions.
- Verify localization and mojibake risks.
- Acceptance: product can be tested as a production candidate, not just a migration build.

## Next Action Queue

- [x] P01-CI-cleanup: remove CI artifact upload and update process docs.
- [x] P02-form-kit-skeleton: add Compose form models and primitives.
- [x] P03-first-form: migrate the first profile form.
- [x] P04-profile-form-scaling: migrate remaining profile forms.
- [x] P05-route-form: migrate route/group edit forms after profile form pattern is proven.
- [x] P06-app-settings-forms: migrate app/global settings screens to Compose.
- [x] P07-dashboard-owner: remove `DashboardFragment` ownership after forms are not blocking navigation.
- [ ] P08-navigation-ownership: replace legacy fragment route IDs with typed shell routes.
