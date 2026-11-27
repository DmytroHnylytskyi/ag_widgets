"""Thin CLI bridge between the Electron Settings window and the E2E key store.

Electron never reads or writes ``secrets.json``: it calls this helper, which
owns the DPAPI-encrypted E2E key exactly like ``collector/zcode_auth.py`` owns
the ZCode token. Every command prints one JSON object on stdout and exits 0 on
success; the pairing URI (it contains the topic and the key) is only ever
returned to the requesting window, never logged.

Usage:
    python collector/pairing.py --get
    python collector/pairing.py --set-server https://ntfy.sh
    python collector/pairing.py --set-ntfy-enabled true|false
    python collector/pairing.py --set-e2e enable|disable|rotate
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from agwidgets import crypto, e2e, paths  # noqa: E402


def _write_json(payload: dict[str, Any]) -> None:
    text = json.dumps(payload, ensure_ascii=False) + "\n"
    buffer = getattr(sys.stdout, "buffer", None)
    if buffer is not None:
        try:
            buffer.write(text.encode("utf-8", errors="replace"))
            buffer.flush()
            return
        except (AttributeError, OSError, ValueError):
            pass
    print(text, end="")


def _as_bool(raw: str) -> bool:
    value = str(raw).strip().lower()
    if value in ("true", "1", "yes", "on"):
        return True
    if value in ("false", "0", "no", "off"):
        return False
    raise ValueError("expected true or false")


def _pairing_snapshot() -> dict[str, Any]:
    topic = paths.ensure_ntfy_topic()
    config = paths.load_config()
    server = paths.get_ntfy_server(config)
    e2e_key = paths.get_e2e_key()
    return {
        "ok": True,
        "topic": topic,
        "server": server,
        "ntfy_enabled": paths.is_ntfy_enabled(config),
        "e2e_enabled": e2e_key is not None,
        "uri": e2e.build_pairing_uri(topic, server, e2e_key),
    }


def _set_server(raw: str) -> dict[str, Any]:
    server = e2e.normalize_ntfy_server(raw)
    paths.update_json(
        paths.config_path(),
        lambda config: {**config, "ntfy_server": server},
    )
    return {"ok": True, "server": server, "changed": True, "refresh": True}


def _set_ntfy_enabled(raw: str) -> dict[str, Any]:
    enabled = _as_bool(raw)
    paths.update_json(
        paths.config_path(),
        lambda config: {**config, "ntfy_enabled": enabled},
    )
    return {"ok": True, "ntfy_enabled": enabled, "changed": True, "refresh": enabled}


def _set_e2e(action: str) -> dict[str, Any]:
    choice = str(action).strip().lower()
    if choice == "enable":
        existing = paths.get_e2e_key()
        paths.ensure_e2e_key()
        return {
            "ok": True,
            "e2e_enabled": True,
            "changed": existing is None,
            "refresh": True,
        }
    if choice == "disable":
        had_key = paths.get_e2e_key() is not None
        paths.set_e2e_key(None)
        return {"ok": True, "e2e_enabled": False, "changed": had_key, "refresh": had_key}
    if choice == "rotate":
        previous = paths.get_e2e_key()
        paths.set_e2e_key(e2e.generate_key())
        return {
            "ok": True,
            "e2e_enabled": True,
            "changed": True,
            "refresh": True,
            "previously_enabled": previous is not None,
        }
    raise ValueError("expected enable, disable or rotate")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Read and update the AG Widgets pairing settings.",
    )
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--get", action="store_true", help="Print the pairing snapshot.")
    group.add_argument("--set-server", metavar="URL", help="Store a validated https ntfy server.")
    group.add_argument(
        "--set-ntfy-enabled",
        metavar="BOOL",
        help="Enable or disable ntfy publishing (true|false).",
    )
    group.add_argument(
        "--set-e2e",
        metavar="ACTION",
        help="Enable, disable or rotate the AES-256-GCM key (enable|disable|rotate).",
    )
    args = parser.parse_args(argv)

    try:
        if args.get:
            result = _pairing_snapshot()
        elif args.set_server is not None:
            result = _set_server(args.set_server)
        elif args.set_ntfy_enabled is not None:
            result = _set_ntfy_enabled(args.set_ntfy_enabled)
        else:
            result = _set_e2e(args.set_e2e)
    except (ValueError, paths.ConfigReadError, crypto.SecretDecryptError) as error:
        message = str(error)
        # A malformed URL is user input, not a secret; the key is never included.
        _write_json({"ok": False, "error": message})
        return 1

    _write_json(result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
