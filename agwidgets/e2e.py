"""Optional end-to-end encryption for the ntfy channel (AES-256-GCM).

The phone and the PC share a 256-bit key that never travels through ntfy: it is
transferred once through the ``agw://pair`` QR code and stored encrypted on both
sides (DPAPI on Windows, Android Keystore on the phone). Every published status
becomes an authenticated envelope::

    {"v":1,"alg":"aes-256-gcm","iv":"<b64url-12B>","ct":"<b64url-ct||tag>"}

Only the Python standard library is used. AES-256 (FIPS 197), GHASH, GCTR and
the GCM authentication tag follow NIST SP 800-38D; the tag is compared with
:func:`hmac.compare_digest` so a tampered message cannot be distinguished from a
wrong key by timing.

The same wire format and the same key/URI rules are implemented in
``desktop/src/renderer/qr.js`` (rendering only), ``desktop/src/main/pairing.js``
(bridge) and ``android/.../data/E2eCrypto.kt`` (``javax.crypto``). The shared
vectors live in ``tests/fixtures/e2e_pairing_cases.json``.
"""

from __future__ import annotations

import base64
import binascii
import hmac
import os
import re
import secrets
from typing import Any
from urllib.parse import parse_qsl, quote, urlsplit

DEFAULT_NTFY_URL = "https://ntfy.sh"
NTFY_SERVER_MAX_LENGTH = 256
NTFY_URL_RE = re.compile(r"^https://[^\s/?#@]+(?::[0-9]{1,5})?(?:/[^\s?#]*)?$", re.IGNORECASE)

E2E_ALGORITHM = "aes-256-gcm"
E2E_VERSION = 1
E2E_AAD = b"agw-ntfy-e2e:v1"
E2E_KEY_BYTES = 32
E2E_IV_BYTES = 12
E2E_TAG_BYTES = 16
E2E_KEY_ENCODED_LENGTH = 43

TOPIC_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
_KEY_RE = re.compile(r"^[A-Za-z0-9_-]{43}$")
_PAIRING_SCHEME = "agw"
_PAIRING_HOST = "pair"
_PAIRING_KNOWN_PARAMS = ("v", "t", "s", "k")


class E2eError(ValueError):
    """Base class for envelope and pairing failures."""


class E2eDecryptError(E2eError):
    """Raised when an envelope cannot be authenticated or decrypted."""


# ---------------------------------------------------------------------------
# AES-256 (FIPS 197)
# ---------------------------------------------------------------------------


def _rotl8(value: int, shift: int) -> int:
    return ((value << shift) | (value >> (8 - shift))) & 0xFF


def _generate_sbox() -> tuple[int, ...]:
    """Derive the Rijndael S-box from the affine transform (no 256-entry table)."""
    sbox = [0] * 256
    p = 1
    q = 1
    while True:
        # p <- p * 3 in GF(2^8)
        p = (p ^ (p << 1) ^ (0x1B if p & 0x80 else 0)) & 0xFF
        # q <- q / 3 in GF(2^8)
        q ^= (q << 1) & 0xFF
        q ^= (q << 2) & 0xFF
        q ^= (q << 4) & 0xFF
        q &= 0xFF
        if q & 0x80:
            q ^= 0x09
        sbox[p] = q ^ _rotl8(q, 1) ^ _rotl8(q, 2) ^ _rotl8(q, 3) ^ _rotl8(q, 4) ^ 0x63
        if p == 1:
            break
    sbox[0] = 0x63
    return tuple(sbox)


SBOX = _generate_sbox()

_ROUNDS = 14  # AES-256
_RCON = (0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36)


def _xtime(value: int) -> int:
    value <<= 1
    return (value ^ 0x1B) & 0xFF if value & 0x100 else value


def _expand_key(key: bytes) -> list[list[int]]:
    """Return 4*(Nr+1) round-key words for a 32-byte AES-256 key."""
    if len(key) != E2E_KEY_BYTES:
        raise ValueError("AES-256 requires a 32-byte key")
    words = [list(key[4 * index : 4 * index + 4]) for index in range(8)]
    for index in range(8, 4 * (_ROUNDS + 1)):
        temp = list(words[index - 1])
        if index % 8 == 0:
            temp = temp[1:] + temp[:1]  # RotWord
            temp = [SBOX[byte] for byte in temp]  # SubWord
            temp[0] ^= _RCON[index // 8 - 1]
        elif index % 8 == 4:
            temp = [SBOX[byte] for byte in temp]
        words.append([words[index - 8][offset] ^ temp[offset] for offset in range(4)])
    return words


def _add_round_key(state: list[int], words: list[list[int]], round_index: int) -> None:
    for column in range(4):
        word = words[4 * round_index + column]
        for row in range(4):
            state[4 * column + row] ^= word[row]


def _sub_bytes(state: list[int]) -> None:
    for index in range(16):
        state[index] = SBOX[state[index]]


def _shift_rows(state: list[int]) -> None:
    source = list(state)
    for row in range(4):
        for column in range(4):
            state[4 * column + row] = source[4 * ((column + row) % 4) + row]


def _mix_columns(state: list[int]) -> None:
    for column in range(4):
        offset = 4 * column
        a0, a1, a2, a3 = state[offset : offset + 4]
        state[offset] = _xtime(a0) ^ (_xtime(a1) ^ a1) ^ a2 ^ a3
        state[offset + 1] = a0 ^ _xtime(a1) ^ (_xtime(a2) ^ a2) ^ a3
        state[offset + 2] = a0 ^ a1 ^ _xtime(a2) ^ (_xtime(a3) ^ a3)
        state[offset + 3] = (_xtime(a0) ^ a0) ^ a1 ^ a2 ^ _xtime(a3)


def _encrypt_block(block: bytes, words: list[list[int]]) -> bytes:
    state = list(block)
    _add_round_key(state, words, 0)
    for round_index in range(1, _ROUNDS):
        _sub_bytes(state)
        _shift_rows(state)
        _mix_columns(state)
        _add_round_key(state, words, round_index)
    _sub_bytes(state)
    _shift_rows(state)
    _add_round_key(state, words, _ROUNDS)
    return bytes(state)


# ---------------------------------------------------------------------------
# GHASH / GCTR (NIST SP 800-38D)
# ---------------------------------------------------------------------------

_GF_R = 0xE1000000000000000000000000000000


def _gf_multiply(x: int, y: int) -> int:
    """Multiply two GF(2^128) elements with the GCM polynomial."""
    result = 0
    value = y
    for bit in range(128):
        if (x >> (127 - bit)) & 1:
            result ^= value
        if value & 1:
            value = (value >> 1) ^ _GF_R
        else:
            value >>= 1
    return result


def _ghash(hash_key: int, aad: bytes, ciphertext: bytes) -> bytes:
    y = 0
    for data in (aad, ciphertext):
        for offset in range(0, len(data), 16):
            chunk = data[offset : offset + 16]
            if len(chunk) < 16:
                chunk = chunk + b"\x00" * (16 - len(chunk))
            y = _gf_multiply(y ^ int.from_bytes(chunk, "big"), hash_key)
    lengths = (len(aad) * 8).to_bytes(8, "big") + (len(ciphertext) * 8).to_bytes(8, "big")
    y = _gf_multiply(y ^ int.from_bytes(lengths, "big"), hash_key)
    return y.to_bytes(16, "big")


def _inc32(block: bytes) -> bytes:
    """Increment the low 32 bits of a 128-bit counter block."""
    value = (int.from_bytes(block[-4:], "big") + 1) & 0xFFFFFFFF
    return block[:-4] + value.to_bytes(4, "big")


def _gctr(words: list[list[int]], initial_counter: bytes, data: bytes) -> bytes:
    if not data:
        return b""
    output = bytearray()
    counter = int.from_bytes(initial_counter, "big")
    for offset in range(0, len(data), 16):
        keystream = _encrypt_block(counter.to_bytes(16, "big"), words)
        chunk = data[offset : offset + 16]
        output.extend(a ^ b for a, b in zip(chunk, keystream))
        # GCM increments only the low 32 bits of the counter block.
        counter = (counter & ~0xFFFFFFFF) | ((counter + 1) & 0xFFFFFFFF)
    return bytes(output)


def _gcm_encrypt(key: bytes, iv: bytes, plaintext: bytes, aad: bytes) -> bytes:
    """Return ``ciphertext || tag`` for one AES-256-GCM message."""
    if len(iv) != E2E_IV_BYTES:
        raise ValueError("GCM requires a 12-byte IV")
    words = _expand_key(key)
    hash_key = int.from_bytes(_encrypt_block(b"\x00" * 16, words), "big")
    j0 = iv + b"\x00\x00\x00\x01"
    ciphertext = _gctr(words, _inc32(j0), plaintext)
    tag = bytes(
        a ^ b
        for a, b in zip(_encrypt_block(j0, words), _ghash(hash_key, aad, ciphertext))
    )
    return ciphertext + tag


def _gcm_decrypt(key: bytes, iv: bytes, data: bytes, aad: bytes) -> bytes:
    """Verify ``ct || tag`` and return the plaintext.

    Raises :class:`E2eDecryptError` on a wrong key, a tampered ciphertext or a
    corrupted tag; the comparison is constant time.
    """
    if len(iv) != E2E_IV_BYTES:
        raise E2eDecryptError("invalid IV length")
    if len(data) < E2E_TAG_BYTES:
        raise E2eDecryptError("ciphertext is too short")
    ciphertext, tag = data[:-E2E_TAG_BYTES], data[-E2E_TAG_BYTES:]

    words = _expand_key(key)
    hash_key = int.from_bytes(_encrypt_block(b"\x00" * 16, words), "big")
    j0 = iv + b"\x00\x00\x00\x01"
    expected = bytes(
        a ^ b
        for a, b in zip(_encrypt_block(j0, words), _ghash(hash_key, aad, ciphertext))
    )
    if not hmac.compare_digest(expected, tag):
        raise E2eDecryptError("authentication tag mismatch")
    return _gctr(words, _inc32(j0), ciphertext)


# ---------------------------------------------------------------------------
# base64url helpers
# ---------------------------------------------------------------------------


def b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64url_decode(value: str) -> bytes:
    """Strict base64url decode: no whitespace, padding, or trailing bits."""
    if not isinstance(value, str):
        raise ValueError("base64url value must be a string")
    if (
        not value
        or any(char.isspace() for char in value)
        or re.fullmatch(r"[A-Za-z0-9_-]+", value) is None
    ):
        raise ValueError("value is not base64url")
    padded = value + "=" * (-len(value) % 4)
    try:
        data = base64.urlsafe_b64decode(padded.encode("ascii"))
    except (binascii.Error, ValueError) as error:
        raise ValueError("value is not valid base64url") from error
    if b64url_encode(data) != value:
        raise ValueError("value is not canonical base64url")
    return data


# ---------------------------------------------------------------------------
# E2E envelope
# ---------------------------------------------------------------------------


def generate_key() -> str:
    """Return a fresh 256-bit key as 43 base64url characters."""
    return b64url_encode(secrets.token_bytes(E2E_KEY_BYTES))


def decode_key(raw: str) -> bytes:
    """Decode the shared key; exactly 43 base64url chars mapping to 32 bytes."""
    if not isinstance(raw, str) or _KEY_RE.fullmatch(raw.strip()) is None:
        raise ValueError("E2E key must be 43 base64url characters")
    data = b64url_decode(raw.strip())
    if len(data) != E2E_KEY_BYTES:
        raise ValueError("E2E key must decode to 32 bytes")
    return data


def is_e2e_envelope(obj: Any) -> bool:
    """Return ``True`` when ``obj`` looks like an encrypted status envelope."""
    return (
        isinstance(obj, dict)
        and obj.get("v") == E2E_VERSION
        and obj.get("alg") == E2E_ALGORITHM
        and isinstance(obj.get("iv"), str)
        and isinstance(obj.get("ct"), str)
    )


def encrypt_envelope(
    plaintext: bytes,
    key_b64: str,
    *,
    iv: bytes | None = None,
) -> dict[str, Any]:
    """Encrypt ``plaintext`` into the on-wire envelope dictionary."""
    if not isinstance(plaintext, (bytes, bytearray)):
        raise TypeError("plaintext must be bytes")
    key = decode_key(key_b64)
    nonce = secrets.token_bytes(E2E_IV_BYTES) if iv is None else bytes(iv)
    if len(nonce) != E2E_IV_BYTES:
        raise ValueError("IV must be 12 bytes")
    payload = _gcm_encrypt(key, nonce, bytes(plaintext), E2E_AAD)
    return {
        "v": E2E_VERSION,
        "alg": E2E_ALGORITHM,
        "iv": b64url_encode(nonce),
        "ct": b64url_encode(payload),
    }


def decrypt_envelope(envelope: dict[str, Any], key_b64: str) -> bytes:
    """Authenticate and decrypt an envelope; raises :class:`E2eDecryptError`."""
    if not isinstance(envelope, dict):
        raise E2eDecryptError("envelope must be an object")
    if envelope.get("v") != E2E_VERSION:
        raise E2eDecryptError("unsupported envelope version")
    if envelope.get("alg") != E2E_ALGORITHM:
        raise E2eDecryptError("unsupported envelope algorithm")
    try:
        iv = b64url_decode(envelope.get("iv"))  # type: ignore[arg-type]
        payload = b64url_decode(envelope.get("ct"))  # type: ignore[arg-type]
    except ValueError as error:
        raise E2eDecryptError("malformed envelope encoding") from error
    if len(iv) != E2E_IV_BYTES:
        raise E2eDecryptError("invalid IV length")
    if len(payload) < E2E_TAG_BYTES:
        raise E2eDecryptError("ciphertext is too short")
    try:
        key = decode_key(key_b64)
    except ValueError as error:
        raise E2eDecryptError("invalid key") from error
    return _gcm_decrypt(key, iv, payload, E2E_AAD)


# ---------------------------------------------------------------------------
# ntfy server normalisation
# ---------------------------------------------------------------------------


def normalize_ntfy_server(url: str | None) -> str:
    """Return a safe ``https://host[:port][/path]`` or raise ``ValueError``.

    Rejected on purpose: a non-HTTPS scheme (cleartext traffic is disabled in the
    Android manifest), credentials in the URL, a query string or fragment, a
    control character, and a host-less value.
    """
    if url is None:
        raise ValueError("ntfy server is not set")
    if not isinstance(url, str):
        raise ValueError("ntfy server must be a string")
    raw = url.strip()
    if not raw:
        raise ValueError("ntfy server is empty")
    if any(char.isspace() or ord(char) < 0x20 or ord(char) == 0x7F for char in raw):
        raise ValueError("ntfy server contains whitespace or control characters")

    parts = urlsplit(raw)
    if parts.scheme.lower() != "https":
        raise ValueError("ntfy server must use https")
    if parts.username or parts.password:
        raise ValueError("ntfy server must not contain credentials")
    if parts.query or parts.fragment:
        raise ValueError("ntfy server must not contain a query or fragment")
    hostname = parts.hostname
    if not hostname:
        raise ValueError("ntfy server has no host")
    if "@" in parts.netloc:
        raise ValueError("ntfy server must not contain credentials")

    try:
        port = parts.port
    except ValueError as error:
        raise ValueError("ntfy server has an invalid port") from error
    if port is not None and not 1 <= port <= 65535:
        raise ValueError("ntfy server port is out of range")

    host = hostname.lower()
    if ":" in host:  # IPv6 literal
        host = f"[{host}]"
    path = parts.path or ""
    if path.endswith("/"):
        path = path.rstrip("/")
    normalized = f"https://{host}"
    if port is not None and port != 443:
        normalized += f":{port}"
    normalized += path
    if len(normalized) > NTFY_SERVER_MAX_LENGTH:
        raise ValueError("ntfy server is too long")
    if NTFY_URL_RE.fullmatch(normalized) is None:
        raise ValueError("ntfy server has an unexpected format")
    return normalized


# ---------------------------------------------------------------------------
# Pairing URI (agw://pair)
# ---------------------------------------------------------------------------


def _validate_topic(topic: Any) -> str:
    value = topic.strip() if isinstance(topic, str) else ""
    if TOPIC_RE.fullmatch(value) is None:
        raise ValueError("invalid ntfy topic")
    return value


def build_pairing_uri(
    topic: str,
    server: str = DEFAULT_NTFY_URL,
    key_b64: str | None = None,
) -> str:
    """Build ``agw://pair?v=1&t=...`` with the default server omitted."""
    clean_topic = _validate_topic(topic)
    clean_server = normalize_ntfy_server(server)
    query = [f"v={E2E_VERSION}", f"t={clean_topic}"]
    if clean_server != DEFAULT_NTFY_URL:
        query.append("s=" + quote(clean_server, safe=""))
    if key_b64 is not None:
        decode_key(key_b64)
        query.append("k=" + key_b64.strip())
    return f"{_PAIRING_SCHEME}://{_PAIRING_HOST}?" + "&".join(query)


def parse_pairing_uri(uri: str) -> dict[str, Any]:
    """Parse a pairing URI into ``{topic, server, e2e_key, v}``.

    Unknown query parameters are ignored; duplicated or malformed known
    parameters are rejected.
    """
    if not isinstance(uri, str) or not uri.strip():
        raise ValueError("pairing URI is empty")
    parts = urlsplit(uri.strip())
    if parts.scheme.lower() != _PAIRING_SCHEME:
        raise ValueError("pairing URI must use the agw scheme")
    if (parts.hostname or "").lower() != _PAIRING_HOST:
        raise ValueError("pairing URI must target agw://pair")
    if parts.path not in ("", "/"):
        raise ValueError("pairing URI must not contain a path")

    pairs = parse_qsl(parts.query, keep_blank_values=True)
    collected: dict[str, str] = {}
    for key, value in pairs:
        if key not in _PAIRING_KNOWN_PARAMS:
            continue
        if key in collected:
            raise ValueError(f"duplicated pairing parameter: {key}")
        collected[key] = value

    if collected.get("v") != str(E2E_VERSION):
        raise ValueError("unsupported pairing version")
    if "t" not in collected:
        raise ValueError("pairing URI has no topic")
    topic = _validate_topic(collected["t"])

    server = DEFAULT_NTFY_URL
    if "s" in collected:
        server = normalize_ntfy_server(collected["s"])

    e2e_key: str | None = None
    if "k" in collected:
        if not collected["k"].strip():
            raise ValueError("empty E2E key")
        decode_key(collected["k"])
        e2e_key = collected["k"].strip()

    return {"v": E2E_VERSION, "topic": topic, "server": server, "e2e_key": e2e_key}
