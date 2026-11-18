package org.squidify.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.squidify.android.data.DownloadSource

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
}
