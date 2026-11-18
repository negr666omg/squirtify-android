package org.squidify.android.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.squidify.android.data.DownloadJob
import org.squidify.android.data.DownloadSource
import org.squidify.android.data.DownloadStatus
import org.squidify.android.player.PlayerSong

class DownloadStatusSelectionTest {

    @Test
    fun `KHInsider status matches exact track URL not duplicate title`() {
        val current = PlayerSong.fromKhi(
            albumName = "Album",
            trackTitle = "Opening",
            trackUrl = "https://example.test/album/track-2",
            durationText = "1:30",
            coverUrl = null,
        )
        val differentTrack = DownloadJob(
            id = "other",
            source = DownloadSource.KHINSIDER,
            title = "Opening",
            requestUrl = "https://example.test/album/track-1",
            format = "mp3",
            fileName = "Opening.mp3",
            status = DownloadStatus.DONE,
        )

        assertNull(downloadStatusFor(current, listOf(differentTrack)))
    }

    @Test
    fun `KHInsider status finds matching track even when another same-title track exists`() {
        val current = PlayerSong.fromKhi(
            albumName = "Album",
            trackTitle = "Opening",
            trackUrl = "https://example.test/album/track-2",
            durationText = "1:30",
            coverUrl = null,
        )
        val jobs = listOf(
            DownloadJob(
                id = "track-1",
                source = DownloadSource.KHINSIDER,
                title = "Opening",
                requestUrl = "https://example.test/album/track-1",
                format = "mp3",
                fileName = "Opening.mp3",
                status = DownloadStatus.DONE,
            ),
            DownloadJob(
                id = "track-2",
                source = DownloadSource.KHINSIDER,
                title = "Opening",
                requestUrl = "https://example.test/album/track-2",
                format = "flac",
                fileName = "Opening.flac",
                status = DownloadStatus.RUNNING,
            ),
        )

        assertEquals(DownloadStatus.RUNNING, downloadStatusFor(current, jobs))
    }

    @Test
    fun `active download wins over stale failed job for same song`() {
        val song = PlayerSong(
            id = "song-1",
            title = "Track",
            artist = "Artist",
            album = "Album",
            durationMs = 90_000,
        )
        val jobs = listOf(
            DownloadJob(
                id = "old",
                source = DownloadSource.SQUIDIFY,
                songId = "song-1",
                title = "Track",
                requestUrl = "https://example.test/old",
                format = "flac",
                fileName = "Track.flac",
                status = DownloadStatus.FAILED,
            ),
            DownloadJob(
                id = "new",
                source = DownloadSource.SQUIDIFY,
                songId = "song-1",
                title = "Track",
                requestUrl = "https://example.test/new",
                format = "mp3",
                fileName = "Track.mp3",
                status = DownloadStatus.QUEUED,
            ),
        )

        assertEquals(DownloadStatus.QUEUED, downloadStatusFor(song, jobs))
    }

    @Test
    fun `cover art jobs are not reported as track downloads`() {
        val song = PlayerSong.fromKhi(
            albumName = "Album",
            trackTitle = "Track",
            trackUrl = "https://example.test/album/track",
            durationText = "1:30",
            coverUrl = null,
        )
        val cover = DownloadJob(
            id = "cover",
            source = DownloadSource.KHINSIDER,
            title = "cover.jpg",
            requestUrl = "https://example.test/album/track",
            format = "jpg",
            fileName = "cover.jpg",
            status = DownloadStatus.DONE,
            kind = "image",
        )

        assertNull(downloadStatusFor(song, listOf(cover)))
    }
}
