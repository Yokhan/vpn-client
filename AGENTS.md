# Project Agent Rules

## Landing Skills

This repository includes project-local landing skills in `.agents/skills`.

Use them when the task is to build or edit a landing page, a decision guide, a commercial explanation page, an onboarding page, or a standalone HTML page from a plan.

Default landing workflow:

1. Use `codex-landing-orchestrator` first.
2. Convert dense notes into a decision tool, not a static article.
3. Use clear Russian copy without assistant meta-text.
4. Keep all button-like UI elements interactive.
5. Prefer standalone `index.html` with inline CSS and JS unless a framework is explicitly requested.
6. Run landing QA before delivery.

Project-specific boundary:

- Do not present a network/tunnel product as an unrelated game.
- Rocket/launch imagery is allowed as brand metaphor.
- Public copy must not promise bypassing restrictions, anonymity, or guaranteed legal safety.
- Product design and public copy must keep distribution, provider installation, signatures, and user consent explicit.

## Cross-Platform Parity Gate (Windows + Android)

GATES ships **Windows and Android together in parity at the same release version** (`docs/parity-manifest.json`, `docs/PLATFORM_PARITY.md`).

- Default rule: every feature, routing/DNS/kill-switch policy change, access/recovery flow, UI improvement, security fix, and version bump MUST target BOTH Windows (`windows-app/`, `windows-service/`) and Android (`app/`) plus `shared/`, and ship at a single synchronized version.
- Single-platform work is allowed **only** upon explicit user instruction waiving parity for that specific task.
- When any temporary exception creates drift between Windows and Android, it MUST be logged under `"divergences"` in `docs/parity-manifest.json` with `waivedByUser: true`, source platform, lagging platform, contract summary, and reconcile target.
- Before starting or shipping any subsequent release/feature work, agents MUST inspect `docs/parity-manifest.json` and run `tools/Test-PlatformParity.ps1`: any logged divergence MUST be automatically ported to the lagging platform, test-verified, and cleared before merging/releasing.
