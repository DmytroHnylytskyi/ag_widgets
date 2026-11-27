"""Publishing the status payload to a private ntfy topic."""

from __future__ import annotations

import copy
import http.client
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

from . import e2e, paths

NTFY_URL = "https://ntfy.sh"
# ntfy accepts up to 4096 bytes per message. Keep headroom for the JSON envelope.
MAX_BODY_BYTES = 3800
# With E2E the payload is base64url-expanded (4/3) and wrapped in a ~75-byte
# envelope, so the plaintext JSON has to stay well below MAX_BODY_BYTES.
MAX_E2E_PLAINTEXT_BYTES = 2700

_CONTEXT_KEYS = ("conversation_id", "active", "model", "tokens", "window", "pct", "level")
_PRIORITY_BUCKET_KINDS = ("5h", "weekly")


def _encode(payload: dict[str, Any]) -> bytes:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def _fits(payload: dict[str, Any], max_bytes: int = MAX_BODY_BYTES) -> bool:
    return len(_encode(payload)) <= max_bytes


def _shrink(payload: dict[str, Any], max_bytes: int = MAX_BODY_BYTES) -> dict[str, Any]:
    """Return a deep copy of ``payload`` reduced to fit ``max_bytes``.

    The caller's dictionary is never modified: only the copy is trimmed, one
    optional field group at a time.
    """
    shrunk = copy.deepcopy(payload)
    if _fits(shrunk, max_bytes):
        return shrunk

    zcode = shrunk.get("zcode")
    if isinstance(zcode, dict) and zcode.get("matched"):
        zcode["matched"] = str(zcode["matched"])[:64]
    if _fits(shrunk, max_bytes):
        return shrunk

    antigravity = shrunk.get("antigravity")
    if isinstance(antigravity, dict):
        buckets = antigravity.get("buckets")
        if isinstance(buckets, list) and len(buckets) > 4:
            priority = [
                bucket
                for bucket in buckets
                if isinstance(bucket, dict) and bucket.get("kind") in _PRIORITY_BUCKET_KINDS
            ]
            rest = [
                bucket
                for bucket in buckets
                if not (isinstance(bucket, dict) and bucket.get("kind") in _PRIORITY_BUCKET_KINDS)
            ]
            antigravity["buckets"] = (priority + rest)[:4]
    if _fits(shrunk, max_bytes):
        return shrunk

    if isinstance(antigravity, dict):
        antigravity.pop("error", None)
        antigravity.pop("saved_at", None)
    context_summary = shrunk.get("context_summary")
    if isinstance(context_summary, dict):
        context_summary.pop("window_source", None)
    if isinstance(zcode, dict):
        zcode.pop("plan_name", None)
    if _fits(shrunk, max_bytes):
        return shrunk

    if isinstance(context_summary, dict):
        shrunk["context_summary"] = {
            key: context_summary[key] for key in _CONTEXT_KEYS if key in context_summary
        }
    return shrunk


def publish(
    topic: str,
    payload: dict[str, Any],
    timeout: int = 15,
    *,
    server_url: str = NTFY_URL,
    e2e_key: str | None = None,
) -> tuple[bool, str]:
    """POST ``payload`` to ``<server_url>/<topic>``. Returns ``(ok, detail)``.

    With ``e2e_key`` the payload is shrunk to ``MAX_E2E_PLAINTEXT_BYTES`` and
    published as an authenticated AES-256-GCM envelope instead of plaintext.
    """
    candidate = topic.strip() if isinstance(topic, str) else ""
    if re.fullmatch(paths.NTFY_TOPIC_RE, candidate) is None:
        return False, "invalid topic"

    try:
        base_url = e2e.normalize_ntfy_server(server_url)
    except ValueError:
        return False, "invalid server"

    if e2e_key is not None:
        plaintext = _encode(_shrink(payload, MAX_E2E_PLAINTEXT_BYTES))
        if len(plaintext) > MAX_E2E_PLAINTEXT_BYTES:
            return False, f"payload too large ({len(plaintext)} bytes)"
        try:
            envelope = e2e.encrypt_envelope(plaintext, e2e_key)
        except (TypeError, ValueError):
            return False, "invalid e2e key"
        body = _encode(envelope)
        if len(body) > MAX_BODY_BYTES:
            return False, f"payload too large ({len(body)} bytes)"
    else:
        body = _encode(payload)
        if len(body) > MAX_BODY_BYTES:
            body = _encode(_shrink(payload))
            if len(body) > MAX_BODY_BYTES:
                return False, f"payload too large ({len(body)} bytes)"

    encoded_topic = urllib.parse.quote(candidate, safe="")
    request = urllib.request.Request(
        f"{base_url}/{encoded_topic}",
        data=body,
        method="POST",
        headers={
            "Title": "ag-widgets-status",
            "Content-Type": "application/json; charset=utf-8",
            "User-Agent": "ag-widgets-collector/1.0",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return 200 <= response.status < 300, f"HTTP {response.status}"
    except urllib.error.HTTPError as error:
        return False, f"HTTP {error.code}: {error.reason}"
    except (OSError, http.client.HTTPException) as error:
        return False, str(error)
