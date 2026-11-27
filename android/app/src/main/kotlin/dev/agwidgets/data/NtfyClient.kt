package dev.agwidgets.data

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

    suspend fun fetchLatestMessage(
        topic: String,
        timeoutMs: Int = 15_000,
    ): Result<String> = withContext(Dispatchers.IO) {
        val clean = topic.trim()
        if (clean.isEmpty() || !clean.matches(TOPIC_PATTERN)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid ntfy topic"))
        }
        var conn: HttpURLConnection? = null
        try {
            val url = URL("https://ntfy.sh/" + URLEncoder.encode(clean, "UTF-8") + "/json?poll=1&since=latest")
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
                return@withContext Result.failure(IllegalStateException("В теме пока нет сообщений"))
            }
            // Pick the newest status message, skipping unrelated or malformed events.
            val payload = extractLatestStatusPayload(lines)
                ?: return@withContext Result.failure(IllegalStateException("Нет сообщений статуса в теме ntfy"))

            Result.success(payload)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    /** Picks the newest valid status payload from an ntfy NDJSON response. */
    internal fun extractLatestStatusPayload(lines: List<String>): String? {
        val messageLine = lines.asReversed().firstOrNull { isStatusMessage(it) } ?: return null
        return runCatching { JSONObject(messageLine).optString("message", "") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * A usable ntfy event is a `message` event with a payload that parses as
     * [AgStatus]. When a title is present it must be the app's status title.
     */
    private fun isStatusMessage(line: String): Boolean {
        return try {
            val obj = JSONObject(line)
            if (obj.optString("event") != "message") return false
            if (!obj.has("message")) return false
            val title = obj.optString("title", "").trim()
            if (title.isNotEmpty() && title != STATUS_TITLE) return false
            val payload = obj.optString("message", "")
            AgStatus.fromJson(payload) != null
        } catch (_: Exception) {
            false
        }
    }
}
