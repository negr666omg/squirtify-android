package org.squidify.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persisted album metadata used to rebuild the Downloads screen without a
 * network connection. Audio itself remains in DownloadManager's file registry.
 */
@Serializable
data class OfflineAlbumSnapshot(
    val key: String,
    val source: DownloadSource,
    val albumName: String,
    val artist: String? = null,
    val coverUrl: String? = null,
    val albumId: String? = null,
    val albumUrl: String? = null,
    val tracks: List<Song> = emptyList(),
    val khiTracks: List<KhiTrack> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val trackCount: Int
        get() = if (source == DownloadSource.SQUIDIFY) tracks.size else khiTracks.size
}

class OfflineLibraryRepository(private val storage: UserStorage) {
    private val json = Json { ignoreUnknownKeys = true }
    private val storeName = "offline_albums.json"
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _albums = MutableStateFlow(loadFromDisk())
    val albums: StateFlow<List<OfflineAlbumSnapshot>> = _albums.asStateFlow()

    @Synchronized
    fun cacheSquidify(album: Album, songs: List<Song>, coverUrl: String?) {
        if (songs.isEmpty()) return
        upsert(
            OfflineAlbumSnapshot(
                key = "sq:${album.id}",
                source = DownloadSource.SQUIDIFY,
                albumName = album.name,
                artist = album.artist,
                coverUrl = coverUrl,
                albumId = album.id,
                tracks = songs,
            ),
        )
    }

    @Synchronized
    fun cacheKhi(albumUrl: String, album: KhiAlbum, coverUrl: String?) {
        if (album.tracks.isEmpty()) return
        upsert(
            OfflineAlbumSnapshot(
                key = "khi:$albumUrl",
                source = DownloadSource.KHINSIDER,
                albumName = album.name,
                coverUrl = coverUrl,
                albumUrl = albumUrl,
                khiTracks = album.tracks,
            ),
        )
    }

    @Synchronized
    private fun upsert(snapshot: OfflineAlbumSnapshot) {
        val updated = (listOf(snapshot) + _albums.value.filterNot { it.key == snapshot.key })
            .sortedByDescending { it.updatedAt }
            .take(150)
        _albums.value = updated
        val encoded = json.encodeToString(ListSerializer(OfflineAlbumSnapshot.serializer()), updated)
        ioScope.launch { runCatching { storage.writeUserData(storeName, encoded) } }
    }

    @Synchronized
    fun reload() {
        _albums.value = loadFromDisk()
    }

    private fun loadFromDisk(): List<OfflineAlbumSnapshot> = runCatching {
        val text = storage.readUserData(storeName) ?: return emptyList()
        json.decodeFromString(ListSerializer(OfflineAlbumSnapshot.serializer()), text)
    }.getOrDefault(emptyList())
}
