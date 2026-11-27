package dev.agwidgets.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the internal [StatusStore] seams used by the QR-pairing flow:
 * server normalization, E2E-key storage and the single-commit [PairingConfig]
 * application.
 *
 * Runs on a plain JVM: [FakeSharedPreferences] is in-memory and [FakeCipher]
 * replaces the Keystore-backed cipher.
 */
class StatusStoreTest {

    private val e2eKey = "ULwC33v8c8sB4vAhYxYjl2XImDONcPETgYRMjlLrUsM"

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
            .putString("ntfy_server", "https://ntfy.sh")
            .putString("last_json", PC_JSON)
            .putString("last_error", "boom")
            .putString("notified_resets", "gemini-5h|2026-11-25T04:00:00Z")
            .putString("direct_zcode_json", DIRECT_JSON)
            .apply()
    }

    // ------------------------------------------------------------------
    // applyPairing
    // ------------------------------------------------------------------

    @Test
    fun applyPairingWritesChannelSwitchesToPcAndClearsPcCache() {
        val prefs = populatedPrefs()

        val applied = StatusStore.applyPairing(
            prefs,
            PairingConfig("agw-new", "https://ntfy.example.com", e2eKey),
            FakeCipher,
        )

        assertTrue(applied)
        assertEquals("enc:agw-new", prefs.getString("ntfy_topic", null))
        assertFalse(prefs.getBoolean("ntfy_topic_cleared", true))
        assertEquals("https://ntfy.example.com", prefs.getString("ntfy_server", null))
        assertEquals("enc:$e2eKey", prefs.getString("ntfy_e2e_key", null))
        assertEquals("pc", prefs.getString("mode", null))
        assertNull(prefs.getString("last_json", null))
        assertNull(prefs.getString("last_error", null))
        assertNull(prefs.getString("notified_resets", null))
        // ZCode state is independent of the ntfy channel.
        assertEquals(DIRECT_JSON, prefs.getString("direct_zcode_json", null))
    }

    @Test
    fun applyPairingWithoutKeyRemovesAnyStoredKey() {
        val prefs = populatedPrefs().apply {
            edit().putString("ntfy_e2e_key", "enc:$e2eKey").apply()
        }

        val applied = StatusStore.applyPairing(
            prefs,
            PairingConfig("agw-new", "https://ntfy.example.com", null),
            FakeCipher,
        )

        assertTrue(applied)
        assertNull(prefs.getString("ntfy_e2e_key", null))
        assertEquals("enc:agw-new", prefs.getString("ntfy_topic", null))
        assertEquals("pc", prefs.getString("mode", null))
    }

    @Test
    fun applyPairingRejectsCleartextServerWithoutWriting() {
        val prefs = FakeSharedPreferences()

        val applied = StatusStore.applyPairing(
            prefs,
            PairingConfig("agw-new", "http://x", e2eKey),
            FakeCipher,
        )

        assertFalse(applied)
        assertTrue(prefs.getAll().isEmpty())
    }

    @Test
    fun applyPairingRejectsInvalidTopicWithoutWriting() {
        val prefs = FakeSharedPreferences()

        val applied = StatusStore.applyPairing(
            prefs,
            PairingConfig("bad topic", "https://ntfy.sh", null),
            FakeCipher,
        )

        assertFalse(applied)
        assertTrue(prefs.getAll().isEmpty())
    }

    @Test
    fun applyPairingRejectsInvalidKeyWithoutWriting() {
        val prefs = FakeSharedPreferences()

        val applied = StatusStore.applyPairing(
            prefs,
            PairingConfig("agw-new", "https://ntfy.sh", "*".repeat(43)),
            FakeCipher,
        )

        assertFalse(applied)
        assertTrue(prefs.getAll().isEmpty())
    }

    // ------------------------------------------------------------------
    // ntfy server
    // ------------------------------------------------------------------

    @Test
    fun emptyPreferencesUseTheDefaultServer() {
        assertEquals("https://ntfy.sh", StatusStore.getNtfyServer(FakeSharedPreferences()))
    }

    @Test
    fun storedServerIsNormalisedOnRead() {
        val prefs = FakeSharedPreferences()

        assertTrue(StatusStore.setNtfyServer(prefs, "https://ntfy.sh:443/"))
        assertEquals("https://ntfy.sh", prefs.getString("ntfy_server", null))
        assertEquals("https://ntfy.sh", StatusStore.getNtfyServer(prefs))
    }

    @Test
    fun invalidServerIsRejectedWithoutWriting() {
        val prefs = FakeSharedPreferences()

        assertFalse(StatusStore.setNtfyServer(prefs, "http://x"))
        assertNull(prefs.getString("ntfy_server", null))
    }

    // ------------------------------------------------------------------
    // E2E key
    // ------------------------------------------------------------------

    @Test
    fun e2eKeyRoundTripAndClear() {
        val prefs = FakeSharedPreferences()

        assertTrue(StatusStore.setE2eKey(prefs, e2eKey, FakeCipher))
        assertEquals("enc:$e2eKey", prefs.getString("ntfy_e2e_key", null))
        assertEquals(e2eKey, StatusStore.getE2eKey(prefs, FakeCipher))

        assertTrue(StatusStore.setE2eKey(prefs, null, FakeCipher))
        assertNull(prefs.getString("ntfy_e2e_key", null))
        assertNull(StatusStore.getE2eKey(prefs, FakeCipher))
    }

    @Test
    fun invalidKeyIsRejectedWithoutWriting() {
        val prefs = FakeSharedPreferences()

        assertFalse(StatusStore.setE2eKey(prefs, "short", FakeCipher))
        assertNull(prefs.getString("ntfy_e2e_key", null))
    }

    @Test
    fun corruptStoredKeyReadsAsAbsent() {
        val prefs = FakeSharedPreferences().apply {
            edit().putString("ntfy_e2e_key", "garbage").apply()
        }
        assertNull(StatusStore.getE2eKey(prefs, FakeCipher))

        prefs.edit().putString("ntfy_e2e_key", "enc:short").apply()
        assertNull(StatusStore.getE2eKey(prefs, FakeCipher))
    }

    @Test
    fun clearingTheTopicDropsTheE2eKey() {
        val prefs = populatedPrefs().apply {
            edit().putString("ntfy_e2e_key", "enc:$e2eKey").apply()
        }

        val invalidated = StatusStore.applyTopicChange(prefs, "", FakeCipher)

        assertTrue(invalidated)
        assertNull(prefs.getString("ntfy_e2e_key", null))
        assertNull(prefs.getString("ntfy_topic", null))
        assertTrue(prefs.getBoolean("ntfy_topic_cleared", false))
    }

    private companion object {
        const val PC_JSON =
            """{"updated_at": "2026-11-25T03:00:00+02:00", "antigravity": {"plan_tier": "Pro", "buckets": []}}"""

        const val DIRECT_JSON =
            """{"running": true, "coupon_state": "visible", "coupon_visible": true, "matched": "500M", "plan_id": "pro-2500", "checked_at": "2026-11-25T03:10:00+02:00"}"""
    }
}
