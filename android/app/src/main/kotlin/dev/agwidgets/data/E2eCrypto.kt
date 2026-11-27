package dev.agwidgets.data

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * The ntfy channel settings produced by an `agw://pair` QR code.
 *
 * @property topic validated ntfy topic (`[A-Za-z0-9_-]{1,64}`).
 * @property server normalized `https://host[:port][/path]` endpoint.
 * @property e2eKey optional 43-char base64url AES-256 key; `null` disables E2E.
 */
data class PairingConfig(
    val topic: String,
    val server: String,
    val e2eKey: String?,
)

/**
 * Optional end-to-end encryption for the ntfy channel plus the QR pairing rules.
 *
 * This is the Kotlin mirror of `agwidgets/e2e.py` (and of the desktop bridge):
 * the shared vectors live in `tests/fixtures/e2e_pairing_cases.json`. The wire
 * format is an authenticated [AES-256-GCM] envelope:
 *
 * ```
 * {"v":1,"alg":"aes-256-gcm","iv":"<b64url-12B>","ct":"<b64url-ct||tag>"}
 * ```
 *
 * Pure JVM on purpose (only `java.*`, `javax.crypto.*` and `org.json.*`) so the
 * crypto and URI rules are unit-testable without Robolectric.
 */
object E2eCrypto {
    const val DEFAULT_NTFY_SERVER: String = "https://ntfy.sh"

    private const val ENVELOPE_VERSION = 1
    private const val ENVELOPE_ALG = "aes-256-gcm"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private const val TAG_BITS = 128
    private const val SERVER_MAX_LENGTH = 256

    /** Exact wire AAD; the tag only verifies on messages bound to this string. */
    private val AAD: ByteArray = "agw-ntfy-e2e:v1".toByteArray(Charsets.UTF_8)

    private val KEY_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
    private val B64URL_PATTERN = Regex("^[A-Za-z0-9_-]+$")
    private val TOPIC_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** Mirrors the `NTFY_URL_RE` sanity check in Python. */
    private val SERVER_PATTERN = Regex(
        "^https://[^\\s/?#@]+(?::[0-9]{1,5})?(?:/[^\\s?#]*)?$",
        RegexOption.IGNORE_CASE,
    )

    private const val PAIRING_SCHEME = "agw"
    private const val PAIRING_HOST = "pair"
    private val KNOWN_PARAMS = setOf("v", "t", "s", "k")

    private val random = SecureRandom()

    // ------------------------------------------------------------------
    // Key handling
    // ------------------------------------------------------------------

    /** `true` for exactly 43 canonical base64url characters decoding to 32 bytes. */
    fun isValidKey(raw: String?): Boolean = decodeKey(raw) != null

    /**
     * Decodes a 32-byte key.
     *
     * The shape is checked before decoding because `java.util.Base64` accepts
     * `=` padding and non-canonical trailing bits, which the wire format (and
     * the golden fixture) reject.
     */
    private fun decodeKey(raw: String?): ByteArray? {
        val value = raw?.trim() ?: return null
        if (!KEY_PATTERN.matches(value)) return null
        val bytes = decodeBase64Url(value) ?: return null
        return bytes.takeIf { it.size == KEY_BYTES }
    }

    // ------------------------------------------------------------------
    // Envelope
    // ------------------------------------------------------------------

    /** `true` when [raw] parses as a v1 `aes-256-gcm` envelope object. */
    fun isEncryptedEnvelope(raw: String): Boolean {
        if (raw.isBlank()) return false
        return try {
            val obj = JSONObject(raw)
            val version = obj.opt("v")
            version is Number &&
                version.toDouble() == ENVELOPE_VERSION.toDouble() &&
                obj.optString("alg", "") == ENVELOPE_ALG &&
                obj.opt("iv") is String &&
                obj.opt("ct") is String
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Encrypts [plaintext] into the compact on-wire envelope JSON.
     *
     * @param iv optional 12-byte nonce (deterministic tests / fixture vectors);
     *   a fresh random nonce is generated when omitted.
     * @throws IllegalArgumentException for an invalid key or IV length.
     */
    fun encryptEnvelope(plaintext: String, keyB64: String, iv: ByteArray? = null): String {
        val key = decodeKey(keyB64)
            ?: throw IllegalArgumentException("E2E key must be 43 base64url characters decoding to 32 bytes")
        val nonce = iv ?: ByteArray(IV_BYTES).also(random::nextBytes)
        require(nonce.size == IV_BYTES) { "IV must be 12 bytes" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(AAD)
        // doFinal returns `ciphertext || tag`, which is exactly the wire layout.
        val payload = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        return "{\"v\":$ENVELOPE_VERSION,\"alg\":\"$ENVELOPE_ALG\"," +
            "\"iv\":\"${encodeBase64Url(nonce)}\",\"ct\":\"${encodeBase64Url(payload)}\"}"
    }

    /**
     * Authenticates and decrypts an envelope. Never throws: a wrong key, a
     * tampered payload or a malformed document becomes [Result.failure].
     */
    fun decryptEnvelope(envelopeJson: String, keyB64: String): Result<String> = try {
        val obj = JSONObject(envelopeJson)
        val version = obj.opt("v")
        val alg = obj.optString("alg", "")
        val ivRaw = obj.opt("iv")
        val ctRaw = obj.opt("ct")

        when {
            version !is Number || version.toDouble() != ENVELOPE_VERSION.toDouble() ->
                decryptFailure("Неподдерживаемая версия E2E-сообщения")
            alg != ENVELOPE_ALG ->
                decryptFailure("Неподдерживаемый алгоритм E2E-сообщения")
            ivRaw !is String || ctRaw !is String ->
                decryptFailure("Некорректный E2E-конверт")
            else -> {
                val iv = decodeBase64Url(ivRaw)
                val payload = decodeBase64Url(ctRaw)
                val key = decodeKey(keyB64)
                if (iv == null || payload == null) {
                    decryptFailure("Некорректный E2E-конверт")
                } else if (iv.size != IV_BYTES) {
                    decryptFailure("Некорректная длина IV E2E-сообщения")
                } else if (payload.size < TAG_BYTES) {
                    decryptFailure("Слишком короткое E2E-сообщение")
                } else if (key == null) {
                    decryptFailure("Некорректный E2E-ключ")
                } else {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(key, "AES"),
                        GCMParameterSpec(TAG_BITS, iv),
                    )
                    cipher.updateAAD(AAD)
                    Result.success(String(cipher.doFinal(payload), Charsets.UTF_8))
                }
            }
        }
    } catch (e: Exception) {
        // AEADBadTagException, GeneralSecurityException, IllegalArgumentException,
        // JSONException: all of them mean "this message cannot be trusted".
        Result.failure(IllegalStateException("Не удалось расшифровать E2E-сообщение", e))
    }

    // ------------------------------------------------------------------
    // ntfy server
    // ------------------------------------------------------------------

    /**
     * Returns a safe `https://host[:port][/path]` or `null`.
     *
     * Rejected on purpose: a non-HTTPS scheme (cleartext is disabled in the
     * manifest), credentials, a query or fragment, whitespace/control
     * characters, a host-less value, an out-of-range port and over-long URLs.
     */
    fun normalizeNtfyServer(raw: String?): String? {
        val value = raw?.trim() ?: return null
        if (value.isEmpty()) return null
        for (char in value) {
            if (char.isWhitespace() || char.code < 0x20 || char.code == 0x7F) return null
        }

        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.rawUserInfo != null) return null
        if (!uri.rawQuery.isNullOrEmpty()) return null
        if (!uri.rawFragment.isNullOrEmpty()) return null

        val authority = resolveAuthority(uri) ?: return null
        val port = authority.port
        if (port != null && port !in 1..65535) return null

        val host = authority.host.lowercase()
        if (host.isEmpty() || '@' in host) return null
        // `URI.getHost()` already returns IPv6 literals with brackets; only wrap
        // the bare `::1` form produced by the raw-authority fallback.
        val bracketedHost = when {
            host.startsWith("[") && host.endsWith("]") -> host
            ':' in host -> "[$host]"
            else -> host
        }

        val path = (uri.rawPath ?: "").trimEnd('/')
        val normalized = buildString {
            append("https://")
            append(bracketedHost)
            if (port != null && port != 443) append(':').append(port)
            append(path)
        }
        if (normalized.length > SERVER_MAX_LENGTH) return null
        if (!SERVER_PATTERN.matches(normalized)) return null
        return normalized
    }

    /** Host/port pair resolved from a URI, with the raw-authority fallback. */
    private class Authority(val host: String, val port: Int?)

    private fun resolveAuthority(uri: URI): Authority? {
        val host = uri.host
        if (host != null) {
            val port = uri.port
            return Authority(host, if (port == -1) null else port)
        }
        // `java.net.URI` only recognises strictly server-based authorities, so
        // hosts with characters such as `_` fall back to the raw authority
        // (Python's `urlsplit` accepts them).
        val authority = uri.rawAuthority ?: return null
        if (authority.isEmpty() || '@' in authority) return null

        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val hostPart = authority.substring(0, close + 1)
            val rest = authority.substring(close + 1)
            val port = when {
                rest.isEmpty() || rest == ":" -> null
                rest.startsWith(":") -> rest.substring(1).toIntOrNull() ?: return null
                else -> return null
            }
            return Authority(hostPart, port)
        }

        val colon = authority.lastIndexOf(':')
        if (colon < 0) return Authority(authority, null)
        val hostPart = authority.substring(0, colon)
        val portPart = authority.substring(colon + 1)
        // A bare (unbracketed) IPv6 literal is not an accepted server form.
        if (hostPart.isEmpty() || ':' in hostPart) return null
        val port = when {
            portPart.isEmpty() -> null
            portPart.all { it in '0'..'9' } -> portPart.toIntOrNull() ?: return null
            else -> return null
        }
        return Authority(hostPart, port)
    }

    // ------------------------------------------------------------------
    // Pairing URI
    // ------------------------------------------------------------------

    /**
     * Parses `agw://pair?v=1&t=<topic>[&s=<server>][&k=<key>]`.
     *
     * Unknown query parameters are ignored; duplicated or malformed known ones
     * are rejected. Never throws.
     */
    fun parsePairingUri(uri: String): Result<PairingConfig> {
        val value = uri.trim()
        if (value.isEmpty()) return pairingFailure("Пустая ссылка сопряжения")

        val parsed = try {
            URI(value)
        } catch (_: URISyntaxException) {
            return pairingFailure("Некорректная ссылка сопряжения")
        }
        if (!parsed.scheme.equals(PAIRING_SCHEME, ignoreCase = true)) {
            return pairingFailure("Ссылка должна использовать схему agw")
        }
        if (!parsed.host.equals(PAIRING_HOST, ignoreCase = true)) {
            return pairingFailure("Ссылка должна указывать на agw://pair")
        }
        val path = parsed.path ?: ""
        if (path != "" && path != "/") return pairingFailure("Ссылка не должна содержать путь")

        val collected = LinkedHashMap<String, String>()
        val rawQuery = parsed.rawQuery
        if (!rawQuery.isNullOrEmpty()) {
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val separator = pair.indexOf('=')
                val rawKey = if (separator >= 0) pair.substring(0, separator) else pair
                val rawValue = if (separator >= 0) pair.substring(separator + 1) else ""
                val key = decodeQueryComponent(rawKey) ?: return pairingFailure("Некорректный параметр ссылки")
                if (key !in KNOWN_PARAMS) continue
                if (collected.containsKey(key)) return pairingFailure("Повторяющийся параметр $key")
                collected[key] = decodeQueryComponent(rawValue)
                    ?: return pairingFailure("Некорректный параметр ссылки")
            }
        }

        if (collected["v"] != ENVELOPE_VERSION.toString()) {
            return pairingFailure("Неподдерживаемая версия ссылки сопряжения")
        }
        val topic = collected["t"]?.trim()
        if (topic.isNullOrEmpty() || !TOPIC_PATTERN.matches(topic)) {
            return pairingFailure("Некорректный топик ntfy")
        }

        val server = if ("s" in collected) {
            normalizeNtfyServer(collected["s"]) ?: return pairingFailure("Некорректный сервер ntfy")
        } else {
            DEFAULT_NTFY_SERVER
        }

        val e2eKey = if ("k" in collected) {
            val raw = collected["k"]
            if (raw.isNullOrBlank()) return pairingFailure("Пустой E2E-ключ")
            val trimmed = raw.trim()
            if (!isValidKey(trimmed)) return pairingFailure("Некорректный E2E-ключ")
            trimmed
        } else {
            null
        }

        return Result.success(PairingConfig(topic = topic, server = server, e2eKey = e2eKey))
    }

    /** Percent-decodes one query component, keeping `+` as a space like Python. */
    private fun decodeQueryComponent(raw: String): String? = try {
        URLDecoder.decode(raw, "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }

    // ------------------------------------------------------------------
    // Display helpers
    // ------------------------------------------------------------------

    /** Shortens a topic for logs and hints: `agw-7f3a1234` → `agw-••••1234`. */
    fun maskTopic(topic: String): String = when {
        topic.isEmpty() -> "••••"
        topic.length <= 8 -> topic.take(2) + "••••" + topic.takeLast(2)
        else -> topic.take(4) + "••••" + topic.takeLast(4)
    }

    // ------------------------------------------------------------------
    // base64url helpers
    // ------------------------------------------------------------------

    private fun encodeBase64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /**
     * Strict base64url decode: rejects padding, non-base64url characters and
     * non-canonical trailing bits (which a lenient decoder silently accepts).
     */
    private fun decodeBase64Url(value: String): ByteArray? {
        val stripped = value.trim()
        if (stripped.isEmpty() || !B64URL_PATTERN.matches(stripped)) return null
        val bytes = try {
            Base64.getUrlDecoder().decode(stripped)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (encodeBase64Url(bytes) != stripped) return null
        return bytes
    }

    private fun pairingFailure(message: String): Result<PairingConfig> =
        Result.failure(IllegalArgumentException(message))

    private fun decryptFailure(message: String): Result<String> =
        Result.failure(IllegalStateException(message))
}
