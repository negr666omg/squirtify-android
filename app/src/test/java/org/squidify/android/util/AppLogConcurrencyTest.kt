package org.squidify.android.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class AppLogConcurrencyTest {

    @Test
    fun `concurrent log writers receive unique IDs`() {
        val count = 300
        val tag = "AppLogConcurrencyTest"
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)

        try {
            val futures = (0 until count).map { index ->
                pool.submit {
                    start.await()
                    AppLog.i(tag, "event-$index")
                }
            }
            start.countDown()
            futures.forEach { it.get() }

            val ids = AppLog.entries.value
                .filter { it.tag == tag }
                .map { it.id }

            assertEquals(count, ids.size)
            assertEquals(count, ids.toSet().size)
        } finally {
            pool.shutdownNow()
        }
    }
}
