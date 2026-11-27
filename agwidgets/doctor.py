"""Read-only installation diagnostics for ``collector.py --doctor``.

The doctor inspects the runtime directory, the Antigravity integration files,
and (optionally) the network. It must never create, modify, or delete files:
every path is built locally instead of going through ``paths.app_dir()``,
``paths.state_dir()``, ``paths.ensure_ntfy_topic()``, or ``paths.file_lock()``,
and secrets are only ever reported as booleans.

Checks are plain dictionaries ``{"name", "status", "detail"}`` with
``status`` in ``{"ok", "warn", "fail"}``. A failing check never aborts the
remaining ones: every probe is wrapped and an unexpected exception becomes a
``fail`` entry without a traceback. Details and reports are anonymized:
the user's home directory becomes ``~`` and ``%LOCALAPPDATA%`` is used for the
runtime root.
"""

from __future__ import annotations

import http.client
import platform as platform_module
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Callable

from . import antigravity, context, crypto, e2e, paths, zcode

SCHEMA_VERSION = 1
TOOL_NAME = "ag-widgets-doctor"
NTFY_PROBE_URL = "https://ntfy.sh"
AGY_VERSION_TIMEOUT_SEC = 5
NETWORK_TIMEOUT_SEC = 10
STATE_FRESH_SEC = 24 * 60 * 60
STATE_FILE_NAMES = ("context.json", "status.json", "agy_status.json")
HOOK_ERROR_LOG_NAMES = ("hook_errors.log", "hook_errors.log.1")
HOOK_NAME = "ag-widgets-context"
SIDECAR_NAME = "ag-widgets-collector"

_STATUS_MARKERS = {"ok": "[OK]", "warn": "[WARN]", "fail": "[FAIL]"}

_HINTS = {
    "python_version": "Install Python 3.10 or newer and pass -PythonPath to install.ps1 if needed.",
    "agy_cli": "Install Antigravity 2.0 or point AGY_PATH at the agy executable.",
    "annotations_dir": "Open a chat in Antigravity so the annotations directory exists.",
    "antigravity_hook": "Re-run install.ps1 to register the ag-widgets-context hook.",
    "antigravity_sidecar": "Re-run install.ps1 to install and enable the collector sidecar.",
    "runtime_config": "Re-run install.ps1 to create config.json, or fix its JSON syntax.",
    "state_freshness": "Start the collector and review state/hook_errors.log for hook failures.",
    "secrets_storage": "Run collector/zcode_auth.py --set-token-stdin and let the collector create the ntfy topic.",
    "ntfy_reachability": "Check the internet connection or proxy settings.",
    "zcode_api": "Check the internet connection; re-enter the ZCode token if it expired.",
}


def _check(name: str, status: str, detail: str) -> dict[str, str]:
    return {"name": name, "status": status, "detail": detail}


def _nonempty_str(value: Any) -> str:
    return value.strip() if isinstance(value, str) and value.strip() else ""


def _format_age(seconds: float) -> str:
    """Compact age label; deterministic and locale independent."""
    if seconds < 60:
        return f"{int(seconds)}s"
    if seconds < 3600:
        return f"{int(seconds // 60)}m"
    if seconds < 86400:
        return f"{int(seconds // 3600)}h"
    return f"{int(seconds // 86400)}d"


def _stringify_bool(value: Any) -> str:
    return "true" if value is True else "false"


def _read_secret_state(app_dir: Path) -> dict[str, Any]:
    """Read ``secrets.json`` without locks or writes.

    Returned values must never be printed: only the boolean flags
    (``token_configured``, ``topic_configured``, ``encrypted``, ...) and the
    list of sanitized error strings may leave this module.
    """
    path = Path(app_dir) / "secrets.json"
    data = paths.read_json_strict(path)
    state: dict[str, Any] = {
        "present": data is not None,
        "token": "",
        "topic": "",
        "e2e_key": "",
        "device_mid": "",
        "token_configured": False,
        "topic_configured": False,
        "e2e_configured": False,
        "encrypted": False,
        "legacy_plaintext": False,
        "errors": [],
    }
    if data is None:
        return state

    errors: list[str] = []
    encrypted_sources = (
        ("zcode_token_enc", _nonempty_str(data.get("zcode_token_enc"))),
        ("ntfy_topic_enc", _nonempty_str(data.get("ntfy_topic_enc"))),
        ("ntfy_e2e_key_enc", _nonempty_str(data.get("ntfy_e2e_key_enc"))),
    )
    for key, raw in encrypted_sources:
        if not raw:
            continue
        if crypto.is_encrypted(raw):
            state["encrypted"] = True
            try:
                plain = crypto.unprotect_secret(raw)
            except crypto.SecretDecryptError as exc:
                errors.append(f"{key} cannot be decrypted ({exc})")
                continue
        else:
            # An ``*_enc`` key without the DPAPI envelope is plaintext in disguise.
            state["legacy_plaintext"] = True
            plain = raw
        if key == "zcode_token_enc":
            state["token"] = plain
        elif key == "ntfy_topic_enc":
            state["topic"] = plain
        else:
            state["e2e_key"] = plain

    token_legacy = _nonempty_str(data.get("zcode_token"))
    topic_legacy = _nonempty_str(data.get("ntfy_topic"))
    e2e_legacy = _nonempty_str(data.get("ntfy_e2e_key"))
    if token_legacy:
        state["legacy_plaintext"] = True
        state["token"] = state["token"] or token_legacy
    if topic_legacy:
        state["legacy_plaintext"] = True
        state["topic"] = state["topic"] or topic_legacy
    if e2e_legacy:
        state["legacy_plaintext"] = True
        state["e2e_key"] = state["e2e_key"] or e2e_legacy

    state["device_mid"] = _nonempty_str(data.get("device_mid"))
    state["token_configured"] = bool(state["token"])
    state["topic_configured"] = bool(state["topic"])
    state["e2e_configured"] = bool(state["e2e_key"])
    state["errors"] = errors
    return state


def _extract_python_script(command: Any) -> str | None:
    """Return the ``*.py`` path from a hook command line, honoring quotes."""
    if not isinstance(command, str):
        return None
    for match in re.finditer(r'"([^"]+)"|(\S+)', command):
        token = (match.group(1) or match.group(2) or "").strip().strip("'\"")
        if token.lower().endswith(".py"):
            return token
    return None


def _check_python_version() -> tuple[str, str]:
    version = platform_module.python_version()
    if sys.version_info >= (3, 10):
        return "ok", f"Python {version} (>= 3.10)"
    return "fail", f"Python {version} is below the required 3.10"


def _check_agy_cli() -> tuple[str, str]:
    executable = antigravity.find_executable()
    if not executable:
        return "fail", "agy CLI not found (set AGY_PATH or install Antigravity 2.0)"
    try:
        proc = subprocess.run(
            [executable, "--version"],
            capture_output=True,
            text=True,
            timeout=AGY_VERSION_TIMEOUT_SEC,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
    except subprocess.TimeoutExpired:
        return "fail", f"agy executable found ({executable}) but --version timed out"
    except (OSError, subprocess.SubprocessError) as exc:
        return "fail", f"agy executable found ({executable}) but cannot run ({type(exc).__name__})"
    if proc.returncode != 0:
        return "fail", f"agy --version exited with code {proc.returncode}"

    output = (proc.stdout or proc.stderr or "").strip().splitlines()
    version = output[0].strip()[:80] if output else ""
    detail = f"agy executable: {executable}"
    if version:
        detail += f" ({version})"
    return "ok", detail


def _check_annotations_dir(home: Path) -> tuple[str, str]:
    directory = Path(home) / ".gemini" / "antigravity" / "annotations"
    if not directory.is_dir():
        return "warn", f"annotations directory not found: {directory}"
    try:
        count = sum(1 for item in directory.glob("*.pbtxt") if item.is_file())
    except OSError as exc:
        return "warn", f"annotations directory cannot be listed ({type(exc).__name__})"
    active = context.detect_active_chat(directory)
    active_text = "active chat detected" if active else "no active chat detected"
    return "ok", f"{count} annotation file(s); {active_text}"


def _check_antigravity_hook(home: Path) -> tuple[str, str]:
    config_path = Path(home) / ".gemini" / "config" / "hooks.json"
    try:
        data = paths.read_json_strict(config_path)
    except paths.ConfigReadError as exc:
        return "fail", f"hooks.json unreadable: {exc}"
    if data is None:
        return "warn", f"hooks.json not found: {config_path}"

    entry = data.get(HOOK_NAME)
    if not isinstance(entry, dict):
        return "warn", f"hook '{HOOK_NAME}' is not registered in hooks.json"
    events = entry.get("PostInvocation")
    first = events[0] if isinstance(events, list) and events else None
    command = first.get("command") if isinstance(first, dict) else None
    script = _extract_python_script(command)
    if not script:
        return "warn", f"hook '{HOOK_NAME}' has no Python script in PostInvocation[0]"

    script_path = Path(script)
    if not script_path.is_absolute():
        script_path = Path(home) / script_path
    if not script_path.exists():
        return "warn", f"hook script not found: {script_path}"
    return "ok", f"hook '{HOOK_NAME}' registered; script present: {script_path}"


def _check_antigravity_sidecar(home: Path) -> tuple[str, str]:
    config_dir = Path(home) / ".gemini" / "config"
    manifest_path = config_dir / "sidecars" / SIDECAR_NAME / "sidecar.json"
    config_path = config_dir / "config.json"
    try:
        config = paths.read_json_strict(config_path)
    except paths.ConfigReadError as exc:
        return "fail", f"Antigravity config.json unreadable: {exc}"

    manifest_present = manifest_path.is_file()
    sidecars = config.get("sidecars") if isinstance(config, dict) else None
    entry = sidecars.get(SIDECAR_NAME) if isinstance(sidecars, dict) else None
    enabled = isinstance(entry, dict) and entry.get("enabled") is True
    detail = (
        f"manifest={_stringify_bool(manifest_present)}, "
        f"enabled={_stringify_bool(enabled)}"
    )
    if manifest_present and enabled:
        return "ok", detail
    return "warn", detail


def _check_runtime_config(app_dir: Path) -> tuple[str, str]:
    config_path = Path(app_dir) / "config.json"
    try:
        data = paths.read_json_strict(config_path)
    except paths.ConfigReadError as exc:
        return "fail", f"config.json unreadable: {exc}"
    if data is None:
        return "warn", f"config.json not created yet: {config_path}"
    return "ok", f"config.json readable ({len(data)} keys)"


def _check_state_freshness(state_dir: Path) -> tuple[str, str]:
    state_dir = Path(state_dir)
    now = time.time()
    parts: list[str] = []
    degraded = False

    for name in STATE_FILE_NAMES:
        path = state_dir / name
        try:
            stat = path.stat()
        except FileNotFoundError:
            parts.append(f"{name}=missing")
            degraded = True
            continue
        except OSError:
            parts.append(f"{name}=unreadable")
            degraded = True
            continue
        age = max(0.0, now - stat.st_mtime)
        parts.append(f"{name}={_format_age(age)}")
        if age > STATE_FRESH_SEC:
            degraded = True

    for name in HOOK_ERROR_LOG_NAMES:
        path = state_dir / name
        try:
            size = path.stat().st_size
        except FileNotFoundError:
            continue
        except OSError:
            parts.append(f"{name}=unreadable")
            degraded = True
            continue
        parts.append(f"{name}=present ({size} bytes)")
        degraded = True

    return ("warn" if degraded else "ok"), "; ".join(parts)


def _check_secrets_storage(app_dir: Path) -> tuple[str, str]:
    try:
        state = _read_secret_state(app_dir)
    except paths.ConfigReadError as exc:
        return "fail", f"secrets.json unreadable: {exc}"

    if not state["present"]:
        return "warn", "secrets.json not created yet (no ZCode token or ntfy topic)"

    flags = (
        f"token_configured={_stringify_bool(state['token_configured'])}, "
        f"topic_configured={_stringify_bool(state['topic_configured'])}, "
        f"e2e_configured={_stringify_bool(state['e2e_configured'])}, "
        f"encrypted={_stringify_bool(state['encrypted'])}"
    )
    if state["errors"]:
        return "fail", f"{' ; '.join(state['errors'])}; {flags}"
    if state["legacy_plaintext"]:
        return "warn", f"plaintext, migration pending; {flags}"
    if not state["token_configured"] and not state["topic_configured"]:
        return "warn", f"no ZCode token or ntfy topic configured; {flags}"
    return "ok", flags


def _probe_ntfy_status(server_url: str = NTFY_PROBE_URL) -> int:
    """Return the HTTP status of the ntfy server without reading the body."""
    try:
        with urllib.request.urlopen(server_url, timeout=NETWORK_TIMEOUT_SEC) as response:
            return int(getattr(response, "status", 0) or 0)
    except urllib.error.HTTPError as exc:
        code = int(getattr(exc, "code", 0) or 0)
        try:
            exc.close()
        except (OSError, ValueError):
            pass
        return code


def _check_ntfy_reachability(app_dir: Path) -> tuple[str, str]:
    """Probe the configured ntfy server, honoring ``ntfy_enabled``.

    Only the HTTP status leaves this check: the server URL itself is never
    printed, so a private relay path cannot leak into a shared report.
    """
    try:
        config = paths.read_json_strict(Path(app_dir) / "config.json") or {}
    except paths.ConfigReadError:
        config = {}
    if config.get("ntfy_enabled", True) is False:
        return "ok", "ntfy publishing disabled (PC-only mode)"
    try:
        server_url = e2e.normalize_ntfy_server(config.get("ntfy_server"))
    except ValueError:
        server_url = e2e.DEFAULT_NTFY_URL

    try:
        code = _probe_ntfy_status(server_url)
    except (urllib.error.URLError, OSError, ValueError, http.client.HTTPException) as exc:
        return "fail", f"cannot reach the configured ntfy server ({type(exc).__name__})"
    if 0 < code < 500:
        return "ok", f"ntfy server reachable (HTTP {code})"
    return "fail", f"ntfy server returned HTTP {code}"


def _check_zcode_api(app_dir: Path) -> tuple[str, str]:
    try:
        state = _read_secret_state(app_dir)
    except paths.ConfigReadError as exc:
        return "fail", f"secrets.json unreadable: {exc}"
    if state["errors"]:
        return "fail", "ZCode token cannot be decrypted; re-enter the token"
    token = state["token"]
    if not token:
        return "warn", "ZCode token not configured; API probe skipped"
    device_mid = state["device_mid"]
    if not device_mid:
        return "warn", "device id not configured; API probe skipped"

    result = zcode.query_zcode_api(token, device_mid)
    if not isinstance(result, dict):
        return "fail", "ZCode API returned an unexpected response"
    auth_ok = result.get("auth_ok")
    error_kind = result.get("error_kind")
    if error_kind == "auth_expired" or auth_ok is False:
        return "fail", "ZCode token rejected (auth_expired)"
    if error_kind == "network":
        return "fail", "ZCode API unreachable (network)"
    if error_kind:
        return "warn", f"ZCode API probe degraded ({error_kind})"
    if auth_ok is True:
        return "ok", "ZCode API reachable; token accepted"
    return "warn", "ZCode API responded but token state is unknown"


def run_checks(
    network: bool = False,
    *,
    home: Path | None = None,
    app_dir: Path | None = None,
) -> list[dict[str, Any]]:
    """Run all diagnostics against explicit roots without touching the disk.

    ``home`` defaults to ``Path.home()`` and ``app_dir`` to
    ``%LOCALAPPDATA%/ag-widgets``; the defaults are computed without creating
    anything, unlike :func:`agwidgets.paths.app_dir`.
    """
    home_path = Path(home) if home is not None else Path.home()
    app_path = (
        Path(app_dir)
        if app_dir is not None
        else paths.local_app_data() / paths.APP_DIR_NAME
    )
    state_path = app_path / "state"

    checks: list[dict[str, Any]] = []

    def add(name: str, probe: Callable[[], tuple[str, str]]) -> None:
        try:
            status, detail = probe()
        except Exception as exc:  # noqa: BLE001 - a probe must never abort the report.
            checks.append(_check(name, "fail", f"{type(exc).__name__}: {exc}"))
            return
        checks.append(_check(name, str(status), str(detail)))

    add("python_version", _check_python_version)
    add("agy_cli", _check_agy_cli)
    add("annotations_dir", lambda: _check_annotations_dir(home_path))
    add("antigravity_hook", lambda: _check_antigravity_hook(home_path))
    add("antigravity_sidecar", lambda: _check_antigravity_sidecar(home_path))
    add("runtime_config", lambda: _check_runtime_config(app_path))
    add("state_freshness", lambda: _check_state_freshness(state_path))
    add("secrets_storage", lambda: _check_secrets_storage(app_path))
    if network:
        add("ntfy_reachability", lambda: _check_ntfy_reachability(app_path))
        add("zcode_api", lambda: _check_zcode_api(app_path))
    return checks


def run_doctor(network: bool = False) -> list[dict[str, Any]]:
    """Run diagnostics against the real runtime paths (read-only)."""
    return run_checks(network=network)


def _anonymize(text: str) -> str:
    """Replace the user's home and runtime root with neutral placeholders."""
    result = str(text)
    flags = re.IGNORECASE if sys.platform == "win32" else 0
    replacements = (
        (str(paths.local_app_data()), "%LOCALAPPDATA%"),
        (str(Path.home()), "~"),
    )
    for prefix, placeholder in replacements:
        if not prefix:
            continue
        variants = {prefix, prefix.replace("\\", "/"), prefix.replace("/", "\\")}
        for variant in sorted(variants, key=len, reverse=True):
            result = re.sub(re.escape(variant), lambda _: placeholder, result, flags=flags)
    return result


def _clean_detail(value: Any) -> str:
    return _anonymize(str(value)).replace("\r", " ").replace("\n", " ").strip()


def _hints_for(checks: list[dict[str, Any]]) -> list[str]:
    hints: list[str] = []
    for check in checks:
        if check.get("status") == "ok":
            continue
        name = _clean_detail(check.get("name"))
        hint = _HINTS.get(name) or f"Review the '{name}' check and re-run install.ps1."
        if hint not in hints:
            hints.append(hint)
    return hints


def format_report(checks: list[dict[str, Any]]) -> str:
    """Render a human-readable, anonymized report with remediation hints."""
    counts = {"ok": 0, "warn": 0, "fail": 0}
    lines: list[str] = []
    for check in checks:
        status = check.get("status")
        if status not in counts:
            status = "fail"
        counts[status] += 1
        marker = _STATUS_MARKERS[status]
        name = _clean_detail(check.get("name", "unknown"))
        detail = _clean_detail(check.get("detail", ""))
        lines.append(f"{marker} {name}: {detail}")

    lines.append("")
    lines.append(f"{counts['ok']} ok, {counts['warn']} warnings, {counts['fail']} failures")

    hints = _hints_for(checks)
    if hints:
        lines.append("")
        lines.append("Remediation hints:")
        lines.extend(f"- {hint}" for hint in hints)
    return "\n".join(lines)


def build_report(checks: list[dict[str, Any]]) -> dict[str, Any]:
    """Build the anonymized JSON document printed by ``--doctor-json``."""
    normalized: list[dict[str, str]] = []
    failures = 0
    for check in checks:
        status = check.get("status")
        if status not in _STATUS_MARKERS:
            status = "fail"
        if status == "fail":
            failures += 1
        normalized.append(
            {
                "name": _clean_detail(check.get("name", "unknown")),
                "status": status,
                "detail": _clean_detail(check.get("detail", "")),
            }
        )
    return {
        "tool": TOOL_NAME,
        "schema_version": SCHEMA_VERSION,
        "platform": sys.platform,
        "python": platform_module.python_version(),
        "ok": failures == 0,
        "checks": normalized,
    }
