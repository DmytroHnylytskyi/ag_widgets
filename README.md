# AG Widgets

**See the context of the Antigravity chat you actually have open — and never miss a ZCode voucher.**

![Desktop widget preview](docs/desktop-preview.jpg)

Antigravity shows 5h/weekly limits as percentages with reset times, but it does not show the context usage of the specific chat you have open — a known gap in the product. ZCode, meanwhile, regularly hands out vouchers for 300–500M tokens; if you do not check every day, a voucher can expire before you notice.

AG Widgets exists for those two jobs:

1. **Show the context of the currently open Antigravity chat.** It detects the open chat from Antigravity's annotation files and displays that chat's model, tokens/window, health zone, and recent conversations — not the last chat that happened to answer.
2. **Never miss a ZCode voucher.** It polls the ZCode API for vouchers and balance, shows the voucher state on the desktop, and forwards it to the phone even when the PC is off.

Two clients share one visual language (black glass, warm cream accent, Space Grotesk + monospace labels, light/dark themes):

1. **Desktop widget** — Electron on Windows. Shows the open chat's context, 5h/weekly quotas, ZCode vouchers and balance.
2. **Android app + 4×2 widget** — Kotlin/Jetpack Glance. Two builds: **AG Widgets** (Antigravity + ZCode) and **AG Context** (Antigravity only). Works with the PC off for ZCode: polls a private ntfy channel and `zcode.z.ai` directly, and schedules a local reminder at quota reset time.

Russian documentation: [README.ru.md](README.ru.md).

---

## What it shows

| Card | Desktop | Android |
|---|---|---|
| **01 · Context** | Context of the chat open right now: model, tokens/window, health zone, recent chats | 4 quota rings + the PC chat summary |
| **02 · Limits** | Antigravity 5h/weekly percentages and reset countdown, next to the context | Same data in the 4×2 widget |
| **03 · Access** | ZCode vouchers, balance, plan, OAuth sign-in | Direct ZCode polling without the PC |

The desktop widget is fixed at 400×640, always-on-top by default, and lives in the tray. The Android widget is a resizable 4×2 Glance surface with light/dark support.

### Two Android builds

One codebase produces two signed APKs. They have different application ids and can be installed side by side.

| | **AG Widgets** | **AG Context** |
|---|---|---|
| Antigravity context + quotas | yes | yes |
| ZCode vouchers, balance, OAuth | yes | **no** |
| Works without a PC | yes, for ZCode | no |
| package | `dev.agwidgets` | `dev.agwidgets.context` |

**AG Widgets** is the recommended download: ZCode works immediately without a PC, and the Antigravity part can be connected later. **AG Context** is for people who only want Antigravity and do not want any ZCode client, token storage, or ZCode network calls in the APK.

If both builds are installed and connected to the same ntfy topic, reset notifications will be duplicated. Use one build for the PC channel.

---

## Architecture

```text
Windows
  Antigravity hook (PostInvocation/Stop)
    └─► %LOCALAPPDATA%\ag-widgets\state\context.json
  Antigravity CLI statusLine
    ├─► agy_status.json              (quota, plan tier, context window)
    └─► model_windows_learned.json   (real window reported by the CLI)
  Collector sidecar (once a minute)
    ├─ agy -p "/usage" --output-format json   → Antigravity quota
    ├─ zcode.z.ai marketing/touch + billing   → vouchers and balance
    └─ POST https://ntfy.sh/<private-topic>   → numbers only, no secrets

Android
  WorkManager (every 15 minutes, best-effort)
    ├─ GET ntfy.sh/<topic>/json?poll=1&since=latest → quota + PC context
    ├─ GET zcode.z.ai (OAuth token)                  → vouchers and balance
    └─ local reminder near each quota reset_time
```

### Repository layout

```text
agwidgets/            Python core (paths, context model, Antigravity, ZCode, ntfy, status)
collector/            CLI entry points: collector.py, agy_statusline.py, zcode_auth.py
hook/                 Antigravity hook entry point
desktop/              Electron app
  src/main/           paths, storage, window, tray, IPC, collector runner, OAuth
  src/renderer/       format, context, quotas, zcode, preview, app
android/              Kotlin app, Glance widget, WorkManager jobs
tests/                Python unit tests
docs/                 install guides (EN/RU), agent runbook, preview image, context-health notes
```

Runtime data never lives in the repository:

```text
%LOCALAPPDATA%\ag-widgets\
  config.json         runtime configuration (thresholds, model windows, python_path)
  secrets.json        ZCode token, ntfy topic (DPAPI `dpapi:v1:` on Windows) and device id
  state\              context.json, status.json, agy_status.json, learned windows
```

---

## Requirements

- Windows 10/11 (acrylic blur requires Windows 11 22H2+)
- Python 3.10+ ([python.org](https://www.python.org/downloads/) build, not the Microsoft Store stub)
- Node.js 22.12+ and npm (Electron 45)
- [Google Antigravity](https://antigravity.google) — `agy.exe` is auto-detected in `~/.gemini/bin/agy.exe` (being on `PATH` is not required; `AGY_PATH` overrides)
- Optional: JDK 17 + Android SDK for the Android app

---

## Install (Windows)

```powershell
git clone https://github.com/DmytroHnylytskyi/ag_widgets.git ag-widgets
cd ag-widgets
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

**Detailed guide:** [docs/INSTALL_GUIDE.md](docs/INSTALL_GUIDE.md) · **Agent runbook:** [docs/AGENT_INSTALL.md](docs/AGENT_INSTALL.md)

The installer:

1. creates `%LOCALAPPDATA%\ag-widgets\config.json` from `config.example.json`;
2. registers the context hook in `~/.gemini/config/hooks.json`;
3. installs the collector sidecar (once a minute) and enables it in Antigravity;
4. configures the Antigravity CLI status line;
5. installs Electron dependencies;
6. runs the collector once to create your private ntfy topic;
7. creates **AG Widgets** shortcuts on the Desktop and in the Start Menu.

Before changing the Antigravity integration, the installer backs up the existing `~/.gemini/config/*` files to `%LOCALAPPDATA%\ag-widgets\backups\install-<timestamp>\`.

Useful flags:

```powershell
.\install.ps1 -PythonPath "C:\Path\To\python.exe"   # explicit interpreter
.\install.ps1 -NoShortcut                           # skip shortcuts
.\install.ps1 -DryRun                               # print the steps, write nothing
.\install.ps1 -Uninstall                            # remove only ag-widgets entries
```

`-Uninstall` removes only the entries ag-widgets registered and leaves `%LOCALAPPDATA%\ag-widgets` untouched.

> The ntfy topic is a secret. Anyone who knows it can read your published numbers. The topic and the ZCode token live in `secrets.json` — on Windows both are encrypted with DPAPI (`dpapi:v1:`); on Android they sit in private `SharedPreferences` under `AndroidKeyStore` AES-256-GCM (`ks:v1:`). Do not commit or share them.

### Install with an AI Agent (1 prompt)

Paste this into your AI agent (Antigravity, ZCode, Cursor, Claude Code, ...):

```text
Read docs/AGENT_INSTALL.md in this repository and install AG Widgets on my Windows machine.
Start with Phase 0 (read-only recon) and show me the doctor report and the install.ps1 -DryRun plan.
Do not run install.ps1 (without -DryRun), adb install, or -Uninstall until I explicitly confirm.
```

If you also want the Android part, tell the agent which build to install: **AG Widgets** (Antigravity + ZCode) or **AG Context** (Antigravity only).

### Launch

Double-click the **AG Widgets** shortcut, run `start-widget.vbs`, or:

```powershell
cd desktop
npm start
```

Title-bar controls: **⟳ / F5** refresh, **☀/☾** theme, **⋯** menu, **−** minimize to the taskbar, **×** quit. Right-click opens the same menu; drag the title bar to move the widget.

### ZCode sign-in

The easiest path is the **ВОЙТИ В Z.AI** button in card `03 / ACCESS`. It opens the official Z.ai page and saves the token locally. The same flow is available from the CLI:

```powershell
python collector\zcode_auth.py            # browser sign-in
python collector\zcode_auth.py --status   # test the saved token
python collector\zcode_auth.py --set-token-stdin   # paste a JWT via stdin (keeps it out of argv)
python collector\zcode_auth.py --clear-token       # forget the saved token
```

Without a token the card honestly shows "not connected". There is no UI-scraping fallback: voucher state comes only from the ZCode API.

### Configuration

`%LOCALAPPDATA%\ag-widgets\config.json`:

```json
{
  "model_windows": {},
  "context_thresholds": {
    "yellow_pct": 40,
    "red_pct": 65
  }
}
```

- `model_windows` — optional manual overrides, e.g. `{ "gemini-4-neon": 524288 }`. Empty means "use the CLI value or the built-in table".
- `context_thresholds` — percentage of the real window where the card turns yellow/red.

The collector also stores `python_path` here during installation so the widget can run refreshes.

### Diagnostics

Read-only install checks, safe to run at any time (`--doctor` exits with `1` when a check fails):

```powershell
python collector\collector.py --doctor          # human-readable checks
python collector\collector.py --doctor-json     # anonymized JSON to paste into an issue
python collector\collector.py --doctor --online # also probe ntfy.sh and the ZCode API
```

`--doctor-json` never prints the token or the topic. To print the private ntfy topic for pairing the phone, run `python collector\collector.py --show-topic`. For credentials, `python collector\zcode_auth.py --set-token-stdin` reads a JWT from stdin (it stays out of shell history and process lists), and `--clear-token` removes the saved token.

### Design preview without Electron

Open `desktop/index.html` with a mock state:

```text
desktop/index.html?preview=red
desktop/index.html?preview=yellow
desktop/index.html?preview=green
desktop/index.html?preview=empty
desktop/index.html?preview=offline
desktop/index.html?preview=voucher
desktop/index.html?preview=disabled
desktop/index.html?preview=summary
desktop/index.html?preview=red&theme=light
```

---

## Context health

There is no single "hallucination token count": quality degrades continuously and depends on the task, distractors, and where the fact sits in the prompt.

AG Widgets therefore measures zones relative to the **real** window reported by Antigravity:

| Zone | Threshold | Meaning |
|---|---:|---|
| 🟢 OK | < 40% | normal working range |
| 🟡 Attention | 40–65% | quality on hard tasks is already dropping |
| 🔴 Risk | > 65% | reliable part of the window is over — run `/compact` |

Absolute examples:

| Model | 🟡 from | 🔴 after |
|---|---:|---:|
| Gemini 4 Neon (512K) | ~210K | ~341K |
| 1M models (Carbon/Argon/Hydrogen, Claude 5.5) | ~419K | ~682K |
| GPT-OSS 120B (128K) | ~52K | ~85K |

Windows used by default:

| Model | Window |
|---|---:|
| Gemini 4 Neon | 512K (1M only in the Gemini API) |
| Gemini 4 Carbon / Argon / Hydrogen | 1M |
| Gemini 3.8 Flash | 1M |
| Claude Opus 5.5 / Sonnet 5.5 | 1M |
| GPT-OSS 120B | 128K |

The Antigravity CLI reports the exact `context_window_size`; the status line caches it per model and it takes priority over the table. See [docs/CONTEXT_HEALTH.md](docs/CONTEXT_HEALTH.md) for the research and sources.

---

## Android app

### Download

Both are signed community builds with no personal ntfy topic. Checksums (`.sha256` files) are attached to the release — verify them before installing.

- **AG Widgets 1.0.0** (recommended, Antigravity + ZCode):
  https://github.com/DmytroHnylytskyi/ag_widgets/releases/latest/download/AG-Widgets-1.0.0.apk
- **AG Context 1.0.0** (Antigravity only):
  https://github.com/DmytroHnylytskyi/ag_widgets/releases/latest/download/AG-Context-1.0.0.apk

### Build

JDK 17 and the Android SDK are required. Release builds are signed with the project release key stored outside the repository; see [docs/RELEASING.md](docs/RELEASING.md).

```powershell
cd android

# Both signed public releases: no embedded topic.
.\gradlew.bat :app:assembleFullRelease :app:assembleContextRelease `
  -Pcommunity=true --no-configuration-cache

# Local debug builds.
.\gradlew.bat :app:assembleFullDebug :app:assembleContextDebug -Pcommunity=true

# Personal full build: reads agw.topic from local.properties (gitignored).
.\gradlew.bat :app:assembleFullRelease --no-configuration-cache
```

Outputs:

```text
android/app/build/outputs/apk/full/release/app-full-release.apk
android/app/build/outputs/apk/context/release/app-context-release.apk
```

**Before publishing an APK**, always build with `-Pcommunity=true` so your personal ntfy topic is not compiled into `BuildConfig`.

### First run

1. Open the app and allow notifications.
2. Choose a mode:
   - **ZCode only** (AG Widgets): tap **Войти в Z.ai**. No PC is required.
   - **With a PC**: paste the `ntfy_topic` printed by the desktop installer.
3. Tap **Обновить**, then **Добавить виджет на экран**.

The app refreshes every 15 minutes (WorkManager minimum). Manual refresh: the button in the app, **⟳** in the context card, or the **⟳** button on the widget, which refreshes directly (ntfy + ZCode, ~4 s timeout) instead of waiting for the WorkManager cycle. A tap on the widget opens the app. Drag the widget taller to reveal the extended context bar. After switching chats on the PC, the desktop publishes the new active chat immediately; press **⟳** on the phone to pick it up.

> Application ids are `dev.agwidgets` (AG Widgets) and `dev.agwidgets.context` (AG Context). The two builds can be installed side by side. An older private build under `dev.dmitr.agwidgets` must be uninstalled first.

The header **☀/☾** button switches light/dark mode; the 4×2 widget follows the app. By default the theme follows the system.

---

## Privacy

- ntfy receives quota numbers, token counts, model id, context level, conversation UUID, voucher flag, and a short voucher label (≤100 characters). No email, prompts, chat/project titles, or tokens.
- `ntfy_topic` is 96 random bits and lives in `secrets.json` together with the ZCode token. On Windows both values are DPAPI ciphertexts (`dpapi:v1:`); on Android they are private `SharedPreferences` encrypted by `AndroidKeyStore` AES-256-GCM (`ks:v1:`).
- The ZCode token is stored locally in `secrets.json` (desktop) or private SharedPreferences (Android).
- Android uses `allowBackup="false"` and `dataExtractionRules`, so preferences do not go to cloud backup or device transfer.
- The community APKs contain no topic or token. A personal APK embeds only the topic from `local.properties`.
- **AG Context** has no ZCode client, OAuth flow, token storage, or ZCode network calls.

---

## Tests

```powershell
# Python: model windows, context thresholds, ZCode parsing, change detection
python -m unittest discover -s tests -p "test_*.py" -v

# Desktop: Node built-in test runner and a syntax pass (no npm install needed)
node --test desktop/test/*.test.js
node desktop/scripts/check-syntax.js

# Android: status parsing and PC/phone merge, both flavors
cd android
.\gradlew.bat :app:testFullDebugUnitTest :app:testContextDebugUnitTest

# Both community builds
.\gradlew.bat :app:assembleFullRelease :app:assembleContextRelease `
  -Pcommunity=true --no-configuration-cache
```

GitHub Actions runs all three suites on every push to `main` and on pull requests (`.github/workflows/ci.yml`).

---

## Limitations

- **AG Context** needs the desktop collector for everything: it has no ZCode-only mode. **AG Widgets** only needs a PC for the Antigravity cards; ZCode works standalone.
- **Acrylic** requires Windows 11 22H2+. Windows 10 falls back to a translucent background without the system blur.
- The Android widget cannot blur wallpapers system-wide; it uses a glass imitation (gradient, border, highlight).
- **WorkManager** is best-effort: 15-minute minimum, and battery optimizations can delay it. Reset reminders are scheduled locally but are not guaranteed to the second.
- **ZCode API** is an open protocol used by ZCode; endpoints can change. There is no scraping fallback. If ZCode returns HTTP 429, both clients back off for a few minutes instead of retrying in a loop.
- Model windows can change. The CLI value is picked up automatically; the built-in table is only a fallback.

---

## Disclaimer

AG Widgets is an unofficial community project. It is not affiliated with, endorsed by, or supported by Google or Z.ai. Antigravity and ZCode are trademarks of their respective owners.

---

## License

MIT. See [LICENSE](LICENSE).
