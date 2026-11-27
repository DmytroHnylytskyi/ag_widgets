package dev.agwidgets.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** ZCode-only storage: encrypted token, device id, direct cache, and poll sequence. */
object ZCodeStore {
    private const val PREFS_NAME = "agw"
    private const val KEY_TOKEN = "zcode_token"
    private const val KEY_DEVICE_MID = "device_mid"
    private const val KEY_DIRECT = "direct_zcode_json"
    private const val KEY_SEQ = "zcode_seq"

    /** Injectable for local JVM tests; the real cipher needs an Android runtime. */
    internal var cipher: SecretCipher = KeystoreSecretCipher()

    private val lock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getToken(context: Context): String = synchronized(lock) {
        val preferences = prefs(context)
        val stored = preferences.getString(KEY_TOKEN, null) ?: return@synchronized ""
        val plaintext = cipher.decrypt(stored)
        if (plaintext == null) {
            // Stored token exists but the keystore key is gone: report it instead of
            // pretending the user is simply signed out.
            StatusStore.markSecretLost(preferences, StatusStore.SECRET_ZCODE)
            return@synchronized ""
        }
        StatusStore.markSecretHealthy(preferences, StatusStore.SECRET_ZCODE)
        val trimmed = plaintext.trim()
        if (trimmed.isNotEmpty() && !cipher.isEncrypted(stored)) {
            runCatching {
                preferences.edit().putString(KEY_TOKEN, cipher.encrypt(trimmed)).apply()
            }.onFailure { safeWarn("ZCodeStore", "Failed to migrate token to encrypted storage", it) }
        }
        trimmed
    }

    fun setToken(context: Context, token: String) {
        val trimmed = token.trim()
        synchronized(lock) {
            val preferences = prefs(context)
            val edit = preferences.edit()
            if (trimmed.isEmpty()) edit.remove(KEY_TOKEN) else edit.putString(KEY_TOKEN, cipher.encrypt(trimmed))
            edit.apply()
            // A saved (or deliberately cleared) token is not a lost secret anymore.
            StatusStore.markSecretHealthy(preferences, StatusStore.SECRET_ZCODE)
        }
    }

    fun clearToken(context: Context) {
        synchronized(lock) {
            val preferences = prefs(context)
            preferences.edit()
                .remove(KEY_TOKEN)
                .remove(KEY_DIRECT)
                .apply()
            StatusStore.markSecretHealthy(preferences, StatusStore.SECRET_ZCODE)
        }
    }

    fun getOrCreateDeviceMid(context: Context): String = synchronized(lock) {
        val preferences = prefs(context)
        val existing = preferences.getString(KEY_DEVICE_MID, null)?.trim()
        if (!existing.isNullOrEmpty()) return@synchronized existing
        val created = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_DEVICE_MID, created).apply()
        created
    }

    fun getDirect(context: Context): ZCodeStatus? {
        val raw = prefs(context).getString(KEY_DIRECT, null) ?: return null
        return try {
            ZCodeStatus.fromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    fun saveDirect(context: Context, status: ZCodeStatus): Pair<ZCodeStatus?, ZCodeStatus> =
        synchronized(lock) {
            val preferences = prefs(context)
            val previousRaw = preferences.getString(KEY_DIRECT, null)
            val previous = previousRaw?.let {
                try {
                    ZCodeStatus.fromJson(JSONObject(it))
                } catch (_: Exception) {
                    null
                }
            }
            preferences.edit().putString(KEY_DIRECT, status.toJson().toString()).apply()
            previous to status
        }

    fun nextSeq(context: Context): Int = synchronized(lock) {
        val preferences = prefs(context)
        val current = preferences.getInt(KEY_SEQ, 0)
        preferences.edit().putInt(KEY_SEQ, (current + 1) and 0x7FFFFFFF).apply()
        current
    }

    /**
     * After a ZCode 429, skip network calls for a few minutes instead of
     * hammering the API from periodic work, widget taps, and manual refreshes.
     * A manual refresh after the cooldown still goes through.
     */
    fun shouldBackoffRateLimit(context: Context, cooldownSec: Long = 300L): Boolean {
        val status = getDirect(context) ?: return false
        if (status.errorKind != "rate_limited") return false
        val checked = parseCheckedAt(status.checkedAt) ?: return false
        val age = Duration.between(checked, Instant.now()).seconds
        return age >= 0 && age < cooldownSec
    }

    private fun parseCheckedAt(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(iso).toInstant()
        } catch (_: Exception) {
            try {
                Instant.parse(iso)
            } catch (_: Exception) {
                null
            }
        }
    }
}
