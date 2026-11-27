"""Tests for status aggregation, change detection, and active-chat selection."""

from __future__ import annotations

import io
import json
import os
import sys
import tempfile
import time
import unittest
from contextlib import ExitStack
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from agwidgets import antigravity, context, paths, status, zcode  # noqa: E402


def _zcode_status(**overrides):
    """A healthy ZCode API result used by ``build_status``/``run_once`` tests."""
    payload = {
        "running": True,
        "coupon_visible": False,
        "coupon_state": "absent",
        "matched": "",
        "plan_id": "p1",
        "method": "api",
        "auth_ok": True,
        "network_error": False,
        "error_kind": None,
        "remaining_units": 2_000_000,
        "total_units": 5_000_000,
        "ends_at": "2026-12-01T00:00:00Z",
        "plan_name": "Pro",
        "checked_at": "2026-11-25T10:00:00+00:00",
    }
    payload.update(overrides)
    return payload


class ChangeDetectionTests(unittest.TestCase):
    def base_status(self):
        return {
            "antigravity": {
                "plan_tier": "Pro",
                "buckets": [
                    {"id": "gemini-5h", "remaining_fraction": 0.5, "reset_time": "2026-01-01T00:00:00Z"}
                ],
            },
            "zcode": {
                "coupon_visible": False,
                "coupon_state": "absent",
                "error_kind": None,
                "network_error": False,
                "auth_ok": True,
                "remaining_units": 100,
                "total_units": 1000,
                "ends_at": None,
                "plan_name": "",
            },
            "context_summary": {"level": "green", "pct": 10.0, "model": "gemini-4-neon-high"},
        }

    def test_context_percent_change_triggers_publish(self):
        previous = self.base_status()
        current = self.base_status()
        current["context_summary"]["pct"] = 12.5
        self.assertTrue(status.has_meaningful_change(previous, current))

    def test_small_context_change_is_throttled(self):
        previous = self.base_status()
        current = self.base_status()
        current["context_summary"]["pct"] = 11.0
        self.assertFalse(status.has_meaningful_change(previous, current))

    def test_active_conversation_change_triggers_publish(self):
        previous = self.base_status()
        current = self.base_status()
        previous["context_summary"]["conversation_id"] = "a"
        current["context_summary"]["conversation_id"] = "b"
        self.assertTrue(status.has_meaningful_change(previous, current))

    def test_network_error_change_triggers_publish(self):
        previous = self.base_status()
        current = self.base_status()
        current["zcode"]["network_error"] = True
        self.assertTrue(status.has_meaningful_change(previous, current))

    def test_quota_change_triggers_publish(self):
        previous = self.base_status()
        current = self.base_status()
        current["antigravity"]["buckets"][0]["remaining_fraction"] = 0.55
        self.assertTrue(status.has_meaningful_change(previous, current))

    def test_non_dict_previous_payload_is_safe(self):
        self.assertTrue(status.has_meaningful_change(None, self.base_status()))

    def test_coupon_state_change_triggers_publish(self):
        previous = self.base_status()
        current = self.base_status()
        previous["zcode"]["coupon_state"] = "absent"
        current["zcode"]["coupon_state"] = "unknown"
        self.assertTrue(status.has_meaningful_change(previous, current))

    def test_error_kind_change_triggers_publish(self):
        previous = self.base_status()
        current = self.base_status()
        current["zcode"]["error_kind"] = "rate_limited"
        self.assertTrue(status.has_meaningful_change(previous, current))

    def test_ends_at_change_triggers_publish(self):
        previous = self.base_status()
        current = self.base_status()
        previous["zcode"]["ends_at"] = None
        current["zcode"]["ends_at"] = "2026-12-01T00:00:00Z"
        self.assertTrue(status.has_meaningful_change(previous, current))

    def test_plan_name_change_triggers_publish(self):
        previous = self.base_status()
        current = self.base_status()
        previous["zcode"]["plan_name"] = ""
        current["zcode"]["plan_name"] = "Pro"
        self.assertTrue(status.has_meaningful_change(previous, current))

    def test_total_units_change_triggers_publish(self):
        previous = self.base_status()
        current = self.base_status()
        previous["zcode"]["total_units"] = None
        current["zcode"]["total_units"] = 500
        self.assertTrue(status.has_meaningful_change(previous, current))

    def test_plan_tier_change_triggers_publish(self):
        previous = self.base_status()
        current = self.base_status()
        previous["antigravity"]["plan_tier"] = "Pro"
        current["antigravity"]["plan_tier"] = "Ultra"
        self.assertTrue(status.has_meaningful_change(previous, current))

    def test_coupon_visible_none_to_false_triggers_publish(self):
        previous = self.base_status()
        current = self.base_status()
        previous["zcode"].update(coupon_visible=None, coupon_state="unknown")
        current["zcode"].update(coupon_visible=False, coupon_state="unknown")
        self.assertTrue(status.has_meaningful_change(previous, current))

    def test_coupon_visible_false_to_true_triggers_publish(self):
        previous = self.base_status()
        current = self.base_status()
        previous["zcode"].update(coupon_visible=False, coupon_state="unknown")
        current["zcode"].update(coupon_visible=True, coupon_state="unknown")
        self.assertTrue(status.has_meaningful_change(previous, current))

    def test_coupon_visible_true_to_false_triggers_publish(self):
        previous = self.base_status()
        current = self.base_status()
        previous["zcode"].update(coupon_visible=True, coupon_state="unknown")
        current["zcode"].update(coupon_visible=False, coupon_state="unknown")
        self.assertTrue(status.has_meaningful_change(previous, current))

    def test_coupon_visible_none_matches_none(self):
        previous = self.base_status()
        current = self.base_status()
        previous["zcode"].update(coupon_visible=None, coupon_state="unknown")
        current["zcode"].update(coupon_visible=None, coupon_state="unknown")
        self.assertFalse(status.has_meaningful_change(previous, current))

    def test_remaining_units_from_none_triggers_publish(self):
        previous = self.base_status()
        current = self.base_status()
        previous["zcode"].update(total_units=None, remaining_units=None)
        current["zcode"].update(total_units=None, remaining_units=5_000_000)
        self.assertTrue(status.has_meaningful_change(previous, current))

    def test_remaining_units_large_absolute_change_triggers_publish(self):
        previous = self.base_status()
        current = self.base_status()
        previous["zcode"].update(total_units=None, remaining_units=1_000_000)
        current["zcode"].update(total_units=None, remaining_units=2_000_000)
        self.assertTrue(status.has_meaningful_change(previous, current))

    def test_remaining_units_small_absolute_change_is_throttled(self):
        previous = self.base_status()
        current = self.base_status()
        previous["zcode"].update(total_units=None, remaining_units=1_000_000)
        current["zcode"].update(total_units=None, remaining_units=1_500_000)
        self.assertFalse(status.has_meaningful_change(previous, current))

    def test_tokens_change_when_pct_unknown_triggers_publish(self):
        previous = self.base_status()
        current = self.base_status()
        previous["context_summary"].update(pct=None, tokens=100)
        current["context_summary"].update(pct=None, tokens=200)
        self.assertTrue(status.has_meaningful_change(previous, current))

    def test_bool_remaining_units_is_not_numeric(self):
        previous = self.base_status()
        current = self.base_status()
        previous["zcode"]["remaining_units"] = True
        current["zcode"]["remaining_units"] = True
        self.assertFalse(status.has_meaningful_change(previous, current))

    def test_bool_remaining_units_does_not_equal_number(self):
        previous = self.base_status()
        current = self.base_status()
        previous["zcode"]["remaining_units"] = True
        current["zcode"]["remaining_units"] = 5_000_000
        self.assertFalse(status.has_meaningful_change(previous, current))


class ActiveContextTests(unittest.TestCase):
    def _state(self):
        tmp = tempfile.TemporaryDirectory()
        state = Path(tmp.name)
        (state / "context.json").write_text(
            json.dumps(
                {
                    "last": "a",
                    "conversations": {
                        "a": {
                            "model": "gemini-4-neon-high",
                            "tokens": 100,
                            "window": 524288,
                            "level": "green",
                            "updated_at": "2026-01-01T00:00:00+00:00",
                        },
                        "b": {
                            "model": "gemini-4-carbon-high",
                            "tokens": 200,
                            "window": 1048576,
                            "level": "green",
                            "updated_at": "2026-01-01T01:00:00+00:00",
                        },
                    },
                }
            ),
            encoding="utf-8",
        )
        return tmp, state

    def test_open_chat_is_selected(self):
        tmp, state = self._state()
        with tmp, mock.patch.object(
            context, "detect_active_chat", return_value={"id": "b", "title": "Demo"}
        ):
            summary = context.extract_context_summary(state)
        self.assertEqual(summary["conversation_id"], "b")
        self.assertTrue(summary["active"])
        self.assertEqual(summary["model"], "gemini-4-carbon-high")
        self.assertEqual(summary["tokens"], 200)

    def test_open_chat_without_response_waits(self):
        tmp, state = self._state()
        with tmp, mock.patch.object(
            context, "detect_active_chat", return_value={"id": "new", "title": "New"}
        ):
            summary = context.extract_context_summary(state)
        self.assertEqual(summary["conversation_id"], "new")
        self.assertTrue(summary["active"])
        self.assertIsNone(summary["tokens"])
        self.assertIsNone(summary["model"])

    def test_no_active_detection_falls_back_to_last(self):
        tmp, state = self._state()
        with tmp, mock.patch.object(context, "detect_active_chat", return_value=None):
            summary = context.extract_context_summary(state)
        self.assertEqual(summary["conversation_id"], "a")
        self.assertFalse(summary["active"])


class BucketTests(unittest.TestCase):
    def test_classify_bucket_kind(self):
        self.assertEqual(antigravity.classify_bucket_kind("gemini-5h"), "5h")
        self.assertEqual(antigravity.classify_bucket_kind("3p-weekly"), "weekly")
        self.assertEqual(antigravity.classify_bucket_kind("other-limit"), "other")


class BuildStatusTests(unittest.TestCase):
    NOW = datetime(2026, 1, 2, 3, 4, 5, tzinfo=timezone.utc)

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.state = Path(self._tmp.name)
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(mock.patch.object(antigravity, "refresh_quota", return_value=False))
        self.fetch = self.stack.enter_context(
            mock.patch.object(zcode, "fetch_cached_status", return_value=_zcode_status())
        )
        self.stack.enter_context(mock.patch.object(context, "detect_active_chat", return_value=None))

    def _build(self):
        return status.build_status(self.state, {}, self.NOW)

    def test_schema_version_and_timestamps(self):
        result = self._build()
        self.assertEqual(result["schema_version"], 2)
        self.assertEqual(result["updated_at"], "2026-01-02T03:04:05+00:00")
        self.assertEqual(result["pc_updated_at"], result["updated_at"])

    def test_coupon_visible_none_is_preserved(self):
        self.fetch.return_value = _zcode_status(coupon_visible=None, coupon_state="unknown")
        zcode_status = self._build()["zcode"]
        self.assertIsNone(zcode_status["coupon_visible"])
        self.assertEqual(zcode_status["coupon_state"], "unknown")

    def test_legacy_cache_without_coupon_state_reports_unknown(self):
        legacy = _zcode_status()
        legacy.pop("coupon_state")
        self.fetch.return_value = legacy
        zcode_status = self._build()["zcode"]
        self.assertEqual(zcode_status["coupon_state"], "unknown")

    def test_disabled_method_without_coupon_state_is_absent(self):
        legacy = _zcode_status(method="disabled", coupon_visible=False)
        legacy.pop("coupon_state")
        self.fetch.return_value = legacy
        self.assertEqual(self._build()["zcode"]["coupon_state"], "absent")

    def test_missing_cache_uses_disabled_zcode(self):
        self.fetch.return_value = None
        zcode_status = self._build()["zcode"]
        self.assertEqual(zcode_status["method"], "disabled")
        self.assertFalse(zcode_status["coupon_visible"])
        self.assertEqual(zcode_status["coupon_state"], "absent")

    def test_disabled_zcode_contract(self):
        checked_at = "2026-11-25T10:00:00+00:00"
        self.assertEqual(
            status._disabled_zcode(checked_at),
            {
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
            },
        )


def _context_conversation(**overrides):
    conversation = {
        "model": "gemini-4-neon-high",
        "tokens": 1000,
        "window": 524288,
        "level": "green",
        "updated_at": "2026-11-25T10:00:00+00:00",
    }
    conversation.update(overrides)
    return conversation


class RunOnceTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)
        self.state = self.tmp / paths.APP_DIR_NAME / "state"
        self.state.mkdir(parents=True)

        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(mock.patch.dict(os.environ, {"LOCALAPPDATA": str(self.tmp)}))
        self.refresh = self.stack.enter_context(
            mock.patch.object(antigravity, "refresh_quota", return_value=False)
        )
        self.fetch = self.stack.enter_context(
            mock.patch.object(zcode, "fetch_cached_status", return_value=_zcode_status())
        )
        self.query = self.stack.enter_context(
            mock.patch.object(zcode, "query_zcode_api", side_effect=AssertionError("network access"))
        )
        self.publish = self.stack.enter_context(
            mock.patch.object(status.ntfy, "publish", return_value=(True, "HTTP 200"))
        )
        self.detect = self.stack.enter_context(
            mock.patch.object(context, "detect_active_chat", return_value=None)
        )
        self.ensure_topic = self.stack.enter_context(
            mock.patch.object(paths, "ensure_ntfy_topic", return_value="agw-abc_123")
        )

    def _write_context(self, conversations=None, last="a"):
        data = {
            "last": last,
            "conversations": conversations or {"a": _context_conversation()},
        }
        paths.write_json(self.state / "context.json", data)

    def _write_two_conversations(self):
        self._write_context(
            {
                "a": _context_conversation(),
                "b": _context_conversation(model="gemini-4-carbon-high", tokens=2000),
            }
        )

    def _write_publish_state(self, last_published_ts, last_payload=None):
        data = {"last_published_ts": last_published_ts}
        if last_payload is not None:
            data["last_payload"] = last_payload
        paths.write_json(self.state / "publish_state.json", data)

    def _current_payload(self):
        return status.build_status(self.state, {}, datetime.now(timezone.utc))

    def test_default_output_hides_topic(self):
        result = status.run_once()
        self.assertTrue(result["status_written"])
        self.assertNotIn("topic", result)
        self.assertTrue(result["topic_configured"])
        self.assertTrue((self.state / "status.json").exists())
        self.publish.assert_called_once()
        self.assertTrue(result["publish"]["published"])

    def test_show_topic_includes_topic(self):
        result = status.run_once(show_topic=True)
        self.assertEqual(result["topic"], "agw-abc_123")
        self.assertNotIn("topic_configured", result)

    def test_dry_run_does_not_publish(self):
        result = status.run_once(dry_run=True)
        self.publish.assert_not_called()
        self.assertEqual(result["publish"]["reason"], "dry_run")
        self.assertTrue(result["publish"]["would_publish"])
        self.assertTrue((self.state / "status.json").exists())

    def test_active_switch_within_min_interval_is_throttled(self):
        self.assertEqual(status.MIN_ACTIVE_SWITCH_INTERVAL_SEC, 15)
        self._write_two_conversations()
        self.detect.return_value = {"id": "b", "title": "B"}
        self._write_publish_state(
            time.time() - 5,
            {"context_summary": {"conversation_id": "a", "active": False}},
        )

        result = status.run_once()

        self.publish.assert_not_called()
        self.assertFalse(result["publish"]["published"])
        self.assertEqual(result["publish"]["reason"], "throttled_or_unchanged")

    def test_active_switch_after_20s_publishes(self):
        self._write_two_conversations()
        self.detect.return_value = {"id": "b", "title": "B"}
        self._write_publish_state(
            time.time() - 20,
            {"context_summary": {"conversation_id": "a", "active": False}},
        )

        result = status.run_once()

        self.publish.assert_called_once()
        self.assertTrue(result["publish"]["published"])
        self.assertTrue(result["publish"]["changed"])

    def test_active_switch_after_min_interval_publishes(self):
        self._write_two_conversations()
        self.detect.return_value = {"id": "b", "title": "B"}
        self._write_publish_state(
            time.time() - 120,
            {"context_summary": {"conversation_id": "a", "active": False}},
        )

        result = status.run_once()

        self.publish.assert_called_once()
        self.assertTrue(result["publish"]["published"])
        self.assertTrue(result["publish"]["changed"])

    def test_heartbeat_publishes_without_changes(self):
        self._write_context()
        payload = self._current_payload()
        self._write_publish_state(time.time() - (status.HEARTBEAT_INTERVAL_SEC + 100), payload)

        result = status.run_once()

        self.publish.assert_called_once()
        self.assertFalse(result["publish"]["changed"])
        self.assertTrue(result["publish"]["published"])

    def test_clock_rollback_publishes_instead_of_stalling(self):
        self._write_context()
        payload = self._current_payload()
        self._write_publish_state(time.time() + 5000, payload)

        result = status.run_once()

        self.publish.assert_called_once()
        self.assertFalse(result["publish"]["changed"])
        self.assertTrue(result["publish"]["published"])

    def test_garbage_last_published_ts_is_treated_as_zero(self):
        self._write_publish_state("garbage")

        result = status.run_once()

        self.publish.assert_called_once()
        self.assertTrue(result["publish"]["published"])

    def test_bool_last_published_ts_is_treated_as_zero(self):
        self._write_publish_state(True)

        result = status.run_once()

        self.publish.assert_called_once()
        self.assertTrue(result["publish"]["published"])

    def test_config_read_error_keeps_status_unwritten(self):
        self.ensure_topic.side_effect = paths.ConfigReadError("config.json is not valid JSON")

        result = status.run_once()

        self.assertEqual(result["status_written"], False)
        self.assertIn("config.json is not valid JSON", result["error"])
        self.assertFalse((self.state / "status.json").exists())
        self.publish.assert_not_called()

    def test_secret_decrypt_error_keeps_status_unwritten(self):
        self.ensure_topic.side_effect = status.crypto.SecretDecryptError("cannot decrypt topic")

        result = status.run_once()

        self.assertEqual(result["status_written"], False)
        self.assertIn("cannot decrypt topic", result["error"])
        self.assertFalse((self.state / "status.json").exists())


class MainTests(unittest.TestCase):
    def test_dry_run_show_topic_prints_valid_json(self):
        payload = {
            "status_written": True,
            "topic": "agw-abc_123",
            "publish": {"published": False, "reason": "dry_run"},
        }
        stdout = io.StringIO()
        with mock.patch.object(status, "run_once", return_value=payload) as run_once:
            with mock.patch("sys.stdout", stdout):
                status.main(["--dry-run", "--show-topic"])

        self.assertEqual(json.loads(stdout.getvalue()), payload)
        run_once.assert_called_once_with(force_publish=False, dry_run=True, show_topic=True)

    def test_force_flag_is_forwarded(self):
        payload = {"status_written": True, "publish": {"published": True}}
        stdout = io.StringIO()
        with mock.patch.object(status, "run_once", return_value=payload) as run_once:
            with mock.patch("sys.stdout", stdout):
                status.main(["--force"])

        run_once.assert_called_once_with(force_publish=True, dry_run=False, show_topic=False)

    def test_doctor_prints_report_and_passes_offline_flag(self):
        checks = [{"name": "python_version", "status": "ok", "detail": "3.12"}]
        stdout = io.StringIO()
        with mock.patch("agwidgets.doctor.run_doctor", return_value=checks) as run_doctor:
            with mock.patch("agwidgets.doctor.format_report", return_value="REPORT") as format_report:
                with mock.patch.object(status, "run_once") as run_once:
                    with mock.patch("sys.stdout", stdout):
                        with self.assertRaises(SystemExit) as raised:
                            status.main(["--doctor"])

        self.assertEqual(raised.exception.code, 0)
        self.assertEqual(stdout.getvalue().strip(), "REPORT")
        run_doctor.assert_called_once_with(network=False)
        format_report.assert_called_once_with(checks)
        run_once.assert_not_called()

    def test_doctor_failure_exits_with_code_one(self):
        checks = [
            {"name": "python_version", "status": "ok", "detail": "3.12"},
            {"name": "agy_cli", "status": "fail", "detail": "not found"},
        ]
        with mock.patch("agwidgets.doctor.run_doctor", return_value=checks):
            with mock.patch("agwidgets.doctor.format_report", return_value="REPORT"):
                with mock.patch("sys.stdout", io.StringIO()):
                    with self.assertRaises(SystemExit) as raised:
                        status.main(["--doctor"])

        self.assertEqual(raised.exception.code, 1)

    def test_doctor_json_forwards_online_and_prints_payload(self):
        checks = [{"name": "python_version", "status": "ok", "detail": "3.12"}]
        payload = {"ok": True, "checks": checks}
        stdout = io.StringIO()
        with mock.patch("agwidgets.doctor.run_doctor", return_value=checks) as run_doctor:
            with mock.patch("agwidgets.doctor.build_report", return_value=payload) as build_report:
                with mock.patch.object(status, "run_once") as run_once:
                    with mock.patch("sys.stdout", stdout):
                        with self.assertRaises(SystemExit) as raised:
                            status.main(["--doctor-json", "--online"])

        self.assertEqual(raised.exception.code, 0)
        self.assertEqual(json.loads(stdout.getvalue()), payload)
        run_doctor.assert_called_once_with(network=True)
        build_report.assert_called_once_with(checks)
        run_once.assert_not_called()

    def test_online_requires_doctor(self):
        stderr = io.StringIO()
        with mock.patch("sys.stderr", stderr):
            with self.assertRaises(SystemExit) as raised:
                status.main(["--online"])

        self.assertEqual(raised.exception.code, 2)
        self.assertIn("--online requires --doctor", stderr.getvalue())

    def test_status_not_written_exits_with_code_one(self):
        payload = {"status_written": False, "error": "config is unreadable"}
        stdout = io.StringIO()
        with mock.patch.object(status, "run_once", return_value=payload):
            with mock.patch("sys.stdout", stdout):
                with self.assertRaises(SystemExit) as raised:
                    status.main([])

        self.assertEqual(raised.exception.code, 1)
        self.assertEqual(json.loads(stdout.getvalue()), payload)


if __name__ == "__main__":
    unittest.main()
