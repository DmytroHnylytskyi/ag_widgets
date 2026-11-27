# AG Widgets — Installation Guide (Windows)

Five minutes from a clone to a running widget: [`install.ps1`](../install.ps1) backs up your Antigravity config, registers a `PostInvocation`/`Stop` hook and a once-a-minute collector sidecar, sets up the CLI status line, installs the Electron app, creates your private ntfy channel and drops an **AG Widgets** shortcut on the Desktop and in the Start Menu. The Android app and its 4×2 widget are optional and read the same channel. The Android side ships as two builds: **AG Widgets** (Antigravity + ZCode) and **AG Context** (Antigravity only).

Russian version → [INSTALL_GUIDE.ru.md](INSTALL_GUIDE.ru.md); automatic installation by an AI agent → [AGENT_INSTALL.md](AGENT_INSTALL.md); back to the project → [../README.md](../README.md).

**What you get**

- Card `01 / Context` — model, tokens/window and health zone of the Antigravity chat you have open right now.
- Cards `02 / Limits` and `03 / Access` — 5h/weekly quotas with a reset countdown, ZCode vouchers and balance.
- The same numbers on an Android 4×2 widget that keeps working while the PC is off.

---

## 1. Prerequisites (Windows)

Only the tools in this table have to be installed by hand. Python here has no third-party dependencies, and the installer runs `npm ci` for you — do not install JS packages manually.

| Requirement | Version | How to check |
|---|---|---|
| Windows | 10 or 11 (acrylic blur needs 11 22H2+) | `winver` |
| **Python** | 3.10+, from [python.org](https://www.python.org/downloads/) | `where.exe python`, `python --version` |
| **Node.js + npm** | 22.12+ (Electron 45) | `node -v`, `npm -v` |
| **Google Antigravity** | 2.0, from [antigravity.google](https://antigravity.google) | `Test-Path "$HOME\.gemini\bin\agy.exe"` |
| JDK 17 + Android SDK (APK only) | JDK 17, `adb` on PATH | `java -version`, `adb version` |

**Python: do not use the Microsoft Store build.** The Store alias lives in `%LOCALAPPDATA%\Microsoft\WindowsApps\python.exe`. Running it opens the Store instead of starting Python, so PowerShell ends up reporting exit code `9009` ("term is not recognized") or nothing installed at all.

```powershell
where.exe python
python --version
```

The first line of `where.exe python` must **not** be under `WindowsApps`. If it is:

1. Install Python 3.10+ from [python.org](https://www.python.org/downloads/) with **Add python.exe to PATH** checked.
2. Settings → Apps → Advanced app settings → App execution aliases → turn **off** `python.exe` and `python3.exe`.
3. Reopen the terminal and re-check; `install.ps1` refuses a `WindowsApps` interpreter outright.

**Antigravity: `agy.exe` does not have to be on PATH.** The code searches `AGY_PATH` first, then PATH, then `~/.gemini/bin/agy.exe`, then `%LOCALAPPDATA%\agy\bin\agy.exe`. Antigravity 2.0 installs into `~/.gemini/bin` and does not add it to PATH, which is expected. To pin a specific binary:

```powershell
setx AGY_PATH "C:\Path\To\agy.exe"
```

Below, `~/` is shorthand for `%USERPROFILE%`.

---

## 2. Desktop Installation (Windows)

### 2.1 Clone

```powershell
git clone https://github.com/DmytroHnylytskyi/ag_widgets.git ag-widgets
cd ag-widgets
```

An existing local copy works exactly the same way — just `cd` into it. Unless a block says otherwise, commands run from the repository root.

### 2.2 Preview the installer (writes nothing)

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1 -DryRun
```

Every action is printed prefixed with `[dry-run]`; no files, backups, shortcuts, `npm ci` or collector runs happen. Use it to see which paths the installer would touch.

### 2.3 Run the installer

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

It performs these steps in order:

1. Backs up `~/.gemini/config/hooks.json`, `~/.gemini/config/config.json` and `~/.gemini/antigravity-cli/settings.json` into `%LOCALAPPDATA%\ag-widgets\backups\install-<timestamp>\` (files that do not exist are skipped).
2. Creates `%LOCALAPPDATA%\ag-widgets\config.json` from `config.example.json` and stores the interpreter path as `python_path`; an existing config is merged, not overwritten.
3. Registers the `ag-widgets-context` hook under `PostInvocation` and `Stop` in `~/.gemini/config/hooks.json`.
4. Writes and enables the `ag-widgets-collector` sidecar (cron `* * * * *`) in the Antigravity config, keeping any foreign sidecar entries.
5. Configures the Antigravity CLI status line; if another tool's `statusLine` is present, it is replaced **with a warning**, and `-Uninstall` restores the previous value from that backup.
6. Runs `npm ci` in `desktop\`. If `desktop\node_modules\electron\dist\electron.exe` is still missing afterwards, it downloads the Electron 45 binary with `node node_modules\electron\install.js` (Electron 45 no longer ships the binary via postinstall).
7. Runs `python collector\collector.py --force --show-topic` once: this creates your private `ntfy_topic` (96 random bits) and prints it to the screen **one time**. Copy it now if you plan to pair a phone; `--show-topic` can print it again later, but nothing else will.
8. Creates the **AG Widgets** shortcuts on the Desktop and in the Start Menu. They launch through `start-widget.vbs`, so no console window flashes.

Useful flags:

```powershell
.\install.ps1 -PythonPath "C:\Path\To\python.exe"   # explicit interpreter
.\install.ps1 -NoShortcut                           # skip the shortcuts
.\install.ps1 -DryRun                               # print the steps, write nothing
.\install.ps1 -Uninstall                            # remove only the ag-widgets entries
```

The installer is idempotent: re-running it after a `git pull` refreshes the hook command, the sidecar manifest and the dependencies without duplicating entries. With `-DryRun` a too-old Node.js is reported as a warning instead of an error, so a preview never stops halfway.

### 2.4 Verify the installation

```powershell
python collector\collector.py --doctor
python collector\collector.py --doctor-json
python collector\collector.py --doctor --online
```

`--doctor` is 100% read-only: it exits `0` when nothing failed and `1` when at least one check failed. `--doctor-json` prints the same result as an anonymized report you can paste into an issue — it never contains the token or the topic, and paths are collapsed to `~` and `%LOCALAPPDATA%`. `--online` adds the `ntfy_reachability` and `zcode_api` probes.

Each line starts with `[OK]`, `[WARN]` or `[FAIL]`, the report ends with a counts line and, when something is not `ok`, a "Remediation hints" block.

Expect **8 offline checks**; on a finished machine they are all `ok`. A few `warn` results before you have opened an Antigravity chat or signed in to ZCode are normal — `annotations_dir`, `state_freshness` and (with `--online`) `zcode_api` are the usual pre-sign-in warns. Per-check remediation lives in [AGENT_INSTALL.md](AGENT_INSTALL.md).

### 2.5 ZCode sign-in (optional)

The best path is the **ВОЙТИ В Z.AI** button in card `03 / ACCESS` of the widget: it opens the official z.ai OAuth page and stores the token locally. CLI equivalents:

```powershell
python collector\zcode_auth.py                     # browser flow
python collector\zcode_auth.py --status            # test the saved token
python collector\zcode_auth.py --set-token-stdin   # paste a JWT on stdin
python collector\zcode_auth.py --clear-token       # forget the saved token
```

Never pass a JWT as a command-line argument — it leaks into process lists and shell history; `--set-token-stdin` exists for exactly that reason. The token is written to `secrets.json` as DPAPI ciphertext, never to the repository. Without a token the card honestly reads "not connected"; there is no scraping fallback.

**AG Context** (the Antigravity-only Android build) has no ZCode client at all — no sign-in button, no ZCode card and no ZCode requests. Use **AG Widgets** if you want vouchers and balance on the phone.

### 2.6 Launch and controls

Double-click the **AG Widgets** shortcut, run `start-widget.vbs`, or:

```powershell
cd desktop
npm start
```

Title bar: **⟳ / F5** refresh, **☀/☾** theme, **⋯** menu, **−** minimize to taskbar, **×** quit. Right-click opens the same menu; drag the title bar to move the widget. The window is fixed at 400×640, always-on-top by default, and lives in the tray.

---

## 3. Android App & Widget Setup

One code base produces two signed APKs with different application ids, so they can be installed side by side:

| | **AG Widgets** | **AG Context** |
|---|---|---|
| Antigravity context + quotas | yes | yes |
| ZCode vouchers, balance, OAuth | yes | **no** |
| Works without a PC | yes, for ZCode | no |
| package | `dev.agwidgets` | `dev.agwidgets.context` |

**AG Widgets** sets `BuildConfig.ZCODE_ENABLED = true` (Antigravity + ZCode) and is the recommended build — ZCode works immediately, and Antigravity can be connected later. **AG Context** sets `BuildConfig.ZCODE_ENABLED = false`: it has no ZCode sign-in button, no ZCode card and no ZCode network requests at all.

### 3.1 Download the ready APK (or build it)

The `v1.0.0` release ships both signed community APKs, so no build tools are needed:

- **AG Widgets 1.0.0** (Antigravity + ZCode): [`AG-Widgets-1.0.0.apk`](https://github.com/DmytroHnylytskyi/ag_widgets/releases/latest/download/AG-Widgets-1.0.0.apk)
- **AG Context 1.0.0** (Antigravity only): [`AG-Context-1.0.0.apk`](https://github.com/DmytroHnylytskyi/ag_widgets/releases/latest/download/AG-Context-1.0.0.apk)

Each APK has a `.sha256` checksum attached to the release; verify it before installing. Both are community builds with no personal ntfy topic inside.

### 3.2 Build the community APKs

JDK 17 and the Android SDK are only needed when you build locally. From the repository root:

```powershell
cd android
.\gradlew.bat :app:assembleFullDebug -Pcommunity=true
.\gradlew.bat :app:assembleContextDebug -Pcommunity=true
```

Outputs:

```text
android\app\build\outputs\apk\full\debug\app-full-debug.apk
android\app\build\outputs\apk\context\debug\app-context-debug.apk
```

Signed release builds use the project key stored outside the repository:

```powershell
.\gradlew.bat :app:assembleFullRelease :app:assembleContextRelease `
  -Pcommunity=true --no-configuration-cache
```

The full release procedure is in [RELEASING.md](RELEASING.md).

**Always build with `-Pcommunity=true` for anything you share.** Without that flag the build reads `agw.topic` from `android/local.properties` and bakes your personal ntfy topic into `BuildConfig` — sharing such an APK hands your private channel to whoever installs it. A personal build is only for your own phone: put `agw.topic=<your-topic>` into `android/local.properties` (gitignored) and run the same command without the flag; Gradle prints a warning when a topic is embedded. `-Pcommunity=true` is mandatory for every public build, release included.

### 3.3 Install via ADB (or copy the APK)

```powershell
adb devices
adb install -r android\app\build\outputs\apk\full\debug\app-full-debug.apk
adb install -r android\app\build\outputs\apk\context\debug\app-context-debug.apk
```

Pick the APK you actually need: install only one build, or both if you want to compare them (they have different application ids).

`adb devices` must list `<serial>   device`. For wireless debugging: on the phone enable Developer options → Wireless debugging, then `adb pair <ip>:<port>` followed by `adb connect <ip>:<port>`. Or copy the APK to the phone and tap it, allowing "install unknown apps" for the file manager.

Application ids: `dev.agwidgets` (**AG Widgets**) and `dev.agwidgets.context` (**AG Context**). The two builds can be installed side by side. If an earlier private build was installed under a different id, uninstall it first: Android does not migrate preferences between application ids.

### 3.4 First-run checklist

1. Open the app and allow `POST_NOTIFICATIONS`.
2. Choose a mode on the onboarding screen:
   - **Только ZCode** (ZCode only, **AG Widgets**): no PC is required; the phone checks vouchers and balance itself. Sign in later with **Войти в Z.ai**.
   - **С компьютером** (With a PC): needs the `ntfy_topic` printed by the desktop installer. Get it on the PC with `python collector\collector.py --show-topic`.
   **AG Context** has no ZCode client, so it offers only the PC mode ("AG Context работает вместе с коллектором на компьютере").
3. In the PC mode, paste the topic into the topic field and tap **Сохранить** (Save).
4. Tap **Обновить** (refresh) to confirm the PC data arrives.
5. Optional (**AG Widgets** only): sign in to Z.ai in the app for autonomous voucher and balance checks.
6. Add the 4×2 widget to the home screen.

The mode can be changed later from Settings → **Диагностика** (Diagnostics). The app refreshes every 15 minutes — the WorkManager minimum, and best-effort only. The 4×2 widget is resizable and follows the app's theme (system by default).

### 3.5 Widget gestures

- Tap the widget body → opens the app.
- Tap **⟳** on the widget → direct parallel refresh (ntfy + ZCode, 4 s timeout) that does not wait for the background WorkManager cycle.
- Drag the widget vertically → the extra context bar appears when there is room.
- Clearing the `ntfy_topic` and saving immediately drops the cached PC data and cancels pending quota-reset reminders; the ZCode token is kept.
- After you switch chats on the PC, the desktop publishes the new active chat with a 15 s throttle (expect ~15–20 s end to end), so press **⟳** on the phone to pick it up.

## OEM battery optimization

Android vendors aggressively kill background work; without these settings the 15-minute refresh and local reset reminders stop silently.

### Xiaomi / Redmi / POCO (HyperOS / MIUI)

Settings → Apps → Manage apps → AG Widgets → **Autostart** ON; then Battery saver → **No restrictions**. If HyperOS still kills the worker, open Recents, long-press the card and lock it.

### Samsung (One UI)

Settings → Battery → Background usage limits → make sure AG Widgets is NOT in "Sleeping apps" / "Deep sleeping apps" (**Never auto sleeping apps**); Settings → Apps → AG Widgets → Battery → **Unrestricted**.

### Google Pixel / stock Android

Settings → Apps → AG Widgets → App battery usage → **Allow background usage** → **Unrestricted**.

Verification: after these settings, tap **⟳**, wait one WorkManager cycle (15 minutes), then run `python collector\collector.py --doctor --online` on the PC.

---

## 4. Troubleshooting

| Symptom | Diagnostic command | Fix |
|---|---|---|
| Installer fails with exit code 9009 / "python not recognized" / the Store opens | `where.exe python` | It is the `WindowsApps` stub. Install Python 3.10+ from python.org, disable App Execution Aliases, retry with `-PythonPath`. |
| `--doctor` reports `agy_cli: fail` | `python collector\collector.py --doctor-json` | Antigravity is not installed or not found: install Antigravity 2.0, verify `~/.gemini/bin/agy.exe`, or set `AGY_PATH`. |
| Widget window never appears / Electron binary missing | `Test-Path desktop\node_modules\electron\dist\electron.exe` | `cd desktop; node node_modules\electron\install.js` (or re-run `npm ci`). |
| `zcode_api: fail` (auth expired / 429 / `fetch failed`) | `python collector\zcode_auth.py --status`; log at `%LOCALAPPDATA%\ag-widgets\state\desktop_errors.log` | Re-sign in via **ВОЙТИ В Z.AI** or `--set-token-stdin`; on 429 wait and retry. |
| `secrets_storage: fail` (DPAPI cannot decrypt after a Windows profile/user migration) | `python collector\collector.py --doctor-json` | The ciphertext belongs to the old Windows profile. Re-enter the token with `--set-token-stdin` and let the collector create a new topic; do not copy `secrets.json` between machines. |
| **AG Context** has no ZCode card or sign-in button | — | Expected: `BuildConfig.ZCODE_ENABLED = false`, so there is no ZCode client by design. Install **AG Widgets** (`dev.agwidgets`) if you want vouchers and balance on the phone. |
| Android widget does not update in the background | `python collector\collector.py --doctor --online` on the PC | Apply the OEM battery/autostart settings above; check that the topic on the phone matches `--show-topic`. |
| The phone shows the wrong/old chat | `python collector\collector.py --doctor-json` (look at `annotations_dir`) | Chat switching is debounced ~15–20 s; open a chat in Antigravity so annotation files exist, then press **⟳** on the phone. |
| PC quotas look stale on the phone after changing the topic | `python collector\collector.py --show-topic` | Clearing and saving the `ntfy_topic` on the phone drops the cached PC state and cancels stale reset alerts; paste the current topic and tap **⟳**. |
| `antigravity_hook` or `antigravity_sidecar` is `warn` | `python collector\collector.py --doctor` | Re-run `.\install.ps1`; the hook and the sidecar are re-registered idempotently. |
| `ntfy_reachability: fail` behind a corporate proxy or firewall | `python collector\collector.py --doctor --online` | Allow `https://ntfy.sh` and `https://zcode.z.ai` outbound; without them the phone cannot receive PC data. |

---

## 5. Uninstall & Clean Data Wipe

```powershell
.\install.ps1 -Uninstall -DryRun   # preview
.\install.ps1 -Uninstall           # apply
```

Uninstall removes **only** ag-widgets entries: the hook, the sidecar manifest and its config entry, the `statusLine` (restoring the previous one from the install backup when it exists) and the shortcuts. `%LOCALAPPDATA%\ag-widgets` is deliberately kept:

```text
%LOCALAPPDATA%\ag-widgets\
  config.json     thresholds, model_windows, python_path
  secrets.json    ZCode token + ntfy topic (DPAPI ciphertext on Windows)
  state\          context.json, status.json, agy_status.json, desktop_errors.log
  backups\        install-<timestamp>\ and uninstall-<timestamp>\
```

Full removal of runtime data, secrets included:

```powershell
Remove-Item -LiteralPath "$env:LOCALAPPDATA\ag-widgets" -Recurse -Force
```

This deletes the ZCode token and the ntfy topic, so the phone must be paired again with a fresh topic. Install and uninstall backups under `%LOCALAPPDATA%\ag-widgets\backups\` go with the same command; keep them if you want to restore another tool's `statusLine` later.

Android: remove the widgets from the home screen first, then uninstall the app — `dev.agwidgets` (**AG Widgets**) and/or `dev.agwidgets.context` (**AG Context**) — with a long-press → App info → Uninstall.

Optional: delete the cloned repository folder.

## See also

- [INSTALL_GUIDE.ru.md](INSTALL_GUIDE.ru.md) — the Russian version of this guide.
- [AGENT_INSTALL.md](AGENT_INSTALL.md) — unattended installation by an AI agent, with the per-check remediation table.
- [../README.md](../README.md) — architecture, privacy model, configuration, tests.
- [CONTEXT_HEALTH.md](CONTEXT_HEALTH.md) — why the yellow/red thresholds are 40% and 65%.
