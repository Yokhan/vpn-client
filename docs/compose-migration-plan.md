# Compose UI Migration Plan

## Target

YokhanVPN UI should be a Compose-first product UI. XML/View screens are legacy and must not receive new visual behavior or runtime theme adapters. Any new user-facing surface should use the shared Compose design system in `shared-ui`.

## Current State

- `shared-ui` contains the production dashboard composable and shared dashboard assets.
- `app` now uses a Compose main shell with a programmatic `FragmentContainerView` for routes that are not migrated yet.
- Removed from the main shell: `layout_main.xml`, `DrawerLayout`, `NavigationView`, legacy `StatsBar`, `ServiceButton`, and `FabProgressBehavior`.
- Main navigation now opens Compose route screens for configuration list, group list, route list, route assets, webview/dashboard panel, settings hub, tools/backup/network, logcat, and about.
- The main navigation still uses a temporary fragment bridge. Edit forms and preference subroutes remain legacy until their typed Compose form parity is complete.
- Preference screens still use `PreferenceFragmentCompat`, so they cannot fully follow the animated connected/disconnected Compose theme, but their Android host views and custom dialog surfaces are now programmatic.
- Dashboard content is Compose-only. It is still hosted by `DashboardFragment`, and the fragment now creates its `ComposeView` programmatically.
- Yokhan-owned Compose shell, route kit, selectable-apps screen, dashboard adapter, settings host, password dialog/preference helpers, and Android UI entrypoints now live under `com.yokhan.vpn.app.ui.*` / `com.yokhan.vpn.app.widget.*`.
- Yokhan-owned preference UI widgets now live under `com.yokhan.vpn.app.ui.preference.*`; preference XML should not introduce new upstream-derived UI widget tags.
- Yokhan-owned profile settings activities, including AnyTLS, ShadowTLS, custom config, and shared profile preference binding helpers, now live under `com.yokhan.vpn.app.ui.profile.*`.
- Removed top-level legacy route layouts: `layout_configuration_hub.xml`, `layout_group_hub.xml`, `layout_route_hub.xml`, `layout_settings_hub.xml`, `layout_tools.xml`, `layout_about.xml`, `layout_logcat.xml`, `layout_backup.xml`, and `layout_network.xml`.
- Removed backup import/progress dialog layouts; `ToolsFragment` now renders backup import confirmation and progress dialogs through ComposeView.
- Removed XML settings host layouts for group, route, profile, chain, and advanced settings. These screens still use `PreferenceFragmentCompat`, but toolbar/container/list hosting is programmatic through `YokhanSettingsHost`.
- Removed config editor XML and custom keyboard item XML. `ConfigEditActivity` still uses the native editor widgets, but its toolbar/editor/keyboard host is now programmatic.
- Removed the remaining Android `res/layout` product files. Chain rows, move-group rows, dropdown rows, URL/link dialogs, help messages, and password input dialogs no longer depend on app layout XML.
- Detailed production planning lives in `docs/production-roadmap.md`.

## Migration Rules

- Do not add View-tree runtime recoloring or XML-specific design fixes.
- Do not create new XML layouts for product UI; `app/src/main/res/layout` is intentionally empty.
- Keep business logic separate from composables: database/service/profile operations belong in Android controllers/viewmodels, while visual tokens/components live in `shared-ui`.
- Shared tokens must live in `com.yokhan.vpn.shared.ui.design`.
- App-specific Compose adapters, product shell code, and UI preference widgets must live in `com.yokhan.vpn.app.ui.*`; do not add new UI code under upstream-derived namespaces.
- Android-specific string/resources adapters may stay in `app`, but visual styling must not be duplicated there.

## Execution Order

1. Shared Compose design system.
   Create common palette, typography, motion, and reusable primitives in `shared-ui`.

2. Compose main shell.
   Replace `layout_main.xml`/drawer/bottom stats/fab with a Compose shell that owns navigation, bottom status, snackbar host, and connected/disconnected theme animation. Initial shell replacement is complete; snackbar still uses a Material Snackbar attached to the fragment host until all host behavior is Compose-native.

3. Dashboard host cleanup.
   Move dashboard state and actions out of `DashboardFragment`, render `YokhanDashboardScreen` directly from the Compose shell, then delete the dashboard fragment host.

4. Primary product screens.
   Port configurations, groups, routes, and settings hubs to Compose lists/cards using the shared design system. Configuration, group, and route detail lists are now Compose; forms remain.

5. Settings/edit flows.
   Replace `PreferenceFragmentCompat` and preference XML resources with typed Compose forms.

6. Utility screens.
   Port tools, logcat, about, backup, network, scanner, app list, and webview wrappers. Tools, backup, network, logcat, about, route assets, scanner/STUN, app list/app manager, and webview are now Compose screens.

7. Removal pass.
   Delete unused XML layouts, old renderers/widgets, and Matsuri/Neko UI references that are not needed by non-UI core logic.

## Legacy UI Inventory

- Main shell: `MainActivity`, `YokhanMainShell`.
- Product UI package roots: `com.yokhan.vpn.app.ui`, `com.yokhan.vpn.app.ui.profile`, `com.yokhan.vpn.app.ui.compose`, `com.yokhan.vpn.app.ui.dashboard`, `com.yokhan.vpn.app.ui.shell`.
- Removed shell legacy widgets: `layout_main.xml`, `StatsBar`, `ServiceButton`, `FabProgressBehavior`, `main_drawer_menu.xml`.
- Dashboard host: `DashboardFragment`.
- Removed dashboard legacy renderers: `DashboardViewRenderer`, `DashboardProfileListRenderer`, `DashboardOrbMotionController`.
- Compose route fragments: `ConfigurationHubFragment`, `ConfigurationFragment`, `GroupHubFragment`, `GroupFragment`, `RouteHubFragment`, `RouteFragment`, `SettingsFragment`, `ToolsFragment`, `LogcatFragment`, `AboutFragment`.
- Legacy navigation/detail fragments still pending: none in the primary route-list layer; remaining legacy work is in preference-backed forms.
- Legacy Android layout XML retained for non-migrated detail flows: none.
- Form/preference screens: `GroupSettingsActivity`, `RouteSettingsActivity`, profile settings activities, `SettingsPreferenceFragment`, `SettingsAdvancedFragment`.
- Utility activities still pending full Compose parity: none in the app-list/scanner/STUN/profile-selection layer.
- Migrated utility activities: `AssetsActivity`, `ScannerActivity`, `StunActivity`, `AppListActivity`, `AppManagerActivity`, `ProfileSelectActivity`, `SwitchActivity`.
