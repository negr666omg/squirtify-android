package org.squidify.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import kotlin.math.pow
import okhttp3.Request
import org.squidify.android.data.SubsonicClient.Companion.md5Hex
import org.squidify.android.util.AppLog
import java.util.Base64

/** One lyrics line; [seconds] is null for untimed/plain lines. */
data class LyricWord(val text: String, val start: Double?)

data class LyricLineUi(
    val text: String,
    val seconds: Double? = null,
    val words: List<LyricWord> = emptyList(),
)

data class LyricsResult(
    val lines: List<LyricLineUi>,
    val source: String,
    val sourceUrl: String?,
)

/**
 * Lyrics resolution chain with six providers, tried in order:
 * Navidrome (server) → lrc.red (ISRC) → LRCLIB → NetEase → QQ Music →
 * lyrics.ovh (plain text, last resort).
 */
class LyricsClient(
    private val http: OkHttpClient,
    private val subsonic: SubsonicClient,
) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val client = "Squirtify/1.2 (Android)"

    suspend fun forSong(
        song: Song,
        preferred: LyricsProvider = LyricsProvider.AUTO,
    ): LyricsResult? {
        AppLog.i(TAG, "Lyrics lookup: '${song.title}' — '${song.artist ?: "?"}' (provider: $preferred)")

        if (preferred != LyricsProvider.AUTO) {
            // user picked a concrete provider: use ONLY that one
            val result = when (preferred) {
                LyricsProvider.SERVER -> tryServerLyrics(song)
                LyricsProvider.LRC_RED -> try {
                    searchLrcRed(song.title.trim(), song.artist?.trim().orEmpty(), song.isrc, song.duration?.toDouble())
                } catch (e: Exception) {
                    AppLog.w(TAG, "lrc.red lookup failed: ${e.message}"); null
                }
                LyricsProvider.LRCLIB -> try {
                    fetchLrcLib(song.title.trim(), song.artist?.trim().orEmpty(), song.album, song.duration)
                } catch (e: Exception) {
                    AppLog.w(TAG, "lrclib.net lookup failed: ${e.message}"); null
                }
                LyricsProvider.NETEASE -> try {
                    fetchNetEase(song.title.trim(), song.artist?.trim().orEmpty(), song.duration)
                } catch (e: Exception) {
                    AppLog.w(TAG, "NetEase lookup failed: ${e.message}"); null
                }
                LyricsProvider.QQ -> try {
                    fetchQq(song.title.trim(), song.artist?.trim().orEmpty(), song.duration)
                } catch (e: Exception) {
                    AppLog.w(TAG, "QQ Music lookup failed: ${e.message}"); null
                }
                LyricsProvider.OVH -> try {
                    fetchLyricsOvh(song.title.trim(), song.artist?.trim().orEmpty())
                } catch (e: Exception) {
                    AppLog.w(TAG, "lyrics.ovh lookup failed: ${e.message}"); null
                }
                LyricsProvider.AUTO -> null
            }
            if (result != null) return result
            AppLog.w(TAG, "Provider $preferred had no lyrics for '${song.title}'")
            return null
        }

        // AUTO: the full provider chain
        tryServerLyrics(song)?.let { return it }

        val title = song.title.trim()
        val artist = song.artist?.trim().orEmpty()
        val duration = song.duration

        // 2) lrc.red via ISRC search
        try {
            val lrcRed = searchLrcRed(title, artist, song.isrc, duration?.toDouble())
            if (lrcRed != null) {
                AppLog.i(TAG, "Lyrics found on lrc.red")
                return lrcRed
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "lrc.red lookup failed: ${e.message}")
        }

        // 3) LRCLIB
        try {
            val lrclib = fetchLrcLib(title, artist, song.album, duration)
            if (lrclib != null) {
                AppLog.i(TAG, "Lyrics found on lrclib.net")
                return lrclib
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "lrclib.net lookup failed: ${e.message}")
        }

        // 4) NetEase Cloud Music
        try {
            val netease = fetchNetEase(title, artist, duration)
            if (netease != null) {
                AppLog.i(TAG, "Lyrics found on NetEase")
                return netease
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "NetEase lookup failed: ${e.message}")
        }

        // 5) QQ Music
        try {
            val qq = fetchQq(title, artist, duration)
            if (qq != null) {
                AppLog.i(TAG, "Lyrics found on QQ Music")
                return qq
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "QQ Music lookup failed: ${e.message}")
        }

        // 6) lyrics.ovh — plain text only, last resort
        try {
            val ovh = fetchLyricsOvh(title, artist)
            if (ovh != null) {
                AppLog.i(TAG, "Lyrics found on lyrics.ovh (plain)")
                return ovh
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "lyrics.ovh lookup failed: ${e.message}")
        }

        AppLog.w(TAG, "No lyrics found anywhere for '${song.title}'")
        return null
    }

    /** Navidrome / Subsonic structured lyrics, or null. */
    private suspend fun tryServerLyrics(song: Song): LyricsResult? {
        try {
            val structured = subsonic.getLyricsBySongId(song.id)
            val first = structured?.firstOrNull { it.synced && it.line.isNotEmpty() }
                ?: structured?.firstOrNull { it.line.isNotEmpty() }
            if (first != null) {
                val lines = first.line.mapNotNull { l ->
                    if (l.value.isBlank()) null
                    else LyricLineUi(l.value.trim(), l.start?.let { it / 1000.0 })
                }
                if (lines.isNotEmpty() && (lines.first().seconds != null || !first.synced)) {
                    AppLog.i(TAG, "Lyrics found on server (Navidrome, ${lines.size} lines, synced=${first.synced})")
                    return LyricsResult(
                        lines = lines.sortedBy { it.seconds ?: 0.0 },
                        source = "Navidrome",
                        sourceUrl = null,
                    )
                }
            } else {
                AppLog.d(TAG, "No structured lyrics on server")
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Server lyrics lookup failed: ${e.message}")
        }
        return null
    }

    // ------------------------------------------------------------- lrc.red

    @kotlinx.serialization.Serializable
    private data class LrcRedHit(
        val isrc: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val duration: Double? = null,
    )

    private suspend fun searchLrcRed(
        title: String,
        artist: String,
        isrcs: List<String>?,
        duration: Double?,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        // direct ISRC lookup first (exactly like the web app)
        for (isrc in (isrcs ?: emptyList())) {
            val body = httpGetLrcRed("https://lrc.red/s/${enc(isrc)}.lrc") ?: continue
            val parsed = parseLrc(body)
            if (parsed.any { it.seconds != null }) {
                return@withContext LyricsResult(parsed, "lrc.red", "https://lrc.red")
            }
        }

        // search by title + artist
        if (title.isNotEmpty()) {
            val q = listOfNotNull(title, artist.ifBlank { null }).joinToString(" ")
            val searchBody = httpGetLrcRed("https://lrc.red/search.json?q=${enc(q)}")
            if (searchBody != null) {
                val hits = try {
                    val root = json.parseToJsonElement(searchBody)
                    val arr = (root as? kotlinx.serialization.json.JsonObject)?.get("hits")
                        as? kotlinx.serialization.json.JsonArray
                    arr?.mapNotNull {
                        try {
                            json.decodeFromJsonElement(LrcRedHit.serializer(), it)
                        } catch (_: Exception) {
                            null
                        }
                    } ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
                // score best hit like the web app
                val best = pickBest(
                    hits.map { Triple(it.title, it.artist, it.duration) },
                    title, artist, duration,
                )?.let { idx -> hits[idx] }
                if (best?.isrc != null) {
                    val body = httpGetLrcRed("https://lrc.red/s/${enc(best.isrc)}.lrc")
                    if (body != null) {
                        val parsed = parseLrc(body)
                        if (parsed.isNotEmpty()) {
                            return@withContext LyricsResult(parsed, "lrc.red", "https://lrc.red")
                        }
                    }
                }
            }
        }
        null
    }

    /** Scores candidate (title, artist, duration) triples; returns the best index. */
    private fun pickBest(
        candidates: List<Triple<String?, String?, Double?>>,
        title: String,
        artist: String,
        duration: Double?,
    ): Int? {
        var bestIndex: Int? = null
        var bestScore = 0
        candidates.forEachIndexed { i, (cTitle, cArtist, cDuration) ->
            var score = 0
            if (cTitle?.lowercase() == title.lowercase()) score += 10
            else if (cTitle?.lowercase()?.contains(title.lowercase()) == true) score += 5
            if (artist.isNotBlank()) {
                if (cArtist?.lowercase() == artist.lowercase()) score += 10
                else if (cArtist?.lowercase()?.contains(artist.lowercase()) == true) score += 5
            }
            if (cDuration != null && duration != null && kotlin.math.abs(cDuration - duration) <= duration * 0.05) score += 5
            if (score > bestScore) {
                bestScore = score
                bestIndex = i
            }
        }
        return bestIndex
    }

    private suspend fun httpGetLrcRed(url: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("X-Client-ID", client)
                    .header("User-Agent", client)
                    .header("Accept", "*/*")
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        AppLog.d(TAG, "lrc.red $url → HTTP ${r.code}")
                        null
                    } else r.body?.string()
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "lrc.red request error: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

    // ------------------------------------------------------------- LRCLIB

    @kotlinx.serialization.Serializable
    private data class LrcLibTrack(
        val syncedLyrics: String? = null,
        val plainLyrics: String? = null,
        val instrumental: Boolean? = null,
        val trackName: String? = null,
        val artistName: String? = null,
    )

    private suspend fun fetchLrcLib(
        title: String,
        artist: String,
        album: String?,
        duration: Int?,
    ): LyricsResult? = withContext(Dispatchers.IO) {
        if (title.isEmpty()) return@withContext null

        suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", client)
                    .header("Lrclib-Client", client)
                    .header("Accept", "application/json")
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.string()
                }
            } catch (_: Exception) {
                null
            }
        }

        val qp = buildString {
            append("track_name=").append(enc(title))
            if (artist.isNotBlank()) append("&artist_name=").append(enc(artist))
            if (!album.isNullOrBlank()) append("&album_name=").append(enc(album))
            if (duration != null && duration in 1..3600) append("&duration=").append(duration)
        }
        var body = get("https://lrclib.net/api/get?$qp")
        var track = body?.let { parseLrcLib(it) }

        if (track == null || (track.syncedLyrics.isNullOrBlank() && track.plainLyrics.isNullOrBlank())) {
            val q = listOfNotNull(title, artist.ifBlank { null }).joinToString(" ")
            body = get("https://lrclib.net/api/search?q=${enc(q)}")
            track = body?.let {
                try {
                    val arr = json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonArray
                    arr?.firstOrNull()?.let { e ->
                        json.decodeFromJsonElement(LrcLibTrack.serializer(), e)
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }

        if (track?.instrumental == true) {
            return@withContext LyricsResult(listOf(LyricLineUi("♪ Instrumental ♪")), "LRCLIB", "https://lrclib.net")
        }
        val synced = track?.syncedLyrics
        if (!synced.isNullOrBlank()) {
            val parsed = parseLrc(synced)
            if (parsed.isNotEmpty()) {
                return@withContext LyricsResult(parsed, "LRCLIB", "https://lrclib.net")
            }
        }
        val plain = track?.plainLyrics
        if (!plain.isNullOrBlank()) {
            val lines = plain.lines().map { it.trim() }.filter { it.isNotEmpty() }
                .map { LyricLineUi(it) }
            if (lines.isNotEmpty()) {
                return@withContext LyricsResult(lines, "LRCLIB", "https://lrclib.net")
            }
        }
        null
    }

    private fun parseLrcLib(body: String): LrcLibTrack? = try {
        json.decodeFromString(LrcLibTrack.serializer(), body)
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------- NetEase

    @kotlinx.serialization.Serializable
    private data class NetEaseSong(
        val id: Long = 0,
        val name: String = "",
        val duration: Long? = null, // ms
        val artists: List<NetEaseArtist> = emptyList(),
    )

    @kotlinx.serialization.Serializable
    private data class NetEaseArtist(
        val name: String? = null,
    )

    private suspend fun fetchNetEase(title: String, artist: String, duration: Int?): LyricsResult? =
        withContext(Dispatchers.IO) {
            if (title.isEmpty()) return@withContext null
            val q = listOfNotNull(title, artist.ifBlank { null }).joinToString(" ")
            val searchBody = httpGetString(
                "https://music.163.com/api/search/get?s=${enc(q)}&type=1&limit=10",
                mapOf("Referer" to "https://music.163.com"),
            ) ?: return@withContext null
            val root = try { json.parseToJsonElement(searchBody) as? JsonObject } catch (_: Exception) { null }
            val songs = root.obj("result").arr("songs")?.mapNotNull { el ->
                try { json.decodeFromJsonElement(NetEaseSong.serializer(), el) } catch (_: Exception) { null }
            } ?: emptyList()
            if (songs.isEmpty()) return@withContext null

            val best = pickBest(
                songs.map { s ->
                    Triple(s.name, s.artists.firstOrNull()?.name, s.duration?.let { it / 1000.0 })
                },
                title, artist, duration?.toDouble(),
            )?.let { songs[it] } ?: songs.first()

            val lyricBody = httpGetString(
                "https://music.163.com/api/song/lyric?id=${best.id}&lv=1&kv=1&tv=-1",
                mapOf("Referer" to "https://music.163.com"),
            ) ?: return@withContext null
            val lrc = try {
                (json.parseToJsonElement(lyricBody) as? JsonObject).obj("lrc").str("lyric")
            } catch (_: Exception) {
                null
            }
            if (lrc.isNullOrBlank()) return@withContext null
            val parsed = parseLrc(lrc)
            if (parsed.isNotEmpty()) {
                LyricsResult(parsed, "NetEase", "https://music.163.com")
            } else null
        }

    // ------------------------------------------------------------- QQ Music

    @kotlinx.serialization.Serializable
    private data class QqSong(
        val songmid: String? = null,
        val songname: String? = null,
        val singer: List<QqSinger> = emptyList(),
        val interval: Int? = null, // seconds
    )

    @kotlinx.serialization.Serializable
    private data class QqSinger(
        val name: String? = null,
    )

    private suspend fun fetchQq(title: String, artist: String, duration: Int?): LyricsResult? =
        withContext(Dispatchers.IO) {
            if (title.isEmpty()) return@withContext null
            val q = listOfNotNull(title, artist.ifBlank { null }).joinToString(" ")
            val searchBody = httpGetString(
                "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${enc(q)}&format=json&n=10&p=1",
                mapOf("Referer" to "https://y.qq.com/"),
            ) ?: return@withContext null
            val root = try {
                json.parseToJsonElement(searchBody.trim().removePrefix("(").removeSuffix(")")) as? JsonObject
            } catch (_: Exception) {
                null
            }
            val songs = root.obj("data").obj("song").arr("list")?.mapNotNull { el ->
                try { json.decodeFromJsonElement(QqSong.serializer(), el) } catch (_: Exception) { null }
            } ?: emptyList()
            if (songs.isEmpty()) return@withContext null

            val best = pickBest(
                songs.map { s ->
                    Triple(s.songname, s.singer.firstOrNull()?.name, s.interval?.toDouble())
                },
                title, artist, duration?.toDouble(),
            )?.let { songs[it] } ?: songs.first()
            val mid = best.songmid ?: return@withContext null

            val lyricBody = httpGetString(
                "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid&format=json&nobase64=0",
                mapOf("Referer" to "https://y.qq.com/portal/player.html"),
            ) ?: return@withContext null
            val b64 = try {
                (json.parseToJsonElement(lyricBody.trim().removePrefix("(").removeSuffix(")")) as? JsonObject)
                    .str("lyric")
            } catch (_: Exception) {
                null
            }
            if (b64.isNullOrBlank()) return@withContext null
            val lrc = try {
                String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
            if (lrc.isNullOrBlank()) return@withContext null
            val parsed = parseLrc(lrc)
            if (parsed.isNotEmpty()) {
                LyricsResult(parsed, "QQ Music", "https://y.qq.com")
            } else null
        }

    // ------------------------------------------------------------- lyrics.ovh

    @kotlinx.serialization.Serializable
    private data class OvhResult(
        val lyrics: String? = null,
    )

    private suspend fun fetchLyricsOvh(title: String, artist: String): LyricsResult? =
        withContext(Dispatchers.IO) {
            if (title.isEmpty() || artist.isBlank()) return@withContext null
            val body = httpGetString(
                "https://api.lyrics.ovh/v1/${enc(artist)}/${enc(title)}",
                emptyMap(),
            ) ?: return@withContext null
            val text = try {
                json.decodeFromString(OvhResult.serializer(), body).lyrics
            } catch (_: Exception) {
                null
            }
            if (text.isNullOrBlank()) return@withContext null
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                .map { LyricLineUi(it) }
            if (lines.isNotEmpty()) {
                LyricsResult(lines, "lyrics.ovh", "https://lyrics.ovh")
            } else null
        }

    // ------------------------------------------------------------- helpers

    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject
    private fun JsonObject?.arr(key: String): JsonArray? = this?.get(key) as? JsonArray
    private fun JsonObject?.str(key: String): String? =
        (this?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content

    private suspend fun httpGetString(url: String, headers: Map<String, String>): String? =
        withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android)")
                headers.forEach { (k, v) -> builder.header(k, v) }
                http.newCall(builder.build()).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.string()
                }
            } catch (_: Exception) {
                null
            }
        }

    // ------------------------------------------------------------- LRC parse

    /** Parses plain and enhanced (word-timed) LRC text. */
    fun parseLrc(text: String): List<LyricLineUi> {
        val out = mutableListOf<LyricLineUi>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[ti:") || line.startsWith("[ar:") || line.startsWith("[al:") ||
                line.startsWith("[by:") || line.startsWith("[length:") || line.startsWith("[offset:") ||
                line.startsWith("[re:") || line.startsWith("[ve:")
            ) continue
            val tsMatches = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""").findAll(line).toList()
            if (tsMatches.isEmpty()) {
                val bare = line.removePrefix("[").removeSuffix("]").trim()
                if (bare.isNotEmpty() && !bare.contains(':')) out += LyricLineUi(bare)
                continue
            }
            val content = line.substring(tsMatches.last().range.last + 1).trim()
            val words = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>([^<>]*)""")
                .findAll(content)
                .map { m ->
                    val mm = m.groupValues[1].toDouble()
                    val ss = m.groupValues[2].toDouble()
                    val frac = m.groupValues[3].let {
                        if (it.isEmpty()) 0.0 else it.toDouble() / 10.0.pow(it.length.toDouble())
                    }
                    LyricWord(m.groupValues[4].trim(), mm * 60 + ss + frac)
                }
                .toList()
            val cleanText = if (words.isNotEmpty()) words.joinToString(" ") { it.text }.trim()
            else content.replace(Regex("""<[^>]*>"""), "").trim()
            if (cleanText.isEmpty() && words.isEmpty()) continue
            for (ts in tsMatches) {
                val mm = ts.groupValues[1].toDouble()
                val ss = ts.groupValues[2].toDouble()
                val frac = ts.groupValues[3].let {
                    if (it.isEmpty()) 0.0 else it.toDouble() / 10.0.pow(it.length.toDouble())
                }
                val seconds = mm * 60 + ss + frac
                out += LyricLineUi(cleanText.ifBlank { "♪" }, seconds, words)
            }
        }
        return out.sortedBy { it.seconds ?: 0.0 }
    }

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")

    private companion object {
        const val TAG = "Lyrics"
    }
}
