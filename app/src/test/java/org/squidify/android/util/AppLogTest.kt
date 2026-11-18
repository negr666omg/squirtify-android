package org.squidify.android.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AppLogTest {
    @Test
    fun `parses a persisted log line using the emitted format`() {
        val entry = AppLog.parseLine("11-18 13:02:03.456 I/ Player: hello world")

        assertNotNull(entry)
        assertEquals(AppLog.Level.INFO, entry.level)
        assertEquals("Player", entry.tag)
        assertEquals("hello world", entry.message)
    }

    @Test
    fun `parses error level and tag containing spaces`() {
        val entry = AppLog.parseLine("11-18 13:02:03.456 E/ Playback Service: IllegalStateException")

        assertNotNull(entry)
        assertEquals(AppLog.Level.ERROR, entry.level)
        assertEquals("Playback Service", entry.tag)
        assertEquals("IllegalStateException", entry.message)
    }
}
