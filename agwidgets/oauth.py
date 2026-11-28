"""Interactive ZCode OAuth login for the command line."""

from __future__ import annotations

import http.client
import json
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from typing import Any

from . import crypto, net, paths, zcode

INIT_URL = f"{zcode.ZCODE_ORIGIN}/api/v1/oauth/cli/init"
POLL_BASE = f"{zcode.ZCODE_ORIGIN}/api/v1/oauth/cli/poll"


def _emit(text: str) -> None:
    """Write ``text`` plus a newline as UTF-8, tolerating legacy code pages."""
    data = (text + "\n").encode("utf-8", errors="replace")
    stream = getattr(sys.stdout, "buffer", None)
    if stream is not None:
        try:
            stream.write(data)
            stream.flush()
            return
        except (AttributeError, OSError):
            pass
    try:
        sys.stdout.write(text + "\n")
        sys.stdout.flush()
    except (AttributeError, OSError, UnicodeEncodeError):
        pass


def is_trusted_authorize_url(url: str) -> str | None:
    """Return a normalized sign-in URL when it belongs to Z.ai, else ``None``.

    Only ``https`` on ``z.ai`` (or a subdomain) is accepted, without userinfo
    and without a non-default port. The URL is rebuilt from the parsed parts so
    odd spellings cannot smuggle a different host past :func:`webbrowser.open`.
    """
    if not isinstance(url, str) or not url.strip():
        return None
    try:
        parts = urllib.parse.urlsplit(url.strip())
        hostname = (parts.hostname or "").lower()
        port = parts.port
    except ValueError:  # malformed IPv6 literal or port such as ``:abc``
        return None

    if parts.scheme.lower() != "https":
        return None
    if hostname != "z.ai" and not hostname.endswith(".z.ai"):
        return None
    if parts.username is not None or parts.password is not None:
        return None
    if port not in (None, 443):
        return None

    # Drop userinfo and the default port; rebuild from parsed components only.
    return urllib.parse.urlunsplit((parts.scheme.lower(), hostname, parts.path, parts.query, parts.fragment))


def _headers(poll_token: str | None = None) -> dict[str, str]:
    """Build the headers shared by the init and poll requests."""
    headers = {
        "Accept": "application/json",
        "X-ZCode-App-Version": zcode.ZCODE_APP_VERSION,
    }
    if poll_token:
        headers["Authorization"] = f"Bearer {poll_token}"
    try:
        device_mid = str(zcode.load_secrets().get("device_mid") or "").strip()
    except (paths.ConfigReadError, crypto.SecretDecryptError):
        device_mid = ""
    if device_mid:
        headers["X-Device-Mid"] = device_mid
    return headers


def _post_json(url: str, body: dict[str, Any], headers: dict[str, str]) -> dict[str, Any]:
    payload = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=payload,
        method="POST",
        headers={**headers, "Content-Type": "application/json", "Accept": "application/json"},
    )
    with net.urlopen(request, timeout=20) as response:
        return json.loads(response.read().decode("utf-8", errors="replace"))


def _get_json(url: str, headers: dict[str, str]) -> dict[str, Any]:
    request = urllib.request.Request(url, method="GET", headers={**headers, "Accept": "application/json"})
    with net.urlopen(request, timeout=20) as response:
        return json.loads(response.read().decode("utf-8", errors="replace"))


def run_login(provider: str = "zai") -> None:
    """Run the OAuth flow in the browser and persist the resulting token."""
    poll_token = secrets.token_hex(32)

    _emit(f"Initializing ZCode OAuth flow (provider={provider})...")
    init_response = _post_json(INIT_URL, {"provider": provider}, _headers(poll_token))
    if init_response.get("code") != 0 or not isinstance(init_response.get("data"), dict):
        raise RuntimeError(f"OAuth init failed: {init_response}")

    data = init_response["data"]
    flow_id = str(data["flow_id"])
    authorize_url = str(data.get("authorize_url") or "")
    try:
        poll_interval = float(data.get("poll_interval_sec") or 3.0)
    except (TypeError, ValueError):
        poll_interval = 3.0
    poll_interval = max(2.0, min(10.0, poll_interval))

    trusted = is_trusted_authorize_url(authorize_url)
    if trusted is None:
        raise RuntimeError("Refusing an untrusted sign-in URL")

    _emit("\nOpen this URL and sign in to Z.ai:")
    _emit(f"  {trusted}\n")
    try:
        webbrowser.open(trusted)
    except OSError:
        pass

    poll_url = f"{POLL_BASE}/{urllib.parse.quote(flow_id, safe='')}"
    deadline = time.monotonic() + 300.0
    _emit("Waiting for sign-in to complete...")

    while time.monotonic() < deadline:
        time.sleep(poll_interval)
        try:
            poll_response = _get_json(poll_url, _headers(poll_token))
        except urllib.error.HTTPError as error:
            if 400 <= error.code < 500 and error.code not in (408, 429):
                raise RuntimeError(f"OAuth poll error HTTP {error.code}") from error
            continue
        except (OSError, http.client.HTTPException, json.JSONDecodeError, ValueError):
            # A single truncated 502/HTML answer must not abort a long sign-in.
            continue

        poll_data = poll_response.get("data")
        if not isinstance(poll_data, dict):
            continue
        status = str(poll_data.get("status") or "")
        if status == "pending":
            continue
        if status == "failed":
            raise RuntimeError("Sign-in was rejected or failed.")
        if status == "ready":
            token = str(poll_data.get("token") or "").strip()
            if not token:
                raise RuntimeError("Server did not return data.token")
            saved = zcode.save_secrets({"zcode_token": token})
            zcode.invalidate_cache(paths.state_dir())
            _emit("\nSuccess. Token saved locally to:")
            _emit(f"  {paths.secrets_path()}")
            _emit("\nVoucher and balance check:")
            result = zcode.query_zcode_api(token, str(saved.get("device_mid") or ""))
            _emit(json.dumps(result, ensure_ascii=False, indent=2))
            return

    raise RuntimeError("Sign-in timed out after 5 minutes.")


def status() -> dict[str, Any]:
    """Test the saved token and return the normalized ZCode status."""
    saved = zcode.load_secrets()
    token = str(saved.get("zcode_token") or "").strip()
    if not token:
        raise RuntimeError("No ZCode token configured. Run: python collector/zcode_auth.py")
    return zcode.query_zcode_api(token, str(saved.get("device_mid") or ""))


def save_token(token: str) -> dict[str, Any]:
    """Persist a manually supplied JWT and immediately verify it."""
    cleaned = str(token or "").strip()
    if not cleaned:
        raise ValueError("Token must not be empty")
    saved = zcode.save_secrets({"zcode_token": cleaned})
    zcode.invalidate_cache(paths.state_dir())
    return zcode.query_zcode_api(cleaned, str(saved.get("device_mid") or ""))


def clear_token() -> bool:
    """Remove the stored ZCode token and invalidate the cached API result."""
    cleared = bool(zcode.clear_zcode_token())
    zcode.invalidate_cache(paths.state_dir())
    return cleared
