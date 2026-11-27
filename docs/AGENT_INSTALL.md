# AG Widgets — agent install runbook (Windows)

> **Кратко для человека (по-русски).** Runbook ниже написан на английском для максимальной надёжности ИИ-агентов (Antigravity, ZCode, Cursor, Claude Code, Codex).
> Агент сначала только читает (`-DryRun`, `--doctor-json`), затем спрашивает подтверждение у пользователя и только потом запускает `install.ps1` / `adb install`.
> Секреты (ntfy topic, ZCode JWT, `secrets.json`) нельзя коммитить, логировать или вставлять в отчёты и чат.
> Агент не вводит пароли пользователя и не трогает его браузерную сессию.
> Подробные руководства для человека: [INSTALL_GUIDE.ru.md](INSTALL_GUIDE.ru.md) · [INSTALL_GUIDE.md](INSTALL_GUIDE.md)

**Audience:** an AI coding agent with shell access on the user's Windows machine.
**Goal:** install AG Widgets deterministically, with hard stop points before any write.
**Order:** Phase 0 probe → Phase 1 triage → Phase 2 desktop install → Phase 3 optional Android → Phase 4 report.
Run every command from the repository root (the folder that contains `install.ps1`).

| Phase | Writes? | Exit condition |
|---|---|---|
| 0 — probe | read-only | all prerequisites present |
| 1 — triage | read-only | no `fail` left that the agent can fix |
| 2 — desktop install | yes | user confirmed; `--doctor --online` shows 0 `fail` |
| 3 — Android (optional) | yes | installed on the device, or the user declined |
| 4 — report | read-only | anonymized report shown to the user |

---

## 0. Scope & safety guardrails (non-negotiable)

- **NEVER** commit, push, store in shared agent memory, or paste into issues/PRs/chat reports: `ntfy_topic`, `zcode_token`, `secrets.json`, `android/local.properties`, or real usernames and paths (`C:\Users\<name>`). In reports print booleans only: `topic_configured=true`.
- **NEVER** pass a ZCode JWT on the command line (`--set-token <jwt>`). Allowed paths: browser OAuth (`python collector\zcode_auth.py`), the widget's **ВОЙТИ В Z.AI** button, or `--set-token-stdin` with the JWT piped on stdin. The user pastes the JWT into their own terminal (Ctrl+Z, Enter) — never into the chat. Never echo the value.
- **NEVER** run `install.ps1`, `adb install`, `gradlew` builds, or `-Uninstall` without first showing the user the exact plan (`-DryRun` output) and receiving explicit confirmation.
- **NEVER** build or share an APK without `-Pcommunity=true`; a build without that flag embeds `agw.topic` from `local.properties` into `BuildConfig`.
- **NEVER** hand-edit the user's `~/.gemini/config/*`, `%LOCALAPPDATA%\ag-widgets\secrets.json`, or browser sessions. Those are owned by `install.ps1` and the collector.
- **NEVER** install system software silently. If a prerequisite is missing: give the exact download link, explain why it is needed, and **STOP**.
- A `warn` from `--doctor-json` is not a failure. Only `fail` blocks the install; pre-install warns are expected and are resolved by `install.ps1`.

---

## 1. Phase 0 — read-only environment probe

```powershell
$PSVersionTable.PSVersion; [System.Environment]::OSVersion.VersionString
where.exe python
python --version
node -v
npm -v
python collector\collector.py --doctor-json
powershell -ExecutionPolicy Bypass -File .\install.ps1 -DryRun
```

Read-only guarantees:

- `-DryRun` writes nothing: it prints `[dry-run] <action>` lines and changes no files. Show its output to the user as the plan.
- `--doctor-json` writes nothing, never prints the token or the topic, and anonymizes paths to `~` / `%LOCALAPPDATA%`.
- `--doctor-json` exits with `0` when there is no `fail` and with `1` when at least one check failed. Exit code `1` is a normal diagnostic result, not a crash — still read the JSON.

Decision table (evaluate top-down, first match wins):

| Condition | Action |
|---|---|
| `where.exe python` first hit is under `...\WindowsApps\` | **STOP.** It is the Microsoft Store stub. Ask the user to install Python 3.10+ from https://www.python.org/downloads/ with "Add python.exe to PATH", then disable **Settings → Apps → Advanced app settings → App execution aliases → python.exe / python3.exe**. Do not try to work around the stub. |
| `python --version` is below 3.10 | **STOP.** Same python.org link and version requirement. |
| `node -v` is below 22.12.0 | **STOP.** https://nodejs.org — LTS 22.x or newer. |
| `node` or `npm` is not found | **STOP.** Install Node.js 22.12+ from https://nodejs.org and reopen the terminal. |
| `--doctor-json` cannot run at all (no Python) | **STOP** after the Python fix above; then restart at Phase 0. |
| Everything above is present | Continue to Phase 1. |

Notes: `install.ps1 -DryRun` still validates prerequisites (it refuses a missing Python or a Store stub, and warns about Node), but it never writes. A terminal opened before a `PATH` change must be reopened.

---

## 2. Phase 1 — decision tree over `--doctor-json`

Shape: `{"tool","schema_version","checks":[{"name","status","detail"}]}` with `status` in `ok | warn | fail`. Exit code: `0` if no `fail`, `1` otherwise. Secrets appear only as booleans; paths are anonymized. `--online` adds exactly two checks: `ntfy_reachability` and `zcode_api`.

Exactly 10 checks exist; handle each one as follows.

| Check | Status semantics | Deterministic fix |
|---|---|---|
| `python_version` | `ok` if the interpreter is >= 3.10, `fail` otherwise. | Install Python 3.10+ from https://www.python.org/downloads/, then `install.ps1 -PythonPath "C:\Path\To\python.exe"`. Hard `fail`. |
| `agy_cli` | Runs `agy --version`; `fail` when the executable is not found, times out, or exits non-zero. Lookup order: `AGY_PATH`, `PATH`, `~/.gemini/bin/agy.exe`, `%LOCALAPPDATA%\agy\bin\agy.exe` (being on `PATH` is not required). | Install Antigravity 2.0 from https://antigravity.google, or `setx AGY_PATH "C:\Path\To\agy.exe"` and reopen the terminal. Hard `fail` — only the user can do this. |
| `annotations_dir` | `warn` when `~/.gemini/antigravity/annotations` does not exist; `ok` lists the `*.pbtxt` count and whether an active chat was detected. | Ask the user to open a chat in Antigravity once. Pre-install `warn` is expected. |
| `antigravity_hook` | `fail` if `~/.gemini/config/hooks.json` is unreadable; `warn` when it is missing, when the `ag-widgets-context` hook is not registered, when `PostInvocation[0]` has no Python script, or when the script file is missing. | Run `install.ps1` (it registers the hook and backs up the old file). |
| `antigravity_sidecar` | `fail` if `~/.gemini/config/config.json` is unreadable; `warn` when the manifest `~/.gemini/config/sidecars/ag-widgets-collector/sidecar.json` is absent or the sidecar is not `enabled=true`. | Run `install.ps1`. |
| `runtime_config` | `fail` if `%LOCALAPPDATA%\ag-widgets\config.json` is unreadable JSON; `warn` when it does not exist yet. | Run `install.ps1` (creates it from `config.example.json`) or fix the JSON syntax. |
| `state_freshness` | `warn` when any of `context.json`, `status.json`, `agy_status.json` is missing or older than 24 h, or when `hook_errors.log` is present; `ok` otherwise. | Start the collector (`python collector\collector.py --force`) and inspect `%LOCALAPPDATA%\ag-widgets\state\hook_errors.log`. |
| `secrets_storage` | `fail` when `secrets.json` cannot be decrypted (DPAPI error, typically after a Windows profile migration); `warn` when the file is absent, still plaintext (migration pending), or has neither a token nor a topic; `ok` reports `token_configured`, `topic_configured`, `encrypted`. | `python collector\zcode_auth.py --set-token-stdin` and let the collector create the topic (`--show-topic`). Never copy `secrets.json` between machines. |
| `ntfy_reachability` (only with `--online`) | `fail` when ntfy.sh is unreachable or returns HTTP >= 500; `ok` on any HTTP status in 1..499. | Check connectivity/proxy/VPN, then re-run. |
| `zcode_api` (only with `--online`) | `fail` when the token cannot be decrypted, the token is rejected (`auth_expired`), or the network fails; `warn` when no token or no device id is configured, or when the probe is degraded (`rate_limited`, `api_changed`, ...); `ok` when the token is accepted. | Sign in again (browser OAuth, **ВОЙТИ В Z.AI**, or `--set-token-stdin`); on `rate_limited` wait and retry; on `api_changed` report it, do not guess. |

**Expected `warn`s before install** (resolved by `install.ps1`, do not block): `antigravity_hook`, `antigravity_sidecar`, `runtime_config`, `secrets_storage`, `state_freshness`, `annotations_dir`.

**Hard `fail`s that require the user** (the agent cannot fix them): `python_version`, `agy_cli`; after install also `secrets_storage` decryption errors and `zcode_api` auth errors.

---

## 3. Phase 2 — confirmed Windows desktop installation

1. **Show the plan and ask.** Paste the `-DryRun` output, then list what a real run will touch:
   - backups into `%LOCALAPPDATA%\ag-widgets\backups\install-<timestamp>\`: `~/.gemini/config/hooks.json`, `~/.gemini/config/config.json`, `~/.gemini/antigravity-cli/settings.json`;
   - runtime `%LOCALAPPDATA%\ag-widgets\config.json` (created from `config.example.json`, `python_path` stored);
   - hook entry `ag-widgets-context` (`PostInvocation` + `Stop`) in `hooks.json`;
   - sidecar manifest and `enabled=true` entry `ag-widgets-collector` in the Antigravity config;
   - `statusLine` in `~/.gemini/antigravity-cli/settings.json`;
   - `desktop\node_modules` via `npm ci` plus the Electron binary download;
   - **AG Widgets** shortcuts on the Desktop and in the Start Menu.

   **STOP until the user explicitly says yes.**

2. **Run the installer** (same terminal, repository root):

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

   Add `-PythonPath "C:\Path\To\python.exe"` when the probe found a non-`PATH` interpreter, and `-NoShortcut` if the user does not want shortcuts.

3. **Verify the Electron binary:**

```powershell
Test-Path desktop\node_modules\electron\dist\electron.exe
```

   If `False`, the postinstall step did not fetch it (Electron 45 no longer downloads the binary in postinstall):

```powershell
cd desktop; node node_modules\electron\install.js; cd ..
Test-Path desktop\node_modules\electron\dist\electron.exe
```

4. **Verify the installation:**

```powershell
python collector\collector.py --doctor --online
```

   Success = zero `fail`. `zcode_api: warn` ("token not configured") is acceptable when the user has not signed in yet. Any remaining `fail` → return to the Phase 1 table.

5. **Hand the launch to the user:** the **AG Widgets** shortcut on the Desktop / Start Menu, or `start-widget.vbs`. For ZCode, the user clicks **ВОЙТИ В Z.AI** in card `03 / ACCESS`; the agent must never handle the user's password, browser session, or JWT.

6. **Phone pairing (only if planned):** the user runs `python collector\collector.py --show-topic` themselves (or you run it only on an explicit request) and treats the printed value as a secret.

7. **Secrets hygiene after the run:** a real `install.ps1` run prints the private ntfy topic in the terminal. Do not copy that line into chat, a report, a file, or memory; if the user needs it, they read it from their own terminal.

8. **Rollback (only on request):** show `.\install.ps1 -Uninstall -DryRun`, get confirmation, then `.\install.ps1 -Uninstall`. It removes only ag-widgets entries and keeps `%LOCALAPPDATA%\ag-widgets` (which still holds the topic and secrets).

Post-install artifacts to check when something looks wrong:

| Artifact | Location | Expected |
|---|---|---|
| Context hook | `~/.gemini/config/hooks.json` → `ag-widgets-context` | `PostInvocation` + `Stop`, timeout 5 s |
| Collector sidecar | `~/.gemini/config/sidecars/ag-widgets-collector/sidecar.json` + `enabled=true` in `config.json` | schedule `* * * * *`, runs `collector/collector.py` |
| CLI status line | `~/.gemini/antigravity-cli/settings.json` → `statusLine` | points to `collector/agy_statusline.py` |
| Runtime config | `%LOCALAPPDATA%\ag-widgets\config.json` | contains `python_path` |
| Secrets | `%LOCALAPPDATA%\ag-widgets\secrets.json` | DPAPI `dpapi:v1:` values; never printed or copied |
| State | `%LOCALAPPDATA%\ag-widgets\state\` | `context.json`, `status.json`, `agy_status.json` |
| Electron | `desktop\node_modules\electron\dist\electron.exe` | present |
| Shortcuts | Desktop and Start Menu `AG Widgets.lnk` | `wscript.exe` + `start-widget.vbs` |

---

## 4. Phase 3 — optional Android install

Ask whether the user wants Android. If no, skip to Phase 4. If yes, also ask which build — or explain the choice:

| | **AG Widgets** | **AG Context** |
|---|---|---|
| Antigravity context + quotas | yes | yes |
| ZCode vouchers, balance, OAuth | yes | **no** |
| Works without a PC | yes, for ZCode | no |
| package | `dev.agwidgets` | `dev.agwidgets.context` |

`AG Context` sets `BuildConfig.ZCODE_ENABLED = false`: no ZCode sign-in button, no ZCode card and no ZCode network requests. The different application ids allow both builds to be installed side by side.

The `v1.0.0` release already ships both signed community APKs (`AG-Widgets-1.0.0.apk`, `AG-Context-1.0.0.apk`, each with a `.sha256`). If the user only wants a ready APK, verify the checksum and skip the build; never hand over a locally built APK made without `-Pcommunity=true`.

Tooling probe (all read-only):

```powershell
adb version
adb devices
$env:ANDROID_HOME
java -version
```

- If `adb devices` shows no line ending in `device` (empty list, `unauthorized`, or `offline`): **STOP**. Ask the user to enable USB debugging, accept the RSA prompt on the phone, or fix wireless pairing.
- When building locally, `java -version` must report JDK 17 and `ANDROID_HOME` must point at an Android SDK. If either is missing: give the link, explain, and **STOP**. A ready release APK needs only `adb`.

Build the public APK (never a personal one for someone else's device):

```powershell
cd android
.\gradlew.bat :app:assembleFullDebug -Pcommunity=true --console=plain
.\gradlew.bat :app:assembleContextDebug -Pcommunity=true --console=plain
cd ..
```

APKs:

```text
android\app\build\outputs\apk\full\debug\app-full-debug.apk
android\app\build\outputs\apk\context\debug\app-context-debug.apk
```

Show the plan, get confirmation, then install only the chosen build (use `-s <serial>` whenever more than one device is attached):

```powershell
adb -s <serial> install -r android\app\build\outputs\apk\full\debug\app-full-debug.apk
# Antigravity-only build instead of the line above:
adb -s <serial> install -r android\app\build\outputs\apk\context\debug\app-context-debug.apk
```

Pairing procedure:

1. Run `python collector\collector.py --show-topic` only when the user is ready to pair, and show the topic once in the direct private chat — never write it to a file, commit, memory, or report.
2. On first launch the user picks a mode: **Только ZCode** (ZCode only, **AG Widgets**, no PC required) or **С компьютером** (With a PC, needs the topic). **AG Context** has no ZCode mode by design. The mode can be changed later under Settings → «Диагностика».
3. User: allow notifications → (PC mode) paste the topic → Save → tap refresh → add the 4×2 widget.

Point the user to the OEM battery/autostart section: [INSTALL_GUIDE.md](INSTALL_GUIDE.md#oem-battery-optimization) and [INSTALL_GUIDE.ru.md](INSTALL_GUIDE.ru.md) (Xiaomi HyperOS/MIUI, Samsung One UI, Pixel/stock). Without it the 15-minute WorkManager refresh and local reset reminders can be silently killed.

| OEM | What the user must allow |
|---|---|
| Xiaomi HyperOS / MIUI | Autostart for the installed build (`dev.agwidgets` or `dev.agwidgets.context`); battery saver "No restrictions" |
| Samsung One UI | Remove the app from "Sleeping apps"; allow background activity |
| Pixel / stock Android | Battery usage "Unrestricted" |

---

## 5. Phase 4 — final verification & report template

Fill this in and show it to the user:

```markdown
## AG Widgets install report
- OS: Windows <version>, Python <v> (not WindowsApps), Node <v>
- Doctor offline: <ok>/8 ok, <warn> warn, 0 fail
- Doctor online: ntfy_reachability=<status>, zcode_api=<status>
- Electron binary: present/absent (<path>)
- Antigravity hook + sidecar: registered/enabled
- Widget launched: yes/no
- Android: skipped / installed on <serial> (AG Widgets or AG Context, community build)
- Secrets: topic_configured=<bool>, token_configured=<bool> (values never printed)
- Next step for the user: <one line>
```

Rules for the report: no topic, no JWT, no `secrets.json` contents, no full personal paths — use `~` and `%LOCALAPPDATA%`. If a check still fails, keep the report factual and end with the single next action for the user.

| Symptom | Next action |
|---|---|
| `--doctor --online` still shows a `fail` | Return to the Phase 1 table for exactly that check name |
| Widget opens but card `03 / ACCESS` says "not connected" | User signs in via **ВОЙТИ В Z.AI**, then re-run the doctor |
| Phone shows no data | Re-run `--show-topic`, re-enter the topic in the app, tap refresh |
| Widget window does not start | Re-check the Electron binary (Phase 2 step 3) and run `start-widget.vbs` from the repository root |

---

See also: [INSTALL_GUIDE.md](INSTALL_GUIDE.md), [INSTALL_GUIDE.ru.md](INSTALL_GUIDE.ru.md), [../README.md](../README.md), [CONTEXT_HEALTH.md](CONTEXT_HEALTH.md).
