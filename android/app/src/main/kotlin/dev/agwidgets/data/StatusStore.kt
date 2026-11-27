package dev.agwidgets.data

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import dev.agwidgets.BuildConfig
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Core app storage shared by AG Widgets and AG Context: encrypted ntfy topic,
 * last PC status, reset notifications, theme, and the selected usage mode.
 *
 * ZCode-specific secrets live in [ZCodeStore], which exists only in the full
 * flavor (the context flavor has a no-op stub).
 */
object StatusStore {
    private const val PREFS_NAME = "agw"
    private const val KEY_TOPIC = "ntfy_topic"
    private const val KEY_TOPIC_CLEARED = "ntfy_topic_cleared"
    private const val KEY_LAST_JSON = "last_json"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_NOTIFIED_RESETS = "notified_resets"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_MODE = "mode"
    private const val KEY_COUPON_NOTIFIED = "coupon_notified"

    private const val MAX_NOTIFIED_RESETS = 30
    private const val RESET_ENTRY_SEPARATOR = "\n"

    const val MODE_UNSET = "unset"
    const val MODE_PC = "pc"
    const val MODE_ZCODE = "zcode"

    /** Injectable for local JVM tests; the real cipher needs an Android runtime. */
    internal var cipher: SecretCipher = KeystoreSecretCipher()

    /** Guards read-modify-write sequences on [SharedPreferences]. */
    private val lock = Any()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getTopic(context: Context): String = synchronized(lock) {
        val preferences = prefs(context)
        val stored = preferences.getString(KEY_TOPIC, null)
        if (stored != null) {
            val plaintext = cipher.decrypt(stored) ?: return@synchronized ""
            val trimmed = plaintext.trim()
            if (trimmed.isNotEmpty() && !cipher.isEncrypted(stored)) {
                // Transparent migration of topics written by older releases.
                runCatching {
                    preferences.edit().putString(KEY_TOPIC, cipher.encrypt(trimmed)).apply()
                }.onFailure { safeWarn("StatusStore", "Failed to migrate topic to encrypted storage", it) }
            }
            return@synchronized trimmed
        }
        // The user explicitly cleared the topic: never resurrect the baked-in default.
        if (preferences.getBoolean(KEY_TOPIC_CLEARED, false)) return@synchronized ""
        BuildConfig.AGW_TOPIC
    }

    /**
     * Applies a topic change to [preferences] in a single editor commit.
     *
     * PC-derived cache ([KEY_LAST_JSON], [KEY_LAST_ERROR], [KEY_NOTIFIED_RESETS])
     * belongs to exactly one topic, so it is dropped whenever the topic is
     * cleared or actually changes. ZCode secrets are never touched: they are
     * independent of the ntfy channel.
     *
     * @return `true` when the PC cache was invalidated, `false` when the topic
     *   was re-saved unchanged (the cache stays valid).
     */
    internal fun applyTopicChange(
        preferences: SharedPreferences,
        topic: String,
        cipher: SecretCipher,
        builtInDefault: String = BuildConfig.AGW_TOPIC,
    ): Boolean {
        val stored = preferences.getString(KEY_TOPIC, null)
        val previousTopic = when {
            stored != null -> cipher.decrypt(stored)?.trim().orEmpty()
            preferences.getBoolean(KEY_TOPIC_CLEARED, false) -> ""
            else -> builtInDefault.trim()
        }
        val trimmed = topic.trim()
        if (trimmed.isEmpty()) {
            preferences.edit()
                .remove(KEY_TOPIC)
                .putBoolean(KEY_TOPIC_CLEARED, true)
                .remove(KEY_LAST_JSON)
                .remove(KEY_LAST_ERROR)
                .remove(KEY_NOTIFIED_RESETS)
                .apply()
            return true
        }
        val changed = trimmed != previousTopic
        val edit = preferences.edit()
            .putString(KEY_TOPIC, cipher.encrypt(trimmed))
            .putBoolean(KEY_TOPIC_CLEARED, false)
        if (changed) {
            edit.remove(KEY_LAST_JSON)
                .remove(KEY_LAST_ERROR)
                .remove(KEY_NOTIFIED_RESETS)
        }
        edit.apply()
        return changed
    }

    fun setTopic(context: Context, topic: String) {
        synchronized(lock) {
            applyTopicChange(prefs(context), topic, cipher)
        }
    }

    /** Drops the PC-derived cache (status, error, notified resets) but keeps ZCode. */
    fun clearPcCache(context: Context) {
        synchronized(lock) {
            prefs(context).edit()
                .remove(KEY_LAST_JSON)
                .remove(KEY_LAST_ERROR)
                .remove(KEY_NOTIFIED_RESETS)
                .apply()
        }
    }

    /**
     * How the app is used: with a PC (Antigravity + optional ZCode) or ZCode only.
     * Existing installs are inferred from saved data so they skip onboarding.
     */
    fun getMode(context: Context): String {
        val saved = prefs(context).getString(KEY_MODE, null)?.lowercase()
        if (saved == MODE_PC || saved == MODE_ZCODE || saved == MODE_UNSET) return saved
        if (getTopic(context).isNotBlank()) return MODE_PC
        if (BuildConfig.ZCODE_ENABLED && ZCodeStore.getToken(context).isNotBlank()) return MODE_ZCODE
        return MODE_UNSET
    }

    fun setMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_MODE, mode.lowercase()).apply()
    }

    fun getLastStatus(context: Context): AgStatus? {
        val raw = prefs(context).getString(KEY_LAST_JSON, null) ?: return null
        return AgStatus.fromJson(raw)
    }

    /** PC status with the fresher phone-side ZCode check taking precedence. */
    fun getMergedStatus(context: Context): AgStatus? =
        mergeStatus(getLastStatus(context), ZCodeStore.getDirect(context))

    fun saveNewStatus(context: Context, rawJson: String): Pair<AgStatus?, AgStatus?> =
        synchronized(lock) {
            val preferences = prefs(context)
            val previousRaw = preferences.getString(KEY_LAST_JSON, null)
            preferences.edit()
                .putString(KEY_LAST_JSON, rawJson)
                .remove(KEY_LAST_ERROR)
                .apply()
            previousRaw?.let { AgStatus.fromJson(it) } to AgStatus.fromJson(rawJson)
        }

    fun isResetNotified(context: Context, bucketId: String, resetTime: String): Boolean {
        val key = "$bucketId|$resetTime"
        val preferences = prefs(context)
        val stored = try {
            preferences.getString(KEY_NOTIFIED_RESETS, null)
        } catch (_: ClassCastException) {
            null
        }
        if (stored != null) {
            return stored.split(RESET_ENTRY_SEPARATOR).any { it == key }
        }
        val legacy = try {
            preferences.getStringSet(KEY_NOTIFIED_RESETS, null)
        } catch (_: ClassCastException) {
            null
        }
        return legacy?.contains(key) == true
    }

    fun markResetNotified(context: Context, bucketId: String, resetTime: String) {
        val key = "$bucketId|$resetTime"
        synchronized(lock) {
            val preferences = prefs(context)
            val entries = readNotifiedResets(preferences)
            val updated = (entries.filterNot { it == key } + key).takeLast(MAX_NOTIFIED_RESETS)
            // Replaces a legacy StringSet value with the ordered new format.
            preferences.edit()
                .putString(KEY_NOTIFIED_RESETS, updated.joinToString(RESET_ENTRY_SEPARATOR))
                .apply()
        }
    }

    fun getLastNotifiedCoupon(context: Context): String? =
        prefs(context).getString(KEY_COUPON_NOTIFIED, null)

    fun markCouponNotified(context: Context, key: String) {
        synchronized(lock) {
            prefs(context).edit().putString(KEY_COUPON_NOTIFIED, key).apply()
        }
    }

    fun registerListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun getThemeMode(context: Context): String =
        prefs(context).getString(KEY_THEME_MODE, "system")?.lowercase() ?: "system"

    fun setThemeMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode.lowercase()).apply()
    }

    fun isDarkTheme(context: Context): Boolean = when (getThemeMode(context)) {
        "light" -> false
        "dark" -> true
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun saveError(context: Context, error: String) {
        prefs(context).edit().putString(KEY_LAST_ERROR, error).apply()
    }

    fun getLastError(context: Context): String? =
        prefs(context).getString(KEY_LAST_ERROR, null)

    /**
     * Reads the notified-reset list in the ordered string format, falling back
     * to the legacy StringSet written by older releases.
     */
    private fun readNotifiedResets(preferences: SharedPreferences): List<String> {
        val stored = try {
            preferences.getString(KEY_NOTIFIED_RESETS, null)
        } catch (_: ClassCastException) {
            null
        }
        if (stored != null) {
            return stored.split(RESET_ENTRY_SEPARATOR).filter { it.isNotEmpty() }
        }
        val legacy = try {
            preferences.getStringSet(KEY_NOTIFIED_RESETS, null)
        } catch (_: ClassCastException) {
            null
        }
        return legacy.orEmpty().filter { it.isNotEmpty() }
    }
}

/** Pure merge used by [StatusStore.getMergedStatus] and by unit tests. */
internal fun mergeStatus(pc: AgStatus?, directZCode: ZCodeStatus?): AgStatus? {
    if (pc == null && directZCode == null) return null
    if (pc == null) return AgStatus(
        updatedAt = directZCode?.checkedAt,
        planTier = null,
        buckets = emptyList(),
        zcode = directZCode,
        contextSummary = null,
        rawJson = directZCode?.toJson()?.toString(2) ?: "{}",
        pcUpdatedAt = null,
    )
    if (directZCode == null) return pc
    val pcZ = pc.zcode
    val pcTime = parseCheckedAt(pcZ?.checkedAt)
    val directTime = parseCheckedAt(directZCode.checkedAt)
    val useDirect = pcZ == null || (directTime != null && (pcTime == null || directTime >= pcTime))
    val mergedZ = if (useDirect) directZCode else pcZ
    // pc.updatedAt and pc.pcUpdatedAt must not be replaced with directZCode time.
    val mergedRaw = buildMergedRaw(pc.rawJson, mergedZ)
    return pc.copy(zcode = mergedZ, rawJson = mergedRaw)
}

/** Parses `checked_at` timestamps without depending on the UI layer. */
private fun parseCheckedAt(iso: String?): Instant? {
    if (iso.isNullOrEmpty()) return null
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

/** Keeps the raw diagnostics document in sync with the merged ZCode state. */
private fun buildMergedRaw(raw: String, zcode: ZCodeStatus?): String = try {
    JSONObject(raw).apply {
        put("zcode", zcode?.toJson() ?: JSONObject.NULL)
    }.toString(2)
} catch (_: Exception) {
    raw
}
