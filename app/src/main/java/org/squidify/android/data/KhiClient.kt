package org.squidify.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.squidify.android.util.AppLog
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/** Client for the khinsider.squid.wtf (KHI-DL) companion API. */
class KhiClient(
    private val http: OkHttpClient,
    val baseUrl: String = DEFAULT_BASE,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // Resolver responses are small JSON documents. Bound the total request
    // time so a blocked VPN route cannot leave playback waiting indefinitely.
    private val apiHttp = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val albumCache = ConcurrentHashMap<String, KhiAlbum>()
    private data class CachedResolve(val value: KhiResolve, val fetchedAtMs: Long)
    private val resolveCache = ConcurrentHashMap<String, CachedResolve>()
    // Direct file links may be temporary. Do not keep resolver URLs forever.
    private val resolveTtlMs = 5 * 60 * 1000L

    private suspend fun getString(path: String, query: String): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$baseUrl$path?$query")
                .header("Accept", "application/json")
                .build()
            apiHttp.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    AppLog.e("KHI", "$path → HTTP ${r.code}")
                    throw IllegalStateException("HTTP ${r.code}")
                }
                r.body!!.string()
            }
        }

    private fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")

    suspend fun searchAlbums(q: String, page: Int = 1): KhiSearch {
        val body = getString("/api/search", "q=${enc(q)}&page=$page")
        return json.decodeFromString(KhiSearch.serializer(), body)
    }

    suspend fun album(url: String): KhiAlbum {
        albumCache[url]?.let { return it }
        val body = getString("/api/album", "url=${enc(url)}")
        val album = json.decodeFromString(KhiAlbum.serializer(), body)
        albumCache[url] = album
        return album
    }

    suspend fun resolve(trackUrl: String, forceRefresh: Boolean = false): KhiResolve {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            resolveCache[trackUrl]?.let { cached ->
                if (now - cached.fetchedAtMs < resolveTtlMs) return cached.value
                resolveCache.remove(trackUrl, cached)
            }
        }
        val body = getString("/api/resolve", "url=${enc(trackUrl)}")
        val res = json.decodeFromString(KhiResolve.serializer(), body)
        resolveCache[trackUrl] = CachedResolve(res, System.currentTimeMillis())
        return res
    }

    /** Proxy URL for cover art / thumbnails served through KHI-DL. */
    fun imageUrl(coverUrl: String?): String? {
        if (coverUrl.isNullOrBlank()) return null
        if (coverUrl.startsWith("$baseUrl/api/image")) return coverUrl
        return "$baseUrl/api/image?url=${enc(coverUrl)}"
    }

    fun clearCaches() {
        albumCache.clear()
        resolveCache.clear()
    }

    companion object {
        const val DEFAULT_BASE = "https://khinsider.squid.wtf"
    }
}
