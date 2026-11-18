package org.squidify.android.data

import kotlinx.serialization.Serializable

@Serializable
data class KhiSearch(
    val items: List<KhiAlbumSummary> = emptyList(),
    val totalMatches: Int? = null,
)

@Serializable
data class KhiAlbumSummary(
    val title: String = "",
    val id: String = "",
    val icon: String? = null,
    val url: String = "",
    val albumId: String? = null,
    val albumType: String? = null,
    val year: String? = null,
)

@Serializable
data class KhiAlbum(
    val name: String = "",
    val year: String? = null,
    val albumType: String? = null,
    val coverUrl: String? = null,
    /** All cover images of the album, in original quality/format. */
    val albumImages: List<String> = emptyList(),
    val totalFilesize: String? = null,
    val availableFormats: List<String> = emptyList(),
    val tracks: List<KhiTrack> = emptyList(),
    val publisher: String? = null,
    val catalogNumber: String? = null,
    val description: String? = null,
    val platforms: List<String> = emptyList(),
    val composers: String? = null,
    val dateAdded: String? = null,
)

@Serializable
data class KhiTrack(
    val number: Int = 0,
    val title: String = "",
    val duration: String? = null,
    val fileSize: String? = null,
    val bitrate: String? = null,
    val url: String = "",
)

@Serializable
data class KhiResolve(
    val mp3: String? = null,
    val flac: String? = null,
    val directUrl: String? = null,
    /** File size in bytes, as reported by the resolver (string-typed in JSON). */
    val size: String? = null,
    val type: String? = null,
    val acceptsRanges: Boolean? = null,
) {
    fun urlFor(format: String): String? = when (format.lowercase()) {
        "flac" -> flac ?: directUrl
        "mp3" -> mp3 ?: directUrl
        else -> directUrl ?: mp3 ?: flac
    }

    val sizeBytes: Long? get() = size?.toLongOrNull()
}
