package org.squidify.android.player

import org.squidify.android.data.DownloadSource
import org.squidify.android.data.KhiQuality
import org.squidify.android.data.Song
import org.squidify.android.data.StreamQuality

/** Track in the playback queue; unifies Squidify songs and KHInsider tracks. */
data class PlayerSong(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val coverArtId: String? = null,      // Subsonic coverArt id (Squidify)
    val coverUrl: String? = null,        // direct/proxied image URL (KHInsider)
    val source: DownloadSource = DownloadSource.SQUIDIFY,
    val albumId: String? = null,
    val artistId: String? = null,
    /** KHInsider track page URL (resolved lazily to a direct URL). */
    val khiTrackUrl: String? = null,
    /** Cached direct URL (after resolve). */
    val khiDirectUrl: String? = null,
    /** Whether the song is starred/favourite on the server (Squidify). */
    val starred: Boolean = false,
) {
    companion object {
        fun from(song: Song): PlayerSong = PlayerSong(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = (song.duration ?: 0) * 1000L,
            coverArtId = song.coverArt ?: song.albumId?.let { "al-$it" },
            source = DownloadSource.SQUIDIFY,
            albumId = song.albumId,
            artistId = song.artistId,
            starred = song.starred != null,
        )

        fun fromKhi(
            albumName: String,
            trackTitle: String,
            trackUrl: String,
            durationText: String?,
            coverUrl: String?,
        ): PlayerSong {
            val secs = parseDuration(durationText)
            return PlayerSong(
                id = "khi:$trackUrl",
                title = trackTitle,
                artist = albumName,
                album = albumName,
                durationMs = secs * 1000L,
                coverUrl = coverUrl,
                source = DownloadSource.KHINSIDER,
                khiTrackUrl = trackUrl,
            )
        }

        fun parseDuration(text: String?): Long {
            if (text.isNullOrBlank()) return 0
            var secs = 0L
            for (part in text.split(':')) {
                val v = part.trim().toLongOrNull() ?: return secs
                secs = secs * 60 + v
            }
            return secs
        }
    }
}
