# Changelog

## 1.1.0 — 2026-11-27

Everything from waves 2 and 3 of the redesign, plus the follow-up fixes below.

Desktop:

- Six themes (system, graphite, paper, oled, glass, classic). The author's
  **classic** look is the default and keeps the Windows acrylic material; the
  other themes are opt-in customization.
- Settings window with live preview, font scale, opacity, corner radius,
  density, custom accent, block reordering and JSON export/import.
- Four window form factors: full, compact, minibar, pill (with remembered
  bounds per mode) and a fractional-DPI safe drag.
- Sync tab: `agw://pair` QR code, optional AES-256-GCM E2E for the ntfy channel,
  custom HTTPS ntfy server, ntfy on/off.
- Native Windows notifications for a red context zone, new ZCode vouchers and
  5-hour quota resets, with per-channel toggles and de-duplication.
- RU/EN interface with live switching; the tray menu and notifications follow.
- `pythonw.exe` is preferred on Windows (no console flashes), ZCode OAuth
  retries once after a fresh sign-out, chat titles reach the recent-chat chips.
- Signing out of ZCode publishes the signed-out state immediately again.
- New Electron 45 installs no longer fail because the binary was skipped.

Android (both `full` and `context`):

- Redesigned dashboard (context → limits → ZCode) and a Settings screen with
  the same six themes, masked ZCode token input, and RU/EN.
- Per-widget configuration activity: blocks, block order, presets, custom
  48 dp sliders, JSON import/export, classic 4×2 restored 1:1.
- QR pairing with the Play Services code scanner (no CAMERA permission) plus a
  manual paste fallback, and optional E2E decryption of the ntfy status.
- The widget refresh button now updates immediately instead of waiting for
  WorkManager, honoring the 429 backoff and the ZCode-only mode.
- Clearing or changing the ntfy topic drops cached PC data and cancels pending
  reset alarms.

Docs and CI:

- EN/RU installation guides, an AI-agent install runbook, and docs tests that
  guard links, doctor coverage and secrets.

Fixed (each one is documented with root cause and rejected alternatives in
`docs/BUGFIXES.md`):

- Duplicate ntfy publishes when the sidecar and a manual refresh overlapped;
  collector errors are reported instead of failing with a traceback.
- A keystore race could permanently lose the ntfy topic, the ZCode token or the
  E2E key; unreadable secrets now say so instead of looking unconfigured.
- The refresh button could store an invalid topic and silently drop the E2E key;
  the pairing dialog no longer reopens after a rotation.
- A custom accent was unreadable on `classic-light` (2.07:1); accent tokens now
  use the real card surface of every theme.
- Muted notifications never reported a chat that was already red; an untimed 5 h
  reset silenced all later resets.
- The widget and the collector could pick different active chats while an
  annotation was being written.
- The free ntfy quota (250 messages/day/IP) is budgeted; the active-chat
  debounce is 60 s, which is also what the phone can actually show.
- Reading all annotations on every state push cost ~134 ms per push with 1000
  chats; parsed entries are cached by (mtime, size).
- A Bearer JWT could follow a redirect to another host; JSON files with a BOM no
  longer stop the collector.
- "No voucher" while ZCode showed one: the voucher feed is version-gated and the
  widget advertised an outdated client version (bug 7 in `docs/BUGFIXES.md`).

## 1.0.0 — 2026-11-25

First public release. Two Android builds from one codebase:

- **AG Widgets** (`dev.agwidgets`): Antigravity context and quotas plus ZCode vouchers/balance.
- **AG Context** (`dev.agwidgets.context`): Antigravity context and quotas only; no ZCode client, OAuth, token storage, or ZCode network calls.

Features:

- Context card for the chat that is open right now, with model, tokens/window, and health zones.
- Antigravity 5h/weekly quota cards with reset countdowns.
- ZCode voucher and balance checks, including OAuth sign-in (full build).
- First-run choice between "ZCode only" and "With a PC".
- Android 4×2 Glance widget with light/dark themes.
- Local reset reminders through WorkManager.
- Private ntfy channel between the desktop collector and the phone.
- Secrets encrypted at rest: Windows DPAPI for the desktop, AndroidKeyStore AES-256-GCM for the phone.
- Honest tri-state voucher reporting (`visible` / `absent` / `unknown`) and the status schema v2.
- Reversible installer (`-DryRun`, `-Uninstall`) and read-only `--doctor` diagnostics.
- Back off for five minutes after a ZCode HTTP 429 instead of retrying in a loop.
- CI for Python, desktop, and Android tests, plus shared golden fixtures.
- Signed Android release APKs; Windows remains source-only.
