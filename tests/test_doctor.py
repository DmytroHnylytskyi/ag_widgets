"""Tests for the read-only ``--doctor`` diagnostics."""

from __future__ import annotations

import json
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from agwidgets import crypto, doctor, paths  # noqa: E402

SECRET_TOKEN = "super-secret-jwt-value"
SECRET_TOPIC = "agw-supersecrettopic"
VALID_E2E_KEY = "_v_pkoZlcxxtao-UZzCDCP7_6ZKGZXMcbWqPlGcwgwg"
CUSTOM_NTFY_SERVER = "https://relay.example.com/secret-path"
EXPECTED_CHECKS = [
    "python_version",
    "agy_cli",
    "annotations_dir",
    "antigravity_hook",
    "antigravity_sidecar",
    "runtime_config",
    "state_freshness",
    "secrets_storage",
]


class _Completed:
    """Minimal ``subprocess.CompletedProcess`` stand-in."""

    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = "") -> None:
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class DoctorTestCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)
        self.home = self.tmp / "home"
        self.app = self.tmp / "appdata" / paths.APP_DIR_NAME
        self.state = self.app / "state"

        self.find_patcher = mock.patch.object(
            doctor.antigravity, "find_executable", return_value=None
        )
        self.find = self.find_patcher.start()
        self.addCleanup(self.find_patcher.stop)

        self.run_proc_patcher = mock.patch.object(
            doctor.subprocess, "run", return_value=_Completed(stdout="2.0.1\n")
        )
        self.run_proc = self.run_proc_patcher.start()
        self.addCleanup(self.run_proc_patcher.stop)

        self.detect_patcher = mock.patch.object(
            doctor.context, "detect_active_chat", return_value=None
        )
        self.detect = self.detect_patcher.start()
        self.addCleanup(self.detect_patcher.stop)

        # Offline by default: any accidental network probe fails loudly.
        self.probe_ntfy_patcher = mock.patch.object(
            doctor, "_probe_ntfy_status", side_effect=AssertionError("network access")
        )
        self.probe_ntfy = self.probe_ntfy_patcher.start()
        self.addCleanup(self.probe_ntfy_patcher.stop)

        self.query_patcher = mock.patch.object(
            doctor.zcode, "query_zcode_api", side_effect=AssertionError("network access")
        )
        self.query = self.query_patcher.start()
        self.addCleanup(self.query_patcher.stop)

    # -- helpers ---------------------------------------------------------

    def run_checks(self, network: bool = False):
        return doctor.run_checks(network=network, home=self.home, app_dir=self.app)

    def by_name(self, checks):
        return {check["name"]: check for check in checks}

    def write_json(self, path: Path, payload) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    def write_secrets(self, payload) -> None:
        self.write_json(self.app / "secrets.json", payload)

    def create_hook(self, script: Path | None = None) -> Path:
        script = script or (self.home / "hook" / "context_hook.py")
        script.parent.mkdir(parents=True, exist_ok=True)
        script.write_text("# hook\n", encoding="utf-8")
        hooks_path = self.home / ".gemini" / "config" / "hooks.json"
        command = f'"{sys.executable}" "{script}"'
        self.write_json(
            hooks_path,
            {
                "ag-widgets-context": {
                    "PostInvocation": [{"type": "command", "command": command, "timeout": 5}]
                }
            },
        )
        return hooks_path

    def create_sidecar(self, enabled: bool = True) -> None:
        config_dir = self.home / ".gemini" / "config"
        self.write_json(
            config_dir / "sidecars" / "ag-widgets-collector" / "sidecar.json",
            {"builtin": "schedule"},
        )
        self.write_json(
            config_dir / "config.json",
            {"sidecars": {"ag-widgets-collector": {"enabled": enabled}}},
        )

    def create_state_files(self, age_sec: float = 0.0) -> None:
        self.state.mkdir(parents=True, exist_ok=True)
        stamp = time.time() - age_sec
        for name in doctor.STATE_FILE_NAMES:
            path = self.state / name
            path.write_text("{}", encoding="utf-8")
            os.utime(path, (stamp, stamp))


class EmptyInstallTests(DoctorTestCase):
    def test_all_checks_present_in_deterministic_order(self):
        checks = self.run_checks()
        self.assertEqual([check["name"] for check in checks], EXPECTED_CHECKS)
        for check in checks:
            self.assertIn(check["status"], ("ok", "warn", "fail"))
            self.assertIsInstance(check["detail"], str)

    def test_missing_install_reports_warnings_and_one_failure(self):
        checks = self.run_checks()
        by_name = self.by_name(checks)

        self.assertEqual(by_name["python_version"]["status"], "ok")
        self.assertEqual(by_name["agy_cli"]["status"], "fail")
        for name in EXPECTED_CHECKS[2:]:
            self.assertEqual(by_name[name]["status"], "warn", name)

        report = doctor.build_report(checks)
        self.assertFalse(report["ok"])
        self.assertEqual(len(report["checks"]), len(EXPECTED_CHECKS))

    def test_app_dir_is_not_created(self):
        self.run_checks()
        self.assertFalse(self.app.exists())
        self.assertFalse((self.tmp / "appdata").exists())


class AgyCliTests(DoctorTestCase):
    def test_missing_agy_fails(self):
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["agy_cli"]["status"], "fail")
        self.assertIn("not found", by_name["agy_cli"]["detail"])
        self.run_proc.assert_not_called()

    def test_found_agy_reports_version(self):
        self.find.return_value = "C:/tools/agy.exe"
        self.run_proc.return_value = _Completed(returncode=0, stdout="Antigravity 2.0.1\n")
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["agy_cli"]["status"], "ok")
        self.assertIn("C:/tools/agy.exe", by_name["agy_cli"]["detail"])
        self.assertIn("Antigravity 2.0.1", by_name["agy_cli"]["detail"])

    def test_nonzero_exit_fails(self):
        self.find.return_value = "C:/tools/agy.exe"
        self.run_proc.return_value = _Completed(returncode=2)
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["agy_cli"]["status"], "fail")
        self.assertIn("code 2", by_name["agy_cli"]["detail"])

    def test_launch_error_fails(self):
        self.find.return_value = "C:/tools/agy.exe"
        self.run_proc.side_effect = OSError("permission denied")
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["agy_cli"]["status"], "fail")
        self.assertIn("cannot run", by_name["agy_cli"]["detail"])

    def test_timeout_fails(self):
        self.find.return_value = "C:/tools/agy.exe"
        self.run_proc.side_effect = doctor.subprocess.TimeoutExpired(cmd="agy", timeout=5)
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["agy_cli"]["status"], "fail")
        self.assertIn("timed out", by_name["agy_cli"]["detail"])


class AnnotationsTests(DoctorTestCase):
    def test_missing_annotations_dir_warns(self):
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["annotations_dir"]["status"], "warn")
        self.detect.assert_not_called()

    def test_annotations_count_and_active_chat(self):
        annotations = self.home / ".gemini" / "antigravity" / "annotations"
        annotations.mkdir(parents=True)
        (annotations / "chat-a.pbtxt").write_text("title: \"A\"\n", encoding="utf-8")
        (annotations / "chat-b.pbtxt").write_text("title: \"B\"\n", encoding="utf-8")
        self.detect.return_value = {"id": "chat-a", "title": "A"}

        by_name = self.by_name(self.run_checks())

        self.assertEqual(by_name["annotations_dir"]["status"], "ok")
        self.assertIn("2 annotation file(s)", by_name["annotations_dir"]["detail"])
        self.assertIn("active chat detected", by_name["annotations_dir"]["detail"])
        self.detect.assert_called_once_with(annotations)

    def test_annotations_without_active_chat(self):
        annotations = self.home / ".gemini" / "antigravity" / "annotations"
        annotations.mkdir(parents=True)
        self.detect.return_value = None
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["annotations_dir"]["status"], "ok")
        self.assertIn("no active chat detected", by_name["annotations_dir"]["detail"])


class HookAndSidecarTests(DoctorTestCase):
    def test_registered_hook_with_script_is_ok(self):
        self.create_hook()
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["antigravity_hook"]["status"], "ok")

    def test_hook_with_missing_script_warns(self):
        hooks_path = self.home / ".gemini" / "config" / "hooks.json"
        missing = self.home / "hook" / "gone.py"
        self.write_json(
            hooks_path,
            {
                "ag-widgets-context": {
                    "PostInvocation": [
                        {"type": "command", "command": f'python "{missing}"'}
                    ]
                }
            },
        )
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["antigravity_hook"]["status"], "warn")
        self.assertIn("script not found", by_name["antigravity_hook"]["detail"])

    def test_missing_hook_key_warns(self):
        self.write_json(self.home / ".gemini" / "config" / "hooks.json", {"other": {}})
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["antigravity_hook"]["status"], "warn")
        self.assertIn("not registered", by_name["antigravity_hook"]["detail"])

    def test_corrupt_hooks_json_fails(self):
        hooks_path = self.home / ".gemini" / "config" / "hooks.json"
        hooks_path.parent.mkdir(parents=True)
        hooks_path.write_text("{broken", encoding="utf-8")
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["antigravity_hook"]["status"], "fail")
        self.assertIn("not valid JSON", by_name["antigravity_hook"]["detail"])

    def test_enabled_sidecar_is_ok(self):
        self.create_sidecar(enabled=True)
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["antigravity_sidecar"]["status"], "ok")
        self.assertIn("manifest=true", by_name["antigravity_sidecar"]["detail"])
        self.assertIn("enabled=true", by_name["antigravity_sidecar"]["detail"])

    def test_disabled_sidecar_warns(self):
        self.create_sidecar(enabled=False)
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["antigravity_sidecar"]["status"], "warn")
        self.assertIn("enabled=false", by_name["antigravity_sidecar"]["detail"])

    def test_missing_sidecar_manifest_warns(self):
        self.write_json(
            self.home / ".gemini" / "config" / "config.json",
            {"sidecars": {"ag-widgets-collector": {"enabled": True}}},
        )
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["antigravity_sidecar"]["status"], "warn")
        self.assertIn("manifest=false", by_name["antigravity_sidecar"]["detail"])


class RuntimeConfigTests(DoctorTestCase):
    def test_missing_config_warns(self):
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["runtime_config"]["status"], "warn")
        self.assertIn("not created yet", by_name["runtime_config"]["detail"])

    def test_readable_config_is_ok(self):
        self.write_json(self.app / "config.json", {"python_path": "python.exe"})
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["runtime_config"]["status"], "ok")
        self.assertIn("1 keys", by_name["runtime_config"]["detail"])

    def test_corrupt_config_fails(self):
        config_path = self.app / "config.json"
        config_path.parent.mkdir(parents=True)
        config_path.write_text("[1, 2, 3]", encoding="utf-8")
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["runtime_config"]["status"], "fail")
        self.assertIn("JSON object", by_name["runtime_config"]["detail"])


class StateFreshnessTests(DoctorTestCase):
    def test_fresh_state_is_ok(self):
        self.create_state_files(age_sec=10)
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["state_freshness"]["status"], "ok")
        for name in doctor.STATE_FILE_NAMES:
            self.assertIn(f"{name}=", by_name["state_freshness"]["detail"])
        self.assertNotIn("missing", by_name["state_freshness"]["detail"])

    def test_stale_state_warns(self):
        self.create_state_files(age_sec=2 * 24 * 3600 + 60)
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["state_freshness"]["status"], "warn")
        self.assertIn("context.json=2d", by_name["state_freshness"]["detail"])

    def test_missing_state_warns(self):
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["state_freshness"]["status"], "warn")
        self.assertIn("context.json=missing", by_name["state_freshness"]["detail"])

    def test_hook_error_log_is_reported(self):
        self.create_state_files(age_sec=10)
        log_path = self.state / "hook_errors.log"
        log_path.write_text("boom\n", encoding="utf-8")
        size = log_path.stat().st_size
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["state_freshness"]["status"], "warn")
        self.assertIn("hook_errors.log=present", by_name["state_freshness"]["detail"])
        self.assertIn(f"{size} bytes", by_name["state_freshness"]["detail"])

    def test_rotated_hook_error_log_is_reported(self):
        self.create_state_files(age_sec=10)
        (self.state / "hook_errors.log.1").write_text("old\n", encoding="utf-8")
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["state_freshness"]["status"], "warn")
        self.assertIn("hook_errors.log.1=present", by_name["state_freshness"]["detail"])


class SecretsStorageTests(DoctorTestCase):
    def test_missing_secrets_warns(self):
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["secrets_storage"]["status"], "warn")
        self.assertIn("not created yet", by_name["secrets_storage"]["detail"])

    def test_corrupt_secrets_fails(self):
        secrets_path = self.app / "secrets.json"
        secrets_path.parent.mkdir(parents=True)
        secrets_path.write_text("{not json", encoding="utf-8")
        by_name = self.by_name(self.run_checks())
        self.assertEqual(by_name["secrets_storage"]["status"], "fail")
        self.assertIn("not valid JSON", by_name["secrets_storage"]["detail"])

    def test_undecryptable_secret_fails(self):
        self.write_secrets(
            {
                "zcode_token_enc": "dpapi:v1:QUJDRA==",
                "ntfy_topic_enc": "dpapi:v1:RUZHSA==",
                "device_mid": "device-1",
            }
        )
        with mock.patch.object(
            doctor.crypto, "unprotect_secret", side_effect=crypto.SecretDecryptError("boom")
        ):
            checks = self.run_checks()

        by_name = self.by_name(checks)
        self.assertEqual(by_name["secrets_storage"]["status"], "fail")
        text = doctor.format_report(checks) + json.dumps(doctor.build_report(checks))
        self.assertNotIn("QUJDRA==", text)
        self.assertNotIn("RUZHSA==", text)

    def test_encrypted_secrets_are_ok_and_only_booleans_are_reported(self):
        self.write_secrets(
            {
                "zcode_token_enc": "dpapi:v1:QUJDRA==",
                "ntfy_topic_enc": "dpapi:v1:RUZHSA==",
                "device_mid": "device-1",
            }
        )
        with mock.patch.object(
            doctor.crypto, "unprotect_secret", return_value="decrypted"
        ):
            checks = self.run_checks()

        detail = self.by_name(checks)["secrets_storage"]["detail"]
        self.assertEqual(self.by_name(checks)["secrets_storage"]["status"], "ok")
        self.assertIn("token_configured=true", detail)
        self.assertIn("topic_configured=true", detail)
        self.assertIn("encrypted=true", detail)
        text = doctor.format_report(checks) + json.dumps(doctor.build_report(checks))
        self.assertNotIn("decrypted", text)
        self.assertNotIn("dpapi:v1:", text)
        self.assertNotIn("QUJDRA==", text)

    def test_legacy_plaintext_warns(self):
        self.write_secrets({"zcode_token": SECRET_TOKEN, "ntfy_topic": SECRET_TOPIC})
        checks = self.run_checks()
        check = self.by_name(checks)["secrets_storage"]

        self.assertEqual(check["status"], "warn")
        self.assertIn("plaintext, migration pending", check["detail"])

        text = doctor.format_report(checks)
        report = json.dumps(doctor.build_report(checks))
        self.assertNotIn(SECRET_TOKEN, text)
        self.assertNotIn(SECRET_TOKEN, report)
        self.assertNotIn(SECRET_TOPIC, text)
        self.assertNotIn(SECRET_TOPIC, report)

    def test_empty_secrets_document_warns(self):
        self.write_secrets({})
        check = self.by_name(self.run_checks())["secrets_storage"]
        self.assertEqual(check["status"], "warn")
        self.assertIn("no ZCode token or ntfy topic", check["detail"])

    def test_encrypted_e2e_key_is_reported_as_boolean_only(self):
        self.write_secrets(
            {"ntfy_e2e_key_enc": "dpapi:v1:QUJDRA==", "device_mid": "device-1"}
        )
        with mock.patch.object(
            doctor.crypto, "unprotect_secret", return_value=VALID_E2E_KEY
        ):
            checks = self.run_checks()

        detail = self.by_name(checks)["secrets_storage"]["detail"]
        self.assertIn("e2e_configured=true", detail)

        text = doctor.format_report(checks) + json.dumps(doctor.build_report(checks))
        self.assertNotIn(VALID_E2E_KEY, text)
        self.assertNotIn("agw://", text)
        self.assertNotIn("dpapi:v1:", text)

    def test_legacy_e2e_key_is_reported_as_boolean_only(self):
        self.write_secrets({"ntfy_e2e_key": VALID_E2E_KEY})
        checks = self.run_checks()

        check = self.by_name(checks)["secrets_storage"]
        self.assertIn("e2e_configured=true", check["detail"])
        self.assertEqual(check["status"], "warn")
        self.assertIn("plaintext, migration pending", check["detail"])

        text = doctor.format_report(checks) + json.dumps(doctor.build_report(checks))
        self.assertNotIn(VALID_E2E_KEY, text)
        self.assertNotIn("agw://", text)

    def test_absent_e2e_key_reports_false(self):
        self.write_secrets({"zcode_token": SECRET_TOKEN})
        check = self.by_name(self.run_checks())["secrets_storage"]
        self.assertIn("e2e_configured=false", check["detail"])


class NetworkCheckTests(DoctorTestCase):
    def test_offline_checks_do_not_probe(self):
        checks = self.run_checks(network=False)
        self.assertNotIn("ntfy_reachability", [check["name"] for check in checks])
        self.assertNotIn("zcode_api", [check["name"] for check in checks])

    def test_network_checks_are_appended_when_enabled(self):
        with mock.patch.object(doctor, "_probe_ntfy_status", return_value=200):
            checks = self.run_checks(network=True)

        names = [check["name"] for check in checks]
        self.assertEqual(names, EXPECTED_CHECKS + ["ntfy_reachability", "zcode_api"])
        by_name = self.by_name(checks)
        self.assertEqual(by_name["ntfy_reachability"]["status"], "ok")
        self.assertIn("HTTP 200", by_name["ntfy_reachability"]["detail"])
        # No token configured: the API probe is skipped, not failed.
        self.assertEqual(by_name["zcode_api"]["status"], "warn")

    def test_ntfy_failure_fails(self):
        with mock.patch.object(doctor, "_probe_ntfy_status", side_effect=OSError("offline")):
            checks = self.run_checks(network=True)
        self.assertEqual(self.by_name(checks)["ntfy_reachability"]["status"], "fail")

    def test_ntfy_server_error_fails(self):
        with mock.patch.object(doctor, "_probe_ntfy_status", return_value=503):
            checks = self.run_checks(network=True)
        self.assertEqual(self.by_name(checks)["ntfy_reachability"]["status"], "fail")

    def test_custom_server_host_and_path_are_never_printed(self):
        self.write_json(self.app / "config.json", {"ntfy_server": CUSTOM_NTFY_SERVER})
        with mock.patch.object(
            doctor, "_probe_ntfy_status", return_value=200
        ) as probe:
            checks = self.run_checks(network=True)

        probe.assert_called_once_with(CUSTOM_NTFY_SERVER)
        check = self.by_name(checks)["ntfy_reachability"]
        self.assertEqual(check["status"], "ok")
        self.assertIn("HTTP 200", check["detail"])

        text = doctor.format_report(checks) + json.dumps(doctor.build_report(checks))
        self.assertNotIn("relay.example.com", text)
        self.assertNotIn("secret-path", text)
        self.assertNotIn(CUSTOM_NTFY_SERVER, text)

    def test_disabled_ntfy_skips_the_probe(self):
        self.write_json(self.app / "config.json", {"ntfy_enabled": False})
        checks = self.run_checks(network=True)

        check = self.by_name(checks)["ntfy_reachability"]
        self.assertEqual(check["status"], "ok")
        self.assertIn("PC-only", check["detail"])
        self.probe_ntfy.assert_not_called()

    def test_invalid_server_falls_back_to_default(self):
        self.write_json(
            self.app / "config.json", {"ntfy_server": "http://cleartext.example"}
        )
        with mock.patch.object(
            doctor, "_probe_ntfy_status", return_value=200
        ) as probe:
            checks = self.run_checks(network=True)

        probe.assert_called_once_with(doctor.e2e.DEFAULT_NTFY_URL)
        self.assertEqual(doctor.e2e.DEFAULT_NTFY_URL, "https://ntfy.sh")
        self.assertEqual(self.by_name(checks)["ntfy_reachability"]["status"], "ok")
        text = doctor.format_report(checks) + json.dumps(doctor.build_report(checks))
        self.assertNotIn("cleartext.example", text)

    def test_zcode_auth_expired_fails(self):
        self.write_secrets({"zcode_token": SECRET_TOKEN, "device_mid": "device-1"})
        self.query.side_effect = None
        self.query.return_value = {"auth_ok": False, "error_kind": "auth_expired"}
        with mock.patch.object(doctor, "_probe_ntfy_status", return_value=200):
            checks = self.run_checks(network=True)
        check = self.by_name(checks)["zcode_api"]
        self.assertEqual(check["status"], "fail")
        self.assertNotIn(SECRET_TOKEN, check["detail"])

    def test_zcode_rate_limited_warns(self):
        self.write_secrets({"zcode_token": SECRET_TOKEN, "device_mid": "device-1"})
        self.query.side_effect = None
        self.query.return_value = {"auth_ok": None, "error_kind": "rate_limited"}
        with mock.patch.object(doctor, "_probe_ntfy_status", return_value=200):
            checks = self.run_checks(network=True)
        check = self.by_name(checks)["zcode_api"]
        self.assertEqual(check["status"], "warn")
        self.assertIn("rate_limited", check["detail"])

    def test_zcode_ok_reports_accepted_token(self):
        self.write_secrets({"zcode_token": SECRET_TOKEN, "device_mid": "device-1"})
        self.query.side_effect = None
        self.query.return_value = {"auth_ok": True, "error_kind": None}
        with mock.patch.object(doctor, "_probe_ntfy_status", return_value=200):
            checks = self.run_checks(network=True)
        check = self.by_name(checks)["zcode_api"]
        self.assertEqual(check["status"], "ok")
        self.query.assert_called_once_with(SECRET_TOKEN, "device-1")
        self.assertNotIn(SECRET_TOKEN, check["detail"])


class SecretLeakTests(DoctorTestCase):
    def test_secrets_never_appear_in_reports(self):
        self.write_secrets(
            {
                "zcode_token": SECRET_TOKEN,
                "ntfy_topic": SECRET_TOPIC,
                "device_mid": "device-1",
            }
        )
        checks = self.run_checks()

        text = doctor.format_report(checks)
        report = json.dumps(doctor.build_report(checks))

        for payload in (text, report):
            self.assertNotIn(SECRET_TOKEN, payload)
            self.assertNotIn(SECRET_TOPIC, payload)
            self.assertNotIn(SECRET_TOKEN[:12], payload)
            self.assertNotIn(SECRET_TOPIC[:12], payload)


class ReportTests(unittest.TestCase):
    def test_format_report_markers_and_summary(self):
        checks = [
            {"name": "alpha", "status": "ok", "detail": "fine"},
            {"name": "beta", "status": "warn", "detail": "meh"},
            {"name": "gamma", "status": "fail", "detail": "broken"},
        ]
        text = doctor.format_report(checks)
        self.assertIn("[OK] alpha: fine", text)
        self.assertIn("[WARN] beta: meh", text)
        self.assertIn("[FAIL] gamma: broken", text)
        self.assertIn("1 ok, 1 warnings, 1 failures", text)
        self.assertIn("Remediation hints:", text)

    def test_build_report_ok_matches_failures(self):
        ok_checks = [{"name": "a", "status": "ok", "detail": "fine"}]
        warn_checks = [{"name": "a", "status": "warn", "detail": "meh"}]
        fail_checks = [{"name": "a", "status": "fail", "detail": "broken"}]

        self.assertTrue(doctor.build_report(ok_checks)["ok"])
        self.assertTrue(doctor.build_report(warn_checks)["ok"])
        self.assertFalse(doctor.build_report(fail_checks)["ok"])
        self.assertEqual(doctor.build_report(fail_checks)["checks"], fail_checks)

    def test_report_is_anonymized(self):
        home = str(Path.home())
        forward_home = home.replace("\\", "/")
        checks = [
            {
                "name": "path_check",
                "status": "warn",
                "detail": f"{home}{os.sep}.gemini{os.sep}config and {forward_home}/.gemini/bin",
            }
        ]
        text = doctor.format_report(checks)
        report = json.dumps(doctor.build_report(checks))
        self.assertNotIn(home, text)
        self.assertNotIn(forward_home, text)
        self.assertNotIn(home, report)
        self.assertNotIn(forward_home, report)
        self.assertIn("~", text)

    def test_unknown_status_is_treated_as_failure(self):
        report = doctor.build_report([{"name": "x", "status": "weird", "detail": "?"}])
        self.assertFalse(report["ok"])
        self.assertEqual(report["checks"][0]["status"], "fail")


class HintCoverageTests(unittest.TestCase):
    """``_HINTS`` is the documented contract for every check name."""

    EXPECTED = {
        "python_version",
        "agy_cli",
        "annotations_dir",
        "antigravity_hook",
        "antigravity_sidecar",
        "runtime_config",
        "state_freshness",
        "secrets_storage",
        "ntfy_reachability",
        "zcode_api",
    }

    def test_hints_cover_exactly_ten_checks(self):
        self.assertEqual(set(doctor._HINTS), self.EXPECTED)
        self.assertEqual(len(doctor._HINTS), 10)

    def test_every_hint_is_a_non_empty_string(self):
        for name, hint in doctor._HINTS.items():
            with self.subTest(name=name):
                self.assertIsInstance(hint, str)
                self.assertTrue(hint.strip())


class ReadOnlyTests(DoctorTestCase):
    def snapshot(self) -> list[str]:
        return sorted(str(path.relative_to(self.tmp)) for path in self.tmp.rglob("*"))

    def test_run_checks_does_not_touch_the_filesystem(self):
        before = self.snapshot()
        doctor.run_checks(home=self.home, app_dir=self.app)
        after = self.snapshot()
        self.assertEqual(before, after)
        self.assertFalse(self.app.exists())

    def test_run_doctor_does_not_create_runtime_dir(self):
        runtime_root = self.tmp / "localappdata"
        before = self.snapshot()
        with mock.patch.object(
            doctor.paths, "local_app_data", return_value=runtime_root
        ):
            with mock.patch.object(Path, "home", return_value=self.home):
                checks = doctor.run_doctor()
        after = self.snapshot()

        self.assertEqual(before, after)
        self.assertFalse((runtime_root / paths.APP_DIR_NAME).exists())
        self.assertEqual([check["name"] for check in checks], EXPECTED_CHECKS)


class IsolationTests(DoctorTestCase):
    def test_one_failing_probe_does_not_abort_the_rest(self):
        with mock.patch.object(
            doctor, "_check_runtime_config", side_effect=RuntimeError("boom")
        ):
            checks = self.run_checks()

        self.assertEqual([check["name"] for check in checks], EXPECTED_CHECKS)
        by_name = self.by_name(checks)
        self.assertEqual(by_name["runtime_config"]["status"], "fail")
        self.assertIn("RuntimeError: boom", by_name["runtime_config"]["detail"])
        self.assertNotIn("Traceback", by_name["runtime_config"]["detail"])
        self.assertEqual(by_name["secrets_storage"]["status"], "warn")


if __name__ == "__main__":
    unittest.main()
