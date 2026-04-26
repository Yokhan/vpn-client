# Compose UI Migration Plan

## Target

YokhanVPN UI should be a Compose-first product UI. XML/View screens are legacy and must not receive new visual behavior or runtime theme adapters. Any new user-facing surface should use the shared Compose design system in `shared-ui`.

## Current State

- `shared-ui` contains the production dashboard composable and shared dashboard assets.
- `app` still uses XML for the main shell: `layout_main.xml`, `DrawerLayout`, `NavigationView`, legacy `StatsBar`, and `ServiceButton`.
- Main navigation still opens legacy fragments: configurations, groups, routes, settings, tools, logcat, about, backup, network, and webview.
- Preference screens still use `PreferenceFragmentCompat`, so they cannot follow the animated connected/disconnected Compose theme.
- Dashboard uses Compose for content, but it is still hosted by `DashboardFragment` through `layout_dashboard.xml`.

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
   Replace `layout_main.xml`/drawer/bottom stats/fab with a Compose shell that owns navigation, bottom status, snackbar host, and connected/disconnected theme animation.

3. Dashboard host cleanup.
   Move dashboard state and actions out of `DashboardFragment`, render `YokhanDashboardScreen` directly from the Compose shell, then delete the XML dashboard host.

4. Primary product screens.
   Port configurations, groups, routes, and settings hubs to Compose lists/cards using the shared design system.

5. Settings/edit flows.
   Replace `PreferenceFragmentCompat` and edit layouts with typed Compose forms.

6. Utility screens.
   Port tools, logcat, about, backup, network, scanner, app list, and webview wrappers.

7. Removal pass.
   Delete unused XML layouts, old renderers/widgets, and Matsuri/Neko UI references that are not needed by non-UI core logic.

## Legacy UI Inventory

- Main shell: `MainActivity`, `layout_main.xml`, `StatsBar`, `ServiceButton`.
- Dashboard host: `DashboardFragment`, `layout_dashboard.xml`, `DashboardViewRenderer`, `DashboardProfileListRenderer`, `DashboardOrbMotionController`.
- Navigation fragments: `ConfigurationHubFragment`, `ConfigurationFragment`, `GroupHubFragment`, `GroupFragment`, `RouteHubFragment`, `RouteFragment`, `SettingsFragment`, `ToolsFragment`, `LogcatFragment`, `AboutFragment`, `WebviewFragment`.
- Form/preference screens: `GroupSettingsActivity`, `RouteSettingsActivity`, `SettingsPreferenceFragment`, `SettingsAdvancedFragment`.
- Utility activities: `AppListActivity`, `AppManagerActivity`, `AssetsActivity`, `ScannerActivity`, `StunActivity`, `ProfileSelectActivity`, `SwitchActivity`.
