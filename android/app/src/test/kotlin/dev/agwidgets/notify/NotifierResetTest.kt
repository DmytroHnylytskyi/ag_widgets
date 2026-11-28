package dev.agwidgets.notify

import dev.agwidgets.data.QuotaBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM checks for quota-reset notification gating. */
class NotifierResetTest {

    @Test
    fun resetToFullWithEpochZeroSentinelStillNotifies() {
        val prev = QuotaBucket("gemini-5h", "5h", 0.20, "2026-11-25T15:00:00Z")
        val curr = QuotaBucket("gemini-5h", "5h", 1.0, "1970-01-01T00:00:00Z")

        assertTrue(Notifier.shouldNotifyReset(prev, curr))
    }

    @Test
    fun untouchedFullBucketWithEpochZeroDoesNotNotify() {
        val prev = QuotaBucket("3p-5h", "5h", 1.0, "1970-01-01T00:00:00Z")
        val curr = QuotaBucket("3p-5h", "5h", 1.0, "1970-01-01T00:00:00Z")

        assertFalse(Notifier.shouldNotifyReset(prev, curr))
    }

    @Test
    fun clockJitterWhileQuotaDecreasesDoesNotNotify() {
        val prev = QuotaBucket("gemini-5h", "5h", 0.80, "2026-11-25T15:00:00Z")
        val curr = QuotaBucket("gemini-5h", "5h", 0.75, "2026-11-25T15:00:02Z")

        assertFalse(Notifier.shouldNotifyReset(prev, curr))
    }

    @Test
    fun resetWindowAdvanceWithQuotaIncreaseNotifies() {
        val prev = QuotaBucket("gemini-5h", "5h", 0.85, "2026-11-25T15:00:00Z")
        val curr = QuotaBucket("gemini-5h", "5h", 1.0, "2026-11-25T20:00:00Z")

        assertTrue(Notifier.shouldNotifyReset(prev, curr))
    }

    @Test
    fun dedupeKeyPrefersThePreviousResetTime() {
        assertEquals(
            "2026-11-25T15:00:00Z",
            Notifier.resetDedupeKey("2026-11-25T15:00:00Z", "2026-11-25T20:00:00Z"),
        )
    }

    @Test
    fun dedupeKeyFallsBackToTheCurrentResetTime() {
        assertEquals(
            "2026-11-25T20:00:00Z",
            Notifier.resetDedupeKey(null, "2026-11-25T20:00:00Z"),
        )
    }

    @Test
    fun dedupeKeyIsNullWithoutResetTimesSoLaterResetsStillNotify() {
        // The old code used the "changed" sentinel here, which made the first
        // untimed reset silence every later one for that bucket.
        assertNull(Notifier.resetDedupeKey(null, null))
        assertNull(Notifier.resetDedupeKey("", "not-a-time"))
    }
}

