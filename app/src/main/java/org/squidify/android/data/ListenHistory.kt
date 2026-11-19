package org.squidify.android.data

import android.content.Context
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

/** One entry of the on-device listening history. */
@Serializable
data class HistoryEntry(
    val songId: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
    val coverArtId: String? = null,
    val coverUrl: String? = null,
    val source: DownloadSource = DownloadSource.SQUIDIFY,
    val albumId: String? = null,
    val artistId: String? = null,
    val khiTrackUrl: String? = null,
    val starred: Boolean = false,
    val playedAt: Long,
) {
    fun toPlayerSong(): PlayerSong = PlayerSong(
        id = songId,
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
        starred = starred,
    )

    companion object {
        fun from(song: PlayerSong, playedAt: Long) = HistoryEntry(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            coverArtId = song.coverArtId,
            coverUrl = song.coverUrl,
            source = song.source,
            albumId = song.albumId,
            artistId = song.artistId,
            khiTrackUrl = song.khiTrackUrl,
            starred = song.starred,
            playedAt = playedAt,
        )
    }
}

/**
 * Local listening history: what was actually played on this device,
 * independent of any server-side stats. Kept as a JSON list in
 * Squirtify/UserData when the storage folder is granted.
 */
class ListenHistory(
    @Suppress("unused") private val context: android.content.Context,
    private val storage: UserStorage,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val storeFileName = "history.json"

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    private val ioScope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val MAX_ENTRIES = 400
    }

    init {
        load()
    }

    /** Re-reads the store (e.g. after the storage folder was granted). */
    fun reload() = load()

    private fun load() {
        try {
            val text = storage.readUserData(storeFileName)
            if (text != null) {
                _entries.value =
                    json.decodeFromString(ListSerializer(HistoryEntry.serializer()), text)
            }
        } catch (_: Exception) {
            _entries.value = emptyList()
        }
    }

    /** Records a played track; consecutive repeats collapse into one entry. */
    fun record(song: PlayerSong) {
        val last = _entries.value.firstOrNull()
        if (last != null && last.songId == song.id && last.source == song.source) {
            // refresh the existing entry instead of duplicating it
            val updated = HistoryEntry.from(song, System.currentTimeMillis())
            _entries.value = listOf(updated) + _entries.value.drop(1)
        } else {
            _entries.value = (listOf(HistoryEntry.from(song, System.currentTimeMillis())) + _entries.value)
                .take(MAX_ENTRIES)
        }
        persist()
    }

    fun removeAt(index: Int) {
        val list = _entries.value.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _entries.value = list
            persist()
        }
    }

    fun clear() {
        _entries.value = emptyList()
        persist()
    }

    private fun persist() {
        ioScope.launch {
            try {
                storage.writeUserData(
                    storeFileName,
                    json.encodeToString(ListSerializer(HistoryEntry.serializer()), _entries.value),
                )
            } catch (_: Exception) {
            }
        }
    }
}
