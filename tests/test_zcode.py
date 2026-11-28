"""Tests for ZCode API response parsing, caching, and degraded states."""

from __future__ import annotations

import hashlib
import http.client
import io
import json
import os
import struct
import sys
import tempfile
import unittest
import urllib.error
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from agwidgets import crypto, paths, zcode  # noqa: E402


def _banner_touch(plan_id: str = "p1", label: str = "Claim", title: str = "300M tokens"):
    """Build a marketing touch payload with one claimable banner button."""
    return {
        "code": 0,
        "data": {
            "deliveries": [
                {
                    "resource_position": "banner",
                    "banner": {
                        "title": {"content": title},
                        "buttons": [
                            {
                                "text": {"content": label},
                                "action": {
                                    "type": "claim_zcode_plan",
                                    "args": {"plan_id": plan_id},
                                },
                            }
                        ],
                    },
                }
            ]
        },
    }


def _empty_touch():
    return {"code": 0, "data": {"deliveries": []}}


def _healthy_balance():
    return {"code": 0, "data": {"server_time": 2_000, "plans": [], "balances": []}}


def _query_zcode(touch, balance):
    """Run ``query_zcode_api`` with canned HTTP responses (touch, then balance)."""
    with mock.patch.object(zcode, "_http_get_json", side_effect=[touch, balance]):
        return zcode.query_zcode_api("token", "device-mid")


def _http_response(status: int, body: bytes):
    """Context-manager stand-in for ``urllib.request.urlopen``."""
    response = mock.MagicMock()
    response.status = status
    response.read.return_value = body
    response.__enter__.return_value = response
    response.__exit__.return_value = False
    return response


def _http_error(code: int, body: bytes) -> urllib.error.HTTPError:
    return urllib.error.HTTPError(
        zcode.ZCODE_ORIGIN, code, "error", hdrs=None, fp=io.BytesIO(body)
    )


def _token_fingerprint(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()[:16]


def _api_result(**overrides):
    """A healthy ``query_zcode_api`` result used by the cache tests."""
    result = {
        "running": True,
        "coupon_visible": False,
        "coupon_state": "absent",
        "matched": "",
        "plan_id": "",
        "method": "api",
        "auth_ok": True,
        "network_error": False,
        "error_kind": None,
        "remaining_units": 100,
        "total_units": 200,
        "ends_at": None,
        "plan_name": "",
        "checked_at": "2026-11-25T10:00:00+00:00",
    }
    result.update(overrides)
    return result


class _IsolatedLocalAppData(unittest.TestCase):
    """Point ``%LOCALAPPDATA%`` at a throwaway directory for one test."""

    def setUp(self):
        self._temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self._temp_dir.cleanup)
        patcher = mock.patch.dict(os.environ, {"LOCALAPPDATA": self._temp_dir.name})
        patcher.start()
        self.addCleanup(patcher.stop)
        self.local_appdata = Path(self._temp_dir.name)


class ZCodeParsingTests(unittest.TestCase):
    def test_extract_claim_coupon_banner(self):
        self.assertEqual(
            zcode._extract_claim_coupon(_banner_touch()),
            ("visible", "p1", "Claim"),
        )

    def test_extract_claim_coupon_in_banner_success_popup(self):
        # A campaign may keep the claim button only in the success dialog that
        # follows a banner's close button.
        payload = {
            "code": 0,
            "data": {
                "deliveries": [
                    {
                        "resource_position": "banner",
                        "campaign_id": "weekend-build",
                        "banner": {
                            "title": {"format": "plaintext", "content": "Weekend build"},
                            "buttons": [
                                {"text": {"content": "Later"}, "action": {"type": "close"}},
                            ],
                            "success_popup": {
                                "title": {"content": "Success"},
                                "buttons": [
                                    {
                                        "text": {"content": "Claim 300M"},
                                        "action": {
                                            "type": "claim_zcode_plan",
                                            "args": {"plan_id": "voucher-nested"},
                                        },
                                    }
                                ],
                            },
                        },
                    }
                ]
            },
        }
        self.assertEqual(
            zcode._extract_claim_coupon(payload),
            ("visible", "voucher-nested", "Claim 300M"),
        )

    def test_extract_claim_coupon_none(self):
        self.assertEqual(
            zcode._extract_claim_coupon({"data": {"deliveries": []}}),
            ("absent", "", ""),
        )
        self.assertEqual(zcode._extract_claim_coupon({}), ("unknown", "", ""))

    def test_extract_claim_coupon_junk_payloads_do_not_raise(self):
        self.assertEqual(zcode._extract_claim_coupon({}), ("unknown", "", ""))
        self.assertEqual(zcode._extract_claim_coupon({"data": []}), ("unknown", "", ""))
        self.assertEqual(
            zcode._extract_claim_coupon({"data": {"deliveries": [None, 1, "x"]}}),
            ("absent", "", ""),
        )

    def test_extract_claim_coupon_strange_args_and_text(self):
        payload = {
            "data": {
                "deliveries": [
                    {
                        "resource_position": "popup",
                        "popup": {
                            "title": "plain title",
                            "buttons": [
                                {
                                    "text": "Claim",
                                    "action": {
                                        "type": "claim_zcode_plan",
                                        "args": ["not", "a", "dict"],
                                    },
                                }
                            ],
                        },
                    }
                ]
            }
        }
        # Strings instead of {"content": ...} and a list of args must not
        # raise AttributeError; the label falls back to the plan id.
        self.assertEqual(
            zcode._extract_claim_coupon(payload), ("visible", "", "plan:")
        )

    def test_extract_balance_only_active_plans(self):
        payload = {
            "data": {
                "server_time": 2_000,
                "plans": [
                    {"plan_id": "active", "status": "active", "name": "Pro", "ends_at": 3_000},
                    {"plan_id": "old", "status": "expired", "ends_at": 1_000},
                ],
                "balances": [
                    {"plan_id": "active", "total_units": 500, "available_units": 200, "period_end": 3_000},
                    {"plan_id": "old", "total_units": 900, "available_units": 900, "period_end": 1_000},
                ],
            }
        }
        result = zcode._extract_balance(payload)
        self.assertEqual(result["remaining_units"], 200)
        self.assertEqual(result["total_units"], 500)
        self.assertEqual(result["plan_name"], "Pro")
        self.assertEqual(result["ends_at"], "1970-01-01T00:50:00Z")

    def test_extract_balance_used_units_fallback(self):
        payload = {
            "data": {
                "server_time": 1000,
                "plans": [{"plan_id": "p", "status": "active", "ends_at": 2000}],
                "balances": [{"plan_id": "p", "total_units": 100, "used_units": 40}],
            }
        }
        result = zcode._extract_balance(payload)
        self.assertEqual(result["remaining_units"], 60)
        self.assertEqual(result["total_units"], 100)

    def test_extract_balance_all_expired_plans_are_not_summed(self):
        payload = {
            "data": {
                "server_time": 2_000,
                "plans": [
                    # Expired by status even though ends_at is in the future.
                    {"plan_id": "old", "status": "expired", "name": "Old", "ends_at": 3_000},
                    # Active by status but already past its end date.
                    {"plan_id": "older", "status": "active", "ends_at": 1_000},
                ],
                "balances": [
                    {"plan_id": "old", "total_units": 10_000_000, "available_units": 9_000_000},
                    {"plan_id": "older", "total_units": 5_000_000, "available_units": 4_000_000},
                ],
            }
        }
        result = zcode._extract_balance(payload)
        self.assertIsNone(result["remaining_units"])
        self.assertIsNone(result["total_units"])

    def test_extract_balance_without_plans_sums_balances(self):
        for plans in (None, []):
            with self.subTest(plans=plans):
                data = {
                    "server_time": 1_000,
                    "balances": [
                        {"total_units": 100, "available_units": 20},
                        {"total_units": 50, "available_units": 10},
                    ],
                }
                if plans is not None:
                    data["plans"] = plans
                result = zcode._extract_balance({"data": data})
                self.assertEqual(result["remaining_units"], 30)
                self.assertEqual(result["total_units"], 150)

    def test_extract_balance_server_time_in_milliseconds(self):
        payload = {
            "data": {
                "server_time": 2_000_000_000_000,  # milliseconds
                "plans": [
                    {"plan_id": "active", "status": "active", "name": "Pro", "ends_at": 2_000_000_100},
                    {"plan_id": "expired", "status": "expired", "ends_at": 1_999_999_900},
                ],
                "balances": [
                    {"plan_id": "active", "total_units": 100, "available_units": 40},
                    {"plan_id": "expired", "total_units": 900, "available_units": 900},
                ],
            }
        }
        result = zcode._extract_balance(payload)
        self.assertEqual(result["remaining_units"], 40)
        self.assertEqual(result["total_units"], 100)
        self.assertEqual(result["plan_name"], "Pro")
        self.assertEqual(result["ends_at"], "2033-05-18T03:35:00Z")

    def test_parse_timestamp_iso_numeric_and_iso_strings(self):
        expected = "2023-11-14T22:13:20Z"
        self.assertEqual(zcode._parse_timestamp_iso(1_700_000_000.0), expected)
        self.assertEqual(zcode._parse_timestamp_iso(1_700_000_000), expected)
        self.assertEqual(zcode._parse_timestamp_iso(1_700_000_000_000), expected)
        self.assertEqual(
            zcode._parse_timestamp_iso("2026-11-25T10:00:00Z"),
            "2026-11-25T10:00:00Z",
        )
        self.assertEqual(
            zcode._parse_timestamp_iso("2026-11-25T10:00:00"),
            "2026-11-25T10:00:00Z",
        )

    def test_parse_timestamp_iso_rejects_invalid_values(self):
        for value in ("garbage", True, False, float("inf"), float("nan"), -5, 0, None, ""):
            with self.subTest(value=value):
                self.assertIsNone(zcode._parse_timestamp_iso(value))

    def test_to_number_rejects_bools_and_non_finite(self):
        self.assertEqual(zcode._to_number("12"), 12.0)
        self.assertEqual(zcode._to_number(12), 12.0)
        for value in (True, False, None, float("inf"), float("-inf"), float("nan")):
            with self.subTest(value=value):
                self.assertIsNone(zcode._to_number(value))


class ZCodeClientHeaderTests(unittest.TestCase):
    """The marketing feed is gated on the client version and language."""

    def setUp(self):
        self._version_cache = zcode._APP_VERSION_CACHE
        zcode._APP_VERSION_CACHE = None
        self.addCleanup(self._restore_cache)

    def _restore_cache(self):
        zcode._APP_VERSION_CACHE = self._version_cache

    def test_marketing_requests_advertise_the_detected_client(self):
        with mock.patch.object(zcode, "_http_get_json", return_value=(200, None, None)) as http_get, \
                mock.patch.object(zcode, "zcode_app_version", return_value="4.0.0"), \
                mock.patch.object(zcode, "client_language", return_value="en-US"):
            zcode.query_zcode_api("token", "mid")

        touch_headers = http_get.call_args_list[0].args[1]
        self.assertEqual(touch_headers["X-ZCode-App-Version"], "4.0.0")
        self.assertEqual(touch_headers["X-Client-Language"], "en-US")

    def test_stale_version_and_russian_locale_are_gone(self):
        # Regression guard: the two header values that hid every campaign.
        with mock.patch.object(zcode, "_http_get_json", return_value=(200, None, None)) as http_get:
            zcode.query_zcode_api("token", "mid")

        headers = http_get.call_args_list[0].args[1]
        self.assertNotEqual(headers["X-ZCode-App-Version"], "3.5.0")
        self.assertNotEqual(headers["X-Client-Language"], "ru-RU")

    def test_version_reads_installed_asar(self):
        package = json.dumps({"name": "@zcode/desktop", "version": "9.9.9"}).encode("utf-8")
        header = json.dumps(
            {"files": {"package.json": {"size": len(package), "offset": "0"}}}
        ).encode("utf-8")
        with tempfile.TemporaryDirectory() as temp_dir:
            app_dir = Path(temp_dir)
            resources = app_dir / "resources"
            resources.mkdir()
            (resources / "app.asar").write_bytes(
                struct.pack("<I", 4)
                + struct.pack("<I", 8 + len(header))
                + struct.pack("<I", len(header))
                + struct.pack("<I", len(header))
                + header
                + package
            )
            with mock.patch.object(zcode, "_app_dirs", return_value=[app_dir]):
                self.assertEqual(zcode.zcode_app_version(), "9.9.9")

    def test_app_dir_env_is_the_first_candidate(self):
        with mock.patch.dict(os.environ, {zcode.APP_DIR_ENV: r"C:\Portable\ZCode"}):
            self.assertEqual(zcode._app_dirs()[0], Path(r"C:\Portable\ZCode"))

    def test_version_env_override_wins(self):
        with mock.patch.dict(os.environ, {zcode.APP_VERSION_ENV: "1.2.3"}):
            self.assertEqual(zcode.zcode_app_version(), "1.2.3")

    def test_version_falls_back_without_an_install(self):
        with mock.patch.object(zcode, "_app_dirs", return_value=[]):
            self.assertEqual(zcode.zcode_app_version(), zcode.ZCODE_APP_VERSION)

    def test_unreadable_asar_falls_back(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            app_dir = Path(temp_dir)
            (app_dir / "resources").mkdir()
            (app_dir / "resources" / "app.asar").write_bytes(b"not an asar")
            with mock.patch.object(zcode, "_app_dirs", return_value=[app_dir]):
                self.assertEqual(zcode.zcode_app_version(), zcode.ZCODE_APP_VERSION)

    def test_chinese_system_asks_for_chinese_campaigns(self):
        with mock.patch.object(zcode.locale, "getlocale", return_value=("zh_CN", "utf-8")):
            self.assertEqual(zcode.client_language(), "zh-CN")

    def test_other_locales_normalize_to_english(self):
        for name in ("ru_RU", "Ukrainian_Ukraine", "de_DE", "", None):
            with self.subTest(name=name), mock.patch.object(
                zcode.locale, "getlocale", return_value=(name, None)
            ):
                self.assertEqual(zcode.client_language(), "en-US")

    def test_language_env_override_wins(self):
        with mock.patch.dict(os.environ, {zcode.CLIENT_LANGUAGE_ENV: "zh-CN"}):
            self.assertEqual(zcode.client_language(), "zh-CN")


class ZCodeStateTests(unittest.TestCase):
    @mock.patch.object(zcode, "_http_get_json")
    def test_network_failure_is_not_expired_token(self, http_get):
        http_get.return_value = (0, None, "network")
        result = zcode.query_zcode_api("token", "mid")
        self.assertTrue(result["network_error"])
        self.assertFalse(result["running"])
        self.assertIsNone(result["auth_ok"])
        self.assertEqual(result["error_kind"], "network")
        self.assertEqual(result["coupon_state"], "unknown")
        self.assertIsNone(result["coupon_visible"])

    @mock.patch.object(zcode, "_http_get_json")
    def test_401_is_expired_token_not_network(self, http_get):
        http_get.return_value = (401, {"code": 401}, None)
        result = zcode.query_zcode_api("token", "mid")
        self.assertFalse(result["network_error"])
        self.assertFalse(result["auth_ok"])
        self.assertEqual(result["error_kind"], "auth_expired")
        self.assertEqual(result["coupon_state"], "unknown")
        self.assertIsNone(result["coupon_visible"])

    @mock.patch.object(zcode, "_http_get_json")
    def test_partial_failure_keeps_token_valid(self, http_get):
        http_get.side_effect = [
            (200, {"code": 0, "data": {"deliveries": []}}, None),
            (500, None, None),
        ]
        result = zcode.query_zcode_api("token", "mid")
        self.assertFalse(result["network_error"])
        self.assertTrue(result["auth_ok"])
        self.assertEqual(result["error_kind"], "server_error")
        self.assertEqual(result["coupon_state"], "absent")
        self.assertIs(result["coupon_visible"], False)

    def test_coupon_visible_when_claim_button_present(self):
        result = _query_zcode((200, _banner_touch(), None), (200, _healthy_balance(), None))
        self.assertEqual(result["coupon_state"], "visible")
        self.assertIs(result["coupon_visible"], True)
        self.assertEqual(result["plan_id"], "p1")
        self.assertEqual(result["matched"], "Claim")

    def test_coupon_absent_with_empty_deliveries(self):
        result = _query_zcode((200, _empty_touch(), None), (200, _healthy_balance(), None))
        self.assertEqual(result["coupon_state"], "absent")
        self.assertIs(result["coupon_visible"], False)

    def test_touch_server_error_is_unknown(self):
        result = _query_zcode((500, None, None), (200, _healthy_balance(), None))
        self.assertEqual(result["coupon_state"], "unknown")
        self.assertIsNone(result["coupon_visible"])
        self.assertEqual(result["error_kind"], "server_error")
        self.assertTrue(result["auth_ok"])

    def test_touch_rate_limited_is_unknown(self):
        result = _query_zcode((429, None, None), (200, _healthy_balance(), None))
        self.assertEqual(result["coupon_state"], "unknown")
        self.assertIsNone(result["coupon_visible"])
        self.assertEqual(result["error_kind"], "rate_limited")

    def test_touch_unparseable_body_is_unknown(self):
        result = _query_zcode(
            (200, None, "parse_failed"), (200, _healthy_balance(), None)
        )
        self.assertEqual(result["coupon_state"], "unknown")
        self.assertIsNone(result["coupon_visible"])
        self.assertEqual(result["error_kind"], "parse_failed")

    def test_touch_missing_deliveries_is_unknown(self):
        # A successful touch without a deliveries list means the schema moved:
        # the coupon state is unknown and the drift is reported as api_changed.
        payloads = (
            {"code": 0, "data": {}},
            {"code": 0, "data": {"deliveries": {"items": []}}},
        )
        for payload in payloads:
            with self.subTest(payload=payload):
                result = _query_zcode(
                    (200, payload, None), (200, _healthy_balance(), None)
                )
                self.assertEqual(result["coupon_state"], "unknown")
                self.assertIsNone(result["coupon_visible"])
                self.assertEqual(result["error_kind"], "api_changed")

    def test_server_error_is_not_masked_by_schema_drift(self):
        # The touch schema moved, but a failing balance request is the more
        # actionable problem and must keep the higher error priority.
        result = _query_zcode(
            (200, {"code": 0, "data": {}}, None),
            (500, None, None),
        )
        self.assertEqual(result["coupon_state"], "unknown")
        self.assertEqual(result["error_kind"], "server_error")

    def test_touch_business_error_code_is_unknown(self):
        result = _query_zcode(
            (200, {"code": 1, "message": "nope"}, None),
            (200, _healthy_balance(), None),
        )
        self.assertEqual(result["coupon_state"], "unknown")
        self.assertIsNone(result["coupon_visible"])
        self.assertEqual(result["error_kind"], "api_changed")

    def test_business_401_in_http_200_is_auth_expired(self):
        result = _query_zcode(
            (200, {"code": 401, "message": "expired"}, None),
            (200, _healthy_balance(), None),
        )
        self.assertFalse(result["network_error"])
        self.assertIs(result["auth_ok"], False)
        self.assertEqual(result["error_kind"], "auth_expired")
        self.assertIsNone(result["coupon_visible"])

    def test_http_403_is_auth_expired(self):
        result = _query_zcode((403, {"code": 403}, None), (403, {"code": 403}, None))
        self.assertFalse(result["network_error"])
        self.assertIs(result["auth_ok"], False)
        self.assertIn(result["error_kind"], ("auth_expired",))
        self.assertIsNone(result["coupon_visible"])

    def test_both_requests_network_leave_auth_unknown(self):
        result = _query_zcode((0, None, "network"), (0, None, "network"))
        self.assertTrue(result["network_error"])
        self.assertFalse(result["running"])
        self.assertIsNone(result["auth_ok"])
        self.assertEqual(result["error_kind"], "network")

    def test_rate_limited_wins_over_server_error(self):
        result = _query_zcode((429, None, None), (500, None, None))
        self.assertEqual(result["error_kind"], "rate_limited")

    def test_both_200_parse_failed_leaves_auth_unknown(self):
        result = _query_zcode((200, None, "parse_failed"), (200, None, "parse_failed"))
        self.assertIsNone(result["auth_ok"])
        self.assertEqual(result["error_kind"], "parse_failed")
        self.assertEqual(result["coupon_state"], "unknown")
        self.assertIsNone(result["coupon_visible"])


class ZCodeHttpTests(unittest.TestCase):
    @mock.patch("urllib.request.urlopen")
    def test_valid_json_returns_body(self, urlopen):
        urlopen.return_value = _http_response(200, b'{"code": 0, "data": {}}')
        self.assertEqual(
            zcode._http_get_json("https://example.test/api", {}),
            (200, {"code": 0, "data": {}}, None),
        )

    @mock.patch("urllib.request.urlopen")
    def test_invalid_json_on_200_is_parse_failed(self, urlopen):
        urlopen.return_value = _http_response(200, b"<html>not json</html>")
        self.assertEqual(
            zcode._http_get_json("https://example.test/api", {}),
            (200, None, "parse_failed"),
        )

    @mock.patch("urllib.request.urlopen")
    def test_http_error_with_json_returns_code_and_body(self, urlopen):
        urlopen.side_effect = _http_error(400, b'{"code": 400, "message": "bad"}')
        self.assertEqual(
            zcode._http_get_json("https://example.test/api", {}),
            (400, {"code": 400, "message": "bad"}, None),
        )

    @mock.patch("urllib.request.urlopen")
    def test_http_error_with_html_returns_code_without_body(self, urlopen):
        urlopen.side_effect = _http_error(502, b"<html>bad gateway</html>")
        self.assertEqual(
            zcode._http_get_json("https://example.test/api", {}),
            (502, None, None),
        )

    @mock.patch("urllib.request.urlopen")
    def test_os_error_is_network(self, urlopen):
        urlopen.side_effect = OSError("connection refused")
        self.assertEqual(
            zcode._http_get_json("https://example.test/api", {}),
            (0, None, "network"),
        )

    @mock.patch("urllib.request.urlopen")
    def test_url_error_is_network(self, urlopen):
        urlopen.side_effect = urllib.error.URLError("name resolution failed")
        self.assertEqual(
            zcode._http_get_json("https://example.test/api", {}),
            (0, None, "network"),
        )

    @mock.patch("urllib.request.urlopen")
    def test_http_exception_is_network(self, urlopen):
        urlopen.side_effect = http.client.HTTPException("bad frame")
        self.assertEqual(
            zcode._http_get_json("https://example.test/api", {}),
            (0, None, "network"),
        )

    @mock.patch("urllib.request.urlopen")
    def test_oversized_body_is_parse_failed(self, urlopen):
        urlopen.return_value = _http_response(
            200, b"x" * (zcode.MAX_BODY_BYTES + 1)
        )
        self.assertEqual(
            zcode._http_get_json("https://example.test/api", {}),
            (200, None, "parse_failed"),
        )


class ZCodeCacheTests(_IsolatedLocalAppData):
    def setUp(self):
        super().setUp()
        self.state_dir = paths.state_dir()
        self.cache_path = self.state_dir / "zcode_api_cache.json"

    def _write_secrets(self, token: str, **extra):
        data = {"zcode_token": token, "device_mid": "mid-1"}
        data.update(extra)
        paths.write_json(paths.secrets_path(), data)

    def test_no_token_returns_none_without_request(self):
        with mock.patch.object(zcode, "query_zcode_api") as query:
            self.assertIsNone(zcode.fetch_cached_status(self.state_dir))
        query.assert_not_called()

    @mock.patch.object(zcode, "query_zcode_api")
    def test_backward_clock_jump_does_not_freeze_cache(self, query):
        self._write_secrets("tok-clock")
        token_fp = hashlib.sha256(b"tok-clock").hexdigest()[:16]
        future_ts = datetime.now(timezone.utc).timestamp() + 3600.0
        paths.write_json(
            self.cache_path,
            {
                "ts": future_ts,
                "seq": 2,
                "token_fingerprint": token_fp,
                "result": {"auth_ok": True, "coupon_state": "absent"},
            },
        )
        query.return_value = {"auth_ok": True, "coupon_state": "visible"}
        result = zcode.fetch_cached_status(self.state_dir)
        query.assert_called_once()
        self.assertEqual(result["coupon_state"], "visible")

    @mock.patch.object(zcode, "query_zcode_api")
    def test_successful_response_is_cached_and_reused(self, query):
        self._write_secrets("token-1")
        query.return_value = _api_result()

        first = zcode.fetch_cached_status(self.state_dir)
        self.assertEqual(first, _api_result())
        query.assert_called_once()

        cached = paths.read_json(self.cache_path)
        self.assertEqual(cached["seq"], 1)
        self.assertEqual(cached["token_fingerprint"], _token_fingerprint("token-1"))

        second = zcode.fetch_cached_status(self.state_dir)
        self.assertEqual(second, first)
        query.assert_called_once()  # Still inside the 600 s window.

        forced = zcode.fetch_cached_status(self.state_dir, force=True)
        self.assertEqual(forced, first)
        self.assertEqual(query.call_count, 2)
        self.assertEqual(query.call_args.kwargs.get("seq"), 1)
        self.assertEqual(paths.read_json(self.cache_path)["seq"], 2)

    @mock.patch.object(zcode, "query_zcode_api")
    def test_token_change_invalidates_cache_immediately(self, query):
        self._write_secrets("new-token")
        stale = _api_result(remaining_units=999)
        paths.write_json(
            self.cache_path,
            {
                "ts": datetime.now(timezone.utc).timestamp(),
                "seq": 5,
                "token_fingerprint": _token_fingerprint("old-token"),
                "result": stale,
            },
        )
        query.return_value = _api_result(remaining_units=1)

        result = zcode.fetch_cached_status(self.state_dir)

        query.assert_called_once()
        self.assertEqual(result["remaining_units"], 1)
        self.assertEqual(
            paths.read_json(self.cache_path)["token_fingerprint"],
            _token_fingerprint("new-token"),
        )

    def test_failed_poll_uses_short_retry_window(self):
        self._write_secrets("token-1")
        failure = _api_result(
            running=False,
            coupon_state="unknown",
            coupon_visible=None,
            auth_ok=None,
            network_error=True,
            error_kind="network",
        )
        now_ts = datetime.now(timezone.utc).timestamp()

        with self.subTest(age="120s"):
            paths.write_json(
                self.cache_path,
                {
                    "ts": now_ts - 120,
                    "seq": 1,
                    "token_fingerprint": _token_fingerprint("token-1"),
                    "result": failure,
                },
            )
            with mock.patch.object(
                zcode, "query_zcode_api", return_value=_api_result()
            ) as query:
                fresh = zcode.fetch_cached_status(self.state_dir)
            query.assert_called_once()
            self.assertFalse(fresh["network_error"])

        with self.subTest(age="30s"):
            paths.write_json(
                self.cache_path,
                {
                    "ts": now_ts - 30,
                    "seq": 2,
                    "token_fingerprint": _token_fingerprint("token-1"),
                    "result": failure,
                },
            )
            with mock.patch.object(
                zcode, "query_zcode_api", return_value=_api_result()
            ) as query:
                cached = zcode.fetch_cached_status(self.state_dir)
            query.assert_not_called()
            self.assertTrue(cached["network_error"])

    @mock.patch.object(zcode, "query_zcode_api")
    def test_rate_limited_cache_blocks_forced_refresh(self, query):
        self._write_secrets("token-1")
        limited = _api_result(
            coupon_state="unknown",
            coupon_visible=None,
            remaining_units=None,
            total_units=None,
            error_kind="rate_limited",
        )
        now_ts = datetime.now(timezone.utc).timestamp()
        paths.write_json(
            self.cache_path,
            {
                "ts": now_ts - 30,
                "seq": 1,
                "token_fingerprint": _token_fingerprint("token-1"),
                "result": limited,
            },
        )

        result = zcode.fetch_cached_status(self.state_dir, force=True)

        query.assert_not_called()
        self.assertEqual(result["error_kind"], "rate_limited")

    @mock.patch.object(zcode, "query_zcode_api")
    def test_rate_limited_cache_expires_after_cooldown(self, query):
        self._write_secrets("token-1")
        limited = _api_result(
            coupon_state="unknown",
            coupon_visible=None,
            remaining_units=None,
            total_units=None,
            error_kind="rate_limited",
        )
        now_ts = datetime.now(timezone.utc).timestamp()
        paths.write_json(
            self.cache_path,
            {
                "ts": now_ts - zcode.ZCODE_RATE_LIMIT_RETRY_SEC - 1,
                "seq": 1,
                "token_fingerprint": _token_fingerprint("token-1"),
                "result": limited,
            },
        )
        query.return_value = _api_result(remaining_units=9)

        result = zcode.fetch_cached_status(self.state_dir, force=True)

        query.assert_called_once()
        self.assertEqual(result["remaining_units"], 9)

    @mock.patch.object(zcode, "query_zcode_api")
    def test_force_ignores_fresh_cache(self, query):
        self._write_secrets("token-1")
        paths.write_json(
            self.cache_path,
            {
                "ts": datetime.now(timezone.utc).timestamp(),
                "seq": 1,
                "token_fingerprint": _token_fingerprint("token-1"),
                "result": _api_result(remaining_units=7),
            },
        )
        query.return_value = _api_result(remaining_units=8)

        result = zcode.fetch_cached_status(self.state_dir, force=True)

        query.assert_called_once()
        self.assertEqual(result["remaining_units"], 8)

    @mock.patch.object(zcode, "query_zcode_api")
    def test_broken_cache_falls_back_to_a_fresh_request(self, query):
        self._write_secrets("token-1")
        self.cache_path.write_text("{not valid json", encoding="utf-8")
        query.return_value = _api_result(remaining_units=5)

        result = zcode.fetch_cached_status(self.state_dir)

        query.assert_called_once()
        self.assertEqual(result["remaining_units"], 5)


class ZCodeSecretsTests(_IsolatedLocalAppData):
    @unittest.skipUnless(crypto.encryption_available(), "DPAPI is Windows-only")
    def test_save_secrets_encrypts_token(self):
        saved = zcode.save_secrets({"zcode_token": "jwt-1"})
        self.assertEqual(saved["zcode_token"], "jwt-1")

        raw = paths.secrets_path().read_text(encoding="utf-8")
        data = json.loads(raw)
        self.assertNotIn("zcode_token", data)
        self.assertTrue(data["zcode_token_enc"].startswith(crypto.DPAPI_PREFIX))
        self.assertNotIn("jwt-1", raw)
        self.assertTrue(str(data["device_mid"]).strip())

        loaded = zcode.load_secrets()
        self.assertEqual(loaded["zcode_token"], "jwt-1")

    @unittest.skipUnless(crypto.encryption_available(), "DPAPI is Windows-only")
    def test_save_secrets_preserves_other_fields_and_resaves_token(self):
        topic_blob = crypto.protect_secret("agw-test-topic")
        first = zcode.save_secrets(
            {"zcode_token": "jwt-1", "ntfy_topic_enc": topic_blob}
        )
        device_mid = first["device_mid"]

        second = zcode.save_secrets({})

        self.assertEqual(second["zcode_token"], "jwt-1")
        data = paths.read_json(paths.secrets_path())
        self.assertEqual(data["device_mid"], device_mid)
        self.assertEqual(data["ntfy_topic_enc"], topic_blob)
        self.assertNotIn("zcode_token", data)

    def test_legacy_plaintext_token_is_returned_and_migrated(self):
        paths.write_json(
            paths.secrets_path(),
            {"zcode_token": "legacy-jwt", "device_mid": "mid-1"},
        )

        loaded = zcode.load_secrets()

        self.assertEqual(loaded["zcode_token"], "legacy-jwt")
        data = paths.read_json(paths.secrets_path())
        if crypto.encryption_available():
            self.assertNotIn("zcode_token", data)
            self.assertTrue(data["zcode_token_enc"].startswith(crypto.DPAPI_PREFIX))
        else:
            self.assertEqual(data["zcode_token"], "legacy-jwt")
        self.assertEqual(data["device_mid"], "mid-1")

    @unittest.skipUnless(crypto.encryption_available(), "DPAPI is Windows-only")
    def test_clear_zcode_token_keeps_device_and_removes_cache(self):
        topic_blob = crypto.protect_secret("agw-test-topic")
        saved = zcode.save_secrets(
            {"zcode_token": "jwt-1", "ntfy_topic_enc": topic_blob}
        )
        cache_path = paths.state_dir() / "zcode_api_cache.json"
        paths.write_json(cache_path, {"ts": 1, "seq": 1, "result": {}})

        self.assertTrue(zcode.clear_zcode_token())

        data = paths.read_json(paths.secrets_path())
        self.assertNotIn("zcode_token", data)
        self.assertNotIn("zcode_token_enc", data)
        self.assertEqual(data["device_mid"], saved["device_mid"])
        self.assertEqual(data["ntfy_topic_enc"], topic_blob)
        self.assertFalse(cache_path.exists())
        self.assertFalse(zcode.clear_zcode_token())

    def test_corrupt_secrets_raise_without_overwrite(self):
        secrets_file = paths.secrets_path()
        secrets_file.parent.mkdir(parents=True, exist_ok=True)
        secrets_file.write_text("{not json", encoding="utf-8")

        with self.assertRaises(paths.ConfigReadError):
            zcode.load_secrets()
        with self.assertRaises(paths.ConfigReadError):
            zcode.save_secrets({"zcode_token": "jwt-1"})

        self.assertEqual(secrets_file.read_text(encoding="utf-8"), "{not json")

    @unittest.skipUnless(crypto.encryption_available(), "DPAPI is Windows-only")
    def test_undecryptable_token_raises_and_is_not_overwritten(self):
        data = {"zcode_token_enc": "dpapi:v1:bm90", "device_mid": "mid-1"}
        paths.write_json(paths.secrets_path(), data)

        with self.assertRaises(crypto.SecretDecryptError):
            zcode.load_secrets()
        with self.assertRaises(crypto.SecretDecryptError):
            zcode.save_secrets({})

        self.assertEqual(paths.read_json(paths.secrets_path()), data)


if __name__ == "__main__":
    unittest.main()
