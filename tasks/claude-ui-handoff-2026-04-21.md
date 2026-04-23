# UI Handoff for Claude

Date: 2026-04-21

## Current State

- Branch: `main`
- HEAD: `4e310b994d060d1d1edf2adc442048bb8414a440`
- `git status -sb`: `## main...origin/main`
- Latest published release: `1.6.3`
- GitHub Release: `https://github.com/Yokhan/YokhanVPN/releases/tag/1.6.3`
- Last successful release workflow: `24730893292`

## What Was Already Done

### 1.6 UI shell / UX foundation

- New Home/dashboard is in `app/src/main/java/io/nekohasekai/sagernet/ui/DashboardFragment.kt`
- Configuration now has a hub screen: `app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationHubFragment.kt`
- Settings now have a hub screen: `app/src/main/java/io/nekohasekai/sagernet/ui/SettingsFragment.kt`
- Advanced settings were split out: `app/src/main/java/io/nekohasekai/sagernet/ui/SettingsAdvancedFragment.kt`
- Group and route hub screens were added:
  - `app/src/main/java/io/nekohasekai/sagernet/ui/GroupHubFragment.kt`
  - `app/src/main/java/io/nekohasekai/sagernet/ui/RouteHubFragment.kt`
- Profile add flow was moved into `app/src/main/java/io/nekohasekai/sagernet/ui/ProfileAddController.kt`
- `MainActivity` now uses a cleaner shell with nested navigation for sub-fragment flows

### Runtime / architecture fixes

- Critical main-thread Room access was removed from the main runtime path
- `RoomPreferenceDataStore` was hardened for safer cross-process config behavior
- `BaseService` now refreshes config before `reload()` and `start`
- Service actions and parts of bootstrap were moved away from blocking calls onto async paths
- Fixed the regression where the service process could read stale `selectedProxy` and runtime flags
- Notification title now resolves correctly again instead of relying on a UI-only cache

### Repo / release hygiene

- The repo was cleaned up from tracked local Claude config
- Hygiene rules for line endings and local-only files were added
- Version was bumped to `1.6.3` in `nb4a.properties`

## Key Commits

- `a263a9d` `Release 1.6 UI shell and startup fixes`
- `165720b` `Harden repo hygiene for local Claude config`
- `5db25ce` merge feature branch into `main`
- `0a931f3` `Fix positional formatting warnings in strings`
- `00e76de` `Bump version to 1.6.3`
- `4e310b9` `Restore release signing in GitHub workflow`

## Important UI Files

### Fragments

- `app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt`
- `app/src/main/java/io/nekohasekai/sagernet/ui/DashboardFragment.kt`
- `app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationHubFragment.kt`
- `app/src/main/java/io/nekohasekai/sagernet/ui/SettingsFragment.kt`
- `app/src/main/java/io/nekohasekai/sagernet/ui/SettingsAdvancedFragment.kt`
- `app/src/main/java/io/nekohasekai/sagernet/ui/GroupHubFragment.kt`
- `app/src/main/java/io/nekohasekai/sagernet/ui/RouteHubFragment.kt`
- `app/src/main/java/io/nekohasekai/sagernet/ui/ProfileAddController.kt`

### Layouts

- `app/src/main/res/layout/layout_dashboard.xml`
- `app/src/main/res/layout/layout_configuration_hub.xml`
- `app/src/main/res/layout/layout_settings_hub.xml`
- `app/src/main/res/layout/layout_group_hub.xml`
- `app/src/main/res/layout/layout_route_hub.xml`

## Best Next UI Work

### Main remaining legacy debt

- The biggest remaining legacy UI block is still `app/src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt`
- If Claude is going to do deeper UI cleanup, this is the main target

### Reasonable next steps

- Split `ConfigurationFragment` into narrower flows:
  - profile list
  - bulk actions
  - import / test / utilities
- Unify editor flow for profile/group/route screens
- Continue visual polish on top of the new hub screens, not on top of the old drawer-first flow
- Verify back stack, restore state, and empty/error states on new internal transitions

## Release / signing warning

- Do not break the current release flow in `.github/workflows/release.yml`
- Release signing now depends on these repo secrets:
  - `KEYSTORE_BASE64`
  - `KEYSTORE_PASS`
  - `ALIAS_NAME`
  - `ALIAS_PASS`
- The workflow now restores `release.keystore`; without that step CI will publish `*-unsigned.apk` again
- Final `1.6.3` release was re-published with signed APKs and without the `-unsigned` suffix

## For The Next Agent

- Base branch for continued work: `main`
- Start from the new shell and hub screens, not from the old `tasks/current.md`
- If a new release is needed after UI changes, verify version bump, then release workflow, then arm64 install on device
