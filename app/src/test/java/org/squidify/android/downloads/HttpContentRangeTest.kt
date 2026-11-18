package org.squidify.android.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpContentRangeTest {

    @Test
    fun `parses a complete byte range`() {
        assertEquals(
            HttpContentRange(start = 100L, endInclusive = 199L, total = 1000L),
            parseHttpContentRange("bytes 100-199/1000"),
        )
    }

    @Test
    fun `parses range with unknown total`() {
        assertEquals(
            HttpContentRange(start = 0L, endInclusive = 99L, total = null),
            parseHttpContentRange("bytes 0-99/*"),
        )
    }

    @Test
    fun `rejects malformed and impossible ranges`() {
        assertNull(parseHttpContentRange(null))
        assertNull(parseHttpContentRange("items 0-10/100"))
        assertNull(parseHttpContentRange("bytes 20-10/100"))
        assertNull(parseHttpContentRange("bytes 0-100/100"))
    }
}
