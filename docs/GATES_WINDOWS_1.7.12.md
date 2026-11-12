# GATES Windows 1.7.12 — XHTTP release rollup

Status: released and verified on 2026-11-11

Base: tag `v1.7.9` (Windows package version was `1.7.6`)

Target: Windows 10/11 x64, per-machine MSI/EXE

## Кратко (RU)

- Windows 1.7.12 переведён на XHTTP через финский вход Aeza `46.226.160.43`, выход — Финляндия `45.136.125.201`.
- UUID устройства берётся из account-профиля; смена entry/port на сервере не требует переустановки клиента.
- Перед подключением приложение обновляет account-профиль с сервера.
- Установщик останавливает службу, вычищает дерево процессов, ставит MSI и запускает службу заново. Проверено: `MSI_EXIT=0`, без `3010` и без перезагрузки.
- Причина залипания «отключено при работающем туннеле» — осиротевшие `xray.exe`/`sing-box.exe`, державшие TUN. Исправлено в установщике и self-heal.

## Artifacts

| File | Size (bytes) | SHA-256 |
| --- | ---: | --- |
| `GATES-1.7.12.msi` | 160,739,328 | `f2bc7fe4d525238ddbaff9db85903a02ee961cb2fb9b676ecce86479269f3093` |
| `GATES-1.7.12.exe` | 161,462,784 | `e1d299523b95d86a4d18b58665594877466e5d52a9f8925d0e2c653b7f70df3b` |
| `GATES-install.cmd` | 2,609 | served copy SHA-256 `BF87E80F66E15D06B0F2040CD002BFEDBAA74F6323372EA376242AA0F87265F0` |

Public URLs:

```text
https://app.yokhan.xyz/download/
https://cloak.yokhan.xyz/downloads/GATES-latest-windows.msi
https://cloak.yokhan.xyz/downloads/GATES-latest-windows.exe
https://cloak.yokhan.xyz/downloads/GATES-install.cmd
https://cloak.yokhan.xyz/downloads/SHA256SUMS-windows.txt
```

The website Windows button points to `GATES-install.cmd`. The installer downloads the
current MSI itself, so the button does not need to change for each patch release.

## What changed

### 1. XHTTP transport for Windows

- Added the `xhttp` route and the pinned `xray.exe` runtime sidecar.
- `sing-box` reaches the sidecar through a loopback SOCKS outbound on
  `127.0.0.1:2095` (configurable via `xhttpSidecarPort`).
- The sidecar is required in TUN mode. Proxy-only mode is rejected when an XHTTP
  spec exists; XHTTP must run through the per-machine GATES Runtime service.
- The entry IP is excluded from TUN routes so the XHTTP connection itself does not
  loop back into the tunnel.
- Physical bind is required: XHTTP must bind to a real physical interface, not the
  `GATES` TUN adapter.
- Runtime pin: `xray.exe` SHA-256
  `d5b558191cccb1e92a5772b68b8cab67717b26f634b0f15153215f65ae982a7e`,
  also recorded in `xray.sha256` and verified during packaging.

### 2. Server-driven account profile

- Account profiles are imported from the app API and tagged with
  `profileSource=account`.
- Before every `connect`, the UI refreshes the account profile (`import-app`),
  reloads `activeRoute`, and updates available routes.
- Entry/port changes on the server are therefore picked up without reinstalling or
  manually pressing “Обновить доступ”.
- Per-device XHTTP UUID comes from the account profile (`device.vless_uuid`).

### 3. Safe Runtime service self-heal

Previous behaviour restarted the service whenever the app prepared the runtime. That
could drop a working tunnel merely by opening the app.

Current behaviour in `WindowsRuntimeServiceManager.buildInstallScript`:

- If `GatesVpn` is running with a valid control token, compare installed file
  timestamps with the running process start times:
  - `Gates.Service.exe` vs the WinSW wrapper;
  - `GATES.exe` vs the runtime child.
- If the running image is current, print `GATES_SERVICE_STATE=running_matching` and
  exit `0` without touching the tunnel.
- Only when an installed file is newer than its running process:
  - stop the service;
  - `taskkill /T /F /PID <wrapper>` to terminate the full child tree;
  - kill only `xray.exe`/`sing-box.exe` whose path is under the GATES install root;
  - start the service again.

This preserves an active connection on ordinary app starts while still loading a new
image after an MSI upgrade.

### 4. Installer and process-tree cleanup

The MSI alone is not a safe upgrade path over a running service. `GATES-install.cmd`
implements the supported upgrade flow:

1. self-elevate via UAC;
2. download `GATES-latest-windows.msi` with `curl`, fall back to PowerShell;
3. close the UI and stop `GatesVpn`;
4. wait for `STOPPED`, then `taskkill /F /T /IM Gates.Service.exe`;
5. kill only GATES-owned `xray.exe`/`sing-box.exe` by executable path;
6. run `msiexec /i ... /passive /norestart`;
7. accept exit codes `0`, `3010`, `1641`, `1638`;
8. run `msiexec /fa ... /qn /norestart` to force a full file repair;
9. verify `runtime/lib/modules`, `Gates.Service.jar`, and every `GATES.cfg`
   classpath entry;
10. start `GatesVpn`;
11. launch GATES through `explorer.exe`, so the UI runs as the logged-in user instead
    of inheriting the installer’s elevated token.

Root cause of the earlier `3010`: installing the MSI while the old SYSTEM runtime
child was still running left the launcher image locked. Windows scheduled a rename on
reboot. Stopping the service and killing the whole process tree before `msiexec`
removes the lock; the verified 1.7.12 run returned `0`.

A second failure appeared after a reboot: the MSI returned success but left 41
app jars and `runtime/lib/modules` missing, so both UI and service exited with
code 1. The MSI was complete; the upgrade/repair registration was not. The
installer now forces `/fa` and verifies the resulting image, which restores the
missing files even on an already-broken install.

Orphaned `xray.exe`/`sing-box.exe` were the third failure mode: the old runtime kept
the TUN and traffic alive, while the new service could not start its own tunnel and
reported `STOPPED`. The UI then showed “Отключено” even though traffic still exited
through the previous tunnel. The cleanup above prevents that state.

### 5. UI and packaging

- XHTTP route label: `XHTTP (Xray)`; Finland VLESS label updated to `Finland VLESS (r1)`.
- Dropped/picked `.lnk` shortcuts are resolved to their target executable before app
  routing.
- Pinned Xray runtime files (`xray.exe`, `xray.sha256`, `LICENSE-Xray-core`) are
  included in `processResources` and the native app image.
- `windows-app/tools/GATES-install.cmd` is the versioned copy of the distributed
  installer.

### 6. MSI upgrade integrity repair

A 1.7.12 upgrade over the previous package could leave the installed app image
incomplete: 41 classpath jars and `runtime/lib/modules` were missing. The UI
and the service then exited with `Child process exited with code 1`. The MSI
itself is complete (an administrative extract contains 47 app files), so this
is an upgrade/repair problem, not a missing build artifact.

The long-term installer now:

- runs `msiexec /i ... /passive /norestart`;
- then runs `msiexec /fa ... /qn /norestart` to force a full package repair;
- verifies `runtime/lib/modules`, `Gates.Service.jar`, and every
  `app.classpath` entry from `GATES.cfg`;
- starts the service only after verification;
- writes diagnostics to `C:\ProgramData\GATES\installer.log`;
- fails with a clear message instead of launching a broken app.

A device already in the broken state is repaired by running the same
installer. Manual extraction of the MSI is a last-resort fallback.

## Verification evidence (2026-11-11)

Build and tests:

```powershell
$env:YOKHAN_SING_BOX_DIR = "<path-to-runtime-bundle>"
.\gradlew.bat :windows-app:test
.\gradlew.bat :windows-app:packageMsi :windows-app:packageExe
```

- `:windows-app:test` — passed.
- Pester installer contract — `4 passed, 0 failed` (includes the `/fa` repair and
  file-integrity assertions).
- MSI contract check — `ProductName=GATES`, `ProductVersion=1.7.12`,
  `UpgradeCode={33D7B058-0DA8-4ADA-9108-D4511DA47B21}`; required files include
  `GATES.exe`, `Gates.Service.*`, `sing-box.exe`, `libcronet.dll`, `xray.exe`,
  `xray.sha256`, `LICENSE-Xray-core`.
- Served installer end-to-end: download 153.2 MiB, `MSI_EXIT=0`,
  `Installed GATES version: 1.7.12`, service state `RUNNING`.
- Live tunnel: control port `38417` listening, `GATES` adapter `Up`, XHTTP exit
  `45.136.125.201`.
- Post-reboot repair (2026-11-12): the installed app had only 6 of 47 files and
  no `runtime/lib/modules`; restoring them from the 1.7.12 MSI administrative
  extract brought the service and XHTTP tunnel back. The installer `/fa` repair
  path is covered by contract tests; a full installer rerun on the repaired
  host is the remaining end-to-end check.
- No reboot was required. The earlier direct 1.7.10 MSI over the running service
  returned `3010`; the guarded flow does not.

## Agent runbook

### Build

```powershell
$env:YOKHAN_SING_BOX_DIR = "<runtime bundle with sing-box.exe, libcronet.dll, xray.exe, xray.sha256>"
.\gradlew.bat :windows-app:test
.\gradlew.bat :windows-app:packageMsi :windows-app:packageExe
```

Do not use `GATES --cli` or other headless GATES commands; the owner controls keys and
profiles. Use read-only checks (`Get-Service`, `Get-NetTCPConnection`, HTTP probes)
unless a change is explicitly authorized.

### Publish

Upload the MSI, EXE, checksums, and `windows-app/tools/GATES-install.cmd` to the
release downloads directory, then refresh the `GATES-latest-windows.*` copies. Keep
the previous versioned MSI for rollback. The current server path is:

```text
/opt/yokhan-app/frontend-releases/app-logik-20261107-r5/frontend/downloads/
```

### Upgrade

Use `GATES-install.cmd`. Do not run the MSI directly over a running `GatesVpn`
service. If installing manually, stop the service and kill the full process tree
first, including GATES-owned `xray.exe`/`sing-box.exe`.

### Verify

```powershell
Get-Item "C:\Program Files\GATES\GATES.exe" |
  Select-Object @{n='Version';e={$_.VersionInfo.FileVersion}}
Get-Service GatesVpn
Get-NetTCPConnection -State Listen | Where-Object LocalPort -eq 38417
curl.exe -sS https://api.ipify.org   # expected 45.136.125.201
```

## Operational guardrails and lessons

### Encoding: never pipe non-ASCII scripts through PowerShell to SSH

On 2026-11-11 a Telegram broadcast was streamed as
`Get-Content -Raw script.py | ssh host "python3 -"`. PowerShell/SSH converted Cyrillic
to `?`; the local file was valid UTF-8 and the local encoding hook passed, but the
delivered payload was corrupted. The send reported `9/9, 0 failures` because only the
exit code and target count were checked.

Mandatory for future agents:

- Transfer non-ASCII scripts/messages with `scp`, never through a PowerShell/SSH
  stdin pipe.
- Verify the remote file hash and render the exact message server-side
  (`repr()`/codepoint check) before sending.
- Send a test message to the admin chat first; only after explicit owner approval
  send to users.
- Treat a broadcast as a high-risk action even when the source file passed the local
  text-policy hook. A code-level broadcast wrapper is still recommended; until it
  exists, this is a manual hard gate.

### Secrets

- Never commit bot tokens, `XHTTP_SYNC_TOKEN`, `.env` contents, private keys, or
  per-user subscription URLs.
- Session/token values belong in the server environment files, not in source or docs.

### Service cleanup scope

- Kill only `xray.exe`/`sing-box.exe` whose path is under `C:\Program Files\GATES`.
- Use `taskkill /T` for the WinSW wrapper tree; do not kill unrelated user processes
  with the same image name.

## Rollback

1. Stop `GatesVpn`.
2. Kill the GATES process tree and GATES-owned `xray.exe`/`sing-box.exe`.
3. Install the retained previous MSI (for example `GATES-1.7.11.msi`) with
   `msiexec /i ... /passive /norestart`.
4. Start `GatesVpn` and verify the control port and exit IP.

Android 1.8.13 (`GATES-1.8.13-arm64-v8a.apk`, SHA-256
`a72ede55e1d10a1970727f41754c411ff40a0ab7d23ae81d1f773b646b7f6d63`) is unchanged by
this Windows rollup.

## Known limitations

- The Windows MSI/EXE are not Authenticode-signed.
- Direct MSI upgrades over a running service can still return `3010` or leave an
  incomplete app image; use the guarded installer, which performs an `/fa`
  repair and verifies every classpath jar plus `runtime/lib/modules`.
- Launching GATES from an already elevated shell still produces an elevated UI; the
  installer avoids this with `explorer.exe`.
- The Telegram broadcast encoding rule is documented here but not yet enforced by a
  code-level wrapper/hook.
