# Changelog

## 1.7.9 - 2026-11-07

- Removed the hardcoded Hub download channel (`https://app.yokhan.xyz/hub/gates`) from app update, About, route, and FAQ actions.
- App updates now query the update API by channel (`YOKHAN_UPDATE_CHANNEL`, default `stable`) and resolve the download URL from the API-provided `release_url` or the canonical `YOKHAN_APP_DOWNLOAD_URL` (`https://fitness.yokhan.xyz/download`).
- Update checks now read `latest_version`, `channel`, `release_url`, `asset.download_url`, `mandatory`, and `min_supported_version` from the release API, and respect mandatory updates.
- APKs are distributed via Telegram and the canonical download link instead of the retired Hub page.

## 1.6 - 2026-04-21

- Reworked the main app shell around a new Home dashboard with connection status, quick actions, active profile details, and live speed visibility.
- Split top-level navigation into dedicated hub screens for Configuration, Group, Route, and Settings to reduce the old overloaded entry flow.
- Added a structured Settings hub with direct controls for service mode, stealth mode, app routing, startup behavior, and advanced options.
- Added a broader manual profile add flow so advanced profile types are reachable without dropping into a single legacy entry point.
- Moved remaining service start, stop, reload, and preference bootstrap paths off the UI thread to reduce startup jank and shortcut/tile freezes.
- Fixed cross-process configuration sync so the background service picks up fresh profile and runtime flag changes reliably.
- Restored notification group-name resolution from the database instead of relying on process-local UI caches.
- Updated Android build bootstrap scripts for Windows and other host toolchains, and bumped the app version to 1.6.
