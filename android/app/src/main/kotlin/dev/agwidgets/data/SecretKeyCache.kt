package dev.agwidgets.data

import javax.crypto.SecretKey

/**
 * Process-wide cache of `AndroidKeyStore` keys, keyed by alias.
 *
 * `KeystoreSecretCipher.secretKey()` is check-then-act: it looks the entry up
 * and, when it is missing, generates a new one. `AndroidKeyStore` *replaces* the
 * entry when a key with that alias already exists, and the app has more than one
 * cipher instance (`StatusStore` and `ZCodeStore` each own one, with separate
 * locks). On the first run after an upgrade the topic migration (UI thread) and
 * the token migration (`RefreshWorker`) could therefore both see "no key",
 * generate one each, and the second generation silently invalidated every blob
 * encrypted with the first — the user lost the ntfy topic, the ZCode token or
 * the E2E key for good.
 *
 * One loader per alias, shared by the whole process, removes that race. It also
 * removes the `KeyStore.load()` call from every read: the widget receiver reads
 * the topic on each update, in the main thread.
 */
internal object SecretKeyCache {
    private val lock = Any()
    private val keys = HashMap<String, SecretKey>()

    /** Return the cached key for [alias] or load it exactly once. */
    fun getOrLoad(alias: String, load: () -> SecretKey): SecretKey = synchronized(lock) {
        keys[alias] ?: load().also { keys[alias] = it }
    }

    /** Drop every cached key; the next access loads from the keystore again. */
    fun clear() {
        synchronized(lock) {
            keys.clear()
        }
    }
}
