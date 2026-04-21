# Changelog

## 1.6 - 2026-04-21

- Reworked the main app shell around a new Home dashboard with connection status, quick actions, active profile details, and live speed visibility.
- Split top-level navigation into dedicated hub screens for Configuration, Group, Route, and Settings to reduce the old overloaded entry flow.
- Added a structured Settings hub with direct controls for service mode, stealth mode, app routing, startup behavior, and advanced options.
- Added a broader manual profile add flow so advanced profile types are reachable without dropping into a single legacy entry point.
- Moved remaining service start, stop, reload, and preference bootstrap paths off the UI thread to reduce startup jank and shortcut/tile freezes.
- Fixed cross-process configuration sync so the background service picks up fresh profile and runtime flag changes reliably.
- Restored notification group-name resolution from the database instead of relying on process-local UI caches.
- Updated Android build bootstrap scripts for Windows and other host toolchains, and bumped the app version to 1.6.
