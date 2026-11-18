package org.squidify.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeClientAuthTest {

    @Test
    fun `expired JWT is refreshed and API request is retried once`() = runBlocking {
        var loginCount = 0
        var apiCount = 0
        val authorizationHeaders = mutableListOf<String?>()

        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val responseCode: Int
                val responseBody: String

                if (request.url.encodedPath == "/auth/login") {
                    loginCount++
                    val token = if (loginCount == 1) "expired" else "fresh"
                    responseCode = 200
                    responseBody = """{"token":"$token"}"""
                } else {
                    apiCount++
                    val authorization = request.header("x-nd-authorization")
                    authorizationHeaders += authorization
                    responseCode = if (authorization == "Bearer expired") 401 else 200
                    responseBody = if (responseCode == 401) {
                        """{"error":"expired"}"""
                    } else {
                        "[]"
                    }
                }

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message(if (responseCode == 200) "OK" else "Unauthorized")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val client = NativeClient(http, serverUrl = "https://example.test")
        val songs = client.songs(end = 1)

        assertEquals(emptyList<NativeSong>(), songs)
        assertEquals(2, loginCount)
        assertEquals(2, apiCount)
        assertEquals(
            listOf("Bearer expired", "Bearer fresh"),
            authorizationHeaders,
        )
    }
}
