package dev.agwidgets.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The keystore race is what these tests are about: two cipher instances (the
 * `StatusStore` one and the `ZCodeStore` one) can ask for the same alias at the
 * same moment on the first run after an upgrade. AndroidKeyStore overwrites a
 * key when it is generated for an existing alias, so a second generation would
 * make the first ciphertext permanently unreadable. The cache must therefore
 * call the loader exactly once per alias, even under concurrency.
 */
class SecretKeyCacheTest {

    @After
    fun tearDown() {
        SecretKeyCache.clear()
    }

    private fun key(seed: Byte): SecretKey = SecretKeySpec(ByteArray(32) { seed }, "AES")

    @Test
    fun concurrentFirstUseLoadsOneKeyPerAlias() {
        val loads = AtomicInteger(0)
        val start = CountDownLatch(1)
        val keys = arrayOfNulls<SecretKey>(8)

        val threads = (0 until keys.size).map { index ->
            Thread {
                start.await()
                keys[index] = SecretKeyCache.getOrLoad("race-alias") {
                    loads.incrementAndGet()
                    Thread.sleep(10) // Widen the window a real keystore call would have.
                    key(7)
                }
            }.also(Thread::start)
        }
        start.countDown()
        threads.forEach(Thread::join)

        assertEquals("the loader must run once per alias", 1, loads.get())
        val first = keys.first()
        keys.forEach { assertSame("every caller gets the cached instance", first, it) }
    }

    @Test
    fun differentAliasesAreCachedIndependently() {
        val first = SecretKeyCache.getOrLoad("alias-a") { key(1) }
        val second = SecretKeyCache.getOrLoad("alias-b") { key(2) }

        assertEquals(1.toByte(), first.encoded[0])
        assertEquals(2.toByte(), second.encoded[0])
    }

    @Test
    fun clearForcesAReload() {
        val loads = AtomicInteger(0)
        val loader = { loads.incrementAndGet(); key(3) }

        SecretKeyCache.getOrLoad("reload-alias", loader)
        SecretKeyCache.getOrLoad("reload-alias", loader)
        assertEquals(1, loads.get())

        SecretKeyCache.clear()
        SecretKeyCache.getOrLoad("reload-alias", loader)
        assertEquals(2, loads.get())
    }
}
