package org.squidify.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.squidify.android.data.DownloadSource
import org.squidify.android.data.DownloadJob

class DownloadsAlbumKeyTest {

    @Test
    fun `same album artist and source share one group`() {
        val first = downloadAlbumKey("Discovery", "Daft Punk", DownloadSource.SQUIDIFY)
        val second = downloadAlbumKey("Discovery", "Daft Punk", DownloadSource.SQUIDIFY)

        assertEquals(first, second)
    }

    @Test
    fun `same album title by different artists stays in separate groups`() {
        val first = downloadAlbumKey("Greatest Hits", "Artist A", DownloadSource.SQUIDIFY)
        val second = downloadAlbumKey("Greatest Hits", "Artist B", DownloadSource.SQUIDIFY)

        assertNotEquals(first, second)
    }

    @Test
    fun `same album and artist from different sources stay separate`() {
        val squidify = downloadAlbumKey("Soundtrack", "Various Artists", DownloadSource.SQUIDIFY)
        val khi = downloadAlbumKey("Soundtrack", "Various Artists", DownloadSource.KHINSIDER)

        assertNotEquals(squidify, khi)
    }

    @Test
    fun `missing album name has a stable fallback`() {
        assertEquals(
            "Artist A",
            downloadAlbumKey(null, "Artist A", DownloadSource.SQUIDIFY).albumName,
        )
        assertEquals(
            "Unknown Album",
            downloadAlbumKey(null, null, DownloadSource.SQUIDIFY).albumName,
        )
    }
    @Test
    fun `same Subsonic album groups tracks with different performers`() {
        val first = downloadJobAlbumKey(
            DownloadJob(
                id = "one",
                source = DownloadSource.SQUIDIFY,
                title = "Track One",
                artist = "Performer A",
                album = "Soundtrack",
                albumId = "album-42",
                requestUrl = "https://example.test/one",
                format = "flac",
                fileName = "01 - Track One.flac",
            ),
        )
        val second = downloadJobAlbumKey(
            DownloadJob(
                id = "two",
                source = DownloadSource.SQUIDIFY,
                title = "Track Two",
                artist = "Performer B",
                album = "Soundtrack",
                albumId = "album-42",
                requestUrl = "https://example.test/two",
                format = "flac",
                fileName = "02 - Track Two.flac",
            ),
        )

        assertEquals(first, second)
    }

    @Test
    fun `same named KHInsider albums remain separate by page URL`() {
        val first = downloadJobAlbumKey(
            DownloadJob(
                id = "one",
                source = DownloadSource.KHINSIDER,
                title = "Track",
                album = "Soundtrack",
                requestUrl = "https://kh.example/game-one/track",
                format = "mp3",
                fileName = "Track.mp3",
            ),
        )
        val second = downloadJobAlbumKey(
            DownloadJob(
                id = "two",
                source = DownloadSource.KHINSIDER,
                title = "Track",
                album = "Soundtrack",
                requestUrl = "https://kh.example/game-two/track",
                format = "mp3",
                fileName = "Track.mp3",
            ),
        )

        assertNotEquals(first, second)
    }

}
