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
        // 4×1 / 2×1 cells live below 95dp.
        assertEquals(WidgetLayoutMode.COMPACT, widgetLayoutMode(56f))
        assertEquals(WidgetLayoutMode.COMPACT, widgetLayoutMode(94f))
    }

    @Test
    fun mediumWidgetsUseStandardLayoutWithInclusiveBounds() {
        // Real 4×2 cells are ~110–155dp; 95..185 must stay STANDARD so the
        // third block (ZCode) is not cut by the launcher.
        assertEquals(WidgetLayoutMode.STANDARD, widgetLayoutMode(95f))
        assertEquals(WidgetLayoutMode.STANDARD, widgetLayoutMode(120f))
        assertEquals(WidgetLayoutMode.STANDARD, widgetLayoutMode(155f))
        assertEquals(WidgetLayoutMode.STANDARD, widgetLayoutMode(185f))
    }

    @Test
    fun tallWidgetsUseExpandedLayout() {
        assertEquals(WidgetLayoutMode.EXPANDED, widgetLayoutMode(186f))
        assertEquals(WidgetLayoutMode.EXPANDED, widgetLayoutMode(420f))
    }

    // ---- visibleBlocks ----

    @Test
    fun classicFourByTwoKeepsHeaderQuotasAndZCode() {
        // The regression: a 4×2 landed in COMPACT and lost the ZCode block.
        listOf(120f, 150f).forEach { heightDp ->
            val visible = visibleBlocks(WidgetPresets.classic.config, widgetLayoutMode(heightDp))
                .map { it.id }
            assertEquals(
                "Classic 4×2 at ${heightDp}dp must keep all three blocks",
                listOf(WidgetBlockId.HEADER, WidgetBlockId.QUOTAS, WidgetBlockId.ZCODE),
                visible,
            )
        }
    }

    @Test
    fun compactHeightsStillCutTheTail() {
        val visible = visibleBlocks(WidgetPresets.classic.config, widgetLayoutMode(80f))
        assertEquals(2, visible.size)
        assertEquals(
            listOf(WidgetBlockId.HEADER, WidgetBlockId.QUOTAS),
            visible.map { it.id },
        )
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
