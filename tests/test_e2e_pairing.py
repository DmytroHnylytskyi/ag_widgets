"""Tests for the optional E2E encryption, the pairing URI, and the key store.

The shared wire format lives in ``tests/fixtures/e2e_pairing_cases.json`` (the
same vectors are consumed by the Android ``E2eCrypto.kt`` and the Electron
pairing bridge), so every assertion here pins the Python implementation to the
cross-platform contract instead of to its own output.
"""

from __future__ import annotations

import io
import json
import os
import re
import sys
import tempfile
import unittest
import urllib.error
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from agwidgets import e2e, ntfy, paths  # noqa: E402
from collector import pairing  # noqa: E402

FIXTURE_PATH = ROOT / "tests" / "fixtures" / "e2e_pairing_cases.json"

# A valid 43-character base64url key taken from the fixture (32 decoded bytes).
VALID_KEY = "_v_pkoZlcxxtao-UZzCDCP7_6ZKGZXMcbWqPlGcwgwg"
VALID_TOPIC = "agw-abc_123"
CUSTOM_SERVER = "https://relay.example.com/secret-path"


def load_fixture() -> dict:
    return json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))


class _FakeResponse:
    """Minimal context-manager response object returned by a mocked urlopen."""

    def __init__(self, status: int) -> None:
        self.status = status

    def __enter__(self) -> "_FakeResponse":
        return self

    def __exit__(self, exc_type, exc, traceback) -> bool:
        return False


class AesGcmVectorTests(unittest.TestCase):
    def setUp(self):
        self.fixture = load_fixture()

    def test_fixture_schema_and_constants(self):
        self.assertEqual(self.fixture["schema"], "agw_e2e_pairing_v1")
        self.assertEqual(self.fixture["aad"], e2e.E2E_AAD.decode("utf-8"))
        self.assertEqual(self.fixture["default_ntfy_server"], e2e.DEFAULT_NTFY_URL)
        self.assertEqual(self.fixture["e2e_key_length"], e2e.E2E_KEY_ENCODED_LENGTH)
        self.assertEqual(self.fixture["e2e_key_bytes"], e2e.E2E_KEY_BYTES)
        self.assertEqual(self.fixture["e2e_iv_bytes"], e2e.E2E_IV_BYTES)
        self.assertEqual(self.fixture["e2e_tag_bytes"], e2e.E2E_TAG_BYTES)

    def test_encrypt_matches_every_shared_vector(self):
        for vector in self.fixture["aes_gcm_vectors"]:
            with self.subTest(vector=vector["name"]):
                plaintext = bytes.fromhex(vector["plaintext_hex"])
                iv = e2e.b64url_decode(vector["iv_b64"])

                envelope = e2e.encrypt_envelope(
                    plaintext, vector["key_b64"], iv=iv
                )

                self.assertEqual(envelope["v"], e2e.E2E_VERSION)
                self.assertEqual(envelope["alg"], e2e.E2E_ALGORITHM)
                self.assertEqual(envelope["iv"], vector["iv_b64"])
                self.assertEqual(envelope["ct"], vector["ct_b64"])

    def test_decrypt_returns_fixture_plaintext(self):
        for vector in self.fixture["aes_gcm_vectors"]:
            with self.subTest(vector=vector["name"]):
                plaintext = bytes.fromhex(vector["plaintext_hex"])
                self.assertEqual(
                    e2e.decrypt_envelope(vector["envelope"], vector["key_b64"]),
                    plaintext,
                )

    def test_utf8_plaintext_is_byte_identical(self):
        for vector in self.fixture["aes_gcm_vectors"]:
            text = vector.get("plaintext_utf8")
            if text is None:
                continue
            with self.subTest(vector=vector["name"]):
                encoded = text.encode("utf-8")
                self.assertEqual(encoded, bytes.fromhex(vector["plaintext_hex"]))
                envelope = e2e.encrypt_envelope(
                    encoded,
                    vector["key_b64"],
                    iv=e2e.b64url_decode(vector["iv_b64"]),
                )
                self.assertEqual(envelope["ct"], vector["ct_b64"])
                self.assertEqual(
                    e2e.decrypt_envelope(vector["envelope"], vector["key_b64"]), encoded
                )

    def test_tamper_cases_raise_decrypt_error(self):
        for case in self.fixture["aes_gcm_tamper_cases"]:
            if not case["expect_error"]:
                continue
            with self.subTest(case=case["name"]):
                with self.assertRaises(e2e.E2eDecryptError):
                    e2e.decrypt_envelope(case["envelope"], case["key_b64"])


class ServerNormalisationTests(unittest.TestCase):
    def setUp(self):
        self.fixture = load_fixture()

    def test_valid_server_urls_are_normalised(self):
        for case in self.fixture["server_url_cases"]["valid"]:
            with self.subTest(input=case["input"]):
                self.assertEqual(
                    e2e.normalize_ntfy_server(case["input"]), case["expected"]
                )

    def test_invalid_server_urls_are_rejected(self):
        for value in self.fixture["server_url_cases"]["invalid"]:
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    e2e.normalize_ntfy_server(value)


class PairingUriTests(unittest.TestCase):
    def setUp(self):
        self.fixture = load_fixture()
        self.default_server = self.fixture["default_ntfy_server"]

    def test_valid_uris_parse_to_expected_fields(self):
        for case in self.fixture["pairing_uri_cases"]["valid"]:
            with self.subTest(uri=case["uri"]):
                parsed = e2e.parse_pairing_uri(case["uri"])
                self.assertEqual(parsed["v"], e2e.E2E_VERSION)
                self.assertEqual(parsed["topic"], case["topic"])
                self.assertEqual(parsed["server"], case["server"])
                self.assertEqual(parsed["e2e_key"], case["e2e_key"])

    def test_invalid_uris_raise_value_error(self):
        for case in self.fixture["pairing_uri_cases"]["invalid"]:
            with self.subTest(uri=case["uri"], reason=case["reason"]):
                with self.assertRaises(ValueError):
                    e2e.parse_pairing_uri(case["uri"])

    def test_explicitly_empty_key_has_a_specific_error(self):
        for uri in ("agw://pair?v=1&t=agw-abc&k=", "agw://pair?v=1&t=agw-abc&k=%20"):
            with self.subTest(uri=uri):
                with self.assertRaises(ValueError) as raised:
                    e2e.parse_pairing_uri(uri)
                self.assertIn("empty E2E key", str(raised.exception))

    def test_roundtrip_preserves_topic_server_and_key(self):
        for server in (self.default_server, "https://ntfy.example.com:8443/sub"):
            with self.subTest(server=server):
                uri = e2e.build_pairing_uri(VALID_TOPIC, server, VALID_KEY)
                parsed = e2e.parse_pairing_uri(uri)
                self.assertEqual(parsed["topic"], VALID_TOPIC)
                self.assertEqual(parsed["server"], server)
                self.assertEqual(parsed["e2e_key"], VALID_KEY)

    def test_default_server_is_omitted_from_the_uri(self):
        uri = e2e.build_pairing_uri(VALID_TOPIC, self.default_server, VALID_KEY)
        self.assertNotIn("s=", uri)
        self.assertNotIn("https://", uri)

    def test_custom_server_is_percent_encoded(self):
        server = "https://ntfy.example.com:8443/sub"
        uri = e2e.build_pairing_uri(VALID_TOPIC, server, None)
        self.assertIn("s=https%3A%2F%2Fntfy.example.com%3A8443%2Fsub", uri)
        self.assertNotIn(server, uri)

    def test_build_rejects_invalid_topic_server_and_key(self):
        with self.assertRaises(ValueError):
            e2e.build_pairing_uri("bad topic")
        with self.assertRaises(ValueError):
            e2e.build_pairing_uri(VALID_TOPIC, "http://ntfy.sh")
        with self.assertRaises(ValueError):
            e2e.build_pairing_uri(VALID_TOPIC, self.default_server, "short")


class KeyHandlingTests(unittest.TestCase):
    def test_generate_key_shape_and_uniqueness(self):
        first = e2e.generate_key()
        second = e2e.generate_key()
        self.assertEqual(len(first), 43)
        self.assertRegex(first, r"^[A-Za-z0-9_-]{43}$")
        self.assertEqual(len(e2e.decode_key(first)), 32)
        self.assertNotEqual(first, second)

    def test_decode_key_rejects_bad_lengths(self):
        for value in (
            "A" * 42,
            "A" * 44,
            e2e.b64url_encode(bytes(31)),  # 42 chars
            e2e.b64url_encode(bytes(33)),  # 44 chars
            "",
        ):
            with self.subTest(length=len(value)):
                with self.assertRaises(ValueError):
                    e2e.decode_key(value)

    def test_decode_key_rejects_non_base64url_characters(self):
        for char in ("*", "+", "/", "="):
            with self.subTest(char=char):
                with self.assertRaises(ValueError):
                    e2e.decode_key("A" * 42 + char)

    def test_decode_key_rejects_empty_and_whitespace_only(self):
        for value in ("", "   "):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    e2e.decode_key(value)

    def test_b64url_decode_is_strict(self):
        for value in ("AB", "AAA=", "AA AA", " AA", "", "   "):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    e2e.b64url_decode(value)

    def test_b64url_roundtrip(self):
        for data in (b"\x00", bytes(range(32))):
            with self.subTest(length=len(data)):
                self.assertEqual(e2e.b64url_decode(e2e.b64url_encode(data)), data)


class EnvelopeDetectionTests(unittest.TestCase):
    def setUp(self):
        self.envelope = load_fixture()["aes_gcm_vectors"][0]["envelope"]

    def test_real_envelope_is_detected(self):
        self.assertTrue(e2e.is_e2e_envelope(self.envelope))

    def test_plain_status_is_not_an_envelope(self):
        status_payload = {
            "schema_version": 2,
            "updated_at": "2026-11-26T10:00:00+00:00",
            "zcode": {"matched": ""},
        }
        self.assertFalse(e2e.is_e2e_envelope(status_payload))

    def test_non_dict_values_are_not_envelopes(self):
        for value in (None, [], "text", 42):
            with self.subTest(value=value):
                self.assertFalse(e2e.is_e2e_envelope(value))

    def test_wrong_version_or_algorithm_is_not_an_envelope(self):
        for key, wrong in (("v", 2), ("alg", "aes-128-gcm")):
            with self.subTest(field=key):
                candidate = dict(self.envelope)
                candidate[key] = wrong
                self.assertFalse(e2e.is_e2e_envelope(candidate))

    def test_missing_or_non_string_iv_ct_is_not_an_envelope(self):
        for key in ("iv", "ct"):
            with self.subTest(field=key):
                candidate = dict(self.envelope)
                candidate[key] = 123
                self.assertFalse(e2e.is_e2e_envelope(candidate))


class EncryptInputValidationTests(unittest.TestCase):
    def test_wrong_iv_length_raises_value_error(self):
        for length in (0, 8, 11, 13, 16):
            with self.subTest(length=length):
                with self.assertRaises(ValueError):
                    e2e.encrypt_envelope(b"data", VALID_KEY, iv=b"\x00" * length)

    def test_non_bytes_plaintext_raises_type_error(self):
        for value in ("text", None, 42, ["a"]):
            with self.subTest(value=value):
                with self.assertRaises(TypeError):
                    e2e.encrypt_envelope(value, VALID_KEY)  # type: ignore[arg-type]

    def test_decrypt_rejects_non_object_envelope(self):
        with self.assertRaises(e2e.E2eDecryptError):
            e2e.decrypt_envelope("not-a-dict", VALID_KEY)  # type: ignore[arg-type]


class PublishWithE2eTests(unittest.TestCase):
    """``ntfy.publish`` must emit a real envelope and never leak the key."""

    def test_e2e_body_is_a_decryptable_envelope(self):
        payload = {
            "schema_version": 2,
            "updated_at": "2026-11-26T10:00:00+00:00",
            "zcode": {"matched": "x"},
        }
        with mock.patch.object(
            ntfy.urllib.request, "urlopen", return_value=_FakeResponse(200)
        ) as urlopen:
            self.assertEqual(
                ntfy.publish(VALID_TOPIC, payload, e2e_key=VALID_KEY),
                (True, "HTTP 200"),
            )

        urlopen.assert_called_once()
        request = urlopen.call_args.args[0]
        body = json.loads(request.data.decode("utf-8"))
        self.assertTrue(e2e.is_e2e_envelope(body))
        self.assertEqual(e2e.decrypt_envelope(body, VALID_KEY), ntfy._encode(payload))
        self.assertEqual(request.full_url, f"https://ntfy.sh/{VALID_TOPIC}")
        self.assertIn("application/json", request.get_header("Content-type") or "")

        self.assertNotIn(VALID_KEY, request.data.decode("utf-8"))
        self.assertNotIn(VALID_KEY, request.get_header("Title") or "")

    def test_e2e_payload_over_limit_is_rejected(self):
        payload = {"context_summary": {"conversation_id": "c" * 5000}}
        with mock.patch.object(ntfy.urllib.request, "urlopen") as urlopen:
            ok, detail = ntfy.publish(VALID_TOPIC, payload, e2e_key=VALID_KEY)

        self.assertFalse(ok)
        match = re.fullmatch(r"payload too large \((\d+) bytes\)", detail)
        self.assertIsNotNone(match)
        self.assertGreater(int(match.group(1)), ntfy.MAX_E2E_PLAINTEXT_BYTES)
        urlopen.assert_not_called()

    def test_invalid_e2e_key_is_rejected(self):
        with mock.patch.object(ntfy.urllib.request, "urlopen") as urlopen:
            for key in ("short", "", "A" * 42, "A" * 42 + "*"):
                with self.subTest(key=key):
                    self.assertEqual(
                        ntfy.publish(VALID_TOPIC, {"a": 1}, e2e_key=key),
                        (False, "invalid e2e key"),
                    )
        urlopen.assert_not_called()

    def test_invalid_server_is_rejected(self):
        with mock.patch.object(ntfy.urllib.request, "urlopen") as urlopen:
            self.assertEqual(
                ntfy.publish(VALID_TOPIC, {"a": 1}, server_url="http://ntfy.sh"),
                (False, "invalid server"),
            )
        urlopen.assert_not_called()

    def test_invalid_topic_is_rejected(self):
        with mock.patch.object(ntfy.urllib.request, "urlopen") as urlopen:
            self.assertEqual(
                ntfy.publish("bad topic!", {"a": 1}, e2e_key=VALID_KEY),
                (False, "invalid topic"),
            )
        urlopen.assert_not_called()

    def test_http_500_fails(self):
        error = urllib.error.HTTPError(
            "https://ntfy.sh/agw", 500, "Internal Server Error", None, None
        )
        with mock.patch.object(ntfy.urllib.request, "urlopen", side_effect=error):
            ok, detail = ntfy.publish(VALID_TOPIC, {"a": 1}, e2e_key=VALID_KEY)
        self.assertFalse(ok)
        self.assertIn("HTTP 500", detail)

    def test_large_plaintext_is_shrunk_without_e2e(self):
        payload = {"zcode": {"matched": "m" * 4000, "plan_id": "p1"}}
        self.assertGreater(len(ntfy._encode(payload)), ntfy.MAX_BODY_BYTES)
        with mock.patch.object(
            ntfy.urllib.request, "urlopen", return_value=_FakeResponse(200)
        ) as urlopen:
            ok, _ = ntfy.publish(VALID_TOPIC, payload)

        self.assertTrue(ok)
        request = urlopen.call_args.args[0]
        self.assertLessEqual(len(request.data), ntfy.MAX_BODY_BYTES)
        self.assertEqual(len(json.loads(request.data.decode("utf-8"))["zcode"]["matched"]), 64)


class PairingCliTests(unittest.TestCase):
    """Drive ``collector/pairing.py`` end to end against a temp app dir."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)
        self._env = mock.patch.dict(os.environ, {"LOCALAPPDATA": str(self.tmp)})
        self._env.start()
        self.addCleanup(self._env.stop)

    def run_cli(self, *argv: str):
        stdout = io.StringIO()
        stderr = io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            code = pairing.main(list(argv))
        return code, stdout.getvalue(), stderr.getvalue()

    def read_config(self) -> dict:
        return json.loads(paths.config_path().read_text(encoding="utf-8"))

    def test_get_prints_exactly_one_snapshot_object(self):
        code, stdout, _ = self.run_cli("--get")
        self.assertEqual(code, 0)

        payload = json.loads(stdout)
        self.assertEqual(
            set(payload),
            {"ok", "topic", "server", "ntfy_enabled", "e2e_enabled", "uri"},
        )
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["server"], e2e.DEFAULT_NTFY_URL)
        self.assertTrue(payload["ntfy_enabled"])
        self.assertFalse(payload["e2e_enabled"])

        parsed = e2e.parse_pairing_uri(payload["uri"])
        self.assertEqual(parsed["topic"], payload["topic"])
        self.assertEqual(parsed["server"], payload["server"])
        self.assertIsNone(parsed["e2e_key"])

    def test_set_server_rejects_cleartext_with_exit_one(self):
        code, stdout, _ = self.run_cli("--set-server", "http://ntfy.sh")
        self.assertEqual(code, 1)
        payload = json.loads(stdout)
        self.assertFalse(payload["ok"])
        self.assertIn("error", payload)
        self.assertIn("https", payload["error"])

    def test_set_server_accepts_https_and_roundtrips(self):
        code, stdout, _ = self.run_cli("--set-server", CUSTOM_SERVER)
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(stdout)["server"], CUSTOM_SERVER)

        _, snapshot, _ = self.run_cli("--get")
        payload = json.loads(snapshot)
        self.assertEqual(payload["server"], CUSTOM_SERVER)
        self.assertEqual(e2e.parse_pairing_uri(payload["uri"])["server"], CUSTOM_SERVER)

    def test_set_e2e_enable_disable_rotate(self):
        code, stdout, _ = self.run_cli("--set-e2e", "enable")
        self.assertEqual(code, 0)
        enabled = json.loads(stdout)
        self.assertTrue(enabled["e2e_enabled"])
        first_key = paths.get_e2e_key()
        self.assertIsNotNone(first_key)

        _, snapshot, stderr = self.run_cli("--get")
        payload = json.loads(snapshot)
        self.assertTrue(payload["e2e_enabled"])
        parsed = e2e.parse_pairing_uri(payload["uri"])
        self.assertEqual(parsed["e2e_key"], first_key)
        self.assertNotIn(first_key, stderr)
        self.assertNotIn("k=" + first_key, stderr)

        code, stdout, _ = self.run_cli("--set-e2e", "rotate")
        self.assertEqual(code, 0)
        self.assertTrue(json.loads(stdout)["e2e_enabled"])
        rotated = paths.get_e2e_key()
        self.assertNotEqual(rotated, first_key)

        _, snapshot, _ = self.run_cli("--get")
        rotated_uri = e2e.parse_pairing_uri(json.loads(snapshot)["uri"])
        self.assertEqual(rotated_uri["e2e_key"], rotated)

        code, stdout, _ = self.run_cli("--set-e2e", "disable")
        self.assertEqual(code, 0)
        self.assertFalse(json.loads(stdout)["e2e_enabled"])
        self.assertIsNone(paths.get_e2e_key())

        _, snapshot, _ = self.run_cli("--get")
        payload = json.loads(snapshot)
        self.assertFalse(payload["e2e_enabled"])
        self.assertIsNone(e2e.parse_pairing_uri(payload["uri"])["e2e_key"])

    def test_set_e2e_rejects_unknown_action(self):
        code, stdout, _ = self.run_cli("--set-e2e", "explode")
        self.assertEqual(code, 1)
        self.assertFalse(json.loads(stdout)["ok"])

    def test_set_ntfy_enabled_false_is_persisted(self):
        code, stdout, _ = self.run_cli("--set-ntfy-enabled", "false")
        self.assertEqual(code, 0)
        self.assertFalse(json.loads(stdout)["ntfy_enabled"])
        self.assertIs(self.read_config()["ntfy_enabled"], False)

        _, snapshot, _ = self.run_cli("--get")
        self.assertFalse(json.loads(snapshot)["ntfy_enabled"])

    def test_set_ntfy_enabled_true_is_persisted(self):
        self.run_cli("--set-ntfy-enabled", "false")
        code, stdout, _ = self.run_cli("--set-ntfy-enabled", "true")
        self.assertEqual(code, 0)
        self.assertTrue(json.loads(stdout)["ntfy_enabled"])
        self.assertIs(self.read_config()["ntfy_enabled"], True)


class E2eKeyStorageTests(unittest.TestCase):
    """Pin the legacy-plaintext migration behaviour in ``agwidgets.paths``."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.tmp = Path(self._tmp.name)
        self._env = mock.patch.dict(os.environ, {"LOCALAPPDATA": str(self.tmp)})
        self._env.start()
        self.addCleanup(self._env.stop)

    def write_secrets(self, payload: dict) -> None:
        path = paths.secrets_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload), encoding="utf-8")

    def read_secrets(self) -> dict:
        return json.loads(paths.secrets_path().read_text(encoding="utf-8"))

    def test_get_migrates_legacy_key_when_encryption_available(self):
        self.write_secrets({"ntfy_e2e_key": VALID_KEY})
        with mock.patch.object(
            paths.crypto, "encryption_available", return_value=True
        ), mock.patch.object(
            paths.crypto, "protect_secret", return_value="dpapi:v1:ENCODED"
        ):
            key = paths.get_e2e_key()

        self.assertEqual(key, VALID_KEY)
        data = self.read_secrets()
        self.assertEqual(data.get("ntfy_e2e_key_enc"), "dpapi:v1:ENCODED")
        self.assertNotIn("ntfy_e2e_key", data)

    def test_get_legacy_key_without_encryption_does_not_rewrite(self):
        self.write_secrets({"ntfy_e2e_key": VALID_KEY})
        with mock.patch.object(
            paths.crypto, "encryption_available", return_value=False
        ), mock.patch.object(paths, "write_json") as write_json:
            key = paths.get_e2e_key()

        self.assertEqual(key, VALID_KEY)
        write_json.assert_not_called()
        self.assertEqual(self.read_secrets(), {"ntfy_e2e_key": VALID_KEY})

    def test_ensure_migrates_legacy_key_when_encryption_available(self):
        self.write_secrets({"ntfy_e2e_key": VALID_KEY})
        with mock.patch.object(
            paths.crypto, "encryption_available", return_value=True
        ), mock.patch.object(
            paths.crypto, "protect_secret", return_value="dpapi:v1:ENCODED"
        ):
            key = paths.ensure_e2e_key()

        self.assertEqual(key, VALID_KEY)
        data = self.read_secrets()
        self.assertEqual(data.get("ntfy_e2e_key_enc"), "dpapi:v1:ENCODED")
        self.assertNotIn("ntfy_e2e_key", data)

    def test_ensure_legacy_key_without_encryption_does_not_rewrite(self):
        self.write_secrets({"ntfy_e2e_key": VALID_KEY})
        with mock.patch.object(
            paths.crypto, "encryption_available", return_value=False
        ), mock.patch.object(paths, "write_json") as write_json:
            key = paths.ensure_e2e_key()

        self.assertEqual(key, VALID_KEY)
        write_json.assert_not_called()
        self.assertEqual(self.read_secrets(), {"ntfy_e2e_key": VALID_KEY})

    def test_set_key_none_removes_stored_key(self):
        self.write_secrets(
            {"ntfy_e2e_key": VALID_KEY, "ntfy_e2e_key_enc": "dpapi:v1:OLD"}
        )
        paths.set_e2e_key(None)

        data = self.read_secrets()
        self.assertNotIn("ntfy_e2e_key", data)
        self.assertNotIn("ntfy_e2e_key_enc", data)

    def test_set_key_rejects_invalid_key_without_touching_the_file(self):
        self.write_secrets({"ntfy_e2e_key": VALID_KEY})
        before = self.read_secrets()
        for value in ("short", "A" * 42, "A" * 42 + "*", ""):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    paths.set_e2e_key(value)
        self.assertEqual(self.read_secrets(), before)


if __name__ == "__main__":
    unittest.main()
