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
- Distribution, provider installation, signatures, and user consent must remain explicit.
