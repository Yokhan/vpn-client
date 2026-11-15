# GATES — Platform Parity & Release Synchronization Policy

## Default Rule: Simultaneous Paired Releases

Every product release, feature, security fix, routing change, and diagnostics update MUST ship simultaneously for both **Windows** and **Android** at the same version number (`1.9.0` baseline).

A platform-specific change is permitted **only** when the user explicitly requests a single-platform exception (e.g., Windows-only hotfix or Android-only hotfix).

## Mandatory Divergence Tracking & Auto-Reconciliation

Whenever an explicit user exception introduces temporary drift between Windows and Android:

1. Record the divergence in [`docs/parity-manifest.json`](parity-manifest.json) under `"divergences"` with:
   - `id`: short identifier (e.g. `wise-tree-bootstrap-2026-11`)
   - `platform`: platform that received the change (`"windows"` or `"android"`)
   - `missingOn`: target platform pending reconciliation (`"android"` or `"windows"`)
   - `summary`: what changed and what contract must be ported
   - `waivedByUser`: `true` (only valid if explicitly authorized by the user)
   - `createdAt`: ISO-8601 date
   - `reconcileTarget`: `"next-unified-release"` (or exact next version)
2. During any subsequent development turn or next release preparation:
   - Any agent working in this repository MUST read `docs/parity-manifest.json` and run `pwsh -File tools/Test-PlatformParity.ps1` / `powershell -ExecutionPolicy Bypass -File tools/Test-PlatformParity.ps1`.
   - If `"divergences"` is non-empty, that drift MUST be automatically pulled into the lagging platform, verified with platform unit tests + shared tests, merged into the unified release branch, and cleared from `"divergences"` before tagging a unified release.

## Shared Parity Contract Checks (`tools/Test-PlatformParity.ps1`)

The automated parity validator verifies:
- `nb4a.properties` `VERSION_NAME` == `windows-app/build.gradle.kts` `windowsPackageVersion` == `WindowsRuntimeController.kt` `WINDOWS_APP_VERSION` == `docs/parity-manifest.json` `unifiedVersion` (unless an explicit waived divergence documents version split).
- Core product capabilities exist across both Windows and Android implementations (Wise Tree bootstrap claim, RU bypass / app split routing, Whole Traffic compliance policy, Xray XHTTP sidecar, signed v2 manifest, parallelized diagnostics/telemetry, protection/Kill Switch status visibility).
- Consistent Yokhan / Igor Nazarov (`iohanrace@gmail.com`) authorship/ownership credits in product docs and user-facing strings while keeping required `docs/third-party-notices.md` and GPL-3.0 notices intact.
- Zero unresolved divergences when `-Strict` is enabled (used on unified release builds and CI).
