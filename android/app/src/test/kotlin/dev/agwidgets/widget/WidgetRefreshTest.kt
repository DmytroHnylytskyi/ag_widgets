package dev.agwidgets.widget

import dev.agwidgets.data.NtfyClient
import dev.agwidgets.data.ZCodeClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only contract for the widget refresh path: the height buckets that drive
 * the adaptive layout and the pre-flight guards of both network clients.
 * No Android, Context or WorkManager APIs are involved and nothing hits the
 * network: every call below fails on validation before opening a connection.
 */
class WidgetRefreshTest {

    // ---- widgetLayoutMode ----

    @Test
    fun shortWidgetsUseCompactLayout() {
        assertEquals(WidgetLayoutMode.COMPACT, widgetLayoutMode(110f))
        assertEquals(WidgetLayoutMode.COMPACT, widgetLayoutMode(164f))
    }

    @Test
    fun mediumWidgetsUseStandardLayoutWithInclusiveBounds() {
        assertEquals(WidgetLayoutMode.STANDARD, widgetLayoutMode(165f))
        assertEquals(WidgetLayoutMode.STANDARD, widgetLayoutMode(215f))
    }

    @Test
    fun tallWidgetsUseExpandedLayout() {
        assertEquals(WidgetLayoutMode.EXPANDED, widgetLayoutMode(216f))
        assertEquals(WidgetLayoutMode.EXPANDED, widgetLayoutMode(420f))
    }

    // ---- RefreshWorker timeout plumbing (client validation only) ----

    @Test
    fun ntfyInvalidTopicFailsWithoutNetwork() {
        val result = runBlocking {
            NtfyClient.fetchLatestMessage("bad topic", timeoutMs = 4_000)
        }

        assertTrue("Expected failure for an invalid topic", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Expected IllegalArgumentException, got $error", error is IllegalArgumentException)
    }

    @Test
    fun ntfySingleArgumentCallStillCompilesWithDefaultTimeout() {
        // The default `timeoutMs = 15_000` must keep every existing one-argument
        // caller source-compatible.
        val result = runBlocking { NtfyClient.fetchLatestMessage("bad topic") }

        assertTrue("Expected failure for an invalid topic", result.isFailure)
    }

    @Test
    fun zcodeBlankTokenFailsWithoutNetwork() {
        val result = runBlocking {
            ZCodeClient.fetchStatus("", "mid", timeoutMs = 4_000)
        }

        assertTrue("Expected failure for a blank token", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Expected IllegalArgumentException, got $error", error is IllegalArgumentException)
    }
}
