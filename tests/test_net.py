"""Redirect safety for requests that carry a Bearer token.

`urllib` keeps `Authorization` across 301/302/307/308, so an authorized request
that is redirected hands the JWT to the target host. The Electron client already
refuses such redirects; these tests pin the same guarantee for the Python side
and check that anonymous requests keep the normal behaviour.
"""

from __future__ import annotations

import sys
import threading
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from agwidgets import net  # noqa: E402


class _RedirectingHandler(BaseHTTPRequestHandler):
    """`/start` redirects to `/target`; `/target` records that it was reached."""

    hits: list[str] = []
    authorization: list[str | None] = []

    def do_GET(self):  # noqa: N802 - http.server naming
        type(self).hits.append(self.path)
        type(self).authorization.append(self.headers.get("Authorization"))
        if self.path == "/start":
            self.send_response(302)
            self.send_header("Location", "/target")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        body = b'{"leaked": true}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):  # noqa: D102 - silence the test server
        pass


class AuthorizedRedirectTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _RedirectingHandler)
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def setUp(self):
        _RedirectingHandler.hits = []
        _RedirectingHandler.authorization = []

    def test_authorized_request_refuses_to_follow_a_redirect(self):
        request = urllib.request.Request(
            f"{self.base}/start", headers={"Authorization": "Bearer SECRET-JWT"}
        )

        with self.assertRaises(urllib.error.HTTPError) as raised:
            net.urlopen(request, timeout=5)

        self.assertEqual(raised.exception.code, 302, "the redirect status is reported as-is")
        self.assertEqual(
            _RedirectingHandler.hits,
            ["/start"],
            "the token must never reach the redirect target",
        )

    def test_anonymous_request_keeps_the_normal_redirect_behaviour(self):
        request = urllib.request.Request(f"{self.base}/start")

        with net.urlopen(request, timeout=5) as response:
            body = response.read()

        self.assertEqual(response.status, 200)
        self.assertIn(b"leaked", body, "anonymous requests may follow redirects")
        self.assertEqual(_RedirectingHandler.hits, ["/start", "/target"])
        self.assertEqual(_RedirectingHandler.authorization, [None, None])


class ClientWiringTests(unittest.TestCase):
    def test_zcode_client_sends_authorized_requests_through_the_safe_opener(self):
        # A regression here is silent (the request still works over HTTPS), so the
        # contract is pinned by forcing a redirect and checking the refusal.
        from agwidgets import zcode

        with _running_redirect_server() as base:
            status, body, error_kind = zcode._http_get_json(
                f"{base}/start", {"Authorization": "Bearer SECRET-JWT"}
            )

        self.assertEqual(status, 302)
        self.assertIsNone(body)
        self.assertIsNone(error_kind)
        self.assertEqual(_RedirectingHandler.hits, ["/start"])


class _running_redirect_server:
    """Context manager: a fresh redirect server per test."""

    def __enter__(self):
        _RedirectingHandler.hits = []
        _RedirectingHandler.authorization = []
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _RedirectingHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        return f"http://127.0.0.1:{self.server.server_address[1]}"

    def __exit__(self, *exc_info):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        return False


if __name__ == "__main__":
    unittest.main()
