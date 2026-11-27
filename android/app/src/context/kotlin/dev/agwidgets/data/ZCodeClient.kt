package dev.agwidgets.data

/**
 * ZCode is not part of AG Context. This stub keeps the shared app code
 * compilable without shipping the API client, OAuth flow, or token handling.
 */
object ZCodeClient {
    private const val UNAVAILABLE = "ZCode is not available in AG Context"

    suspend fun startOAuthFlow(provider: String = "zai"): Result<ZCodeOAuthFlow> =
        Result.failure(UnsupportedOperationException(UNAVAILABLE))

    suspend fun pollOAuthFlow(flow: ZCodeOAuthFlow): ZCodeOAuthPollResult =
        ZCodeOAuthPollResult.Failed(UNAVAILABLE)

    suspend fun fetchStatus(
        token: String,
        deviceMid: String,
        seq: Int = 0,
        previous: ZCodeStatus? = null,
        timeoutMs: Int = 15_000,
    ): Result<ZCodeStatus> =
        // The lite build keeps the full client's pre-flight contract: a blank
        // token is rejected before anything else would be attempted.
        if (token.isBlank()) {
            Result.failure(IllegalArgumentException("Токен ZCode не задан"))
        } else {
            Result.failure(UnsupportedOperationException(UNAVAILABLE))
        }
}
