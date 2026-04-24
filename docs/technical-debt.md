# Technical Debt

This file tracks concrete cleanup work that affects build reliability, UI quality, and future release speed.

## Highest Priority

- Dashboard UI state is too concentrated in `DashboardFragment`. Move visual state transitions into small view methods or custom views so layout, data loading, and animation state are not mixed in one class.
- `StarfieldView` and `OrbMarksView` currently hardcode design constants copied from `preview/dashboard.html`. Extract these into named constants and keep the source mapping in comments or docs.
- Dashboard layout uses many fixed `dp` values. Move repeated dimensions into `dimens.xml` so Android and web parity changes can be audited in one place.
- CI and Release workflows duplicate libcore/bootstrap steps. Extract shared setup into a composite action or reusable workflow once the release process is stable.
- Native core source defaults still fall back to `MatsuriDayo/*`. Full detachment requires Yokhan-owned forks or explicit `CORE_*_GIT_URL` repository variables.

## Process Rules

- Signed test APKs come from manual `CI` dispatch with `signed=true`.
- Production releases come from a version bump on `main` plus a fresh tag that matches `VERSION_NAME`.
- Existing tags are not reused for changed app code.
- Generated artifacts under `.tmp-artifacts/`, `app/build/`, and local image scratch files stay out of commits.
