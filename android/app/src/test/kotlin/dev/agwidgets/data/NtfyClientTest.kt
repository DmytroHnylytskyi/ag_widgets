package dev.agwidgets.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for [NtfyClient]'s E2E-aware payload picker, including the
 * anti-downgrade rule: with a key configured, a plaintext status is not trusted.
 *
 * Pure JVM: only line parsing and `javax.crypto` are involved.
 */
class NtfyClientTest {

    private val statusJson = """{"updated_at":"2026-11-26T14:05:00Z"}"""
    private val e2eKey = "ULwC33v8c8sB4vAhYxYjl2XImDONcPETgYRMjlLrUsM"
    private val wrongKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    private fun ndjson(payload: String, id: String = "1"): String = JSONObject().apply {
        put("id", id)
        put("event", "message")
        put("title", "ag-widgets-status")
        put("message", payload)
    }.toString()

    @Test
    fun plaintextIsAcceptedWhenNoKeyIsConfigured() {
        val lines = listOf(ndjson(statusJson))

        val result = NtfyClient.extractLatestStatusPayload(lines, null)

        assertTrue(result.isSuccess)
        assertEquals(statusJson, result.getOrThrow())
    }

    @Test
    fun plaintextIsRejectedWhenAKeyIsConfigured() {
        val lines = listOf(ndjson(statusJson))

        val result = NtfyClient.extractLatestStatusPayload(lines, e2eKey)

        assertTrue(result.isFailure)
        assertEquals(
            "Ожидалось зашифрованное сообщение (E2E)",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun envelopeWithoutKeyIsRejectedWithQrHint() {
        val envelope = E2eCrypto.encryptEnvelope(statusJson, e2eKey)
        val lines = listOf(ndjson(envelope))

        val result = NtfyClient.extractLatestStatusPayload(lines, null)

        assertTrue(result.isFailure)
        assertEquals(
            "Сообщение зашифровано — отсканируйте QR-код с ПК",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun envelopeWithWrongKeyIsRejected() {
        val envelope = E2eCrypto.encryptEnvelope(statusJson, e2eKey)
        val lines = listOf(ndjson(envelope))

        val result = NtfyClient.extractLatestStatusPayload(lines, wrongKey)

        assertTrue(result.isFailure)
        assertEquals(
            "Неверный E2E-ключ — отсканируйте новый QR с ПК",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun envelopeWithCorrectKeyIsDecrypted() {
        val envelope = E2eCrypto.encryptEnvelope(statusJson, e2eKey)
        val lines = listOf(ndjson(envelope))

        val result = NtfyClient.extractLatestStatusPayload(lines, e2eKey)

        assertTrue(result.isSuccess)
        assertEquals(statusJson, result.getOrThrow())
    }

    @Test
    fun legacyExtractorKeepsPreE2eBehavior() {
        val plainLines = listOf(ndjson(statusJson))
        assertEquals(statusJson, NtfyClient.extractLatestStatusPayload(plainLines))

        val envelope = E2eCrypto.encryptEnvelope(statusJson, e2eKey)
        val envelopeLines = listOf(ndjson(envelope))
        assertNull(NtfyClient.extractLatestStatusPayload(envelopeLines))
    }

    @Test
    fun unrelatedNonStatusMessageAfterEnvelopeIsSkipped() {
        val envelope = E2eCrypto.encryptEnvelope(statusJson, e2eKey)
        val noise = JSONObject().apply {
            put("id", "2")
            put("event", "message")
            put("message", "not a status json")
        }.toString()
        val lines = listOf(ndjson(envelope, "1"), noise)

        val result = NtfyClient.extractLatestStatusPayload(lines, e2eKey)

        assertTrue(result.isSuccess)
        assertEquals(statusJson, result.getOrThrow())
    }

    @Test
    fun emptyLineListFailsBothOverloads() {
        val e2eResult = NtfyClient.extractLatestStatusPayload(emptyList(), e2eKey)
        assertTrue(e2eResult.isFailure)
        assertEquals(
            "Нет сообщений статуса в теме ntfy",
            e2eResult.exceptionOrNull()?.message,
        )
        assertNull(NtfyClient.extractLatestStatusPayload(emptyList()))
    }
}
