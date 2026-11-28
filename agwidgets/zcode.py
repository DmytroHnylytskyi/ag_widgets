"""ZCode API client: coupon detection and token balance."""

from __future__ import annotations

import hashlib
import http.client
import json
import locale
import math
import os
import struct
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from . import crypto, net, paths

ZCODE_ORIGIN = "https://zcode.z.ai"
# Fallback for machines without a local ZCode desktop install. The marketing
# feed is gated on the client version, so this must stay close to the current
# release; ``zcode_app_version`` reads the installed app when it can.
ZCODE_APP_VERSION = "4.0.0"
APP_VERSION_ENV = "AGW_ZCODE_APP_VERSION"  # Explicit escape hatch for tests/forks.
APP_DIR_ENV = "AGW_ZCODE_APP_DIR"  # Point at a portable ZCode install directory.
CLIENT_LANGUAGE_ENV = "AGW_ZCODE_CLIENT_LANGUAGE"
ZCODE_POLL_INTERVAL_SEC = 600  # 10 minutes
ZCODE_ERROR_RETRY_SEC = 60  # retry failed polls sooner than a healthy one
ZCODE_RATE_LIMIT_RETRY_SEC = 300  # do not hammer the API after a 429
MAX_BODY_BYTES = 1_048_576  # 1 MiB cap on API response bodies
MAX_ASAR_HEADER_BYTES = 32 * 1024 * 1024  # app.asar headers are a few MiB
MAX_ASAR_PACKAGE_BYTES = 1_048_576  # package.json is tiny; reject the absurd
_APP_VERSION_CACHE: str | None = None

# Most specific failure first: it wins when both requests disagree.
_ERROR_PRIORITY = (
    "auth_expired",
    "rate_limited",
    "server_error",
    "parse_failed",
    "api_changed",
    "network",
)
_ERROR_CACHE_KINDS = frozenset(
    {"network", "server_error", "rate_limited", "parse_failed", "auth_expired"}
)


def _asar_package_version(asar_path: Path) -> str | None:
    """Read ``version`` from ``package.json`` inside an Electron ``app.asar``.

    The archive starts with a pickle header: a JSON file tree followed by file
    contents. Only the header and the tiny ``package.json`` blob are read, so
    this stays cheap even though desktop bundles are hundreds of MiB.
    """
    try:
        with Path(asar_path).open("rb") as handle:
            prefix = handle.read(16)
            if len(prefix) < 16:
                return None
            header_size = struct.unpack("<I", prefix[8:12])[0]
            if not 0 < header_size <= MAX_ASAR_HEADER_BYTES:
                return None
            raw_header = handle.read(header_size)
            if len(raw_header) != header_size:
                return None
            tree = json.loads(raw_header.decode("utf-8", errors="replace"))
            entry = ((tree.get("files") or {}).get("package.json") or {})
            size = int(entry.get("size") or 0)
            offset = int(entry.get("offset") or 0)
            if not 0 < size <= MAX_ASAR_PACKAGE_BYTES:
                return None
            handle.seek(16 + header_size + offset)
            package = json.loads(handle.read(size).decode("utf-8", errors="replace"))
    except (OSError, ValueError, TypeError, AttributeError):
        return None
    version = package.get("version") if isinstance(package, dict) else None
    return version.strip() if isinstance(version, str) and version.strip() else None


def _app_dirs() -> list[Path]:
    """Return candidate ZCode desktop install directories, most likely first."""
    dirs: list[Path] = []
    override = os.environ.get(APP_DIR_ENV, "").strip()
    if override:
        dirs.append(Path(override))
    local = os.environ.get("LOCALAPPDATA", "").strip()
    if local:
        root = Path(local)
        dirs.append(root / "Programs" / "ZCode")  # electron-builder NSIS default
        try:  # Squirrel installs keep ``app-<version>`` next to the launcher.
            dirs.extend(sorted((root / "ZCode").glob("app-*"), reverse=True))
        except OSError:
            pass
    for variable in ("PROGRAMFILES", "PROGRAMFILES(X86)"):
        base = os.environ.get(variable, "").strip()
        if base:
            dirs.append(Path(base) / "ZCode")
    return dirs


def zcode_app_version() -> str:
    """Return the ZCode client version to advertise to the marketing feed.

    The feed only returns campaign deliveries to a current client, so a stale
    hardcoded value silently hides vouchers. Prefer the installed desktop app's
    own ``package.json``; fall back to the bundled constant when ZCode is not
    installed locally (standalone phone, CLI-only machines).
    """
    override = os.environ.get(APP_VERSION_ENV, "").strip()
    if override:
        return override
    global _APP_VERSION_CACHE
    if _APP_VERSION_CACHE:
        return _APP_VERSION_CACHE
    for app_dir in _app_dirs():
        version = _asar_package_version(app_dir / "resources" / "app.asar")
        if version:
            _APP_VERSION_CACHE = version
            return version
    return ZCODE_APP_VERSION


def client_language() -> str:
    """Return the value for ``X-Client-Language``.

    ZCode campaigns are authored in Chinese and English only, so the client
    normalizes every other system locale to ``en-US`` instead of advertising a
    locale it cannot localize.
    """
    override = os.environ.get(CLIENT_LANGUAGE_ENV, "").strip()
    if override:
        return override
    try:
        name = str(locale.getlocale()[0] or "").strip().lower()
    except (TypeError, ValueError):
        name = ""
    if name.startswith("zh") or name.startswith("chinese"):
        return "zh-CN"
    return "en-US"


def load_secrets() -> dict[str, Any]:
    """Read ``secrets.json``, decrypting the ZCode token when it is encrypted.

    A missing file is a normal first run and yields ``{}``. A file that exists
    but is corrupt (``ConfigReadError``) or whose token cannot be decrypted
    (``SecretDecryptError``) propagates: replacing it with an empty document
    would drop the device id and the ntfy topic.
    """
    path = paths.secrets_path()
    with paths.file_lock(paths.lock_path_for(path)):
        data = paths.read_json_strict(path) or {}
        encrypted = data.get("zcode_token_enc")
        if isinstance(encrypted, str) and encrypted.strip():
            data["zcode_token"] = crypto.unprotect_secret(encrypted.strip())
            return data

        legacy = data.get("zcode_token")
        if isinstance(legacy, str) and legacy.strip() and crypto.encryption_available():
            # One-time transparent migration: never leave the JWT in plaintext.
            token = legacy.strip()
            data["zcode_token_enc"] = crypto.protect_secret(token)
            data.pop("zcode_token", None)
            paths.write_json(path, data)
            data["zcode_token"] = token
        return data


def save_secrets(updates: dict[str, Any]) -> dict[str, Any]:
    """Merge ``updates`` into ``secrets.json`` and ensure a stable device id.

    A supplied ``zcode_token`` is encrypted on Windows and never written in
    plaintext; an empty value clears both storage keys. Every other key of the
    document (``device_mid``, ``ntfy_topic_enc``, ...) is preserved.
    """
    path = paths.secrets_path()
    with paths.file_lock(paths.lock_path_for(path)):
        data = paths.read_json_strict(path) or {}
        token: str | None = None
        if "zcode_token" in updates:
            raw = updates.get("zcode_token")
            token = raw.strip() if isinstance(raw, str) else ""
            data.pop("zcode_token", None)
            data.pop("zcode_token_enc", None)
            if token:
                if crypto.encryption_available():
                    data["zcode_token_enc"] = crypto.protect_secret(token)
                else:
                    data["zcode_token"] = token

        for key, value in updates.items():
            if key in ("zcode_token", "zcode_token_enc"):
                continue
            data[key] = value

        if not str(data.get("device_mid") or "").strip():
            data["device_mid"] = str(uuid.uuid4())

        # Decrypt before writing so a tampered blob aborts without changes.
        plain_token = token
        if plain_token is None:
            stored = data.get("zcode_token_enc")
            if isinstance(stored, str) and stored.strip():
                plain_token = crypto.unprotect_secret(stored.strip())

        paths.write_json(path, data)

        saved = dict(data)
        if token is not None:
            if token:
                saved["zcode_token"] = token
            else:
                saved.pop("zcode_token", None)
        elif plain_token is not None:
            saved["zcode_token"] = plain_token
        return saved


def clear_zcode_token() -> bool:
    """Remove the stored ZCode token, keeping ``device_mid`` and the ntfy topic."""
    path = paths.secrets_path()
    removed = False
    with paths.file_lock(paths.lock_path_for(path)):
        data = paths.read_json_strict(path)
        if data is not None:
            removed = "zcode_token" in data or "zcode_token_enc" in data
            if removed:
                data.pop("zcode_token", None)
                data.pop("zcode_token_enc", None)
                paths.write_json(path, data)
    invalidate_cache(paths.state_dir())
    return removed


def invalidate_cache(state_dir: Path) -> None:
    """Delete the cached ZCode API response so the next poll is fresh."""
    try:
        (Path(state_dir) / "zcode_api_cache.json").unlink(missing_ok=True)
    except OSError:
        pass


def _http_get_json(
    url: str, headers: dict[str, str], timeout: int = 15
) -> tuple[int, dict[str, Any] | None, str | None]:
    """GET ``url`` and decode a JSON object.

    The third item is ``None`` on a valid payload, ``"network"`` when no usable
    response arrived, and ``"parse_failed"`` when the body cannot be decoded.
    HTTP error codes are returned as-is: they already explain the failure.
    """
    try:
        request = urllib.request.Request(url, method="GET", headers=headers)
        with net.urlopen(request, timeout=timeout) as response:
            status = response.status
            raw = response.read(MAX_BODY_BYTES + 1)
    except urllib.error.HTTPError as error:
        try:
            raw = error.read(MAX_BODY_BYTES + 1)
        except (OSError, http.client.HTTPException, ValueError):
            return error.code, None, None
        if len(raw) > MAX_BODY_BYTES:
            return error.code, None, None
        try:
            body = json.loads(raw.decode("utf-8", errors="replace"))
        except ValueError:
            return error.code, None, None
        return error.code, body if isinstance(body, dict) else None, None
    except (OSError, http.client.HTTPException, ValueError):
        return 0, None, "network"

    if len(raw) > MAX_BODY_BYTES:
        return status, None, "parse_failed"
    try:
        body = json.loads(raw.decode("utf-8", errors="replace"))
    except ValueError:
        return status, None, "parse_failed"
    if not isinstance(body, dict):
        return status, None, None
    return status, body, None


def _unpack_http_result(
    result: tuple[Any, ...],
) -> tuple[int, dict[str, Any] | None, str | None]:
    """Normalize a ``_http_get_json`` result, accepting the legacy 2-tuple."""
    try:
        code = int(result[0]) if result else 0
    except (TypeError, ValueError):
        code = 0
    body = result[1] if len(result) > 1 else None
    error_kind = result[2] if len(result) > 2 else None
    if error_kind is None and code == 0:
        error_kind = "network"  # No HTTP status means the request never arrived.
    return code, body if isinstance(body, dict) else None, error_kind


def _classify_http(
    code: int, body: dict[str, Any] | None, error_kind: str | None
) -> str | None:
    """Map one HTTP exchange onto the ``error_kind`` taxonomy."""
    if error_kind == "network":
        return "network"
    if error_kind == "parse_failed":
        return "parse_failed"
    if code in (401, 403):
        return "auth_expired"
    if code == 429:
        return "rate_limited"
    if code >= 500:
        return "server_error"
    if code == 0:
        return "network"
    if code != 200:
        return "api_changed"
    if not isinstance(body, dict):
        return "api_changed"  # Valid HTTP but an unexpected payload shape.
    business_code = body.get("code")
    if business_code == 401:
        return "auth_expired"
    if business_code == 0 or body.get("success") is True:
        return None
    return "api_changed"


def _to_number(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (OverflowError, TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def _parse_timestamp_iso(value: Any) -> str | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        timestamp = float(value)
        if not math.isfinite(timestamp):
            return None
        if timestamp > 1e12:  # milliseconds
            timestamp /= 1000.0
        if timestamp <= 0:
            return None
        try:
            moment = datetime.fromtimestamp(timestamp, tz=timezone.utc)
        except (OverflowError, OSError, ValueError):
            return None
        return moment.isoformat().replace("+00:00", "Z")

    text = str(value).strip()
    if not text:
        return None
    try:
        return _parse_timestamp_iso(float(text))
    except ValueError:
        pass
    try:
        moment = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=timezone.utc)  # Naive ISO means UTC.
    return moment.isoformat().replace("+00:00", "Z")


def _claim_from_container(container: Any) -> tuple[str, str] | None:
    """Find a ``claim_zcode_plan`` button in one banner/popup container.

    Returns ``(plan_id, label)`` or ``None``. The label falls back from the
    button text to the container title because campaign buttons are sometimes
    icon-only.
    """
    if not isinstance(container, dict):
        return None
    buttons = container.get("buttons")
    if not isinstance(buttons, list):
        return None
    for button in buttons:
        if not isinstance(button, dict):
            continue
        action = button.get("action")
        if not isinstance(action, dict) or action.get("type") != "claim_zcode_plan":
            continue
        args = action.get("args")
        plan_id = str((args.get("plan_id") if isinstance(args, dict) else "") or "").strip()
        text = button.get("text")
        button_text = ""
        if isinstance(text, dict):
            button_text = str(text.get("content") or "").strip()
        title = container.get("title")
        title_text = ""
        if isinstance(title, dict):
            title_text = str(title.get("content") or "").strip()
        label = button_text or title_text or f"plan:{plan_id}"
        return plan_id, label[:100]
    return None


def _extract_claim_coupon(touch_payload: dict[str, Any]) -> tuple[str, str, str]:
    """Return ``(coupon_state, plan_id, label)`` from the marketing touch response."""
    if not isinstance(touch_payload, dict):
        return "unknown", "", ""
    data = touch_payload.get("data")
    if not isinstance(data, dict):
        return "unknown", "", ""
    deliveries = data.get("deliveries")
    if not isinstance(deliveries, list):
        return "unknown", "", ""

    for item in deliveries:
        if not isinstance(item, dict):
            continue
        position = item.get("resource_position")
        container = item.get("banner") if position == "banner" else item.get("popup")
        found = _claim_from_container(container)
        if found is not None:
            return "visible", found[0], found[1]
        if position == "banner" and isinstance(container, dict):
            # A banner's success dialog is the only documented nested button
            # list; the claim button may live only there.
            found = _claim_from_container(container.get("success_popup"))
            if found is not None:
                return "visible", found[0], found[1]
    return "absent", "", ""


def _extract_balance(balance_payload: dict[str, Any]) -> dict[str, Any]:
    """Summarize active plans and balances from the billing response."""
    empty = {"remaining_units": None, "total_units": None, "ends_at": None, "plan_name": ""}
    if not isinstance(balance_payload, dict):
        return empty
    data = balance_payload.get("data")
    if not isinstance(data, dict):
        return empty

    now_sec = _to_number(data.get("server_time"))
    if now_sec is None or now_sec <= 0:
        now_sec = datetime.now(timezone.utc).timestamp()
    elif now_sec > 1e12:  # milliseconds
        now_sec /= 1000.0

    plans = data.get("plans") if isinstance(data.get("plans"), list) else []
    has_plans = bool(plans)
    active_plan_ids: set[str] = set()
    active_user_plan_ids: set[str] = set()
    soonest_end: float | None = None
    plan_name = ""

    for plan in plans:
        if not isinstance(plan, dict):
            continue
        status = str(plan.get("status") or "").strip().lower()
        end = _to_number(plan.get("ends_at"))
        if end is not None and end > 1e12:
            end /= 1000.0
        expired = status == "expired" or (end is not None and 0 < end <= now_sec)
        if expired:
            continue
        if plan.get("plan_id"):
            active_plan_ids.add(str(plan["plan_id"]))
        if plan.get("user_plan_id"):
            active_user_plan_ids.add(str(plan["user_plan_id"]))
        if not plan_name and plan.get("name"):
            plan_name = str(plan["name"]).strip()
        if end is not None and end > now_sec and (soonest_end is None or end < soonest_end):
            soonest_end = end

    balances = data.get("balances") if isinstance(data.get("balances"), list) else []
    total_sum = 0.0
    remaining_sum = 0.0
    found = False

    for balance in balances:
        if not isinstance(balance, dict):
            continue
        user_plan_id = str(balance.get("user_plan_id") or "")
        plan_id = str(balance.get("plan_id") or "")
        if has_plans:
            # With every plan expired neither id set matches, so leftover
            # balances of dead plans are not counted as the current quota.
            belongs = (user_plan_id and user_plan_id in active_user_plan_ids) or (
                plan_id and plan_id in active_plan_ids
            )
            if (user_plan_id or plan_id) and not belongs:
                continue

        total = _to_number(balance.get("total_units"))
        remaining = _to_number(balance.get("available_units"))
        if remaining is None:
            remaining = _to_number(balance.get("remaining_units"))
        if remaining is None and total is not None:
            used = _to_number(balance.get("used_units")) or 0.0
            remaining = max(0.0, total - used)

        if total is not None or remaining is not None:
            found = True
            total_sum += total or 0.0
            remaining_sum += remaining or 0.0
            period_end = _to_number(balance.get("period_end"))
            if period_end is not None and period_end > 1e12:
                period_end /= 1000.0
            if period_end is not None and period_end > now_sec:
                if soonest_end is None or period_end < soonest_end:
                    soonest_end = period_end

    if not found:
        return {
            "remaining_units": None,
            "total_units": None,
            "ends_at": _parse_timestamp_iso(soonest_end),
            "plan_name": plan_name,
        }

    return {
        "remaining_units": int(round(remaining_sum)),
        "total_units": int(round(total_sum)) if total_sum > 0 else None,
        "ends_at": _parse_timestamp_iso(soonest_end),
        "plan_name": plan_name,
    }


def query_zcode_api(token: str, device_mid: str, seq: int = 0) -> dict[str, Any]:
    """Call the marketing and billing endpoints and normalize their result."""
    headers = {
        "Authorization": f"Bearer {token.strip()}",
        "X-Device-Mid": device_mid.strip(),
        "X-Client-Language": client_language(),
        "X-ZCode-App-Version": zcode_app_version(),
        "Accept": "application/json",
    }

    touch_code, touch_body, touch_error = _unpack_http_result(
        _http_get_json(f"{ZCODE_ORIGIN}/api/v1/marketing/touch?seq={seq}", headers)
    )
    balance_code, balance_body, balance_error = _unpack_http_result(
        _http_get_json(f"{ZCODE_ORIGIN}/api/v1/zcode-plan/billing/balance", headers)
    )

    checked_at = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    network_error = touch_error == "network" and balance_error == "network"

    kinds = {
        _classify_http(touch_code, touch_body, touch_error),
        _classify_http(balance_code, balance_body, balance_error),
    }
    error_kind = next((kind for kind in _ERROR_PRIORITY if kind in kinds), None)
    auth_expired = "auth_expired" in kinds

    coupon_state, plan_id, matched = "unknown", "", ""
    if (
        touch_error is None
        and touch_code == 200
        and isinstance(touch_body, dict)
        and touch_body.get("code") == 0
    ):
        coupon_state, plan_id, matched = _extract_claim_coupon(touch_body)
    if coupon_state == "unknown" and touch_error is None and touch_code == 200:
        # A successful call without ``data.deliveries`` as a list means the
        # marketing schema changed; report it instead of hiding the reason.
        kinds.add("api_changed")
        error_kind = next((kind for kind in _ERROR_PRIORITY if kind in kinds), None)
    coupon_visible: bool | None = None
    if coupon_state == "visible":
        coupon_visible = True
    elif coupon_state == "absent":
        coupon_visible = False

    balance = {"remaining_units": None, "total_units": None, "ends_at": None, "plan_name": ""}
    if (
        balance_error is None
        and balance_code == 200
        and isinstance(balance_body, dict)
        and (balance_body.get("code") == 0 or balance_body.get("success") is True)
    ):
        balance = _extract_balance(balance_body)

    if auth_expired:
        auth_ok: bool | None = False
    elif (touch_code == 200 and touch_error is None) or (balance_code == 200 and balance_error is None):
        auth_ok = True
    else:
        # A network outage leaves the token state unknown; 429/5xx or an HTML
        # captive portal (200 + parse_failed) are not proof of token validity.
        auth_ok = None

    return {
        "running": not network_error,
        "coupon_visible": coupon_visible,
        "coupon_state": coupon_state,
        "matched": matched,
        "plan_id": plan_id,
        "method": "api",
        "auth_ok": auth_ok,
        "network_error": network_error,
        "error_kind": error_kind,
        "remaining_units": balance["remaining_units"],
        "total_units": balance["total_units"],
        "ends_at": balance["ends_at"],
        "plan_name": balance["plan_name"],
        "checked_at": checked_at,
    }


def _is_failed_result(result: dict[str, Any]) -> bool:
    """Tell a degraded poll apart from a healthy one for cache TTL purposes."""
    if result.get("network_error") is True or result.get("auth_ok") is False:
        return True
    return result.get("error_kind") in _ERROR_CACHE_KINDS


def fetch_cached_status(state_dir: Path, force: bool = False) -> dict[str, Any] | None:
    """Return ZCode status from the API, or ``None`` when no token is configured."""
    secrets = load_secrets()
    token = str(secrets.get("zcode_token") or "").strip()
    if not token:
        return None

    device_mid = str(secrets.get("device_mid") or "").strip()
    if not device_mid:
        device_mid = str(save_secrets({})["device_mid"])

    cache_path = Path(state_dir) / "zcode_api_cache.json"
    token_fingerprint = hashlib.sha256(token.encode("utf-8")).hexdigest()[:16]
    now_ts = datetime.now(timezone.utc).timestamp()
    seq = 0

    cached = paths.read_json(cache_path)  # Best-effort: a broken cache is no cache.
    if cached and str(cached.get("token_fingerprint") or "") == token_fingerprint:
        try:
            seq = max(0, int(cached.get("seq") or 0))
        except (TypeError, ValueError):
            seq = 0
        try:
            cached_ts = float(cached.get("ts") or 0.0)
        except (TypeError, ValueError):
            cached_ts = 0.0
        cached_result = cached.get("result")
        if isinstance(cached_result, dict):
            age = now_ts - cached_ts
            # A 429 is a hard signal even for an explicit refresh: back off for
            # five minutes instead of letting the desktop refresh button retry.
            if cached_result.get("error_kind") == "rate_limited" and 0 <= age < ZCODE_RATE_LIMIT_RETRY_SEC:
                return cached_result
            if not force:
                ttl = (
                    ZCODE_ERROR_RETRY_SEC
                    if _is_failed_result(cached_result)
                    else ZCODE_POLL_INTERVAL_SEC
                )
                if 0 <= age < ttl:
                    return cached_result

    result = query_zcode_api(token, device_mid, seq=seq)
    paths.write_json(
        cache_path,
        {
            "ts": now_ts,
            "seq": seq + 1,
            "token_fingerprint": token_fingerprint,
            "result": result,
        },
    )
    return result
