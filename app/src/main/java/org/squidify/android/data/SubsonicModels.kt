package org.squidify.android.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Song(
    val id: String,
    val title: String = "",
    val album: String? = null,
    val artist: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverArt: String? = null,
    val duration: Int? = null,
    val bitRate: Int? = null,
    val suffix: String? = null,
    val size: Long? = null,
    val playCount: Int? = null,
    val played: String? = null,
    val starred: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val created: String? = null,
    val isrc: List<String>? = null,
    val bpm: Int? = null,
    /** OpenSubsonic extensions (Navidrome uses "samplingRate", not "sampleRate"). */
    val contentType: String? = null,
    val samplingRate: Int? = null,
    @SerialName("sampleRate") val sampleRateAlias: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
    val path: String? = null,
) {
    /** Effective sample rate in Hz, whichever field the server populated. */
    val sampleRateHz: Int? get() = samplingRate ?: sampleRateAlias

    /** Human-readable source-file format line, e.g. "FLAC · 1411 kbps · 44.1 kHz / 16-bit · stereo". */
    val formatDescription: String
        get() = listOfNotNull(
            suffix?.uppercase(),
            bitRate?.let { "$it kbps" },
            sampleRateHz?.let { if (it % 1000 == 0) "${it / 1000} kHz" else "%.1f kHz".format(it / 1000.0) },
            bitDepth?.let { "$it-bit" },
            channelCount?.let { if (it == 1) "mono" else if (it == 2) "stereo" else "$it ch" },
        ).joinToString(" · ")
}

@Serializable
data class Album(
    val id: String,
    val name: String = "",
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val playCount: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val created: String? = null,
    val starred: String? = null,
)

@Serializable
data class Artist(
    val id: String,
    val name: String = "",
    val coverArt: String? = null,
    val albumCount: Int? = null,
    val starred: String? = null,
)

@Serializable
data class ArtistIndex(
    val name: String = "",
    val artist: List<Artist> = emptyList(),
)

@Serializable
data class Genre(
    val value: String = "",
    val songCount: Int? = null,
    val albumCount: Int? = null,
)

@Serializable
data class Playlist(
    val id: String,
    val name: String = "",
    val songCount: Int? = null,
    val duration: Int? = null,
    val owner: String? = null,
    val created: String? = null,
    val changed: String? = null,
    val public: Boolean? = null,
)

@Serializable
data class StructuredLyrics(
    val lang: String? = null,
    val synced: Boolean = false,
    val line: List<StructuredLine> = emptyList(),
)

@Serializable
data class StructuredLine(
    val value: String = "",
    val start: Long? = null,
)

@Serializable
data class PlayQueueEntry(
    val id: String = "",
    val current: Boolean? = null,
    val position: Long? = null,
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val duration: Int? = null,
    val coverArt: String? = null,
    val suffix: String? = null,
    val bitRate: Int? = null,
    val albumId: String? = null,
    val artistId: String? = null,
)
