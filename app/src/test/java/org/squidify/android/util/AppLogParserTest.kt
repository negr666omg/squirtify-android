package org.squidify.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppLogParserTest {

    @Test
    fun `parses persisted log entries with correct severity tag and message`() {
        val entry = AppLog.parseLine("11-18 14:21:45.678 I/ Player: stream started")

        assertNotNull(entry)
        assertEquals(AppLog.Level.INFO, entry!!.level)
        assertEquals("Player", entry.tag)
        assertEquals("stream started", entry.message)
    }

    @Test
    fun `parses each supported severity`() {
        val expected = mapOf(
            "D" to AppLog.Level.DEBUG,
            "I" to AppLog.Level.INFO,
            "W" to AppLog.Level.WARN,
            "E" to AppLog.Level.ERROR,
        )

        expected.forEach { (letter, level) ->
            val entry = AppLog.parseLine("11-18 14:21:45.678 $letter/ Test: message")
            assertEquals(level, entry?.level)
        }
    }

    @Test
    fun `rejects malformed log lines`() {
        assertNull(AppLog.parseLine("not a log line"))
        assertNull(AppLog.parseLine("11-18 14:21:45.678 X/ Test: message"))
    }
}
