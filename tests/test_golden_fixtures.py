"""Golden-fixture tests for the Python implementation.

The fixtures under ``tests/fixtures`` are the shared contract between the
Python collector, the desktop widget, and the Android app. Every case is
executed against the real modules; the fixture files are read-only here.
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from agwidgets import context_model, oauth, zcode  # noqa: E402

FIXTURES_DIR = Path(__file__).with_name("fixtures")
ZCODE_FIXTURE = "zcode_cases.json"
FORMAT_FIXTURE = "format_cases.json"
STATUS_FIXTURE = "status_cases.json"
ALL_FIXTURES = (ZCODE_FIXTURE, FORMAT_FIXTURE, STATUS_FIXTURE)

# Credentials, the personal ntfy topic, and raw JWTs must never leak into the
# shared fixtures.
FORBIDDEN_FRAGMENTS = ("dpapi:v1:", "ks:v1:", "agw-", "eyJ")

REQUIRED_ZCODE_CASES = (
    "active_and_expired_mixed",
    "all_plans_expired",
    "server_time_milliseconds",
    "used_units_fallback_no_plans",
    "banner_voucher_visible",
    "banner_success_popup_claim_visible",
    "popup_voucher_visible",
    "empty_deliveries_absent",
    "missing_deliveries_api_changed",
)

BALANCE_KEYS = {"remaining_units", "total_units", "plan_name", "ends_at"}
TOUCH_KEYS = {"coupon_state", "plan_id", "matched"}


class FixtureTestCase(unittest.TestCase):
    """Shared strict loader: a missing or malformed fixture is a failure."""

    def read_fixture(self, filename: str) -> str:
        path = FIXTURES_DIR / filename
        if not path.is_file():
            self.fail(f"Missing golden fixture file: {path}")
        return path.read_text(encoding="utf-8")

    def load_fixture(self, filename: str) -> dict[str, Any]:
        text = self.read_fixture(filename)
        try:
            data = json.loads(text)
        except json.JSONDecodeError as error:
            self.fail(f"Golden fixture {filename} is not valid JSON: {error}")
        if not isinstance(data, dict):
            self.fail(f"Golden fixture {filename} must contain a JSON object")
        return data

    def fixture_section(
        self, data: dict[str, Any], filename: str, section: str
    ) -> list[dict[str, Any]]:
        cases = data.get(section)
        if not isinstance(cases, list) or not cases:
            self.fail(f"Golden fixture {filename} needs a non-empty {section!r} list")
        for case in cases:
            if not isinstance(case, dict):
                self.fail(f"Every {section!r} case in {filename} must be an object")
        return cases


class ZCodeGoldenFixtureTests(FixtureTestCase):
    def setUp(self):
        self.data = self.load_fixture(ZCODE_FIXTURE)

    def _case_by_name(self, name: str) -> dict[str, Any]:
        for section in ("balance_cases", "touch_cases"):
            for case in self.data.get(section) or []:
                if isinstance(case, dict) and case.get("name") == name:
                    return case
        self.fail(f"Golden fixture {ZCODE_FIXTURE} has no case named {name!r}")

    def test_balance_cases_match_production_parser(self):
        cases = self.fixture_section(self.data, ZCODE_FIXTURE, "balance_cases")
        for case in cases:
            with self.subTest(case=case.get("name")):
                expected = case["expected"]
                self.assertEqual(set(expected), BALANCE_KEYS)
                self.assertEqual(zcode._extract_balance(case["payload"]), expected)

    def test_touch_cases_match_production_parser(self):
        cases = self.fixture_section(self.data, ZCODE_FIXTURE, "touch_cases")
        for case in cases:
            with self.subTest(case=case.get("name")):
                expected = case["expected"]
                self.assertEqual(set(expected), TOUCH_KEYS)
                coupon_state, plan_id, matched = zcode._extract_claim_coupon(
                    case["payload"]
                )
                self.assertEqual(
                    {
                        "coupon_state": coupon_state,
                        "plan_id": plan_id,
                        "matched": matched,
                    },
                    expected,
                )

    def test_required_case_names_are_present(self):
        names = {
            case.get("name")
            for section in ("balance_cases", "touch_cases")
            for case in self.data.get(section) or []
            if isinstance(case, dict)
        }
        for name in REQUIRED_ZCODE_CASES:
            with self.subTest(case=name):
                self.assertIn(name, names)

    def test_all_plans_expired_has_no_units(self):
        case = self._case_by_name("all_plans_expired")
        result = zcode._extract_balance(case["payload"])
        for field in ("remaining_units", "total_units"):
            with self.subTest(field=field):
                self.assertIsNone(case["expected"][field])
                self.assertIsNone(result[field])

    def test_missing_deliveries_reports_unknown(self):
        case = self._case_by_name("missing_deliveries_api_changed")
        self.assertEqual(case["expected"]["coupon_state"], "unknown")
        coupon_state, _, _ = zcode._extract_claim_coupon(case["payload"])
        self.assertEqual(coupon_state, "unknown")


class FormatGoldenFixtureTests(FixtureTestCase):
    def setUp(self):
        self.data = self.load_fixture(FORMAT_FIXTURE)

    def test_window_cases(self):
        cases = self.fixture_section(self.data, FORMAT_FIXTURE, "window_cases")
        for case in cases:
            with self.subTest(input=case["input"]):
                self.assertEqual(
                    context_model.format_window(case["input"]), case["expected"]
                )

    def test_token_cases(self):
        cases = self.fixture_section(self.data, FORMAT_FIXTURE, "token_cases")
        for case in cases:
            with self.subTest(input=case["input"]):
                self.assertEqual(
                    context_model.format_tokens(case["input"]), case["expected"]
                )

    def test_context_level_cases(self):
        cases = self.fixture_section(self.data, FORMAT_FIXTURE, "context_level_cases")
        for case in cases:
            with self.subTest(tokens=case["tokens"], window=case["window"]):
                thresholds = {
                    "yellow_pct": case["yellow_pct"],
                    "red_pct": case["red_pct"],
                }
                result = context_model.compute_context(
                    case["tokens"], case["window"], thresholds
                )
                self.assertEqual(result["pct"], case["expected_pct"])
                self.assertEqual(result["level"], case["expected_level"])
                if case["window"] is None:
                    self.assertIsNone(result["pct"])
                    self.assertEqual(result["level"], "unknown")

    def test_oauth_url_cases(self):
        cases = self.fixture_section(self.data, FORMAT_FIXTURE, "oauth_url_cases")
        for case in cases:
            with self.subTest(url=case["url"]):
                trusted = oauth.is_trusted_authorize_url(case["url"]) is not None
                self.assertEqual(trusted, case["trusted"])


class StatusGoldenFixtureTests(FixtureTestCase):
    def setUp(self):
        self.data = self.load_fixture(STATUS_FIXTURE)

    def test_valid_v2_contract(self):
        payload = self.data.get("valid_v2")
        self.assertIsInstance(payload, dict)
        self.assertEqual(payload["schema_version"], 2)
        self.assertIn("pc_updated_at", payload)

        summary = payload["context_summary"]
        for field in ("yellow_pct", "red_pct", "window_source"):
            with self.subTest(field=field):
                self.assertIn(field, summary)

        buckets = {
            bucket.get("id"): bucket
            for bucket in payload["antigravity"]["buckets"]
            if isinstance(bucket, dict)
        }
        self.assertIn("3p-5h", buckets)
        self.assertEqual(buckets["3p-5h"].get("reset_time"), "1970-01-01T00:00:00Z")

        zcode_status = payload["zcode"]
        state = zcode_status["coupon_state"]
        if state == "visible":
            self.assertIs(zcode_status["coupon_visible"], True)
        elif state == "absent":
            self.assertIs(zcode_status["coupon_visible"], False)
        elif state == "unknown":
            self.assertIsNone(zcode_status["coupon_visible"])
            self.assertEqual(zcode_status["error_kind"], "network")
        else:
            self.fail(f"Unexpected coupon_state in valid_v2: {state!r}")

        computed = context_model.compute_context(
            summary["tokens"],
            summary["window"],
            {"yellow_pct": summary["yellow_pct"], "red_pct": summary["red_pct"]},
        )
        for field in ("pct", "level", "warn_tokens", "risk_tokens"):
            with self.subTest(field=field):
                self.assertEqual(computed[field], summary[field])

    def test_valid_v1_is_legacy(self):
        payload = self.data.get("valid_v1")
        self.assertIsInstance(payload, dict)
        self.assertNotIn("schema_version", payload)
        self.assertIsInstance(payload["zcode"]["coupon_visible"], bool)
        self.assertEqual(payload["context_summary"]["level"], "unknown")

    def test_invalid_empty_payload(self):
        self.assertEqual(self.data.get("invalid_empty"), {})


class FixtureHygieneTests(FixtureTestCase):
    def test_fixtures_contain_no_secrets(self):
        for filename in ALL_FIXTURES:
            text = self.read_fixture(filename)
            for fragment in FORBIDDEN_FRAGMENTS:
                with self.subTest(fixture=filename, fragment=fragment):
                    self.assertNotIn(fragment, text)


if __name__ == "__main__":
    unittest.main()
