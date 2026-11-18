package org.squidify.android.data

import kotlinx.serialization.Serializable

/** Navidrome native /api/song model (subset of fields we actually use). */
@Serializable
data class NativeSong(
    val id: String = "",
    val title: String = "",
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val suffix: String? = null,
    val bitRate: Int? = null,
    val bitDepth: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val duration: Double? = null,
    val size: Long? = null,
    val playCount: Int? = null,
    val playDate: String? = null,
    val starredAt: String? = null,
    val isrc: List<String>? = null,
    val rgAlbumGain: Double? = null,
    val rgAlbumPeak: Double? = null,
    val rgTrackGain: Double? = null,
    val rgTrackPeak: Double? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
) {
    fun toSong(): Song = Song(
        id = id,
        title = title,
        album = album,
        albumId = albumId,
        artist = artist,
        artistId = artistId,
        suffix = suffix,
        bitRate = bitRate,
        duration = duration?.toInt(),
        size = size,
        playCount = playCount,
        played = playDate,
        starred = starredAt,
        isrc = isrc,
        track = trackNumber,
        discNumber = discNumber,
        year = year,
        genre = genre,
        coverArt = albumId?.let { "al-$it" },
    )
}

@Serializable
data class NativeAlbum(
    val id: String = "",
    val name: String = "",
    val albumArtist: String? = null,
    val albumArtistId: String? = null,
    val minYear: Int? = null,
    val maxYear: Int? = null,
    val duration: Double? = null,
    val genre: String? = null,
    val playCount: Int? = null,
    val playDate: String? = null,
    val createdAt: String? = null,
    val imageHash: String? = null,
    val dominantColor: String? = null,
    val compilation: Boolean? = null,
) {
    fun toAlbum(): Album = Album(
        id = id,
        name = name,
        artist = albumArtist,
        artistId = albumArtistId,
        year = maxYear ?: minYear,
        duration = duration?.toInt(),
        genre = genre,
        playCount = playCount,
        created = createdAt,
        coverArt = "al-$id",
    )
}
