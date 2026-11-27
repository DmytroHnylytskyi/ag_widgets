package dev.agwidgets.data

import dev.agwidgets.ui.i18n.AgStrings
import dev.agwidgets.ui.i18n.RuStrings

/**
 * ZCode is not part of AG Context. This stub keeps the shared app code
 * compilable without shipping the API client, OAuth flow, or token handling.
 */
object ZCodeClient {
    private const val UNAVAILABLE = "ZCode is not available in AG Context"

    suspend fun startOAuthFlow(
        provider: String = "zai",
        strings: AgStrings = RuStrings,
    ): Result<ZCodeOAuthFlow> =
        Result.failure(UnsupportedOperationException(UNAVAILABLE))

    suspend fun pollOAuthFlow(
        flow: ZCodeOAuthFlow,
        strings: AgStrings = RuStrings,
    ): ZCodeOAuthPollResult =
        ZCodeOAuthPollResult.Failed(UNAVAILABLE)

    suspend fun fetchStatus(
        token: String,
        deviceMid: String,
        seq: Int = 0,
        previous: ZCodeStatus? = null,
        timeoutMs: Int = 15_000,
        strings: AgStrings = RuStrings,
    ): Result<ZCodeStatus> =
        // The lite build keeps the full client's pre-flight contract: a blank
        // token is rejected before anything else would be attempted.
        if (token.isBlank()) {
            Result.failure(IllegalArgumentException(strings.zcTokenMissing))
        } else {
            Result.failure(UnsupportedOperationException(UNAVAILABLE))
        }
}
