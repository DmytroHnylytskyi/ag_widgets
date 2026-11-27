package dev.agwidgets.data

import dev.agwidgets.ui.i18n.AgStrings
import dev.agwidgets.ui.i18n.RuStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.roundToLong

/** Coupon probe outcome, kept free of Android types so JVM tests can call it. */
internal data class TouchResult(
    val couponState: String,
    val planId: String = "",
    val matched: String = "",
    val errorKind: String? = null,
)

/** Balance snapshot parsed from the billing endpoint. */
internal data class BalanceResult(
    val remainingUnits: Long? = null,
    val totalUnits: Long? = null,
    val endsAt: String? = null,
    val planName: String = "",
    val errorKind: String? = null,
)

object ZCodeClient {
    private const val ORIGIN = "https://zcode.z.ai"
    private const val APP_VERSION = "3.5.0"

    suspend fun startOAuthFlow(
        provider: String = "zai",
        strings: AgStrings = RuStrings,
    ): Result<ZCodeOAuthFlow> = withContext(Dispatchers.IO) {
        try {
            val pollToken = randomHex(32)
            val body = JSONObject().put("provider", provider).toString()
            val (code, json) = postJson(
                url = "$ORIGIN/api/v1/oauth/cli/init",
                body = body,
                headers = mapOf("Authorization" to "Bearer $pollToken")
            )
            if (code !in 200..299 || json == null || json.optInt("code", -1) != 0) {
                return@withContext Result.failure(
                    IllegalStateException(strings.fill(strings.zcInitFailed, "code" to code))
                )
            }
            val data = json.optJSONObject("data")
                ?: return@withContext Result.failure(IllegalStateException(strings.zcInitEmpty))
            val flowId = data.optString("flow_id").trim()
            val rawAuthUrl = data.optString("authorize_url").trim()
            val interval = data.optLong("poll_interval_sec", 3L).coerceIn(2L, 10L)
            if (flowId.isEmpty() || rawAuthUrl.isEmpty()) {
                return@withContext Result.failure(IllegalStateException(strings.zcInitInvalidFields))
            }
            val authUrl = isTrustedAuthorizeUrl(rawAuthUrl)
                ?: return@withContext Result.failure(SecurityException("Refusing untrusted sign-in URL"))
            Result.success(ZCodeOAuthFlow(flowId, authUrl, pollToken, interval))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pollOAuthFlow(
        flow: ZCodeOAuthFlow,
        strings: AgStrings = RuStrings,
    ): ZCodeOAuthPollResult = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(flow.flowId, "UTF-8")
            val (code, json) = getJson(
                url = "$ORIGIN/api/v1/oauth/cli/poll/$encoded",
                headers = mapOf("Authorization" to "Bearer ${flow.pollToken}")
            )
            if (code in 400..499 && code != 408 && code != 429) {
                return@withContext ZCodeOAuthPollResult.Failed(
                    strings.fill(strings.zcSessionExpired, "code" to code)
                )
            }
            if (code !in 200..299 || json == null) {
                return@withContext ZCodeOAuthPollResult.Pending
            }
            val data = json.optJSONObject("data") ?: return@withContext ZCodeOAuthPollResult.Pending
            when (data.optString("status")) {
                "pending" -> ZCodeOAuthPollResult.Pending
                "failed" -> ZCodeOAuthPollResult.Failed(strings.zcLoginRejected)
                "ready" -> {
                    val token = data.optString("token").trim()
                    if (token.isNotEmpty()) ZCodeOAuthPollResult.Ready(token)
                    else ZCodeOAuthPollResult.Failed(strings.zcNoTokenFromServer)
                }
                else -> ZCodeOAuthPollResult.Pending
            }
        } catch (_: Exception) {
            ZCodeOAuthPollResult.Pending
        }
    }

    suspend fun fetchStatus(
        token: String,
        deviceMid: String,
        seq: Int = 0,
        previous: ZCodeStatus? = null,
        timeoutMs: Int = 15_000,
        strings: AgStrings = RuStrings,
    ): Result<ZCodeStatus> = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException(strings.zcTokenMissing))
        }

        val headers = mapOf(
            "Authorization" to "Bearer ${token.trim()}",
            "X-Device-Mid" to deviceMid.trim(),
            "X-Client-Language" to "ru-RU",
            "X-ZCode-App-Version" to APP_VERSION
        )
        val safeSeq = seq.coerceAtLeast(0)
        // The endpoints are independent, so probe them concurrently; a transport
        // failure on one must not hide the other response or delay it.
        val (touchResponse, balanceResponse) = coroutineScope {
            val touch = async {
                runCatching {
                    getJson("$ORIGIN/api/v1/marketing/touch?seq=$safeSeq", headers, timeoutMs)
                }.getOrNull()
            }
            val balance = async {
                runCatching {
                    getJson("$ORIGIN/api/v1/zcode-plan/billing/balance", headers, timeoutMs)
                }.getOrNull()
            }
            touch.await() to balance.await()
        }

        val touchCode = touchResponse?.first ?: 0
        val touchBody = touchResponse?.second
        val balanceCode = balanceResponse?.first ?: 0
        val balanceBody = balanceResponse?.second
        val nowIso = Instant.now().toString()

        try {
            val base = previous?.copy() ?: ZCodeStatus(running = true, couponVisible = false, method = "api")

            // Full transport outage: neither endpoint returned an HTTP status.
            if (touchCode == 0 && balanceCode == 0) {
                return@withContext Result.success(
                    base.copy(
                        running = false,
                        couponVisible = false,
                        method = "api",
                        authOk = null,
                        networkError = true,
                        couponState = ZCodeStatus.COUPON_UNKNOWN,
                        errorKind = "network",
                        checkedAt = nowIso
                    )
                )
            }

            val touch = parseTouchResponse(touchBody, touchCode)
            val balance = parseBalanceResponse(balanceBody, balanceCode)
            val authExpired = touchCode == 401 || touchCode == 403 ||
                balanceCode == 401 || balanceCode == 403 ||
                touch.errorKind == "auth_expired" || balance.errorKind == "auth_expired"

            // 401/403 (or business code 401) from either endpoint proves the token is invalid.
            if (authExpired) {
                return@withContext Result.success(
                    base.copy(
                        running = true,
                        couponVisible = false,
                        method = "api",
                        authOk = false,
                        networkError = false,
                        couponState = ZCodeStatus.COUPON_UNKNOWN,
                        errorKind = "auth_expired",
                        checkedAt = nowIso
                    )
                )
            }

            val touchOk = touch.errorKind == null
            val balanceOk = balance.errorKind == null
            val anyHttpOk = (touchCode in 200..299 && touchBody != null) ||
                (balanceCode in 200..299 && balanceBody != null)

            Result.success(
                base.copy(
                    running = true,
                    couponVisible = touchOk && touch.couponState == ZCodeStatus.COUPON_VISIBLE,
                    couponState = touch.couponState,
                    matched = if (touchOk) touch.matched else base.matched,
                    planId = if (touchOk) touch.planId else base.planId,
                    method = "api",
                    authOk = if (anyHttpOk) true else null,
                    networkError = false,
                    remainingUnits = if (balanceOk) balance.remainingUnits else base.remainingUnits,
                    totalUnits = if (balanceOk) balance.totalUnits else base.totalUnits,
                    endsAt = if (balanceOk) balance.endsAt else base.endsAt,
                    planName = if (balanceOk) balance.planName else base.planName,
                    errorKind = pickErrorKind(touch.errorKind, balance.errorKind),
                    checkedAt = nowIso
                )
            )
        } catch (_: Exception) {
            // Unexpected payload shapes still leave a truthful degraded status.
            val base = previous?.copy() ?: ZCodeStatus(running = true, couponVisible = false, method = "api")
            Result.success(
                base.copy(
                    running = false,
                    couponVisible = false,
                    method = "api",
                    authOk = null,
                    networkError = true,
                    couponState = ZCodeStatus.COUPON_UNKNOWN,
                    errorKind = "network",
                    checkedAt = nowIso
                )
            )
        }
    }

    private fun randomHex(numBytes: Int): String {
        val bytes = ByteArray(numBytes)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getJson(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int = 15_000,
    ): Pair<Int, JSONObject?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } }
            val json = text?.let { runCatching { JSONObject(it) }.getOrNull() }
            code to json
        } finally {
            conn?.disconnect()
        }
    }

    private fun postJson(url: String, body: String, headers: Map<String, String>): Pair<Int, JSONObject?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } }
            val json = text?.let { runCatching { JSONObject(it) }.getOrNull() }
            code to json
        } finally {
            conn?.disconnect()
        }
    }
}

/**
 * Parses a marketing touch response into the tri-state coupon contract.
 * Network/HTTP/structural failures always yield [ZCodeStatus.COUPON_UNKNOWN],
 * never [ZCodeStatus.COUPON_ABSENT].
 */
internal fun parseTouchResponse(body: JSONObject?, httpCode: Int): TouchResult {
    return try {
        val httpKind = httpErrorKind(httpCode)
        if (httpKind != null) {
            return TouchResult(ZCodeStatus.COUPON_UNKNOWN, errorKind = httpKind)
        }
        if (body == null) {
            return TouchResult(ZCodeStatus.COUPON_UNKNOWN, errorKind = "parse_failed")
        }
        val businessCode = body.optInt("code", -1)
        if (businessCode == 401) {
            return TouchResult(ZCodeStatus.COUPON_UNKNOWN, errorKind = "auth_expired")
        }
        if (businessCode != 0) {
            return TouchResult(ZCodeStatus.COUPON_UNKNOWN, errorKind = "api_changed")
        }
        val deliveries = body.optJSONObject("data")?.optJSONArray("deliveries")
            ?: return TouchResult(ZCodeStatus.COUPON_UNKNOWN, errorKind = "api_changed")

        for (i in 0 until deliveries.length()) {
            val delivery = deliveries.optJSONObject(i) ?: continue
            val position = delivery.optString("resource_position")
            val box = if (position == "banner") delivery.optJSONObject("banner") else delivery.optJSONObject("popup")
            val buttons = box?.optJSONArray("buttons") ?: continue
            for (j in 0 until buttons.length()) {
                val button = buttons.optJSONObject(j) ?: continue
                val action = button.optJSONObject("action") ?: continue
                if (action.optString("type") != "claim_zcode_plan") continue
                val planId = action.optJSONObject("args")?.optString("plan_id").orEmpty().trim()
                val buttonText = button.optJSONObject("text")?.optString("content").orEmpty().trim()
                val title = box.optJSONObject("title")?.optString("content").orEmpty().trim()
                val matched = buttonText.ifEmpty { title.ifEmpty { "plan:$planId" } }.take(100)
                return TouchResult(ZCodeStatus.COUPON_VISIBLE, planId, matched)
            }
        }
        TouchResult(ZCodeStatus.COUPON_ABSENT)
    } catch (_: Exception) {
        TouchResult(ZCodeStatus.COUPON_UNKNOWN, errorKind = "parse_failed")
    }
}

/** Parses the billing balance response; never throws. */
internal fun parseBalanceResponse(
    body: JSONObject?,
    httpCode: Int,
    fallbackNowSec: Double = System.currentTimeMillis() / 1000.0,
): BalanceResult {
    return try {
        val httpKind = httpErrorKind(httpCode)
        if (httpKind != null) {
            return BalanceResult(errorKind = httpKind)
        }
        if (body == null) {
            return BalanceResult(errorKind = "parse_failed")
        }
        val businessCode = body.optInt("code", -1)
        if (businessCode == 401) {
            return BalanceResult(errorKind = "auth_expired")
        }
        if (businessCode != 0 && !body.optBoolean("success", false)) {
            return BalanceResult(errorKind = "api_changed")
        }
        val data = body.optJSONObject("data") ?: return BalanceResult()

        var nowSec = optNumber(data, "server_time")?.takeIf { it > 0.0 } ?: fallbackNowSec
        if (nowSec > 1e12) nowSec /= 1000.0

        val plans = data.optJSONArray("plans") ?: JSONArray()
        // Any non-empty plan list means balances must belong to an active plan.
        // With every plan expired no id matches, so dead-plan quotas drop out.
        val hasPlans = plans.length() > 0
        val activePlanIds = mutableSetOf<String>()
        val activeUserPlanIds = mutableSetOf<String>()
        var soonestEnd: Double? = null
        var planName = ""

        for (i in 0 until plans.length()) {
            val plan = plans.optJSONObject(i) ?: continue
            val status = plan.optString("status").trim().lowercase()
            val endSec = parseTimestampSeconds(plan.opt("ends_at"))
            val expired = status == "expired" || (endSec != null && endSec > 0.0 && endSec <= nowSec)
            if (expired) continue
            plan.optString("plan_id").trim().takeIf { it.isNotEmpty() }?.let { activePlanIds.add(it) }
            plan.optString("user_plan_id").trim().takeIf { it.isNotEmpty() }?.let { activeUserPlanIds.add(it) }
            if (planName.isEmpty()) planName = plan.optString("name").trim()
            if (endSec != null && endSec > nowSec && (soonestEnd == null || endSec < soonestEnd)) {
                soonestEnd = endSec
            }
        }

        val balances = data.optJSONArray("balances") ?: JSONArray()
        var totalSum = 0.0
        var remainingSum = 0.0
        var found = false
        for (i in 0 until balances.length()) {
            val balance = balances.optJSONObject(i) ?: continue
            val userPlanId = balance.optString("user_plan_id").trim()
            val planId = balance.optString("plan_id").trim()
            if (hasPlans) {
                val belongs = (userPlanId.isNotEmpty() && userPlanId in activeUserPlanIds) ||
                    (planId.isNotEmpty() && planId in activePlanIds)
                if ((userPlanId.isNotEmpty() || planId.isNotEmpty()) && !belongs) continue
            }

            val total = optNumber(balance, "total_units")
            var remaining = optNumber(balance, "available_units") ?: optNumber(balance, "remaining_units")
            if (remaining == null && total != null) {
                val used = optNumber(balance, "used_units") ?: 0.0
                remaining = (total - used).coerceAtLeast(0.0)
            }
            if (total != null || remaining != null) {
                found = true
                totalSum += total ?: 0.0
                remainingSum += remaining ?: 0.0
                val periodEnd = parseTimestampSeconds(balance.opt("period_end"))
                if (periodEnd != null && periodEnd > nowSec && (soonestEnd == null || periodEnd < soonestEnd)) {
                    soonestEnd = periodEnd
                }
            }
        }

        val endsAt = soonestEnd?.let { Instant.ofEpochMilli((it * 1000.0).roundToLong()).toString() }
        if (!found) {
            return BalanceResult(endsAt = endsAt, planName = planName)
        }
        BalanceResult(
            remainingUnits = remainingSum.roundToLong(),
            totalUnits = if (totalSum > 0.0) totalSum.roundToLong() else null,
            endsAt = endsAt,
            planName = planName
        )
    } catch (_: Exception) {
        BalanceResult(errorKind = "parse_failed")
    }
}

/**
 * Accepts only HTTPS URLs on `z.ai` or its subdomains, without credentials and
 * with the default port. Returns the normalized URL or null when untrusted.
 */
internal fun isTrustedAuthorizeUrl(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme ?: return null
    if (!scheme.equals("https", ignoreCase = true)) return null
    val host = uri.host ?: return null
    if (!host.equals("z.ai", ignoreCase = true) && !host.endsWith(".z.ai", ignoreCase = true)) return null
    if (uri.userInfo != null) return null
    if (uri.port != -1 && uri.port != 443) return null
    return uri.toASCIIString()
}

/** Maps an HTTP status onto the shared error-kind taxonomy. */
private fun httpErrorKind(httpCode: Int): String? = when {
    httpCode == 401 || httpCode == 403 -> "auth_expired"
    httpCode == 429 -> "rate_limited"
    httpCode in 500..599 -> "server_error"
    httpCode !in 200..299 -> "network"
    else -> null
}

/** Most specific failure wins when both endpoints disagree. */
private val ERROR_PRIORITY = listOf(
    "auth_expired",
    "rate_limited",
    "server_error",
    "parse_failed",
    "api_changed",
    "network"
)

private fun pickErrorKind(vararg kinds: String?): String? {
    for (kind in ERROR_PRIORITY) {
        if (kinds.any { it == kind }) return kind
    }
    return kinds.firstOrNull { it != null }
}

private fun optNumber(obj: JSONObject, key: String): Double? {
    if (!obj.has(key) || obj.isNull(key)) return null
    return when (val raw = obj.opt(key)) {
        is Number -> raw.toDouble().takeIf { it.isFinite() }
        is String -> raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }
}

/** Parses epoch seconds, epoch milliseconds, or an ISO-8601 timestamp. */
private fun parseTimestampSeconds(value: Any?): Double? = when (value) {
    null, is Boolean -> null
    is Number -> normalizeEpochSeconds(value.toDouble())
    is String -> {
        val text = value.trim()
        if (text.isEmpty()) {
            null
        } else {
            val numeric = text.toDoubleOrNull()
            if (numeric != null) normalizeEpochSeconds(numeric) else parseIsoSeconds(text)
        }
    }
    else -> null
}

private fun parseIsoSeconds(text: String): Double? {
    runCatching { Instant.parse(text) }.getOrNull()?.let {
        return it.epochSecond.toDouble() + it.nano / 1_000_000_000.0
    }
    runCatching { OffsetDateTime.parse(text) }.getOrNull()?.let {
        val instant = it.toInstant()
        return instant.epochSecond.toDouble() + instant.nano / 1_000_000_000.0
    }
    // Naive ISO timestamps are treated as UTC, matching the desktop collector.
    return runCatching {
        val instant = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)
        instant.epochSecond.toDouble() + instant.nano / 1_000_000_000.0
    }.getOrNull()
}

private fun normalizeEpochSeconds(raw: Double): Double? {
    if (!raw.isFinite()) return null
    return if (raw > 1e12) raw / 1000.0 else raw
}
