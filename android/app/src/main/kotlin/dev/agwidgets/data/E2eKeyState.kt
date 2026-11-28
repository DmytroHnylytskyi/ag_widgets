package dev.agwidgets.data

/**
 * What the stored E2E key looks like right now.
 *
 * The distinction matters for security: "no key" means the channel is plaintext
 * by design, while "unreadable" means E2E was enabled and the keystore key is
 * gone (reset, corrupted blob). Collapsing both into `null` made the phone accept
 * *plaintext* status in the second case, so anyone who knows the topic could feed
 * forged quotas. [Unreadable] must fail closed.
 */
sealed interface E2eKeyState {
    /** No key was ever stored: plaintext polling is expected. */
    data object Absent : E2eKeyState

    /** A usable 43-character base64url key is available. */
    data class Present(val key: String) : E2eKeyState

    /** A blob exists but cannot be decrypted or is not a valid key. */
    data object Unreadable : E2eKeyState
}
