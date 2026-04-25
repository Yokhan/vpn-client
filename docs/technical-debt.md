# Technical Debt

This file tracks concrete cleanup work that affects build reliability, UI quality, and future release speed.

## Highest Priority

- Dashboard UI state is still owned by `DashboardFragment`, but connection-state rendering, orbital motion, and profile list rendering are now split into smaller units. Profile select/edit/menu actions are wired; next step is device-verified visual parity, not another blind refactor.
- New Android dashboard releases use the shared Compose UI and hide the legacy global `StatsBar`/FAB shell. The old XML service chrome remains in the tree only for fallback/debug paths and must not be shown in the Yokhan shell.
- `StarfieldView` and `OrbMarksView` still carry copied web star/mark coordinates, but the runtime constants are now named and source-mapped to `preview/dashboard.html`.
- Dashboard layout still has fixed spacing in secondary sections, but the central launch scene dimensions now live in `dimens.xml`.
- CI and Release workflows duplicate libcore/bootstrap steps. Extract shared setup into a composite action or reusable workflow once the release process is stable.
- Native core source defaults still fall back to `MatsuriDayo/*`. Full detachment requires Yokhan-owned forks or explicit `CORE_*_GIT_URL` repository variables.
- Closed-source transition and upstream cleanup constraints are tracked in `docs/source-closure-and-cleanup.md`.

## Process Rules

- Signed test APKs come from manual `CI` dispatch with `signed=true`.
- Production releases come from a version bump on `main` plus a fresh tag that matches `VERSION_NAME`.
- Existing tags are not reused for changed app code.
- Generated artifacts under `.tmp-artifacts/`, `app/build/`, and local image scratch files stay out of commits.
