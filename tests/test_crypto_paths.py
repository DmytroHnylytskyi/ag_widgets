"""Tests for DPAPI secret storage and runtime path persistence helpers."""

from __future__ import annotations

import json
import os
import re
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from agwidgets import crypto, paths  # noqa: E402


class _IsolatedTestCase(unittest.TestCase):
    """Give every test its own ``%LOCALAPPDATA%`` and temp directory."""

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp_path = Path(self._tmp.name)
        self._env = mock.patch.dict(os.environ, {"LOCALAPPDATA": str(self.tmp_path)})
        self._env.start()
        self.addCleanup(self._env.stop)

    # -- helpers ---------------------------------------------------------
    def _write(self, path: Path, text: str) -> Path:
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def _read(self, path: Path) -> str:
        return Path(path).read_text(encoding="utf-8")

    def _read_secrets(self) -> dict:
        return json.loads(self._read(paths.secrets_path()))

    def _read_config(self) -> dict:
        return json.loads(self._read(paths.config_path()))

    def _tmp_files(self, directory: Path) -> list[str]:
        return sorted(path.name for path in Path(directory).glob("*.tmp"))

    def _stored_topic(self, secrets_data: dict) -> str | None:
        if "ntfy_topic_enc" in secrets_data:
            return crypto.unprotect_secret(secrets_data["ntfy_topic_enc"])
        return secrets_data.get("ntfy_topic")

    def assert_topic_storage(self, secrets_data: dict, expected: str) -> None:
        """Assert the topic is stored with the platform's canonical key."""
        if crypto.encryption_available():
            self.assertIn("ntfy_topic_enc", secrets_data)
            self.assertNotIn("ntfy_topic", secrets_data)
            self.assertEqual(
                crypto.unprotect_secret(secrets_data["ntfy_topic_enc"]), expected
            )
        else:
            self.assertNotIn("ntfy_topic_enc", secrets_data)
            self.assertEqual(secrets_data.get("ntfy_topic"), expected)
        self.assertEqual(self._stored_topic(secrets_data), expected)


class CryptoRoundTripTests(_IsolatedTestCase):
    @unittest.skipUnless(crypto.encryption_available(), "DPAPI requires Windows")
    def test_dpapi_round_trip_ascii(self):
        stored = crypto.protect_secret("secret")
        self.assertTrue(stored.startswith(crypto.DPAPI_PREFIX))
        self.assertNotEqual(stored, "secret")
        self.assertTrue(crypto.is_encrypted(stored))
        self.assertEqual(crypto.unprotect_secret(stored), "secret")

    @unittest.skipUnless(crypto.encryption_available(), "DPAPI requires Windows")
    def test_dpapi_round_trip_unicode(self):
        plain = "секрет-пароль-日本語-ß-äöü"
        stored = crypto.protect_secret(plain)
        self.assertTrue(crypto.is_encrypted(stored))
        self.assertEqual(crypto.unprotect_secret(stored), plain)

    @unittest.skipUnless(crypto.encryption_available(), "DPAPI requires Windows")
    def test_dpapi_round_trip_long_jwt(self):
        jwt = (
            "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9."
            + ("A1b2C3d4" * 80)
            + "."
            + ("sIgNaTuRe" * 20)
        )
        self.assertGreater(len(jwt), 500)
        stored = crypto.protect_secret(jwt)
        self.assertTrue(crypto.is_encrypted(stored))
        self.assertEqual(crypto.unprotect_secret(stored), jwt)

    @unittest.skipUnless(crypto.encryption_available(), "DPAPI requires Windows")
    def test_require_encryption_flag_is_honored(self):
        stored = crypto.protect_secret("must-encrypt", require_encryption=True)
        self.assertTrue(crypto.is_encrypted(stored))
        self.assertEqual(crypto.unprotect_secret(stored), "must-encrypt")

    def test_non_windows_fallback_and_refusal(self):
        with mock.patch.object(crypto, "encryption_available", return_value=False):
            self.assertEqual(crypto.protect_secret("plain"), "plain")
            with self.assertRaises(crypto.CryptoUnavailableError):
                crypto.protect_secret("plain", require_encryption=True)
            with self.assertRaises(crypto.SecretDecryptError):
                crypto.unprotect_secret("dpapi:v1:QUJD")


class CryptoPlaintextTests(_IsolatedTestCase):
    def test_plaintext_passthrough(self):
        self.assertEqual(crypto.unprotect_secret("plain-value"), "plain-value")
        self.assertEqual(crypto.unprotect_secret(""), "")
        self.assertEqual(crypto.unprotect_secret("кириллица-без-шифрования"), "кириллица-без-шифрования")

    def test_is_encrypted_detection(self):
        for value in (
            None,
            123,
            b"dpapi:v1:x",
            "",
            "dpapi:v1",
            "DPAPI:V1:x",
            ["dpapi:v1:x"],
        ):
            with self.subTest(value=value):
                self.assertFalse(crypto.is_encrypted(value))
        self.assertTrue(crypto.is_encrypted("dpapi:v1:"))
        self.assertTrue(crypto.is_encrypted("dpapi:v1:QUJD"))


class CryptoErrorTests(_IsolatedTestCase):
    def test_invalid_base64_raises(self):
        with self.assertRaises(crypto.SecretDecryptError) as caught:
            crypto.unprotect_secret("dpapi:v1:!!!")
        self.assertIsInstance(caught.exception, crypto.CryptoError)
        self.assertNotEqual(str(caught.exception), "")

    def test_empty_blob_raises(self):
        with self.assertRaises(crypto.SecretDecryptError) as caught:
            crypto.unprotect_secret("dpapi:v1:")
        self.assertNotEqual(str(caught.exception), "")

    def test_unknown_blob_raises(self):
        with self.assertRaises(crypto.SecretDecryptError) as caught:
            crypto.unprotect_secret("dpapi:v1:bm90LWEtcmVhbC1ibG9i")
        self.assertNotEqual(str(caught.exception), "")

    @unittest.skipUnless(crypto.encryption_available(), "DPAPI requires Windows")
    def test_wrong_entropy_raises(self):
        stored = crypto.protect_secret("secret", entropy=b"entropy-one")
        with self.assertRaises(crypto.SecretDecryptError):
            crypto.unprotect_secret(stored, entropy=b"entropy-two")
        self.assertEqual(
            crypto.unprotect_secret(stored, entropy=b"entropy-one"), "secret"
        )

    def test_exception_hierarchy(self):
        self.assertTrue(issubclass(crypto.CryptoError, RuntimeError))
        self.assertTrue(issubclass(crypto.CryptoUnavailableError, crypto.CryptoError))
        self.assertTrue(issubclass(crypto.SecretDecryptError, crypto.CryptoError))
        self.assertEqual(crypto.DPAPI_PREFIX, "dpapi:v1:")
        self.assertEqual(crypto.DEFAULT_ENTROPY, b"ag-widgets:secret:v1")


class PathHelperTests(_IsolatedTestCase):
    def test_runtime_paths_use_local_app_data(self):
        self.assertEqual(paths.local_app_data(), self.tmp_path)
        app_dir = paths.app_dir()
        self.assertEqual(app_dir, self.tmp_path / "ag-widgets")
        self.assertTrue(app_dir.is_dir())
        self.assertEqual(paths.state_dir(), app_dir / "state")
        self.assertTrue((app_dir / "state").is_dir())
        self.assertEqual(paths.config_path(), app_dir / "config.json")
        self.assertEqual(paths.secrets_path(), app_dir / "secrets.json")


class WriteJsonTests(_IsolatedTestCase):
    def test_creates_file_without_tmp(self):
        target = self.tmp_path / "nested" / "data.json"
        paths.write_json(target, {"a": 1})
        self.assertTrue(target.is_file())
        self.assertEqual(json.loads(self._read(target)), {"a": 1})
        self.assertEqual(self._tmp_files(target.parent), [])

    def test_writes_pretty_utf8_json(self):
        target = self.tmp_path / "pretty.json"
        data = {"имя": "значение", "nested": {"число": 1}, "items": [1, 2]}
        paths.write_json(target, data)
        raw = self._read(target)
        self.assertIn("имя", raw)
        self.assertNotIn("\\u", raw)
        self.assertIn('\n  "', raw)
        self.assertEqual(json.loads(raw), data)

    def test_retries_replace_using_side_effect_list(self):
        target = self.tmp_path / "retry-list.json"
        side_effect = [PermissionError("busy"), PermissionError("busy"), None]
        with mock.patch(
            "agwidgets.paths.os.replace", side_effect=side_effect
        ) as replace_mock:
            paths.write_json(target, {"retried": True})
        self.assertEqual(replace_mock.call_count, 3)
        self.assertEqual(replace_mock.call_args.args[1], target)
        self.assertEqual(self._tmp_files(target.parent), [])

    def test_real_replace_after_transient_permission_errors(self):
        target = self.tmp_path / "retry-real.json"
        real_replace = os.replace
        attempts: list[int] = []

        def flaky_replace(source, destination):
            attempts.append(1)
            if len(attempts) < paths.REPLACE_ATTEMPTS:
                raise PermissionError("sharing violation")
            return real_replace(source, destination)

        with mock.patch(
            "agwidgets.paths.os.replace", side_effect=flaky_replace
        ):
            paths.write_json(target, {"after": "retry"})
        self.assertEqual(len(attempts), paths.REPLACE_ATTEMPTS)
        self.assertEqual(json.loads(self._read(target)), {"after": "retry"})
        self.assertEqual(self._tmp_files(target.parent), [])

    def test_always_failing_replace_raises_and_cleans_tmp(self):
        target = self.tmp_path / "retry-fail.json"
        old_text = '{"old": true}'
        self._write(target, old_text)
        with mock.patch(
            "agwidgets.paths.os.replace", side_effect=PermissionError("denied")
        ) as replace_mock:
            with self.assertRaises(PermissionError):
                paths.write_json(target, {"new": 1})
        self.assertEqual(replace_mock.call_count, paths.REPLACE_ATTEMPTS)
        self.assertEqual(self._read(target), old_text)
        self.assertEqual(self._tmp_files(target.parent), [])

    def test_concurrent_writes_leave_no_tmp(self):
        target = self.tmp_path / "concurrent.json"
        errors: list[BaseException] = []

        def worker(tag: str) -> None:
            try:
                for index in range(8):
                    paths.write_json(target, {"tag": tag, "index": index})
            except BaseException as error:  # noqa: BLE001 - reported via assert
                errors.append(error)

        threads = [
            threading.Thread(target=worker, args=(tag,), daemon=True)
            for tag in ("alpha", "beta")
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=30)

        self.assertEqual(errors, [])
        for thread in threads:
            self.assertFalse(thread.is_alive())
        payload = json.loads(self._read(target))
        self.assertIn(payload["tag"], {"alpha", "beta"})
        self.assertEqual(self._tmp_files(target.parent), [])


class ReadJsonStrictTests(_IsolatedTestCase):
    def test_missing_file_returns_none(self):
        self.assertIsNone(paths.read_json_strict(self.tmp_path / "absent.json"))

    def test_valid_object_returns_dict(self):
        target = self._write(
            self.tmp_path / "valid.json", '{"a": 1, "имя": "тест"}'
        )
        self.assertEqual(paths.read_json_strict(target), {"a": 1, "имя": "тест"})

    def test_invalid_json_raises(self):
        target = self._write(self.tmp_path / "broken.json", '{"a": ')
        with self.assertRaises(paths.ConfigReadError):
            paths.read_json_strict(target)

    def test_empty_file_raises(self):
        for index, text in enumerate(("", "   \n\t ")):
            with self.subTest(index=index):
                target = self._write(self.tmp_path / f"empty-{index}.json", text)
                with self.assertRaises(paths.ConfigReadError):
                    paths.read_json_strict(target)

    def test_non_object_json_raises(self):
        for index, text in enumerate(("[1, 2]", "null", '"text"', "42")):
            with self.subTest(text=text):
                target = self._write(self.tmp_path / f"array-{index}.json", text)
                with self.assertRaises(paths.ConfigReadError):
                    paths.read_json_strict(target)

    def test_permission_error_raises(self):
        target = self._write(self.tmp_path / "denied.json", "{}")
        with mock.patch.object(Path, "read_text", side_effect=PermissionError("denied")):
            with self.assertRaises(paths.ConfigReadError):
                paths.read_json_strict(target)


class UpdateJsonTests(_IsolatedTestCase):
    def test_applies_updater_and_writes_result(self):
        target = self._write(self.tmp_path / "state.json", '{"a": 1}')
        result = paths.update_json(target, lambda data: {**data, "b": data["a"] + 1})
        self.assertEqual(result, {"a": 1, "b": 2})
        self.assertEqual(json.loads(self._read(target)), result)

    def test_missing_file_updater_receives_empty_dict(self):
        target = self.tmp_path / "created.json"
        captured: list[object] = []

        def updater(data):
            captured.append(data)
            return {"created": True}

        result = paths.update_json(target, updater)
        self.assertEqual(captured, [{}])
        self.assertEqual(result, {"created": True})
        self.assertEqual(json.loads(self._read(target)), {"created": True})

    def test_non_dict_updater_result_raises(self):
        target = self._write(self.tmp_path / "state.json", '{"a": 1}')
        before = self._read(target)
        with self.assertRaises(TypeError):
            paths.update_json(target, lambda data: [1, 2])
        self.assertEqual(self._read(target), before)

    def test_corrupt_file_raises_and_is_not_overwritten(self):
        corrupt = '{"a": '
        target = self._write(self.tmp_path / "corrupt.json", corrupt)
        called: list[object] = []

        def updater(data):
            called.append(data)
            return data

        with self.assertRaises(paths.ConfigReadError):
            paths.update_json(target, updater)
        self.assertEqual(called, [])
        self.assertEqual(self._read(target), corrupt)


class FileLockTests(_IsolatedTestCase):
    def test_lock_path_for_json_and_other_names(self):
        self.assertEqual(
            paths.lock_path_for(self.tmp_path / "secrets.json"),
            self.tmp_path / "secrets.lock",
        )
        self.assertEqual(
            paths.lock_path_for(self.tmp_path / "config.JSON"),
            self.tmp_path / "config.lock",
        )
        self.assertEqual(
            paths.lock_path_for(self.tmp_path / "state.bin"),
            self.tmp_path / "state.bin.lock",
        )

    def test_file_lock_is_reentrant(self):
        lock = self.tmp_path / "nested" / "dir" / "test.lock"
        with paths.file_lock(lock, timeout=1.0):
            self.assertTrue(lock.exists())
            with paths.file_lock(lock, timeout=1.0):
                self.assertTrue(lock.exists())
        self.assertTrue(lock.exists())

    def test_two_threads_update_without_lost_updates(self):
        target = self.tmp_path / "counter.json"
        errors: list[BaseException] = []
        barrier = threading.Barrier(2)

        def bump() -> None:
            try:
                barrier.wait(timeout=10)
                for _ in range(25):
                    paths.update_json(
                        target,
                        lambda data: {**data, "n": int(data.get("n", 0)) + 1},
                    )
            except BaseException as error:  # noqa: BLE001 - reported via assert
                errors.append(error)

        threads = [threading.Thread(target=bump, daemon=True) for _ in range(2)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=60)

        self.assertEqual(errors, [])
        for thread in threads:
            self.assertFalse(thread.is_alive())
        self.assertEqual(json.loads(self._read(target))["n"], 50)


class EnsureNtfyTopicTests(_IsolatedTestCase):
    def test_generates_topic_and_stores_it_encrypted(self):
        topic = paths.ensure_ntfy_topic()
        self.assertRegex(topic, r"^agw-[0-9a-f]{24}$")
        self.assertIsNotNone(re.fullmatch(paths.NTFY_TOPIC_RE, topic))
        self.assert_topic_storage(self._read_secrets(), topic)
        self.assertFalse(paths.config_path().exists())

    def test_is_idempotent(self):
        first = paths.ensure_ntfy_topic()
        secrets_before = self._read(paths.secrets_path())
        second = paths.ensure_ntfy_topic()
        self.assertEqual(first, second)
        self.assertEqual(self._read(paths.secrets_path()), secrets_before)

    def test_migrates_topic_from_config(self):
        self._write(
            paths.config_path(),
            json.dumps(
                {
                    "ntfy_topic": "legacy-topic-1",
                    "theme": "dark",
                    "nested": {"keep": [1, 2]},
                }
            ),
        )
        topic = paths.ensure_ntfy_topic()
        self.assertEqual(topic, "legacy-topic-1")
        self.assert_topic_storage(self._read_secrets(), "legacy-topic-1")

        config = self._read_config()
        self.assertNotIn("ntfy_topic", config)
        self.assertEqual(config["theme"], "dark")
        self.assertEqual(config["nested"], {"keep": [1, 2]})

    def test_migrates_legacy_topic_from_secrets(self):
        self._write(
            paths.secrets_path(),
            json.dumps({"ntfy_topic": "legacy-secret-1", "device_mid": "mid-1"}),
        )
        topic = paths.ensure_ntfy_topic()
        self.assertEqual(topic, "legacy-secret-1")
        secrets_data = self._read_secrets()
        self.assert_topic_storage(secrets_data, "legacy-secret-1")
        self.assertEqual(secrets_data["device_mid"], "mid-1")
        self.assertFalse(paths.config_path().exists())

    def test_corrupt_config_raises_and_preserves_file(self):
        corrupt = '{"ntfy_topic": "legacy", '
        config = self._write(paths.config_path(), corrupt)
        with self.assertRaises(paths.ConfigReadError):
            paths.ensure_ntfy_topic()
        self.assertEqual(self._read(config), corrupt)
        self.assertFalse(paths.secrets_path().exists())

    def test_corrupt_secrets_raises_and_preserves_files(self):
        corrupt_secrets = '{"ntfy_topic": "legacy", '
        secrets = self._write(paths.secrets_path(), corrupt_secrets)
        config_text = json.dumps({"ntfy_topic": "cfg-topic"})
        config = self._write(paths.config_path(), config_text)
        with self.assertRaises(paths.ConfigReadError):
            paths.ensure_ntfy_topic()
        self.assertEqual(self._read(secrets), corrupt_secrets)
        self.assertEqual(self._read(config), config_text)
        self.assertIn("ntfy_topic", self._read_config())

    def test_existing_topic_does_not_read_corrupt_config(self):
        topic = paths.ensure_ntfy_topic()
        if crypto.encryption_available():
            self.assertIn("ntfy_topic_enc", self._read_secrets())
        config = self._write(paths.config_path(), "{not valid json")
        before = self._read(config)
        self.assertEqual(paths.ensure_ntfy_topic(), topic)
        self.assertEqual(self._read(config), before)

    def test_migration_preserves_other_secrets(self):
        self._write(
            paths.secrets_path(),
            json.dumps(
                {"device_mid": "mid-42", "zcode_token_enc": "dpapi:v1:AAAA"}
            ),
        )
        self._write(
            paths.config_path(),
            json.dumps({"ntfy_topic": "cfg-topic-2", "keep": True}),
        )
        topic = paths.ensure_ntfy_topic()
        self.assertEqual(topic, "cfg-topic-2")
        secrets_data = self._read_secrets()
        self.assert_topic_storage(secrets_data, "cfg-topic-2")
        self.assertEqual(secrets_data["device_mid"], "mid-42")
        self.assertEqual(secrets_data["zcode_token_enc"], "dpapi:v1:AAAA")

        config = self._read_config()
        self.assertNotIn("ntfy_topic", config)
        self.assertTrue(config["keep"])


# UTF-8 byte-order mark, spelled out so the test file itself stays ASCII.
BOM_BYTES = bytes([0xEF, 0xBB, 0xBF])


class BomJsonTests(unittest.TestCase):
    """A BOM must not make Python disagree with Electron about the same file."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.path = Path(self._tmp.name) / "config.json"

    def test_read_json_strict_accepts_a_byte_order_mark(self):
        self.path.write_bytes(BOM_BYTES + b'{"ntfy_server": "https://relay.example.com"}')

        self.assertEqual(
            paths.read_json_strict(self.path),
            {"ntfy_server": "https://relay.example.com"},
        )

    def test_read_json_accepts_a_byte_order_mark(self):
        self.path.write_bytes(BOM_BYTES + b'{"a": 1}')

        self.assertEqual(paths.read_json(self.path), {"a": 1})

    def test_a_bom_only_file_is_reported_as_empty(self):
        self.path.write_bytes(BOM_BYTES)

        with self.assertRaises(paths.ConfigReadError):
            paths.read_json_strict(self.path)


if __name__ == "__main__":
    unittest.main()

