package org.squidify.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.squidify.android.data.SubsonicClient
import org.squidify.android.data.StreamQuality

class SubsonicAuthTest {

    @Test
    fun `md5 token matches subsonic spec vector`() {
        // Known vector from the Subsonic API docs: password "sesame", salt "c19b2d"
        assertEquals("26719a1196d2a940705a59634eb18eab", SubsonicClient.md5Hex("sesame" + "c19b2d"))
    }

    @Test
    fun `md5 guest salt vector`() {
        assertEquals("26db320e373732b6e5d5c7c54bda76b2", SubsonicClient.md5Hex("Guest" + "0123456789abcdef"))
    }

    @Test
    fun `auth params carry user token salt version client`() {
        val c = SubsonicClient(okhttp3.OkHttpClient())
        val p = c.authParams().toMap()
        assertEquals("Guest", p["u"])
        assertEquals("1.16.1", p["v"])
        assertEquals("Squirtify", p["c"])
        assertEquals("json", p["f"])
        val salt = p["s"]!!
        assertEquals(16, salt.length)
        assertTrue(salt.all { it in "0123456789abcdef" })
        assertEquals(SubsonicClient.md5Hex("Guest$salt"), p["t"])
    }

    @Test
    fun `stream url encodes quality params`() {
        val c = SubsonicClient(okhttp3.OkHttpClient())
        val url = c.streamUrl("song123", StreamQuality.MP3_320)
        assertTrue(url.startsWith("https://www.squidify.org/rest/stream?"))
        assertTrue(url.contains("id=song123"))
        assertTrue(url.contains("format=mp3"))
        assertTrue(url.contains("maxBitRate=320"))
        assertTrue(url.contains("f=json"))
    }

    @Test
    fun `stream url original omits format and bitrate`() {
        val c = SubsonicClient(okhttp3.OkHttpClient())
        val url = c.streamUrl("song123", StreamQuality.ORIGINAL)
        assertTrue(url.contains("id=song123"))
        assertTrue(!url.contains("format="))
        assertTrue(!url.contains("maxBitRate="))
    }

    @Test
    fun `stream url differs per quality`() {
        val c = SubsonicClient(okhttp3.OkHttpClient())
        val a = c.streamUrl("x", StreamQuality.OPUS_96)
        val b = c.streamUrl("x", StreamQuality.OPUS_192)
        assertNotEquals(a, b)
        assertTrue(a.contains("maxBitRate=96"))
        assertTrue(b.contains("maxBitRate=192"))
    }

    @Test
    fun `cover url uses stable cover-v1 salt token`() {
        val c = SubsonicClient(okhttp3.OkHttpClient())
        val url = c.coverUrl("al-abc", 600)
        assertNotNull(url)
        assertTrue(url!!.startsWith("https://www.squidify.org/rest/getCoverArt?"))
        assertTrue(url.contains("id=al-abc"))
        assertTrue(url.contains("size=600"))
        assertTrue(url.contains("s=cover-v1"))
        assertTrue(url.contains("t=${SubsonicClient.md5Hex("Guestcover-v1")}"))
    }

    @Test
    fun `quality enum maps formats and bitrates`() {
        assertEquals(null, StreamQuality.ORIGINAL.format)
        assertEquals(null, StreamQuality.ORIGINAL.maxBitRate)
        assertEquals("mp3", StreamQuality.MP3_192.format)
        assertEquals(192, StreamQuality.MP3_192.maxBitRate)
        assertEquals("opus", StreamQuality.OPUS_128.format)
        assertEquals(128, StreamQuality.OPUS_128.maxBitRate)
        assertEquals("flac", StreamQuality.ORIGINAL.extension)
        assertEquals("mp3", StreamQuality.MP3_320.extension)
    }
}

