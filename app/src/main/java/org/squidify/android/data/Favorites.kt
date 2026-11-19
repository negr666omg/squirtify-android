package org.squidify.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.squidify.android.player.PlayerSong
import org.squidify.android.util.AppLog

/** Locally stored favourite song snapshot — works for Squirtify and KHInsider. */
@Serializable
data class FavSong(
    val id: String,
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
    val coverArtId: String? = null,
    val coverUrl: String? = null,
    val source: DownloadSource = DownloadSource.SQUIDIFY,
    val albumId: String? = null,
    val artistId: String? = null,
    val khiTrackUrl: String? = null,
    val addedAt: Long = 0,
) {
    fun toPlayerSong(): PlayerSong = PlayerSong(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverArtId = coverArtId,
        coverUrl = coverUrl,
        source = source,
        albumId = albumId,
        artistId = artistId,
        khiTrackUrl = khiTrackUrl,
    )

    companion object {
        fun from(song: Song, now: Long) = FavSong(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = (song.duration ?: 0) * 1000L,
            coverArtId = song.coverArt ?: song.albumId?.let { "al-$it" },
            source = DownloadSource.SQUIDIFY,
            albumId = song.albumId,
            artistId = song.artistId,
            addedAt = now,
        )

        fun from(song: PlayerSong, now: Long) = FavSong(
            id = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            coverArtId = song.coverArtId,
            coverUrl = song.coverUrl,
            source = song.source,
            albumId = song.albumId,
            artistId = song.artistId,
            khiTrackUrl = song.khiTrackUrl ?: song.khiDirectUrl,
            addedAt = now,
        )
    }
}

/** Locally stored favourite album snapshot. */
@Serializable
data class FavAlbum(
    val id: String,
    val name: String = "",
    val artist: String? = null,
    val artistId: String? = null,
    val coverArtId: String? = null,
    val year: Int? = null,
    val songCount: Int? = null,
    val addedAt: Long = 0,
)

/**
 * Favourites are fully local: a JSON file next to the other user data
 * (Squirtify/UserData/favorites.json when the storage tree is granted).
 * No server round-trips — hearts work offline and for KHInsider tracks too.
 */
class FavoritesRepository(
    private val storage: UserStorage,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _songs = MutableStateFlow<List<FavSong>>(emptyList())
    val songs: StateFlow<List<FavSong>> = _songs.asStateFlow()

    private val _albums = MutableStateFlow<List<FavAlbum>>(emptyList())
    val albums: StateFlow<List<FavAlbum>> = _albums.asStateFlow()

    /** Ids of favourite songs — cheap to collect in the UI. */
    private val _songIds = MutableStateFlow<Set<String>>(emptySet())
    val songIds: StateFlow<Set<String>> = _songIds.asStateFlow()

    private val _albumIds = MutableStateFlow<Set<String>>(emptySet())
    val albumIds: StateFlow<Set<String>> = _albumIds.asStateFlow()

    private fun syncDerived() {
        _songIds.value = _songs.value.map { it.id }.toSet()
        _albumIds.value = _albums.value.map { it.id }.toSet()
    }

    private val ioScope = CoroutineScope(Dispatchers.IO)

    init {
        load()
    }

    fun reload() = load()

    private fun load() {
        try {
            val text = storage.readUserData(FILE)
            if (text != null) {
                _songs.value = json.decodeFromString(ListSerializer(FavSong.serializer()), text)
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not load favourites: ${e.message}")
        }
        try {
            val text = storage.readUserData(FILE_ALBUMS)
            if (text != null) {
                _albums.value = json.decodeFromString(ListSerializer(FavAlbum.serializer()), text)
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not load favourite albums: ${e.message}")
        }
        syncDerived()
    }

    private fun persist() {
        ioScope.launch {
            runCatching {
                storage.writeUserData(FILE, json.encodeToString(ListSerializer(FavSong.serializer()), _songs.value))
            }
            runCatching {
                storage.writeUserData(FILE_ALBUMS, json.encodeToString(ListSerializer(FavAlbum.serializer()), _albums.value))
            }
        }
    }

    // ------------------------------------------------------------- queries

    fun isSongFavorite(id: String): Boolean = _songs.value.any { it.id == id }
    fun isAlbumFavorite(id: String): Boolean = _albums.value.any { it.id == id }

    // ------------------------------------------------------------- toggles

    /** Toggles a song; returns the NEW favourite state. */
    fun toggleSong(song: Song): Boolean {
        if (isSongFavorite(song.id)) {
            _songs.value = _songs.value.filterNot { it.id == song.id }
        } else {
            _songs.value = listOf(FavSong.from(song, System.currentTimeMillis())) + _songs.value
        }
        syncDerived()
        persist()
        return isSongFavorite(song.id)
    }

    fun toggleSong(song: PlayerSong): Boolean {
        if (isSongFavorite(song.id)) {
            _songs.value = _songs.value.filterNot { it.id == song.id }
        } else {
            _songs.value = listOf(FavSong.from(song, System.currentTimeMillis())) + _songs.value
        }
        syncDerived()
        persist()
        return isSongFavorite(song.id)
    }

    /** Toggles an album; returns the NEW favourite state. */
    fun toggleAlbum(album: Album): Boolean {
        if (isAlbumFavorite(album.id)) {
            _albums.value = _albums.value.filterNot { it.id == album.id }
        } else {
            _albums.value = listOf(
                FavAlbum(
                    id = album.id,
                    name = album.name,
                    artist = album.artist,
                    artistId = album.artistId,
                    coverArtId = album.coverArt ?: album.id.let { "al-$it" },
                    year = album.year,
                    songCount = album.songCount,
                    addedAt = System.currentTimeMillis(),
                ),
            ) + _albums.value
        }
        syncDerived()
        persist()
        return isAlbumFavorite(album.id)
    }

    fun clearSongs() {
        _songs.value = emptyList()
        syncDerived()
        persist()
    }

    fun clearAlbums() {
        _albums.value = emptyList()
        syncDerived()
        persist()
    }

    companion object {
        private const val TAG = "Favorites"
        private const val FILE = "favorites.json"
        private const val FILE_ALBUMS = "favorite-albums.json"
    }
}
