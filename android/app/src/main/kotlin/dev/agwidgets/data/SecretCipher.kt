package dev.agwidgets.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts secrets at rest. Implementations must never throw on corrupt input:
 * [decrypt] returns `null` instead so callers can show an honest error state.
 */
interface SecretCipher {
    fun encrypt(plaintext: String): String

    /** Returns `null` when the stored blob cannot be decrypted. */
    fun decrypt(stored: String): String?

    fun isEncrypted(stored: String): Boolean

    companion object {
        const val PREFIX = "ks:v1:"
    }
}

/**
 * AES-256-GCM backed by [android.security.keystore.AndroidKeyStore] — no extra
 * Gradle dependencies. Plaintext values written by older releases are returned
 * unchanged from [decrypt] so they can be migrated transparently.
 */
class KeystoreSecretCipher(
    private val alias: String = DEFAULT_ALIAS,
) : SecretCipher {

    override fun isEncrypted(stored: String): Boolean = stored.startsWith(SecretCipher.PREFIX)

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return SecretCipher.PREFIX + encode(cipher.iv) + ":" + encode(ciphertext)
    }

    override fun decrypt(stored: String): String? {
        if (!isEncrypted(stored)) return stored
        return try {
            val parts = stored.removePrefix(SecretCipher.PREFIX).split(":")
            require(parts.size == 2) { "Malformed secret blob" }
            val iv = decode(parts[0])
            val ciphertext = decode(parts[1])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            safeWarn("Unable to decrypt stored secret", e)
            null
        }
    }

    private fun secretKey(): SecretKey = SecretKeyCache.getOrLoad(alias) {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    /** `android.util.Log` is not mocked in local JVM tests; never let it crash. */
    private fun safeWarn(message: String, error: Throwable) {
        runCatching { Log.w(TAG, message, error) }
    }

    companion object {
        const val DEFAULT_ALIAS = "agw_secrets_aes_gcm_v1"
        private const val TAG = "SecretCipher"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
    }
}
