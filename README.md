# YokhanVPN

Private Android VPN client for the Yokhan project. The current production tree is still based on the NekoBox / sing-box Android stack while the app is being migrated toward a cleaner Yokhan-owned architecture.

## What This Repo Is

This repository is the Android client for the Yokhan VPN stack.

Repository visibility is private. This is operational access control, not a license conversion for binaries built from the current GPL-derived code.

Main changes relative to upstream:

- dashboard and core navigation were reworked into the current Yokhan Android design system
- SOCKS5 inbound is off by default to reduce localhost proxy exposure
- `exclude_package` is applied in generated sing-box config, not only in UI
- subscription updates are automated through WorkManager
- Russian app bypass defaults are bundled into the client

## Current State

- package: `com.buffitgates.vpnservice`
- current version: `1.6.9`
- main Android app module: `app/`
- native core wrapper: `libcore/`
- metadata and versioning: `nb4a.properties`

## Local Build

Prerequisites:

- Android SDK
- Android NDK
- JDK 17
- Go 1.25-compatible toolchain for native `libcore` rebuilds

Quick debug build:

```bash
./gradlew app:assembleOssDebug
```

Release build:

```bash
./gradlew app:assembleOssRelease
```

Important:

- cold checkout is not just `./gradlew assembleRelease`
- native `libcore` build pulls sibling sources for `sing-box` and `libneko`
- prepare CI-like build assets with `./run init action gradle`
- rebuild native core with `./run lib core`

Release signing is externalized. Local release signing is picked up from `local.properties` or environment:

- `KEYSTORE_PATH`
- `KEYSTORE_PASS`
- `ALIAS_NAME`
- `ALIAS_PASS`

## CI/CD

The repo now has two GitHub Actions workflows:

- `CI` in `.github/workflows/ci.yml`
- `Release` in `.github/workflows/release.yml`

Use cases:

- every branch and PR gets a CI build artifact
- manual branch builds can produce signed test APKs
- tagged builds or manual dispatch can publish GitHub Releases from this repo

Short version:

1. Push branch changes.
2. Run `CI` with `build_task=app:assembleOssRelease` and `signed=true` if you need an installable APK for testing.
3. Download the artifact from the run.
4. When ready, bump `nb4a.properties`, merge to `main`, push a release tag, and let `Release` publish APKs in `Yokhan/YokhanVPN`.

Full setup and operations are documented in [docs/ci-cd.md](docs/ci-cd.md).
Current cleanup priorities are tracked in [docs/technical-debt.md](docs/technical-debt.md).
Source-closure constraints and the cleanup plan are tracked in [docs/source-closure-and-cleanup.md](docs/source-closure-and-cleanup.md).
Rewrite architecture and language decisions for Android, iOS, Windows, Kotlin Multiplatform, and Rust-owned hot paths are tracked in [docs/rewrite-strategy.md](docs/rewrite-strategy.md).
Bundled asset notices are tracked in [docs/third-party-notices.md](docs/third-party-notices.md).

## Detaching From Matsuri

Android app publishing is now repo-owned in `Yokhan/YokhanVPN`.

The remaining native-source defaults still point to Matsuri forks unless overridden.

To fully detach native build sources, set GitHub repository variables or environment values for:

- `CORE_SING_BOX_REPO` or `CORE_SING_BOX_GIT_URL`
- `CORE_LIBNEKO_REPO` or `CORE_LIBNEKO_GIT_URL`
- `CORE_GOMOBILE_REPO` or `CORE_GOMOBILE_GIT_URL`
- `CORE_GOMOBILE_REF`

If these are not set, build scripts fall back to:

- `MatsuriDayo/sing-box`
- `MatsuriDayo/libneko`
- `MatsuriDayo/gomobile`

## Repo Layout

- `app/` - Android client
- `shared/domain/` - Kotlin Multiplatform shared domain models and dashboard state mapping
- `libcore/` - Go mobile bridge and native bindings
- `buildScript/` - build bootstrap and native build scripts
- `.github/workflows/` - CI and release automation
- `preview/` - web/dashboard design reference used during Android redesign
- `.claude/rules/` - local project and design-system notes

## Related

- server/bot project: `../YokhanIndependentVPN/`
- upstream Android base: `https://github.com/MatsuriDayo/NekoBoxForAndroid`

## License

GPL-3.0, inherited from upstream.
