package dev.agwidgets.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Cross-platform contract for [E2eCrypto], replayed against the shared golden
 * fixture `tests/fixtures/e2e_pairing_cases.json` (the same vectors the Python
 * collector and the desktop bridge use).
 *
 * Plain JVM: the crypto and URI rules are `java.*`/`javax.crypto` only.
 */
class E2ePairingTest {

    private val fixtureRoot: File by lazy { fixturesDir() }

    private val fixture: JSONObject by lazy {
        val file = File(fixtureRoot, FIXTURE_NAME)
        if (!file.isFile) fail("Fixture file not found: ${file.absolutePath}")
        JSONObject(file.readText(Charsets.UTF_8))
    }

    // --- fixture loading --------------------------------------------------

    private fun fixturesDir(): File {
        val base = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            "../../tests/fixtures",
            "../tests/fixtures",
            "tests/fixtures",
            "../../../tests/fixtures",
        ).map { File(base, it) }
        return candidates.firstOrNull { it.isDirectory }
            ?: run {
                fail(
                    "Fixture directory not found from ${base.absolutePath}. Looked in:\n" +
                        candidates.joinToString("\n") { it.absolutePath },
                )
                error("unreachable")
            }
    }

    // --- AES-GCM vectors --------------------------------------------------

    @Test
    fun aesGcmVectorsMatchEncryptionAndDecryption() {
        val vectors = fixture.getJSONArray("aes_gcm_vectors")
        assertEquals(6, vectors.length())

        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            val name = vector.getString("name")
            val key = vector.getString("key_b64")
            val expectedIv = vector.getString("iv_b64")
            val expectedCt = vector.getString("ct_b64")
            val envelope = vector.getJSONObject("envelope")

            assertEquals("$name: envelope iv", expectedIv, envelope.getString("iv"))
            assertEquals("$name: envelope ct", expectedCt, envelope.getString("ct"))

            val bytes = hexToBytes(vector.getString("plaintext_hex"))
            val text = String(bytes, Charsets.UTF_8)
            val isText = text.toByteArray(Charsets.UTF_8).contentEquals(bytes)
            if (isText) {
                // Encryption is pinned to the fixture for every text vector,
                // including the empty NIST plaintext (tag-only ciphertext).
                val encrypted = JSONObject(
                    E2eCrypto.encryptEnvelope(text, key, base64UrlDecode(expectedIv)),
                )
                assertEquals("$name: iv", expectedIv, encrypted.getString("iv"))
                assertEquals("$name: ct", expectedCt, encrypted.getString("ct"))
            }

            val decrypted = E2eCrypto.decryptEnvelope(envelope.toString(), key)
            // Authenticates with the production AAD/IV/key: a wrong AAD, IV or
            // tag layout would make this fail. A payload of exactly 16 bytes is
            // the empty plaintext from NIST TC13 and is valid on the wire.
            assertTrue(
                "$name: fixture envelope must authenticate: ${decrypted.exceptionOrNull()}",
                decrypted.isSuccess,
            )
            if (isText) {
                assertEquals("$name: plaintext", text, decrypted.getOrThrow())
            }
            // Two NIST vectors are raw binary, which the text-only production
            // API cannot re-encrypt; the authenticated decrypt above still
            // pins the wire format (AAD, IV, key, tag) to the fixture.
        }
    }

    @Test
    fun tamperedEnvelopesFailToDecrypt() {
        val cases = fixture.getJSONArray("aes_gcm_tamper_cases")
        assertEquals(8, cases.length())

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            assertTrue("$name: fixture expects an error", case.getBoolean("expect_error"))
            val result = E2eCrypto.decryptEnvelope(
                case.getJSONObject("envelope").toString(),
                case.getString("key_b64"),
            )
            assertTrue("$name: tampered envelope must not decrypt", result.isFailure)
        }
    }

    // --- Keys -------------------------------------------------------------

    @Test
    fun canonicalKeyShapeIsAcceptedAndMalformedKeysRejected() {
        val vectors = fixture.getJSONArray("aes_gcm_vectors")
        for (index in 0 until vectors.length()) {
            val key = vectors.getJSONObject(index).getString("key_b64")
            assertEquals(43, key.length)
            assertTrue("vector key $key", E2eCrypto.isValidKey(key))
        }

        val canonical = "A".repeat(43)
        assertTrue(canonical, E2eCrypto.isValidKey(canonical))

        val rejected = listOf(
            "A".repeat(42),
            "A".repeat(44),
            "*".repeat(43),
            "+".repeat(43),
            "/".repeat(43),
            "=".repeat(43),
            " ",
            "",
            null,
        )
        for (value in rejected) {
            assertFalse("must reject ${value?.let { "'$it'" } ?: "null"}", E2eCrypto.isValidKey(value))
        }
    }

    // --- Server URLs ------------------------------------------------------

    @Test
    fun serverNormalisationMatchesFixture() {
        val cases = fixture.getJSONObject("server_url_cases")

        val valid = cases.getJSONArray("valid")
        assertEquals(5, valid.length())
        for (index in 0 until valid.length()) {
            val case = valid.getJSONObject(index)
            assertEquals(
                "normalize(${case.getString("input")})",
                case.getString("expected"),
                E2eCrypto.normalizeNtfyServer(case.getString("input")),
            )
        }

        val invalid = cases.getJSONArray("invalid")
        assertEquals(9, invalid.length())
        for (index in 0 until invalid.length()) {
            val raw = invalid.getString(index)
            assertNull("must reject '$raw'", E2eCrypto.normalizeNtfyServer(raw))
        }
    }

    // --- Pairing URI ------------------------------------------------------

    @Test
    fun validPairingUrisParseToExpectedFields() {
        val cases = fixture.getJSONObject("pairing_uri_cases").getJSONArray("valid")
        assertEquals(6, cases.length())

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val uri = case.getString("uri")
            val parsed = E2eCrypto.parsePairingUri(uri)
            assertTrue("$uri must parse: ${parsed.exceptionOrNull()}", parsed.isSuccess)
            val config = parsed.getOrThrow()
            assertEquals("$uri: topic", case.getString("topic"), config.topic)
            assertEquals("$uri: server", case.getString("server"), config.server)
            if (case.isNull("e2e_key")) {
                assertNull("$uri: key", config.e2eKey)
            } else {
                assertEquals("$uri: key", case.getString("e2e_key"), config.e2eKey)
            }
        }
    }

    @Test
    fun invalidPairingUrisAreRejected() {
        val cases = fixture.getJSONObject("pairing_uri_cases").getJSONArray("invalid")
        assertEquals(17, cases.length())

        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val uri = case.getString("uri")
            val reason = case.getString("reason")
            assertTrue(
                "'$uri' ($reason) must be rejected",
                E2eCrypto.parsePairingUri(uri).isFailure,
            )
        }
    }

    // --- Display helpers --------------------------------------------------

    @Test
    fun maskTopicNeverRevealsTheWholeTopic() {
        assertEquals("••••", E2eCrypto.maskTopic(""))
        assertEquals("agw-••••1234", E2eCrypto.maskTopic("agw-7f3a1234"))

        val short = E2eCrypto.maskTopic("abcdef")
        assertFalse("short topic leaked in full", short.contains("abcdef"))
        assertEquals("ab••••ef", short)
    }

    @Test
    fun encryptedEnvelopeDetection() {
        val envelope = E2eCrypto.encryptEnvelope(
            """{"updated_at":"2026-11-26T14:05:00Z"}""",
            "ULwC33v8c8sB4vAhYxYjl2XImDONcPETgYRMjlLrUsM",
        )
        assertTrue(E2eCrypto.isEncryptedEnvelope(envelope))
        assertFalse(E2eCrypto.isEncryptedEnvelope("""{"updated_at":"2026-11-26T14:05:00Z"}"""))
        assertFalse(E2eCrypto.isEncryptedEnvelope("not json"))
        assertFalse(E2eCrypto.isEncryptedEnvelope(""))
    }

    // --- helpers ----------------------------------------------------------

    private fun hexToBytes(hex: String): ByteArray {
        if (hex.isEmpty()) return ByteArray(0)
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun base64UrlDecode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private companion object {
        const val FIXTURE_NAME = "e2e_pairing_cases.json"
    }
}
