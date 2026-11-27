"""Status aggregation, change detection, and ntfy publishing."""

from __future__ import annotations

import argparse
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from . import antigravity, context, crypto, ntfy, paths, zcode

MIN_PUBLISH_INTERVAL_SEC = 600  # 10 minutes
HEARTBEAT_INTERVAL_SEC = 3600  # 60 minutes
# Debounce tab-switch thrashing; mirrors ACTIVE_PUBLISH_THROTTLE_MS = 15_000
# in desktop/src/main/collector.js so an active chat switch reaches the phone in ~15-20 s.
MIN_ACTIVE_SWITCH_INTERVAL_SEC = 15


def _as_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _is_number(value: Any) -> bool:
    """Return ``True`` for a finite int/float; ``bool`` is not a number."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    try:
        return math.isfinite(value)
    except (OverflowError, TypeError):
        return False


def _as_float(value: Any) -> float:
    """Coerce a stored timestamp to float; ``bool`` and garbage become ``0.0``."""
    if isinstance(value, bool):
        return 0.0
    try:
        number = float(value)
    except (OverflowError, TypeError, ValueError):
        return 0.0
    return number if math.isfinite(number) else 0.0


def _tri_state(value: Any) -> bool | None:
    """Keep the coupon flag three-state (``True``/``False``/``None``)."""
    return value if isinstance(value, bool) else None


def _disabled_zcode(checked_at: str) -> dict[str, Any]:
    return {
        "running": False,
        "coupon_visible": False,
        "coupon_state": "absent",
        "matched": "",
        "plan_id": "",
        "method": "disabled",
        "auth_ok": None,
        "network_error": False,
        "error_kind": None,
        "remaining_units": None,
        "total_units": None,
        "ends_at": None,
        "plan_name": "",
        "checked_at": checked_at,
    }


def build_status(
    state_dir: Path,
    config: dict[str, Any],
    now: datetime,
    force_refresh: bool = False,
) -> dict[str, Any]:
    """Collect quota, ZCode, and context data into the published status document."""
    antigravity.refresh_quota(state_dir, force=force_refresh)
    zcode_status = zcode.fetch_cached_status(state_dir, force=force_refresh)
    checked_at = now.isoformat(timespec="seconds")
    if not isinstance(zcode_status, dict) or not zcode_status:
        # No token, or a cache entry that lost its payload: use the canonical
        # disabled block so ``method`` and ``coupon_visible`` agree.
        zcode_status = _disabled_zcode(checked_at)

    method = zcode_status.get("method") or "disabled"
    coupon_state = zcode_status.get("coupon_state")
    if coupon_state not in ("visible", "absent", "unknown"):
        # Older cache entries have no ``coupon_state``: report uncertainty
        # instead of claiming the voucher is gone.
        coupon_state = "unknown" if method != "disabled" else "absent"
    error_kind = zcode_status.get("error_kind")
    if not isinstance(error_kind, str) or not error_kind:
        error_kind = None

    return {
        "schema_version": 2,
        "updated_at": checked_at,
        "pc_updated_at": checked_at,
        "antigravity": antigravity.extract_quota(state_dir),
        "zcode": {
            "running": bool(zcode_status.get("running")),
            "coupon_visible": _tri_state(zcode_status.get("coupon_visible")),
            "coupon_state": coupon_state,
            "matched": zcode_status.get("matched") or "",
            "plan_id": zcode_status.get("plan_id") or "",
            "method": method,
            "auth_ok": zcode_status.get("auth_ok"),
            "network_error": bool(zcode_status.get("network_error")),
            "error_kind": error_kind,
            "remaining_units": zcode_status.get("remaining_units"),
            "total_units": zcode_status.get("total_units"),
            "ends_at": zcode_status.get("ends_at"),
            "plan_name": zcode_status.get("plan_name") or "",
            "checked_at": zcode_status.get("checked_at") or checked_at,
        },
        "context_summary": context.extract_context_summary(state_dir, config),
    }


def has_meaningful_change(previous: dict[str, Any] | None, current: dict[str, Any]) -> bool:
    """Return ``True`` when the new status differs enough to notify the phone."""
    if not previous:
        return True

    previous_context = _as_dict(previous.get("context_summary"))
    current_context = _as_dict(current.get("context_summary"))
    if previous_context.get("conversation_id") != current_context.get("conversation_id"):
        return True
    if bool(previous_context.get("active")) != bool(current_context.get("active")):
        return True
    if previous_context.get("level") != current_context.get("level"):
        return True
    if previous_context.get("model") != current_context.get("model"):
        return True

    previous_pct = previous_context.get("pct")
    current_pct = current_context.get("pct")
    if _is_number(previous_pct) and _is_number(current_pct):
        if abs(float(current_pct) - float(previous_pct)) >= 2:
            return True
    elif (previous_pct is None) != (current_pct is None):
        return True
    elif previous_pct is None and current_pct is None:
        # Without a percentage (unknown window) any token growth still matters.
        previous_tokens = previous_context.get("tokens")
        current_tokens = current_context.get("tokens")
        if _is_number(previous_tokens) and _is_number(current_tokens):
            if float(previous_tokens) != float(current_tokens):
                return True
        elif (previous_tokens is None) != (current_tokens is None):
            return True

    previous_zcode = _as_dict(previous.get("zcode"))
    current_zcode = _as_dict(current.get("zcode"))
    if _tri_state(previous_zcode.get("coupon_visible")) != _tri_state(
        current_zcode.get("coupon_visible")
    ):
        return True
    if (previous_zcode.get("coupon_state") or "unknown") != (
        current_zcode.get("coupon_state") or "unknown"
    ):
        return True
    if (previous_zcode.get("error_kind") or "") != (current_zcode.get("error_kind") or ""):
        return True
    if bool(previous_zcode.get("network_error")) != bool(current_zcode.get("network_error")):
        return True
    if previous_zcode.get("auth_ok") != current_zcode.get("auth_ok"):
        return True
    if (previous_zcode.get("plan_id") or "") != (current_zcode.get("plan_id") or ""):
        return True
    if (previous_zcode.get("ends_at") or "") != (current_zcode.get("ends_at") or ""):
        return True
    if (previous_zcode.get("plan_name") or "") != (current_zcode.get("plan_name") or ""):
        return True

    previous_total = previous_zcode.get("total_units")
    current_total = current_zcode.get("total_units")
    if _is_number(previous_total) and _is_number(current_total):
        if float(previous_total) != float(current_total):
            return True
    elif (previous_total is None) != (current_total is None):
        return True

    previous_remaining = previous_zcode.get("remaining_units")
    current_remaining = current_zcode.get("remaining_units")
    if _is_number(previous_remaining) and _is_number(current_remaining):
        if _is_number(current_total) and current_total > 0:
            ratio = abs(float(current_remaining) - float(previous_remaining)) / float(current_total)
            if ratio >= 0.02:
                return True
        elif abs(float(current_remaining) - float(previous_remaining)) >= 1_000_000:
            # No total to build a ratio from: only a large absolute move matters.
            return True
    elif (previous_remaining is None) != (current_remaining is None):
        return True

    previous_antigravity = _as_dict(previous.get("antigravity"))
    current_antigravity = _as_dict(current.get("antigravity"))
    if (previous_antigravity.get("plan_tier") or "") != (
        current_antigravity.get("plan_tier") or ""
    ):
        return True

    previous_buckets = {
        bucket.get("id"): bucket
        for bucket in (previous_antigravity.get("buckets") or [])
        if isinstance(bucket, dict)
    }
    current_buckets = {
        bucket.get("id"): bucket
        for bucket in (current_antigravity.get("buckets") or [])
        if isinstance(bucket, dict)
    }
    if set(previous_buckets) != set(current_buckets):
        return True

    for bucket_id, bucket in current_buckets.items():
        old = previous_buckets.get(bucket_id, {})
        if bucket.get("reset_time") != old.get("reset_time"):
            return True
        new_fraction = bucket.get("remaining_fraction")
        old_fraction = old.get("remaining_fraction")
        if _is_number(new_fraction) and _is_number(old_fraction):
            if abs(float(new_fraction) - float(old_fraction)) >= 0.02:
                return True
        elif new_fraction != old_fraction:
            return True

    return False


def run_once(
    force_publish: bool = False,
    dry_run: bool = False,
    show_topic: bool = False,
) -> dict[str, Any]:
    """Collect status, write ``status.json``, and publish when appropriate."""
    try:
        state_dir = paths.state_dir()
        topic = paths.ensure_ntfy_topic()
        config = paths.load_config()
        ntfy_enabled = paths.is_ntfy_enabled(config)
        server_url = paths.get_ntfy_server(config)
        e2e_key = paths.get_e2e_key()
        now = datetime.now(timezone.utc).astimezone()
        status = build_status(state_dir, config, now, force_refresh=force_publish)
    except (paths.ConfigReadError, crypto.SecretDecryptError) as exc:
        # Never overwrite a config we could not read: report and keep state intact.
        return {"status_written": False, "error": str(exc)}

    now_ts = now.timestamp()
    paths.write_json(state_dir / "status.json", status)

    publish_state_path = state_dir / "publish_state.json"
    publish_state = paths.read_json(publish_state_path) or {}
    last_published_ts = _as_float(publish_state.get("last_published_ts"))
    last_payload = publish_state.get("last_payload")

    if now_ts < last_published_ts:
        # The clock jumped backwards: do not let the heartbeat stall forever.
        elapsed = float(HEARTBEAT_INTERVAL_SEC)
    else:
        elapsed = max(0.0, now_ts - last_published_ts)
    changed = has_meaningful_change(_as_dict(last_payload) or None, status)
    e2e_enabled = e2e_key is not None
    # Enabling/disabling E2E or switching servers changes the channel contract:
    # publish a fresh package immediately instead of waiting out the throttle.
    channel_changed = False
    # Compare against the implicit default so the very first enable on an
    # existing install publishes immediately instead of waiting for a heartbeat.
    if bool(publish_state.get("e2e_enabled", False)) != e2e_enabled:
        channel_changed = True
    previous_server = publish_state.get("server")
    if isinstance(previous_server, str) and previous_server and previous_server != server_url:
        channel_changed = True
    previous_context = _as_dict(_as_dict(last_payload).get("context_summary"))
    current_context = _as_dict(status.get("context_summary"))
    active_changed = previous_context.get("conversation_id") != current_context.get("conversation_id")

    should_publish = (
        force_publish
        or last_published_ts == 0.0
        or channel_changed
        or (active_changed and elapsed >= MIN_ACTIVE_SWITCH_INTERVAL_SEC)
        or elapsed >= HEARTBEAT_INTERVAL_SEC
        or (changed and elapsed >= MIN_PUBLISH_INTERVAL_SEC)
    )

    publish_info: dict[str, Any] = {"published": False, "reason": "throttled_or_unchanged"}
    if not ntfy_enabled:
        publish_info = {"published": False, "reason": "disabled"}
    elif dry_run:
        publish_info = {
            "published": False,
            "reason": "dry_run",
            "would_publish": bool(should_publish),
            "changed": changed,
        }
    elif should_publish:
        ok, detail = ntfy.publish(
            topic,
            status,
            server_url=server_url,
            e2e_key=e2e_key,
        )
        publish_info = {"published": ok, "detail": detail, "changed": changed}
        if ok:
            paths.write_json(
                publish_state_path,
                {
                    "last_published_ts": now_ts,
                    "last_published_at": status["updated_at"],
                    "last_payload": status,
                    "e2e_enabled": e2e_enabled,
                    "server": server_url,
                },
            )

    result: dict[str, Any] = {"status_written": True}
    if show_topic:
        result["topic"] = topic
    else:
        result["topic_configured"] = bool(topic)
    result["publish"] = publish_info
    return result


def _write_text(text: str) -> None:
    """Print text as UTF-8 even on legacy Windows code pages."""
    buffer = getattr(sys.stdout, "buffer", None)
    if buffer is not None:
        try:
            buffer.write((text + "\n").encode("utf-8", errors="replace"))
            buffer.flush()
            return
        except (AttributeError, OSError, ValueError):
            pass
    print(text)


def _write_result(result: dict[str, Any]) -> None:
    """Print the JSON result as UTF-8 even on legacy Windows code pages."""
    text = json.dumps(result, ensure_ascii=False) + "\n"
    buffer = getattr(sys.stdout, "buffer", None)
    if buffer is not None:
        try:
            buffer.write(text.encode("utf-8", errors="replace"))
            buffer.flush()
            return
        except (AttributeError, OSError, ValueError):
            pass
    print(text, end="")


def main(argv: list[str] | None = None) -> None:
    """CLI entry point for the collector and the installer diagnostics."""
    parser = argparse.ArgumentParser(description="Publish the AG Widgets status to ntfy.")
    parser.add_argument("--force", action="store_true", help="Refresh quota and publish immediately.")
    parser.add_argument("--dry-run", action="store_true", help="Compute the decision without publishing.")
    parser.add_argument("--show-topic", action="store_true", help="Include the ntfy topic in the output.")
    parser.add_argument(
        "--doctor",
        action="store_true",
        help="Run read-only installation diagnostics instead of collecting status.",
    )
    parser.add_argument(
        "--doctor-json",
        action="store_true",
        help="Print the diagnostics as anonymized JSON (implies --doctor).",
    )
    parser.add_argument(
        "--online",
        action="store_true",
        help="Let the diagnostics probe ntfy.sh and the ZCode API.",
    )
    args = parser.parse_args(argv)

    if args.online and not (args.doctor or args.doctor_json):
        parser.error("--online requires --doctor or --doctor-json")

    if args.doctor or args.doctor_json:
        from . import doctor  # Local import keeps the normal collector start-up light.

        checks = doctor.run_doctor(network=args.online)
        if args.doctor_json:
            _write_text(json.dumps(doctor.build_report(checks), ensure_ascii=False, indent=2))
        else:
            _write_text(doctor.format_report(checks))
        failed = any(check.get("status") == "fail" for check in checks)
        raise SystemExit(1 if failed else 0)

    result = run_once(
        force_publish=args.force,
        dry_run=args.dry_run,
        show_topic=args.show_topic,
    )

    _write_result(result)
    if result.get("status_written") is False:
        raise SystemExit(1)
