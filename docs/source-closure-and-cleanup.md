# Source Closure And Cleanup Plan

This project is still a fork-derived Android client. Treat the current codebase as a transition base, not as a clean proprietary product.

## Legal Boundary

- Current repository license is GPL-3.0 via `LICENSE`, `AUTHORS`, and upstream-derived Android code.
- Private/internal modification is possible, but distributing APKs built from GPL-derived code triggers GPL source obligations for recipients.
- A closed-source production app requires one of these routes:
  - replace GPL-derived app code with independently owned code;
  - obtain commercial/relicense rights from all relevant copyright holders;
  - keep GPL-covered components separate and comply with their licenses.
- Do not remove attribution or license notices from GPL-derived files while they remain in the product tree.

References:

- GNU GPL FAQ: https://www.gnu.org/licenses/gpl-faq.en.html
- gpl-violations source release FAQ: https://gpl-violations.org/faq/sourcecode-faq/

## What Can Be Done Now

- Repository visibility is private for operational privacy.
- Keep release artifacts, source-offer, and license compliance explicit while APKs are distributed from this code.
- Remove upstream branding from user-facing UI, package metadata, README marketing text, and generated release notes.
- Keep legal attribution in `LICENSE`, `AUTHORS`, and source headers until code is replaced or relicensed.
- Treat private visibility as access control only. It is not a proprietary license conversion for GPL-derived APKs.

## Production Cleanup Tracks

1. UI shell ownership

- Keep dashboard, route kit, shell, settings host, and product-only helper widgets in Yokhan-owned UI package names and components.
- Replace fork-era fragments gradually with feature-owned screens.
- Keep visual tokens in resources and renderer/controller classes, not inside monolithic fragments.

2. Build and release ownership

- Remove fallback native sources that point to `MatsuriDayo/*`.
- Pin Yokhan-owned forks or vendored source archives for native core inputs.
- Make push CI deterministic; manual CI remains a fallback, not the normal path.

3. Package and namespace cleanup

- Product-owned UI entrypoints, preference widgets, profile settings activities, and profile form binding helpers already use `com.yokhan.vpn.app.*`.
- Plan the remaining non-UI namespace migration away from `io.nekohasekai.sagernet`.
- Avoid breaking persisted app data until migration code exists.
- Rename only leaf packages first; database/entity namespaces last.

4. License exit strategy

- Inventory GPL-derived files by feature area.
- Mark files as `keep`, `rewrite`, `replace`, or `needs legal review`.
- Rewrite high-value product UI first; defer low-value settings screens until needed.
- Remove fork attribution only after the relevant code has been replaced or relicensed.

## Immediate Engineering Rules

- No more large UI refactors without device screenshot verification.
- Every dashboard behavior change must preserve: profile selection, edit, share/menu, connect toggle, connection test, timer, speed, stars, rocket, flames.
- Refactors must be staged behind passing local build and CI before release tagging.
