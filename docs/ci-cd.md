# CI/CD Reference

This document describes the current build and release flow for `Yokhan/YokhanVPN`.

## Goals

The pipeline is designed to solve four separate problems:

1. build APK artifacts on every active branch
2. generate signed APKs for manual phone testing without local keystore handling
3. publish GitHub Releases from `Yokhan/YokhanVPN`, not from upstream repos
4. make native source repos configurable so the project can be detached from `MatsuriDayo/*`

## Workflows

### `CI`

File: `.github/workflows/preview.yml`

Triggers:

- `push` on branches
- `pull_request`
- `workflow_dispatch`

What it does:

- builds `libcore`
- runs Gradle build
- uploads APK artifacts

Manual inputs:

- `build_task`
  - `app:assemblePreviewRelease`
  - `app:assembleOssRelease`
- `signed`
  - `false`: unsigned branch artifact
  - `true`: signed branch artifact using repo secrets

Use this workflow for branch testing.

### `Release`

File: `.github/workflows/release.yml`

Triggers:

- `push` of tags like `v1.6.4`
- `push` of tags like `1.6.4`
- `workflow_dispatch`

What it does:

- prepares release metadata
- builds signed OSS release APKs
- optionally builds Play bundle
- optionally publishes a GitHub Release in `Yokhan/YokhanVPN`

Manual inputs:

- `tag`
- `publish`
- `play`
- `draft`

Use this workflow for actual release publication.

## Required GitHub Secrets

These are needed for signed builds and releases:

- `KEYSTORE_BASE64`
  - base64-encoded signing keystore
- `KEYSTORE_PASS`
  - keystore password
- `ALIAS_NAME`
  - key alias
- `ALIAS_PASS`
  - alias password
- `LOCAL_PROPERTIES`
  - optional extra local properties blob used by the existing build bootstrap

Without these secrets:

- branch artifacts can still be built unsigned
- signed CI builds and release publication will not work correctly

## Optional GitHub Repository Variables

These remove the hardcoded dependency on `MatsuriDayo/*` for native source fetches.

- `CORE_SING_BOX_REPO`
- `CORE_SING_BOX_GIT_URL`
- `CORE_LIBNEKO_REPO`
- `CORE_LIBNEKO_GIT_URL`
- `CORE_GOMOBILE_REPO`
- `CORE_GOMOBILE_GIT_URL`
- `CORE_GOMOBILE_REF`

Resolution order in scripts:

1. explicit `*_GIT_URL`
2. repo name like `owner/name`
3. fallback default

Current fallback defaults:

- `MatsuriDayo/sing-box`
- `MatsuriDayo/libneko`
- `MatsuriDayo/gomobile`
- `master2` for gomobile ref

## Branch Testing Flow

Recommended flow for testing on a phone:

1. Push your branch.
2. Open GitHub Actions.
3. Run `CI`.
4. Set:
   - `build_task=app:assembleOssRelease`
   - `signed=true`
5. Wait for the run to finish.
6. Download the uploaded APK artifact.
7. Install the `arm64-v8a` APK on a normal modern Android phone.

Notes:

- artifact name is `apk-<run_number>`
- the APK path inside the artifact is under `app/build/outputs/apk/...`
- if you already have an app installed with a different signing key, Android will reject the update

## Release Flow

Recommended production flow:

1. Merge the target branch into `main`.
2. Create and push a release tag, for example `v1.6.4`.
3. `Release` starts automatically.
4. The workflow builds signed APKs.
5. If publication is enabled, it creates or updates a GitHub Release in `Yokhan/YokhanVPN`.

Manual release flow:

1. Open Actions.
2. Run `Release`.
3. Fill:
   - `tag`
   - `publish=true`
   - `play=false` unless you need AAB
   - `draft=true` if you want a draft first

## Current Native Build Wiring

The following scripts were made configurable:

- `buildScript/lib/core/get_source.sh`
- `libcore/init.sh`

What changed:

- source repos are no longer hardcoded only in the workflow layer
- scripts now accept repo overrides from environment/repo variables
- fallback behavior was preserved so existing builds do not break immediately

This means:

- CI/CD can already publish from `Yokhan/YokhanVPN`
- full native detachment from Matsuri requires either your own forks or direct replacement URLs

## Known Caveats

- `setup-go` cache is wired to `libcore/go.sum`; this avoids noisy cache warnings from root-level Go detection
- GitHub Actions may still show Node 20 deprecation warnings from third-party actions; these do not currently break builds
- if no custom core repo variables are set, native source fetch still falls back to Matsuri repos
- Play bundle build remains optional and separate from the standard APK path

## Minimal Admin Checklist

For this pipeline to keep working later, verify these once in GitHub:

1. `Actions` are enabled for `Yokhan/YokhanVPN`.
2. the signing secrets listed above exist and are valid
3. `main` is the default branch
4. release tags are pushed in the main repo
5. if you want full upstream independence, set custom `CORE_*` repo vars

## Practical Commands

Trigger CI manually with GitHub CLI:

```bash
gh workflow run "CI" -R Yokhan/YokhanVPN --ref feat/dashboard-redesign -f build_task=app:assembleOssRelease -f signed=true
```

Trigger Release manually:

```bash
gh workflow run "Release" -R Yokhan/YokhanVPN --ref main -f tag=v1.6.4 -f publish=true -f play=false -f draft=false
```

Watch a run:

```bash
gh run watch <run-id> -R Yokhan/YokhanVPN
```

Download an artifact:

```bash
gh run download <run-id> -R Yokhan/YokhanVPN -n <artifact-name> -D ./artifact-out
```
