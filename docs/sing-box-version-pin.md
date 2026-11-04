# sing-box version pin and watchdog

`release/sing-box-pin.json` is the approved upstream pin for the GATES core.
Windows and Android builds must resolve `SagerNet/sing-box` to the same version,
tag and commit. Platform-specific artifacts and build tags may differ.

## Rules

- A newer upstream release is a review trigger, not an automatic upgrade.
- The watchdog never edits the pin, merges, builds or publishes.
- A changed existing tag is a supply-chain discrepancy and must be reviewed.
- If the check is unavailable (network, API rate limit, timeout), the approved
  pin remains valid; record the check as unavailable and retry later.
- Release builds must verify their actual checkout and packaged core against the
  pin. A missing or mismatched hash is a hard stop.

## Local check

```powershell
node scripts/check-sing-box-version.mjs
node scripts/check-sing-box-version.mjs --runtime-dir <dir-with-sing-box.exe>
node scripts/check-sing-box-version.mjs --json
```

Exit codes:

- `0` — up to date.
- `1` — newer upstream release found and `--fail-on-new` was requested.
- `2` — invalid or missing pin.
- `3` — upstream check unavailable.
- `4` — local Windows runtime hash mismatch.

## Updating the pin

1. Review upstream release notes and deprecated/removed config fields.
2. Build Windows and Android from the exact candidate commit.
3. Run native HY2, VLESS/Reality and Naive route acceptance.
4. Record the tag, commit, archive and per-file hashes in
   `release/sing-box-pin.json`.
5. Obtain approval. Do not merge or publish automatically.

The Android migration from the Matsuri fork to upstream `experimental/libbox`
and native Naive is tracked separately; this pin only fixes the source version.
