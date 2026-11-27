package dev.agwidgets.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for the scanner payload sanitiser: no Android or ML Kit
 * classes are touched, only Kotlin stdlib.
 */
class QrScannerTest {

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals(
            "agw://pair?v=1&t=example-topic",
            normalizeScannedPayload(" agw://pair?v=1&t=example-topic "),
        )
    }

    @Test
    fun rejectsBlankAndNull() {
        assertNull(normalizeScannedPayload(""))
        assertNull(normalizeScannedPayload("   "))
        assertNull(normalizeScannedPayload(null))
    }

    @Test
    fun keepsPayloadAtMaxLength() {
        val payload = "x".repeat(QrScanner.MAX_PAYLOAD_LENGTH)
        assertEquals(payload, normalizeScannedPayload(payload))
    }

    @Test
    fun rejectsPayloadOverMaxLength() {
        val payload = "x".repeat(QrScanner.MAX_PAYLOAD_LENGTH + 1)
        assertNull(normalizeScannedPayload(payload))
    }

    @Test
    fun keepsCyrillicIntact() {
        assertEquals("Тема ntfy", normalizeScannedPayload("Тема ntfy"))
    }

    @Test
    fun checksLengthAfterTrim() {
        // 2049 spaces must not survive: the trimmed value is empty.
        assertNull(normalizeScannedPayload(" ".repeat(QrScanner.MAX_PAYLOAD_LENGTH + 1)))
        // One character surrounded by spaces becomes that character.
        assertEquals("x", normalizeScannedPayload(" x "))
    }
}
