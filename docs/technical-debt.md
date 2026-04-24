# Technical Debt

This file tracks concrete cleanup work that affects build reliability, UI quality, and future release speed.

## Highest Priority

- Dashboard UI state is still owned by `DashboardFragment`, but connection-state rendering, orbital motion, and profile list rendering are now split into smaller units. Next step: wire real profile edit/menu actions through renderer callbacks instead of TODO listeners.
- `StarfieldView` and `OrbMarksView` still carry copied web star/mark coordinates, but the runtime constants are now named and source-mapped to `preview/dashboard.html`.
- Dashboard layout still has fixed spacing in secondary sections, but the central launch scene dimensions now live in `dimens.xml`.
- CI and Release workflows duplicate libcore/bootstrap steps. Extract shared setup into a composite action or reusable workflow once the release process is stable.
- Native core source defaults still fall back to `MatsuriDayo/*`. Full detachment requires Yokhan-owned forks or explicit `CORE_*_GIT_URL` repository variables.

## Process Rules

- Signed test APKs come from manual `CI` dispatch with `signed=true`.
- Production releases come from a version bump on `main` plus a fresh tag that matches `VERSION_NAME`.
- Existing tags are not reused for changed app code.
- Generated artifacts under `.tmp-artifacts/`, `app/build/`, and local image scratch files stay out of commits.
