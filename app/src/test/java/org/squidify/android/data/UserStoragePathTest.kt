package org.squidify.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UserStoragePathTest {

    @Test
    fun `dot path components cannot escape the album directory`() {
        assertEquals("Unknown", UserStorage.sanitizeSegment("."))
        assertEquals("Unknown", UserStorage.sanitizeSegment(".."))
        assertEquals("Unknown", UserStorage.sanitizeSegment("  ..  "))
    }

    @Test
    fun `path separators and reserved characters become safe filename characters`() {
        assertEquals("Album_2026", UserStorage.sanitizeSegment("Album/2026"))
        assertEquals("A_B_C", UserStorage.sanitizeSegment("A:B*C"))
    }

    @Test
    fun `album directory names are capped at 120 characters`() {
        assertEquals(120, UserStorage.sanitizeSegment("A".repeat(140)).length)
    }
}
