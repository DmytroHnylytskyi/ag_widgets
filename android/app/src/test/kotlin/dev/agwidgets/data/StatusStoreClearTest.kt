package dev.agwidgets.data

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for [StatusStore.applyTopicChange]: the PC-derived cache belongs to a
 * single ntfy topic and must be dropped whenever that topic is cleared or
 * actually changes, while ZCode secrets survive untouched.
 *
 * Runs on a plain JVM: [FakeSharedPreferences] is an in-memory implementation
 * and [FakeCipher] replaces the Keystore-backed cipher.
 */
class StatusStoreClearTest {

    private object FakeCipher : SecretCipher {
        override fun encrypt(plaintext: String): String = PREFIX + plaintext

        override fun decrypt(stored: String): String? =
            if (stored.startsWith(PREFIX)) stored.removePrefix(PREFIX) else stored

        override fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

        private const val PREFIX = "enc:"
    }

    /** Minimal in-memory [SharedPreferences]; `apply()` mutates immediately. */
    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = HashMap(values)

        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
            val stored = values[key]
            @Suppress("UNCHECKED_CAST")
            return if (stored is MutableSet<*>) stored as MutableSet<String> else defValues
        }

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {
            listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) {
            listeners.remove(listener)
        }

        private inner class Editor : SharedPreferences.Editor {
            private val ops = LinkedHashMap<String, Any?>()
            private var cleared = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor =
                putValue(key, value)

            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
                putValue(key, values)

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = putValue(key, value)

            override fun putLong(key: String, value: Long): SharedPreferences.Editor = putValue(key, value)

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = putValue(key, value)

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
                putValue(key, value)

            override fun remove(key: String): SharedPreferences.Editor {
                ops[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                ops.clear()
                cleared = true
                return this
            }

            override fun commit(): Boolean {
                applyNow()
                return true
            }

            override fun apply() = applyNow()

            private fun putValue(key: String, value: Any?): SharedPreferences.Editor {
                ops[key] = value
                return this
            }

            private fun applyNow() {
                if (cleared) values.clear()
                for ((key, value) in ops) {
                    if (value == null) values.remove(key) else values[key] = value
                }
                ops.clear()
                cleared = false
            }
        }
    }

    private fun populatedPrefs(): FakeSharedPreferences = FakeSharedPreferences().apply {
        edit()
            .putString("ntfy_topic", "enc:agw-old")
            .putBoolean("ntfy_topic_cleared", false)
            .putString("last_json", PC_JSON)
            .putString("last_error", "boom")
            .putString("notified_resets", "gemini-5h|2026-11-25T04:00:00Z")
            .putString("zcode_token", "enc:tok")
            .putString("direct_zcode_json", DIRECT_JSON)
            .apply()
    }

    @Test
    fun clearingTopicWipesPcCacheButKeepsZCodeSecrets() {
        val prefs = populatedPrefs()

        val invalidated = StatusStore.applyTopicChange(prefs, "", FakeCipher, "agw-default")

        assertTrue(invalidated)
        assertNull(prefs.getString("ntfy_topic", null))
        assertNull(prefs.getString("last_json", null))
        assertNull(prefs.getString("last_error", null))
        assertNull(prefs.getString("notified_resets", null))
        assertTrue(prefs.getBoolean("ntfy_topic_cleared", false))
        // ZCode state is independent of the ntfy channel.
        assertEquals("enc:tok", prefs.getString("zcode_token", null))
        assertEquals(DIRECT_JSON, prefs.getString("direct_zcode_json", null))
    }

    @Test
    fun changingTopicEncryptsNewValueAndWipesPcCache() {
        val prefs = populatedPrefs()

        val invalidated = StatusStore.applyTopicChange(prefs, "agw-b", FakeCipher, "agw-default")

        assertTrue(invalidated)
        assertEquals("enc:agw-b", prefs.getString("ntfy_topic", null))
        assertFalse(prefs.getBoolean("ntfy_topic_cleared", true))
        assertNull(prefs.getString("last_json", null))
        assertNull(prefs.getString("last_error", null))
        assertNull(prefs.getString("notified_resets", null))
        assertEquals("enc:tok", prefs.getString("zcode_token", null))
    }

    @Test
    fun resavingSameTopicKeepsCacheAndReportsNoChange() {
        val prefs = FakeSharedPreferences().apply {
            edit()
                .putString("ntfy_topic", "enc:agw-a")
                .putBoolean("ntfy_topic_cleared", false)
                .putString("last_json", PC_JSON)
                .putString("last_error", "boom")
                .apply()
        }

        val invalidated = StatusStore.applyTopicChange(prefs, " agw-a ", FakeCipher, "agw-default")

        assertFalse(invalidated)
        assertEquals(PC_JSON, prefs.getString("last_json", null))
        assertEquals("boom", prefs.getString("last_error", null))
        assertFalse(prefs.getBoolean("ntfy_topic_cleared", true))
        assertEquals("enc:agw-a", prefs.getString("ntfy_topic", null))
    }

    @Test
    fun mergeStillRendersZCodeAfterPcCacheClear() {
        val prefs = populatedPrefs()
        StatusStore.applyTopicChange(prefs, "", FakeCipher, "agw-default")

        val pc = prefs.getString("last_json", null)?.let { AgStatus.fromJson(it) }
        assertNull(pc)

        val direct = ZCodeStatus(
            running = true,
            couponVisible = true,
            matched = "500M",
            planId = "pro-2500",
            method = "api",
            authOk = true,
            remainingUnits = 250_000_000L,
            totalUnits = 500_000_000L,
            checkedAt = "2026-11-25T03:10:00+02:00",
        )

        val merged = mergeStatus(pc, direct)

        assertNotNull(merged)
        val status = merged!!
        assertTrue(status.buckets.isEmpty())
        assertNull(status.contextSummary)
        assertNull(status.planTier)
        assertEquals(direct, status.zcode)
        assertNotNull(status.zcode)
        assertEquals("visible", JSONObject(status.rawJson).getString("coupon_state"))
    }

    private companion object {
        const val PC_JSON =
            """{"updated_at": "2026-11-25T03:00:00+02:00", "antigravity": {"plan_tier": "Pro", "buckets": []}}"""

        const val DIRECT_JSON =
            """{"running": true, "coupon_state": "visible", "coupon_visible": true, "matched": "500M", "plan_id": "pro-2500", "checked_at": "2026-11-25T03:10:00+02:00"}"""
    }
}
