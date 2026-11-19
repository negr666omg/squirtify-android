package org.squidify.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueRepeatTest {

    @Test
    fun repeatAlbumSkipsInterleavedAlbums() {
        val albums = listOf("album-a", "album-b", "album-a", "album-c", "album-a")

        assertEquals(2, nextAlbumQueueIndex(albums, 0))
        assertEquals(4, nextAlbumQueueIndex(albums, 2))
    }

    @Test
    fun repeatAlbumWrapsToFirstMatchingTrack() {
        val albums = listOf("album-a", "album-b", "album-a")

        assertEquals(0, nextAlbumQueueIndex(albums, 2))
    }

    @Test
    fun singleTrackAlbumRepeatsItself() {
        assertEquals(1, nextAlbumQueueIndex(listOf("album-a", "album-b"), 1))
    }

    @Test
    fun invalidCurrentIndexReturnsNull() {
        assertNull(nextAlbumQueueIndex(emptyList(), 0))
        assertNull(nextAlbumQueueIndex(listOf("album-a"), -1))
    }
}
