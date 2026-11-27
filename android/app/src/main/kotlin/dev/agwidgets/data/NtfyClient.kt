package dev.agwidgets.data

import dev.agwidgets.ui.i18n.AgStrings
import dev.agwidgets.ui.i18n.RuStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object NtfyClient {
    private val TOPIC_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    private const val STATUS_TITLE = "ag-widgets-status"

    /**
     * Polls the newest status message of [topic].
     *
     * The defaults keep pre-E2E callers working (`ntfy.sh`, plaintext). When an
     * [e2eKey] is configured, a plaintext payload is rejected on purpose so a
     * network attacker cannot strip the encryption (anti-downgrade).
     *
     * [strings] defaults to Russian so the existing user-facing texts stay
     * byte-for-byte unchanged for callers that do not resolve a language yet.
     */
    suspend fun fetchLatestMessage(
        topic: String,
        timeoutMs: Int = 15_000,
        serverUrl: String = E2eCrypto.DEFAULT_NTFY_SERVER,
        e2eKey: String? = null,
        strings: AgStrings = RuStrings,
    ): Result<String> = withContext(Dispatchers.IO) {
        val clean = topic.trim()
        if (clean.isEmpty() || !clean.matches(TOPIC_PATTERN)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid ntfy topic"))
        }
        val base = E2eCrypto.normalizeNtfyServer(serverUrl)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid ntfy server"))
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$base/" + URLEncoder.encode(clean, "UTF-8") + "/json?poll=1&since=latest")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/x-ndjson, application/json")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext Result.failure(IllegalStateException("HTTP $code"))
            }
            val lines = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                reader.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            }
            if (lines.isEmpty()) {
                return@withContext Result.failure(IllegalStateException(strings.ntfyNoMessages))
            }
            extractLatestStatusPayload(lines, e2eKey, strings)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Legacy (pre-E2E) payload picker: the newest valid [AgStatus] JSON as a
     * plain string. Behavior is frozen for existing callers and tests.
     */
    internal fun extractLatestStatusPayload(lines: List<String>): String? =
        extractLegacyStatusPayload(lines)

    private fun extractLegacyStatusPayload(lines: List<String>): String? =
        lines.asReversed().firstNotNullOfOrNull { line ->
            messagePayloadOrNull(line)?.takeIf { AgStatus.fromJson(it) != null }
        }

    /**
     * E2E-aware payload picker.
     *
     * The newest ntfy `message` event with a status-shaped title wins; then the
     * payload is either decrypted (envelope + key) or accepted as plaintext
     * (no key configured). An envelope without a key and plaintext with a key
     * are both failures.
     */
    internal fun extractLatestStatusPayload(
        lines: List<String>,
        e2eKey: String?,
        strings: AgStrings = RuStrings,
    ): Result<String> {
        // Pick the newest status-shaped message (either an E2E envelope or a
        // plaintext AgStatus), skipping unrelated/malformed events on the topic.
        val payload = lines.asReversed().firstNotNullOfOrNull { line ->
            messagePayloadOrNull(line)?.takeIf { candidate ->
                E2eCrypto.isEncryptedEnvelope(candidate) || AgStatus.fromJson(candidate) != null
            }
        } ?: return Result.failure(IllegalStateException(strings.ntfyNoStatus))

        if (E2eCrypto.isEncryptedEnvelope(payload)) {
            if (e2eKey == null) {
                return Result.failure(IllegalStateException(strings.ntfyEncryptedNoKey))
            }
            val plaintext = E2eCrypto.decryptEnvelope(payload, e2eKey, strings).getOrElse {
                return Result.failure(IllegalStateException(strings.ntfyWrongKey))
            }
            if (AgStatus.fromJson(plaintext) == null) {
                return Result.failure(IllegalStateException(strings.ntfyBadJson))
            }
            return Result.success(plaintext)
        }

        if (e2eKey != null) {
            // Anti-downgrade: with a key configured only authenticated envelopes
            // are trusted, otherwise anyone could publish forged quotas.
            return Result.failure(IllegalStateException(strings.ntfyExpectedE2e))
        }
        return Result.success(payload)
    }

    /**
     * Payload of a usable ntfy event: a `message` event with a non-empty
     * message whose optional title is the app's status title.
     */
    private fun messagePayloadOrNull(line: String): String? = try {
        val obj = JSONObject(line)
        if (obj.optString("event") != "message" || !obj.has("message")) {
            null
        } else {
            val title = obj.optString("title", "").trim()
            val payload = obj.optString("message", "")
            if (title.isNotEmpty() && title != STATUS_TITLE) null else payload.takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
    }
}
