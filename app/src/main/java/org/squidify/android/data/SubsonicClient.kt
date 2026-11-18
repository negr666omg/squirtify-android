package org.squidify.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.squidify.android.util.AppLog
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class SubsonicApiException(message: String) : Exception(message)

/**
 * Client for the squidify.org Navidrome server (Subsonic API 1.16.1 + OpenSubsonic),
 * authenticating exactly like the web app: user "Guest" with a token/salt pair.
 */
class SubsonicClient(
    private val http: OkHttpClient,
    val serverUrl: String = DEFAULT_SERVER,
    private val username: String = DEFAULT_USERNAME,
    private val password: String = DEFAULT_PASSWORD,
) {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun authParams(): List<Pair<String, String>> {
        val salt = UUID.randomUUID().toString().replace("-", "").take(16)
        val token = md5Hex(password + salt)
        return listOf(
            "u" to username,
            "t" to token,
            "s" to salt,
            "v" to API_VERSION,
            "c" to CLIENT_NAME,
            "f" to "json",
        )
    }

    fun url(path: String, params: List<Pair<String, String>>): String {
        val base = serverUrl.toHttpUrl().newBuilder().apply {
            encodedPath(path)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return base.toString()
    }

    // ---------------------------------------------------------------- URLs

    fun streamUrl(songId: String, quality: StreamQuality, timeOffsetMs: Long = 0L): String {
        val p = authParams().toMutableList()
        p += "id" to songId
        quality.maxBitRate?.let { p += "maxBitRate" to it.toString() }
        quality.format?.let { p += "format" to it }
        // Transcoded streams are served without HTTP Range support, so seeking
        // is implemented by re-requesting the stream at a time offset.
        if (timeOffsetMs > 0) p += "timeOffset" to (timeOffsetMs / 1000L).toString()
        // NOTE: no per-call log — building a queue calls this once per track
        // (176 lines in one second looked like "streaming the whole queue").
        // The REAL network opens are logged by PlaybackIO when a stream starts.
        return url("/rest/stream", p)
    }

    /**
     * Cover-art URL. [size] is the bounding box in px; 0 = original artwork
     * straight from the file (maximum quality).
     */
    fun coverUrl(coverArtId: String?, size: Int = 600): String? {
        if (coverArtId.isNullOrBlank()) return null
        val salt = "cover-v1"
        val token = md5Hex(password + salt)
        val p = mutableListOf(
            "u" to username,
            "t" to token,
            "s" to salt,
            "v" to API_VERSION,
            "c" to CLIENT_NAME,
            "id" to coverArtId,
        )
        if (size > 0) p += "size" to size.toString()
        return url("/rest/getCoverArt", p)
    }

    // ---------------------------------------------------------------- core

    private suspend fun <T> get(
        path: String,
        params: List<Pair<String, String>>,
        extract: (JsonObject) -> T,
    ): T = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val req = Request.Builder()
            .url(url(path, authParams() + params))
            .header("Accept", "application/json")
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.e(TAG, "$path → HTTP ${resp.code} (${System.currentTimeMillis() - started} ms)")
                    throw SubsonicApiException("HTTP ${resp.code}")
                }
                val body = resp.body?.string() ?: throw SubsonicApiException("Empty body")
                val root = json.parseToJsonElement(body).jsonObject
                    .getOrElse("subsonic-response") { throw SubsonicApiException("Malformed response") }
                    .jsonObject
                val status = root["status"]?.jsonPrimitive?.contentOrNull
                if (status == "failed") {
                    val msg = (root["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: "Subsonic error"
                    val code = (root["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull ?: "?"
                    AppLog.e(TAG, "$path → Subsonic error $code: $msg")
                    throw SubsonicApiException(msg)
                }
                AppLog.d(TAG, "$path ok (${System.currentTimeMillis() - started} ms)")
                extract(root)
            }
        } catch (e: SubsonicApiException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "$path failed after ${System.currentTimeMillis() - started} ms: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }


    // ---------------------------------------------------------------- calls

    data class PingInfo(
        val version: String?,
        val serverVersion: String?,
        val openSubsonic: Boolean,
    )

    suspend fun ping(): PingInfo = get("/rest/ping", emptyList()) { root ->
        PingInfo(
            version = root["version"]?.jsonPrimitive?.contentOrNull,
            serverVersion = root["serverVersion"]?.jsonPrimitive?.contentOrNull,
            openSubsonic = root["openSubsonic"]?.jsonPrimitive?.contentOrNull == "true",
        )
    }

    suspend fun getArtists(): List<ArtistIndex> = get("/rest/getArtists", emptyList()) { root ->
        val container = root["artists"]?.jsonObject ?: return@get emptyList()
        json.decodeFromJsonElement(
            ListSerializer(ArtistIndex.serializer()),
            container["index"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList()),
        )
    }

    suspend fun getArtist(id: String): Pair<Artist, List<Album>> =
        get("/rest/getArtist", listOf("id" to id)) { root ->
            val a = root["artist"]?.jsonObject ?: throw SubsonicApiException("No artist")
            val artist = json.decodeFromJsonElement(Artist.serializer(), a)
            val albums = a["album"]?.jsonArray?.map {
                json.decodeFromJsonElement(Album.serializer(), it)
            } ?: emptyList()
            artist to albums
        }

    suspend fun getGenres(): List<Genre> = get("/rest/getGenres", emptyList()) { root ->
        (root["genres"]?.jsonObject?.get("genre")?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList()))
            .map { json.decodeFromJsonElement(Genre.serializer(), it) }
    }

    suspend fun getAlbumList2(
        type: String,
        size: Int = 24,
        offset: Int = 0,
        genre: String? = null,
        fromYear: Int? = null,
        toYear: Int? = null,
    ): List<Album> {
        val p = mutableListOf("type" to type, "size" to size.toString(), "offset" to offset.toString())
        genre?.let { p += "genre" to it }
        fromYear?.let { p += "fromYear" to it.toString() }
        toYear?.let { p += "toYear" to it.toString() }
        return get("/rest/getAlbumList2", p) { root ->
            (root["albumList2"]?.jsonObject?.get("album")?.jsonArray
                ?: kotlinx.serialization.json.JsonArray(emptyList()))
                .map { json.decodeFromJsonElement(Album.serializer(), it) }
        }
    }

    suspend fun getAlbum(id: String): Pair<Album, List<Song>> =
        get("/rest/getAlbum", listOf("id" to id)) { root ->
            val a = root["album"]?.jsonObject ?: throw SubsonicApiException("No album")
            val album = json.decodeFromJsonElement(Album.serializer(), a)
            val songs = a["song"]?.jsonArray?.map {
                json.decodeFromJsonElement(Song.serializer(), it)
            } ?: emptyList()
            album to songs
        }

    /** Full metadata of one song, incl. the source-file format details. */
    suspend fun getSong(id: String): Song =
        get("/rest/getSong", listOf("id" to id)) { root ->
            val s = root["song"]?.jsonObject ?: throw SubsonicApiException("No song with id '$id'")
            json.decodeFromJsonElement(Song.serializer(), s)
        }

    suspend fun getRandomSongs(size: Int = 20): List<Song> =
        get("/rest/getRandomSongs", listOf("size" to size.toString())) { root ->
            (root["randomSongs"]?.jsonObject?.get("song")?.jsonArray
                ?: kotlinx.serialization.json.JsonArray(emptyList()))
                .map { json.decodeFromJsonElement(Song.serializer(), it) }
        }

    suspend fun search3(
        query: String,
        artistCount: Int = 12,
        albumCount: Int = 24,
        songCount: Int = 40,
    ): Triple<List<Artist>, List<Album>, List<Song>> =
        get(
            "/rest/search3",
            listOf(
                "query" to query,
                "artistCount" to artistCount.toString(),
                "albumCount" to albumCount.toString(),
                "songCount" to songCount.toString(),
                "albumOffset" to "0",
                "artistOffset" to "0",
                "songOffset" to "0",
            ),
        ) { root ->
            val r = root["searchResult3"]?.jsonObject ?: return@get Triple(emptyList(), emptyList(), emptyList())
            fun <T> lst(key: String, ser: kotlinx.serialization.KSerializer<T>): List<T> =
                r[key]?.jsonArray?.map { json.decodeFromJsonElement(ser, it) } ?: emptyList()
            Triple(
                lst("artist", Artist.serializer()),
                lst("album", Album.serializer()),
                lst("song", Song.serializer()),
            )
        }

    data class StarredAll(
        val artists: List<Artist>,
        val albums: List<Album>,
        val songs: List<Song>,
    )

    suspend fun getStarred2(): StarredAll = get("/rest/getStarred2", emptyList()) { root ->
        val r = root["starred2"]?.jsonObject ?: return@get StarredAll(emptyList(), emptyList(), emptyList())
        fun <T> lst(key: String, ser: kotlinx.serialization.KSerializer<T>): List<T> =
            r[key]?.jsonArray?.map { json.decodeFromJsonElement(ser, it) } ?: emptyList()
        StarredAll(
            lst("artist", Artist.serializer()),
            lst("album", Album.serializer()),
            lst("song", Song.serializer()),
        )
    }

    suspend fun starSong(id: String) = get("/rest/star", listOf("id" to id)) { }
    suspend fun unstarSong(id: String) = get("/rest/unstar", listOf("id" to id)) { }
    suspend fun starAlbum(id: String) = get("/rest/star", listOf("albumId" to id)) { }
    suspend fun unstarAlbum(id: String) = get("/rest/unstar", listOf("albumId" to id)) { }
    suspend fun starArtist(id: String) = get("/rest/star", listOf("artistId" to id)) { }
    suspend fun unstarArtist(id: String) = get("/rest/unstar", listOf("artistId" to id)) { }

    suspend fun scrobble(id: String) = get("/rest/scrobble", listOf("id" to id, "submission" to "true")) { }

    suspend fun getPlaylists(): List<Playlist> = get("/rest/getPlaylists", emptyList()) { root ->
        (root["playlists"]?.jsonObject?.get("playlist")?.jsonArray
            ?: kotlinx.serialization.json.JsonArray(emptyList()))
            .map { json.decodeFromJsonElement(Playlist.serializer(), it) }
    }

    suspend fun getPlaylist(id: String): Pair<Playlist, List<Song>> =
        get("/rest/getPlaylist", listOf("id" to id)) { root ->
            val p = root["playlist"]?.jsonObject ?: throw SubsonicApiException("No playlist")
            val playlist = json.decodeFromJsonElement(Playlist.serializer(), p)
            val songs = p["entry"]?.jsonArray?.map {
                json.decodeFromJsonElement(Song.serializer(), it)
            } ?: emptyList()
            playlist to songs
        }

    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()): String =
        get("/rest/createPlaylist", listOf("name" to name) + songIds.map { "songId" to it }) { root ->
            (root["playlist"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull ?: ""
        }

    suspend fun updatePlaylist(
        id: String,
        name: String? = null,
        songIdsToAdd: List<String> = emptyList(),
        songIndexesToRemove: List<Int> = emptyList(),
    ) {
        val p = mutableListOf("playlistId" to id)
        name?.let { p += "name" to it }
        songIdsToAdd.forEach { p += "songIdToAdd" to it }
        songIndexesToRemove.forEach { p += "songIndexToRemove" to it.toString() }
        get("/rest/updatePlaylist", p) { }
    }

    suspend fun deletePlaylist(id: String) = get("/rest/deletePlaylist", listOf("id" to id)) { }

    suspend fun getLyricsBySongId(id: String): List<StructuredLyrics>? =
        get("/rest/getLyricsBySongId", listOf("id" to id)) { root ->
            val ll = (root["lyricsList"] as? JsonObject)?.get("structuredLyrics")
            ll?.jsonArray?.map { json.decodeFromJsonElement(StructuredLyrics.serializer(), it) }
        }

    suspend fun savePlayQueue(songIds: List<String>, current: String?, positionMs: Long) {
        if (songIds.isEmpty()) return
        val p = songIds.map { "id" to it }.toMutableList()
        current?.let { p += "current" to it }
        p += "position" to positionMs.toString()
        try {
            get("/rest/savePlayQueue", p) { }
        } catch (_: Exception) {
            // non-fatal
        }
    }

    data class PlayQueueState(
        val songs: List<Song>,
        val currentIndex: Int,
        val positionMs: Long,
    )

    suspend fun getPlayQueue(): PlayQueueState? = try {
        get("/rest/getPlayQueue", emptyList()) { root ->
            val pq = root["playQueue"]?.jsonObject ?: return@get null
            val songs = pq["entry"]?.jsonArray?.map {
                json.decodeFromJsonElement(Song.serializer(), it)
            } ?: emptyList()
            val current = pq["current"]?.jsonPrimitive?.contentOrNull
            val pos = pq["position"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val idx = songs.indexOfFirst { it.id == current }.coerceAtLeast(0)
            if (songs.isEmpty()) null else PlayQueueState(songs, idx, pos)
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val DEFAULT_SERVER = "https://www.squidify.org"
        const val DEFAULT_USERNAME = "Guest"
        const val DEFAULT_PASSWORD = "Guest"
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "Squirtify"
        private const val TAG = "Subsonic"

        fun md5Hex(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            return md.digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { String.format(Locale.ROOT, "%02x", it) }
        }
    }
}
