# Technical Debt

This file tracks concrete cleanup work that affects build reliability, UI quality, and future release speed.

## Highest Priority

- Dashboard UI state is still owned by `DashboardFragment`, but the XML ComposeView host is gone and connection-state rendering, orbital motion, and profile list rendering are split into smaller units. Profile select/edit/menu actions are wired; next step is device-verified visual parity, not another blind refactor.
- New Android dashboard releases use the shared Compose UI and hide the legacy global `StatsBar`/FAB shell.
- `StarfieldView` and `OrbMarksView` still carry copied web star/mark coordinates, but the runtime constants are now named and source-mapped to `preview/dashboard.html`.
- Dashboard layout still has fixed spacing in secondary sections, but the central launch scene dimensions now live in `dimens.xml`.
- `ConfigurationFragment` is now a Compose route for the active group/profile list. Chain editor rows are now programmatic and no longer use `layout_profile.xml` or `layout_add_entity.xml`.
- `GroupFragment` is now a Compose route. Profile move/group selection rows are now programmatic and no longer use `layout_group_item.xml`.
- `RouteFragment` is now a Compose route. Route edit forms still need their own Compose migration pass.
- `AssetsActivity` is now Compose and no longer uses the route assets RecyclerView/XML/menu resources.
- `WebviewFragment` is now a Compose wrapper around Android `WebView`; `layout_webview.xml` and `yacd_menu.xml` are removed.
- `ScannerActivity` and `StunActivity` are now Compose-hosted utility screens. The scanner still embeds the native camera preview/viewfinder through `AndroidView`, but its XML layout and menu are gone.
- `AppListActivity` and `AppManagerActivity` now share a Compose app-selection screen; their RecyclerView adapters, item layout, app-list layouts, placeholder layout, and menu XML are removed.
- `ProfileSelectActivity` and `SwitchActivity` no longer use `layout_empty.xml`; they create a `FragmentContainerView` host programmatically for the Compose configuration selector.
- `ToolsFragment` backup import confirmation/progress dialogs now use ComposeView and no longer require `layout_import.xml` or `layout_progress.xml`.
- Removed unused legacy layouts with no remaining binding or resource references: old dashboard profile item, debug, edit-group, icon-list, loading, and progress-list layouts.
- Group, route, profile, chain, and advanced settings no longer depend on XML host layouts; `YokhanSettingsHost` creates the toolbar/settings/list container programmatically. The forms themselves still use `PreferenceFragmentCompat` and preference XML.
- Yokhan-owned app UI code is no longer declared under upstream-derived UI namespaces: route fragments/activities, Compose route kit, shell, dashboard Compose adapter, settings host, edit-text dialog, and password preference live under `com.yokhan.vpn.app.*`.
- Dashboard connection test now has an explicit running state in shared domain/UI state, disables duplicate taps while testing, resets on disconnect/view destroy, and shows transient latency as `...`.
- Dashboard resume now sends a Compose scroll-to-top signal on repeated `onResume`, so returning from background gently restores the top of the scrollable main screen.
- Preference/helper UI classes now live under `com.yokhan.vpn.app.ui.preference.*`; XML preference tags and Android imports point to Yokhan namespace.
- Profile settings entrypoints for AnyTLS, ShadowTLS, and custom config now live under `com.yokhan.vpn.app.ui.profile.*`; proxy beans/formatters remain in their fork-derived namespaces until a safe data/core migration exists.
- Reflection-backed profile preference binding helpers now live under `com.yokhan.vpn.app.ui.profile.binding.*`, so Yokhan-owned profile form code no longer imports the Matsuri binding namespace.
- `ConfigEditActivity` no longer uses `layout_edit_config.xml`, and `ExtendedKeyboard` no longer uses `item_keyboard_key.xml`; the editor remains native but the host and key rows are programmatic.
- `app/src/main/res/layout` is now empty. Dropdown rows, help messages, URL/link edit dialogs, and password edit dialogs are programmatic or use typed preference classes.
- Remaining UI migration debt is no longer layout XML; it is preference XML/form architecture and fragment ownership.
- CI and Release workflows duplicate libcore/bootstrap steps. Extract shared setup into a composite action or reusable workflow once the release process is stable.
- Native core source defaults still fall back to `MatsuriDayo/*`. Full detachment requires Yokhan-owned forks or explicit `CORE_*_GIT_URL` repository variables.
- Closed-source transition and upstream cleanup constraints are tracked in `docs/source-closure-and-cleanup.md`.

## Process Rules

- Signed test APKs come from manual `CI` dispatch with `signed=true`.
- Production releases come from a version bump on `main` plus a fresh tag that matches `VERSION_NAME`.
- Existing tags are not reused for changed app code.
- Generated artifacts under `.tmp-artifacts/`, `app/build/`, and local image scratch files stay out of commits.
