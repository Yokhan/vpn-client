# Product, Landing, And Provider Execution Plan

## Goal

Move YokhanVPN toward a production-ready private product with:

- a clean Compose-first Android shell;
- a separately signed network provider path;
- a private onboarding and update flow;
- a future client-facing landing/onboarding page for trusted users;
- deterministic CI/CD and release artifacts;
- explicit compliance and source-license boundaries.

This plan is not legal advice. Any public commercial launch, especially involving Russian users, requires legal review before broad distribution.

## Current Baseline

- Android app is still a monolith: UI, `VpnService`, native core, storage, and profile flows are inside one APK.
- `YokhanNetworkProviderContract` and `YokhanNetworkProviderDetector` exist as inert scaffold.
- GitHub Actions CI now triggers on branch push.
- Desktop Compose preview exists for dashboard UI QA.
- Landing skills are stored in `.agents/skills` for future Codex sessions.
- No client landing should be built until the audience, copy boundary, distribution channel, and support flow are explicitly approved.

## Dependency Graph

```text
P0 Skills and plans
  -> P1 Honest product boundary
  -> P2 Landing brief and client copy boundary
  -> P3 Onboarding provider status
  -> P4 Android provider module split
  -> P5 Private update and release channel
  -> P6 Device QA and support playbook
  -> P7 Production hardening
```

Hard rule: do not start P4 runtime split until P3 provider states are visible and testable in the current monolith.

## Workstreams

### P0. Project Skills And Planning

Status: completed.

Tasks:

1. Save landing skills under `.agents/skills`.
2. Save landing templates under `.agents/templates`.
3. Save this execution plan in `docs`.
4. Link product plans from README.
5. Keep landing artifacts outside Android runtime modules.

Depends on: none.

Validation:

- Files are committed.
- README points to the plan.
- No Android behavior changes.

### P1. Product Boundary And Compliance Copy

Status: pending.

Tasks:

1. Keep public copy honest: private managed access, protected tunnel, trusted circle.
2. Remove promises about bypassing blocks, anonymity, or universal access.
3. Keep "not legal advice" and "requires local legal review" in docs.
4. Define private distribution rules: invite-only, signed artifacts, checksums, support contact.
5. Define source-license rule: current GPL-derived APK distribution requires GPL compliance unless replaced or relicensed.

Depends on: P0.

Validation:

- Landing and README do not present the product as an unrelated game.
- Docs include compliance and source-license boundary.
- No hidden download or silent module activation flows.

### P2. Landing Brief And Client-Facing Copy Boundary

Status: pending.

Tasks:

1. Define the actual client audience: family, friends, known users, small teams, or support operators.
2. Write a client-facing landing brief before any HTML implementation.
3. Separate user-facing copy from internal architecture terms such as CI, GitHub Actions, module split, and device QA.
4. Define what the page may say about private access, provider installation, signatures, support, and updates.
5. Define what the page must not say: public bypass claims, guaranteed anonymity, guaranteed legality, hidden module language, or unrelated-game positioning.
6. Define the support CTA and private distribution channel before adding download or update links.
7. Only after the brief is approved, build a standalone no-build HTML page using `.agents/skills`.

Depends on: P0, P1.

Validation:

- Brief is approved before implementation.
- User-facing copy does not expose internal engineering process.
- No HTML landing artifact is added before explicit implementation approval.

### P3. Android Onboarding Provider Status

Status: in progress.

Tasks:

1. Add provider status model to `shared-domain`.
2. Add Android adapter around `YokhanNetworkProviderDetector`.
3. Add Compose provider status card in the shell/settings.
4. Show states: current monolith, external ready, missing, untrusted, outdated.
5. Block external provider use if fingerprint does not match.
6. Keep monolith fallback for test releases.

Depends on: P0, P1, existing provider detector.

Validation:

- Unit or JVM tests for fingerprint normalization.
- Android build passes.
- Device smoke test shows correct provider state.

### P4. Android Network Provider Module

Status: pending.

Tasks:

1. Create `android-network-provider` application module.
2. Move `VpnService`, foreground notification, native core bridge, and service lifecycle behind provider boundary.
3. Keep storage migration explicit.
4. Add provider manifest permission and contract actions.
5. Sign provider APK with stable release key.
6. Configure `YOKHAN_PROVIDER_CERT_SHA256`.
7. Run dual-mode releases with monolith fallback before disabling it.

Depends on: P3.

Validation:

- Shell APK builds.
- Provider APK builds.
- Fresh install works.
- Missing provider state works.
- Untrusted provider state blocks use.
- Connect/disconnect/reconnect pass on device.

### P5. Private Updates And Release Channel

Status: pending.

Tasks:

1. Separate Shell and Provider artifacts in GitHub Releases.
2. Publish APK SHA-256 and signing certificate SHA-256.
3. Add private update manifest format.
4. Add in-app update state that does not silently install executable modules.
5. Document manual install and update instructions for trusted users.

Depends on: P4 for provider artifacts, P1 for copy.

Validation:

- Release notes clearly label Shell APK and Provider APK.
- Checksums are generated.
- Rollback path is documented.

### P6. QA And Support Playbook

Status: pending.

Tasks:

1. Add smoke checklist for first install.
2. Add support checklist for no config, no provider, bad signature, no permission, no connection.
3. Add landing "what to send support" copy button.
4. Add Android diagnostics export plan.
5. Keep privacy-minimal operational logs.

Depends on: P2, P3.

Validation:

- Non-technical tester can install, verify status, and send a useful support message.

### P7. Production Hardening

Status: pending.

Tasks:

1. Continue removing legacy UI and old package references from user-facing code.
2. Keep legal attribution while GPL-derived code remains.
3. Measure dashboard animation battery and jank.
4. Add baseline profile only after navigation stabilizes.
5. Prepare independent rewrite path for closed-source future.

Depends on: ongoing Android cleanup, P4 for runtime boundary.

Validation:

- Release candidate passes CI, local build, device QA, and support checklist.

## Immediate Pipeline

1. Finish P0 in this commit: save project-local skills, templates, and this dependency plan.
2. Do not build the client landing yet.
3. Next commit: wire provider status into Compose settings/onboarding.
4. After device smoke test: start provider module split.
5. Build the client landing only after the client-facing brief is approved.
