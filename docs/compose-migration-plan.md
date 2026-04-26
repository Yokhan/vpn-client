# Compose UI Migration Plan

## Target

YokhanVPN UI should be a Compose-first product UI. XML/View screens are legacy and must not receive new visual behavior or runtime theme adapters. Any new user-facing surface should use the shared Compose design system in `shared-ui`.

## Current State

- `shared-ui` contains the production dashboard composable and shared dashboard assets.
- `app` now uses a Compose main shell with a programmatic `FragmentContainerView` for routes that are not migrated yet.
- Removed from the main shell: `layout_main.xml`, `DrawerLayout`, `NavigationView`, legacy `StatsBar`, `ServiceButton`, and `FabProgressBehavior`.
- Main navigation now opens Compose route screens for configuration hub, group hub, route hub, settings hub, tools/backup/network, logcat, and about.
- The main navigation still uses a temporary fragment bridge. Detailed lists, edit forms, app manager, scanner, assets, webview, and preference subroutes remain legacy until their route parity is complete.
- Preference screens still use `PreferenceFragmentCompat`, so they cannot follow the animated connected/disconnected Compose theme.
- Dashboard content is Compose-only. It is still hosted by `DashboardFragment`, but `layout_dashboard.xml` now contains only a `ComposeView`.
- Removed top-level legacy route layouts: `layout_configuration_hub.xml`, `layout_group_hub.xml`, `layout_route_hub.xml`, `layout_settings_hub.xml`, `layout_tools.xml`, `layout_about.xml`, `layout_logcat.xml`, `layout_backup.xml`, and `layout_network.xml`.
- Detailed production planning lives in `docs/production-roadmap.md`.

## Migration Rules

- Do not add View-tree runtime recoloring or XML-specific design fixes.
- Do not create new XML layouts for product UI.
- Keep business logic separate from composables: database/service/profile operations belong in Android controllers/viewmodels, while visual tokens/components live in `shared-ui`.
- Shared tokens must live in `com.yokhan.vpn.shared.ui.design`.
- Android-specific string/resources adapters may stay in `app`, but visual styling must not be duplicated there.

## Execution Order

1. Shared Compose design system.
   Create common palette, typography, motion, and reusable primitives in `shared-ui`.

2. Compose main shell.
   Replace `layout_main.xml`/drawer/bottom stats/fab with a Compose shell that owns navigation, bottom status, snackbar host, and connected/disconnected theme animation. Initial shell replacement is complete; snackbar still uses a Material Snackbar attached to the fragment host until all host behavior is Compose-native.

3. Dashboard host cleanup.
   Move dashboard state and actions out of `DashboardFragment`, render `YokhanDashboardScreen` directly from the Compose shell, then delete the dashboard fragment host.

4. Primary product screens.
   Port configurations, groups, routes, and settings hubs to Compose lists/cards using the shared design system. The first hub pass is complete; detailed lists/forms remain.

5. Settings/edit flows.
   Replace `PreferenceFragmentCompat` and edit layouts with typed Compose forms.

6. Utility screens.
   Port tools, logcat, about, backup, network, scanner, app list, and webview wrappers. Tools, backup, network, logcat, and about are now Compose route screens; scanner, app lists, assets, and webview remain.

7. Removal pass.
   Delete unused XML layouts, old renderers/widgets, and Matsuri/Neko UI references that are not needed by non-UI core logic.

## Legacy UI Inventory

- Main shell: `MainActivity`, `YokhanMainShell`.
- Removed shell legacy widgets: `layout_main.xml`, `StatsBar`, `ServiceButton`, `FabProgressBehavior`, `main_drawer_menu.xml`.
- Dashboard host: `DashboardFragment`, `layout_dashboard.xml`.
- Removed dashboard legacy renderers: `DashboardViewRenderer`, `DashboardProfileListRenderer`, `DashboardOrbMotionController`.
- Compose route fragments: `ConfigurationHubFragment`, `GroupHubFragment`, `RouteHubFragment`, `SettingsFragment`, `ToolsFragment`, `LogcatFragment`, `AboutFragment`.
- Legacy navigation/detail fragments still pending: `ConfigurationFragment`, `GroupFragment`, `RouteFragment`, `WebviewFragment`.
- Form/preference screens: `GroupSettingsActivity`, `RouteSettingsActivity`, `SettingsPreferenceFragment`, `SettingsAdvancedFragment`.
- Utility activities: `AppListActivity`, `AppManagerActivity`, `AssetsActivity`, `ScannerActivity`, `StunActivity`, `ProfileSelectActivity`, `SwitchActivity`.
