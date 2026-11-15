# GATES Client (`Yokhan/vpn-client`)

Author & maintainer: **Yokhan / Igor Nazarov** (`iohanrace@gmail.com`).

Cross-platform VPN client for **Windows** and **Android** sharing Kotlin Multiplatform UI/domain (`shared/`), Xray-core + sing-box cores, GATES Wise Tree access recovery, and a unified parity release pipeline.

## Ownership & Architecture

- `windows-app/`, `windows-service/`, `shared/domain/`, `shared/ui/`, GATES Wise Tree access, XHTTP transport/sidecar, signed v2 manifest verifier, RU compatibility routing, Whole Traffic policy, and parallelized diagnostics are authored and maintained by **Yokhan (Igor Nazarov)**.
- Android service shell/VPN compatibility layer continues partial GPL-3.0 upstream notices (`AUTHORS`, `LICENSE`, `docs/third-party-notices.md`) while legacy `io.nekohasekai.sagernet` / `moe.matsuri.nb4a` internals are incrementally rewritten and replaced.

## Mandatory Cross-Platform Parity Rule

See `docs/PLATFORM_PARITY.md` and `docs/parity-manifest.json`.

- Always build, test, and ship **Windows and Android together in strict version and feature parity** unless the user explicitly instructs otherwise for a specific exception.
- Any explicit temporary divergence must be recorded in `docs/parity-manifest.json` and automatically pulled, verified, merged, and cleared on the next pass.
- Validate parity locally with:
  ```powershell
  powershell -NoProfile -ExecutionPolicy Bypass -File tools/Test-PlatformParity.ps1
  ```

## Related

- Control plane & bot backend: `../YokhanIndependentVPN/`
