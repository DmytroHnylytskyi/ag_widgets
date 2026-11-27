"""Tests for payload shrinking and ntfy publishing."""

from __future__ import annotations

import copy
import http.client
import json
import re
import sys
import unittest
import urllib.error
import urllib.parse
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from agwidgets import e2e, ntfy  # noqa: E402

VALID_E2E_KEY = "_v_pkoZlcxxtao-UZzCDCP7_6ZKGZXMcbWqPlGcwgwg"
CUSTOM_SERVER = "https://relay.example.com/secret-path"


class _FakeResponse:
    """Minimal context-manager response object returned by a mocked urlopen."""

    def __init__(self, status: int) -> None:
        self.status = status

    def __enter__(self) -> "_FakeResponse":
        return self

    def __exit__(self, exc_type, exc, traceback) -> bool:
        return False


def _bucket(bucket_id: str, kind: str = "other", padding: int = 0) -> dict:
    bucket = {
        "id": bucket_id,
        "kind": kind,
        "remaining_fraction": 0.5,
        "reset_time": "2026-01-01T00:00:00Z",
    }
    if padding:
        bucket["note"] = "p" * padding
    return bucket


def _encoded_size(payload: dict) -> int:
    return len(ntfy._encode(payload))


class ShrinkTests(unittest.TestCase):
    def test_shrink_returns_new_copy_for_small_payload(self):
        payload = {
            "schema_version": 2,
            "zcode": {"matched": "Claim", "plan_id": "p1"},
            "context_summary": {"pct": 10.0},
        }
        original = copy.deepcopy(payload)

        shrunk = ntfy._shrink(payload)

        self.assertEqual(payload, original)
        self.assertEqual(shrunk, payload)
        self.assertIsNot(shrunk, payload)
        self.assertIsNot(shrunk["zcode"], payload["zcode"])
        self.assertIsNot(shrunk["context_summary"], payload["context_summary"])

        shrunk["zcode"]["matched"] = "changed"
        self.assertEqual(payload["zcode"]["matched"], "Claim")

    def test_shrink_truncates_matched_to_64(self):
        payload = {"zcode": {"matched": "m" * 4000, "plan_id": "p1"}}
        self.assertGreater(_encoded_size(payload), ntfy.MAX_BODY_BYTES)

        shrunk = ntfy._shrink(payload)

        self.assertEqual(len(shrunk["zcode"]["matched"]), 64)
        self.assertEqual(shrunk["zcode"]["plan_id"], "p1")
        self.assertLessEqual(_encoded_size(shrunk), ntfy.MAX_BODY_BYTES)
        self.assertEqual(len(payload["zcode"]["matched"]), 4000)

    def test_shrink_limits_buckets_to_four(self):
        payload = {
            "schema_version": 2,
            "antigravity": {"buckets": [_bucket(f"b{i}", padding=500) for i in range(8)]},
        }
        original = copy.deepcopy(payload)
        self.assertGreater(_encoded_size(payload), ntfy.MAX_BODY_BYTES)

        shrunk = ntfy._shrink(payload)

        self.assertEqual(len(shrunk["antigravity"]["buckets"]), 4)
        self.assertEqual(
            [bucket["id"] for bucket in shrunk["antigravity"]["buckets"]],
            ["b0", "b1", "b2", "b3"],
        )
        self.assertLessEqual(_encoded_size(shrunk), ntfy.MAX_BODY_BYTES)
        self.assertEqual(payload, original)

    def test_shrink_prefers_priority_bucket_kinds(self):
        kinds = ["other", "other", "weekly", "other", "5h", "other", "weekly", "other"]
        payload = {
            "antigravity": {
                "buckets": [
                    _bucket(f"b{index}", kind=kind, padding=500)
                    for index, kind in enumerate(kinds)
                ]
            }
        }

        shrunk = ntfy._shrink(payload)
        buckets = shrunk["antigravity"]["buckets"]

        self.assertEqual([bucket["id"] for bucket in buckets], ["b2", "b4", "b6", "b0"])
        self.assertEqual([bucket["kind"] for bucket in buckets][:3], ["weekly", "5h", "weekly"])
        self.assertEqual(len(buckets), 4)
        self.assertLessEqual(_encoded_size(shrunk), ntfy.MAX_BODY_BYTES)

    def test_shrink_drops_secondary_fields(self):
        payload = {
            "schema_version": 2,
            "zcode": {"plan_id": "p1", "plan_name": "n" * 1200},
            "antigravity": {
                "plan_tier": "Pro",
                "error": "e" * 1200,
                "saved_at": "s" * 1200,
                "buckets": [_bucket("b0"), _bucket("b1")],
            },
            "context_summary": {
                "conversation_id": "a",
                "active": False,
                "model": "gemini-4-neon-high",
                "tokens": 100,
                "window": 524288,
                "pct": 0.02,
                "level": "green",
                "window_source": "w" * 1200,
            },
        }
        original = copy.deepcopy(payload)
        self.assertGreater(_encoded_size(payload), ntfy.MAX_BODY_BYTES)

        shrunk = ntfy._shrink(payload)

        self.assertNotIn("error", shrunk["antigravity"])
        self.assertNotIn("saved_at", shrunk["antigravity"])
        self.assertNotIn("window_source", shrunk["context_summary"])
        self.assertNotIn("plan_name", shrunk["zcode"])
        self.assertEqual(shrunk["antigravity"]["plan_tier"], "Pro")
        self.assertEqual(shrunk["antigravity"]["buckets"][0]["id"], "b0")
        self.assertLessEqual(_encoded_size(shrunk), ntfy.MAX_BODY_BYTES)
        self.assertEqual(payload, original)

    def test_shrink_minimizes_extreme_context_summary(self):
        payload = {
            "zcode": {"plan_id": "p1"},
            "context_summary": {
                "conversation_id": "conv-1",
                "active": True,
                "model": "gemini-4-neon-high",
                "tokens": 10,
                "window": 100,
                "pct": 10.0,
                "level": "green",
                "window_source": "w" * 4000,
                "notes": "n" * 4000,
            },
        }
        original = copy.deepcopy(payload)
        self.assertGreater(_encoded_size(payload), ntfy.MAX_BODY_BYTES)

        shrunk = ntfy._shrink(payload)

        self.assertEqual(
            set(shrunk["context_summary"]),
            {"conversation_id", "active", "model", "tokens", "window", "pct", "level"},
        )
        self.assertLessEqual(_encoded_size(shrunk), ntfy.MAX_BODY_BYTES)
        self.assertEqual(payload, original)


class PublishTests(unittest.TestCase):
    def test_publish_rejects_invalid_topics(self):
        with mock.patch.object(ntfy.urllib.request, "urlopen") as urlopen:
            for topic in ("bad topic!", "", "a" * 65, None, 123):
                with self.subTest(topic=topic):
                    self.assertEqual(ntfy.publish(topic, {"a": 1}), (False, "invalid topic"))
            urlopen.assert_not_called()

    def test_publish_validates_topic_before_network(self):
        urlopen = mock.Mock(side_effect=AssertionError("network must not be reached"))
        with mock.patch.object(ntfy.urllib.request, "urlopen", urlopen):
            self.assertEqual(ntfy.publish("bad topic!", {"a": 1}), (False, "invalid topic"))
        urlopen.assert_not_called()

    def test_publish_posts_json_to_quoted_topic(self):
        payload = {"schema_version": 2, "zcode": {"matched": "x"}}
        with mock.patch.object(
            ntfy.urllib.request, "urlopen", return_value=_FakeResponse(200)
        ) as urlopen:
            self.assertEqual(ntfy.publish("agw-abc_123", payload), (True, "HTTP 200"))

        urlopen.assert_called_once()
        self.assertEqual(urlopen.call_args.kwargs["timeout"], 15)

        request = urlopen.call_args.args[0]
        encoded_topic = urllib.parse.quote("agw-abc_123", safe="")
        self.assertEqual(request.method, "POST")
        self.assertTrue(request.full_url.startswith("https://ntfy.sh/"))
        self.assertIn(encoded_topic, request.full_url)
        self.assertLessEqual(len(request.data), ntfy.MAX_BODY_BYTES)
        self.assertEqual(json.loads(request.data.decode("utf-8")), payload)
        self.assertIn("application/json", request.get_header("Content-type") or "")

    def test_publish_strips_surrounding_whitespace(self):
        with mock.patch.object(
            ntfy.urllib.request, "urlopen", return_value=_FakeResponse(200)
        ) as urlopen:
            self.assertEqual(ntfy.publish("  agw-abc_123  ", {"a": 1}), (True, "HTTP 200"))

        request = urlopen.call_args.args[0]
        self.assertEqual(request.full_url, "https://ntfy.sh/agw-abc_123")

    def test_publish_accepts_max_length_topic(self):
        topic = "a" * 64
        with mock.patch.object(ntfy.urllib.request, "urlopen", return_value=_FakeResponse(200)):
            self.assertEqual(ntfy.publish(topic, {"a": 1}), (True, "HTTP 200"))

    def test_publish_accepts_204(self):
        with mock.patch.object(ntfy.urllib.request, "urlopen", return_value=_FakeResponse(204)):
            self.assertEqual(ntfy.publish("agw-abc_123", {"a": 1}), (True, "HTTP 204"))

    def test_publish_returns_false_for_non_2xx_status(self):
        with mock.patch.object(ntfy.urllib.request, "urlopen", return_value=_FakeResponse(302)):
            self.assertEqual(ntfy.publish("agw-abc_123", {"a": 1}), (False, "HTTP 302"))

    def test_publish_maps_http_error(self):
        error = urllib.error.HTTPError(
            "https://ntfy.sh/agw-abc_123", 500, "Internal Server Error", None, None
        )
        with mock.patch.object(ntfy.urllib.request, "urlopen", side_effect=error):
            self.assertEqual(
                ntfy.publish("agw-abc_123", {"a": 1}),
                (False, "HTTP 500: Internal Server Error"),
            )

    def test_publish_maps_oserror(self):
        with mock.patch.object(
            ntfy.urllib.request, "urlopen", side_effect=OSError("connection refused")
        ):
            self.assertEqual(
                ntfy.publish("agw-abc_123", {"a": 1}),
                (False, "connection refused"),
            )

    def test_publish_maps_http_exception(self):
        with mock.patch.object(
            ntfy.urllib.request,
            "urlopen",
            side_effect=http.client.HTTPException("protocol broken"),
        ):
            self.assertEqual(
                ntfy.publish("agw-abc_123", {"a": 1}),
                (False, "protocol broken"),
            )

    def test_publish_rejects_oversized_payload(self):
        payload = {"context_summary": {"conversation_id": "c" * 5000}}
        with mock.patch.object(ntfy.urllib.request, "urlopen") as urlopen:
            ok, detail = ntfy.publish("agw-abc_123", payload)

        self.assertFalse(ok)
        match = re.fullmatch(r"payload too large \((\d+) bytes\)", detail)
        self.assertIsNotNone(match)
        self.assertGreater(int(match.group(1)), ntfy.MAX_BODY_BYTES)
        urlopen.assert_not_called()

    def test_publish_does_not_mutate_large_payload(self):
        payload = {"zcode": {"matched": "m" * 4000}}
        original = copy.deepcopy(payload)
        with mock.patch.object(ntfy.urllib.request, "urlopen", return_value=_FakeResponse(200)):
            ok, _ = ntfy.publish("agw-abc_123", payload)

        self.assertTrue(ok)
        self.assertEqual(payload, original)


class E2ePublishTests(unittest.TestCase):
    """The encrypted branch must emit an envelope and never leak plaintext."""

    def test_custom_server_url_is_used(self):
        with mock.patch.object(
            ntfy.urllib.request, "urlopen", return_value=_FakeResponse(200)
        ) as urlopen:
            self.assertEqual(
                ntfy.publish("agw-abc_123", {"a": 1}, server_url=CUSTOM_SERVER),
                (True, "HTTP 200"),
            )

        request = urlopen.call_args.args[0]
        self.assertEqual(request.full_url, f"{CUSTOM_SERVER}/agw-abc_123")

    def test_e2e_body_is_a_decryptable_envelope(self):
        payload = {"schema_version": 2, "zcode": {"matched": "sentinel-plaintext"}}
        with mock.patch.object(
            ntfy.urllib.request, "urlopen", return_value=_FakeResponse(200)
        ) as urlopen:
            self.assertEqual(
                ntfy.publish("agw-abc_123", payload, e2e_key=VALID_E2E_KEY),
                (True, "HTTP 200"),
            )

        request = urlopen.call_args.args[0]
        body = json.loads(request.data.decode("utf-8"))
        self.assertTrue(e2e.is_e2e_envelope(body))
        self.assertEqual(
            e2e.decrypt_envelope(body, VALID_E2E_KEY), ntfy._encode(payload)
        )
        self.assertIn("application/json", request.get_header("Content-type") or "")
        self.assertEqual(request.full_url, "https://ntfy.sh/agw-abc_123")
        self.assertNotIn("sentinel-plaintext", request.data.decode("utf-8"))
        self.assertNotIn(VALID_E2E_KEY, request.data.decode("utf-8"))

    def test_e2e_shrinks_payload_before_encrypting(self):
        payload = {"zcode": {"matched": "m" * 4000, "plan_id": "p1"}}
        self.assertGreater(_encoded_size(payload), ntfy.MAX_E2E_PLAINTEXT_BYTES)
        with mock.patch.object(
            ntfy.urllib.request, "urlopen", return_value=_FakeResponse(200)
        ) as urlopen:
            self.assertTrue(ntfy.publish("agw-abc_123", payload, e2e_key=VALID_E2E_KEY)[0])

        body = json.loads(urlopen.call_args.args[0].data.decode("utf-8"))
        plaintext = e2e.decrypt_envelope(body, VALID_E2E_KEY)
        self.assertLessEqual(len(plaintext), ntfy.MAX_E2E_PLAINTEXT_BYTES)
        decoded = json.loads(plaintext.decode("utf-8"))
        self.assertEqual(len(decoded["zcode"]["matched"]), 64)

    def test_e2e_payload_over_limit_is_rejected(self):
        payload = {"context_summary": {"conversation_id": "c" * 5000}}
        with mock.patch.object(ntfy.urllib.request, "urlopen") as urlopen:
            ok, detail = ntfy.publish("agw-abc_123", payload, e2e_key=VALID_E2E_KEY)

        self.assertFalse(ok)
        match = re.fullmatch(r"payload too large \((\d+) bytes\)", detail)
        self.assertIsNotNone(match)
        self.assertGreater(int(match.group(1)), ntfy.MAX_E2E_PLAINTEXT_BYTES)
        urlopen.assert_not_called()

    def test_e2e_invalid_key_is_rejected(self):
        with mock.patch.object(ntfy.urllib.request, "urlopen") as urlopen:
            for key in ("short", "", "A" * 42 + "*"):
                with self.subTest(key=key):
                    self.assertEqual(
                        ntfy.publish("agw-abc_123", {"a": 1}, e2e_key=key),
                        (False, "invalid e2e key"),
                    )
        urlopen.assert_not_called()


if __name__ == "__main__":
    unittest.main()
