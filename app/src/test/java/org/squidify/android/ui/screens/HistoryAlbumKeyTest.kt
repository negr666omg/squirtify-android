package org.squidify.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.squidify.android.data.DownloadSource
import org.squidify.android.data.HistoryEntry

class HistoryAlbumKeyTest {

    private fun khiEntry(trackUrl: String, album: String = "Greatest Hits") = HistoryEntry(
        songId = "khi:$trackUrl",
        title = "Track",
        album = album,
        source = DownloadSource.KHINSIDER,
        khiTrackUrl = trackUrl,
        playedAt = 1L,
    )

    @Test
    fun `tracks from one KHInsider album share a recent album key`() {
        val first = khiEntry("https://example.test/soundtrack/album/track-one")
        val second = khiEntry("https://example.test/soundtrack/album/track-two")

        assertEquals(historyAlbumKey(first), historyAlbumKey(second))
    }

    @Test
    fun `different KHInsider albums with same display name stay separate`() {
        val first = khiEntry("https://example.test/game-a/album/track", "Greatest Hits")
        val second = khiEntry("https://example.test/game-b/album/track", "Greatest Hits")

        assertNotEquals(historyAlbumKey(first), historyAlbumKey(second))
    }

    @Test
    fun `Squidify recent albums prefer stable album id`() {
        val first = HistoryEntry(
            songId = "song-a",
            title = "Track A",
            album = "Same name",
            source = DownloadSource.SQUIDIFY,
            albumId = "album-a",
            playedAt = 1L,
        )
        val second = first.copy(songId = "song-b", albumId = "album-b")

        assertNotEquals(historyAlbumKey(first), historyAlbumKey(second))
    }
}
