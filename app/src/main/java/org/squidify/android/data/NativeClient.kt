package org.squidify.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.squidify.android.util.AppLog

/**
 * Navidrome-native REST API (the same endpoints squidify.org's web client uses
 * for the Songs page and artist albums): /auth/login + /api/song + /api/album.
 */
class NativeClient(
    private val http: OkHttpClient,
    private val serverUrl: String = SubsonicClient.DEFAULT_SERVER,
    private val username: String = SubsonicClient.DEFAULT_USERNAME,
    private val password: String = SubsonicClient.DEFAULT_PASSWORD,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Volatile
    private var jwt: String? = null

    private suspend fun token(): String {
        jwt?.let { return it }
        return withContext(Dispatchers.IO) {
            val body = """{"username":"$username","password":"$password"}"""
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$serverUrl/auth/login")
                .post(body)
                .header("Accept", "application/json")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.e("Native", "/auth/login → HTTP ${resp.code}")
                    throw IllegalStateException("Login failed: HTTP ${resp.code}")
                }
                val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
                val t = obj["token"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalStateException("Login failed: no token")
                jwt = t
                t
            }
        }
    }

    private suspend fun <T> getJson(
        path: String,
        query: Map<String, String>,
        parse: (JsonArray) -> T,
    ): T = withContext(Dispatchers.IO) {
        val urlBuilder = StringBuilder(serverUrl).append(path)
        urlBuilder.append('?')
        query.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) urlBuilder.append('&')
            urlBuilder.append(java.net.URLEncoder.encode(k, "UTF-8"))
                .append('=')
                .append(java.net.URLEncoder.encode(v, "UTF-8"))
        }
        val resp = runWithRetry {
            val req = Request.Builder()
                .url(urlBuilder.toString())
                .header("x-nd-authorization", "Bearer ${token()}")
                .header("Accept", "application/json")
                .build()
            http.newCall(req).execute()
        }
        resp.use { r ->
            if (!r.isSuccessful) {
                if (r.code == 401) jwt = null // force re-login next call
                AppLog.e("Native", "$path → HTTP ${r.code}")
                throw IllegalStateException("HTTP ${r.code}")
            }
            val el = json.parseToJsonElement(r.body!!.string())
            val arr = when (el) {
                is JsonArray -> el
                is JsonObject -> {
                    val data = el["data"]
                    if (data is JsonArray) data else JsonArray(listOf(el))
                }
                else -> JsonArray(emptyList())
            }
            parse(arr)
        }
    }

    private inline fun <T> runWithRetry(block: () -> T): T {
        return try {
            block()
        } catch (e: IllegalStateException) {
            if (e.message?.contains("401") == true) {
                jwt = null
                block()
            } else throw e
        }
    }

    suspend fun songs(
        sort: String = "title",
        order: String = "asc",
        start: Int = 0,
        end: Int = 50,
        artistId: String? = null,
    ): List<NativeSong> = getJson(
        "/api/song",
        buildMap {
            put("_sort", sort)
            put("_order", order)
            put("_start", start.toString())
            put("_end", end.toString())
            artistId?.let { put("artist_id", it) }
        },
    ) { arr -> arr.map { json.decodeFromJsonElement(NativeSong.serializer(), it) } }

    suspend fun song(id: String): NativeSong? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$serverUrl/api/song/${java.net.URLEncoder.encode(id, "UTF-8")}")
            .header("x-nd-authorization", "Bearer ${token()}")
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            val el = json.parseToJsonElement(r.body!!.string())
            val obj = when (el) {
                is JsonObject -> el["data"] as? JsonObject ?: el
                else -> null
            } ?: return@withContext null
            json.decodeFromJsonElement(NativeSong.serializer(), obj)
        }
    }

    suspend fun albumsByArtist(artistId: String, end: Int = 250): List<NativeAlbum> = getJson(
        "/api/album",
        mapOf("artist_id" to artistId, "_end" to end.toString()),
    ) { arr -> arr.map { json.decodeFromJsonElement(NativeAlbum.serializer(), it) } }
}
