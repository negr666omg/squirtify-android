package org.squidify.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.squidify.android.data.KhiClient
import org.squidify.android.data.KhiResolve
import org.squidify.android.data.LyricsClient
import org.squidify.android.downloads.DownloadManager

class ParsingTest {

    private val lyrics = LyricsClient(okhttp3.OkHttpClient(), org.squidify.android.data.SubsonicClient(okhttp3.OkHttpClient()))

    @Test
    fun `parses plain lrc with timestamps`() {
        val lrc = """
            [ti:Test]
            [ar:Artist]
            [00:07.95]First things first
            [00:12.27]I'ma say all the words inside my head
        """.trimIndent()
        val lines = lyrics.parseLrc(lrc)
        assertEquals(2, lines.size)
        assertEquals(7.95, lines[0].seconds!!, 0.001)
        assertEquals("First things first", lines[0].text)
        assertEquals(12.27, lines[1].seconds!!, 0.001)
    }

    @Test
    fun `parses enhanced lrc with word timing`() {
        val lrc = "[00:07.95]<00:07.95>First <00:08.36>things <00:08.85>first"
        val lines = lyrics.parseLrc(lrc)
        assertEquals(1, lines.size)
        assertEquals(3, lines[0].words.size)
        assertEquals("First things first", lines[0].text)
        assertEquals(8.36, lines[0].words[1].start!!, 0.001)
    }

    @Test
    fun `parses multi-timestamp lines into separate lines`() {
        val lines = lyrics.parseLrc("[00:01.00][00:05.00]Chorus line")
        assertEquals(2, lines.size)
        assertEquals(1.0, lines[0].seconds!!, 0.001)
        assertEquals(5.0, lines[1].seconds!!, 0.001)
        assertEquals("Chorus line", lines[1].text)
    }

    @Test
    fun `untagged lines become plain lines`() {
        val lines = lyrics.parseLrc("just a plain line")
        assertEquals(1, lines.size)
        assertNull(lines[0].seconds)
        assertEquals("just a plain line", lines[0].text)
    }

    @Test
    fun `khi resolve picks preferred format`() {
        val r = KhiResolve(
            mp3 = "https://x/mp3.flac",
            flac = "https://x/a.flac",
            directUrl = "https://x/direct",
        )
        assertEquals("https://x/a.flac", r.urlFor("flac"))
        assertEquals("https://x/mp3.flac", r.urlFor("mp3"))
        assertEquals("https://x/direct", r.urlFor("anything"))
    }

    @Test
    fun `khi resolve falls back when format missing`() {
        val r = KhiResolve(mp3 = "https://x/m.mp3", flac = null, directUrl = "https://x/d")
        assertEquals("https://x/d", r.urlFor("flac"))
        assertEquals("https://x/m.mp3", r.urlFor("mp3"))
    }

    @Test
    fun `khi image url proxies through khi-dl`() {
        val khi = KhiClient(okhttp3.OkHttpClient())
        val url = khi.imageUrl("https://nu.vgmtreasurechest.com/x/cover.jpg")
        assertTrue(url!!.startsWith("https://khinsider.squid.wtf/api/image?url="))
        assertTrue(url.contains(coverEncoded()))
        assertNull(khi.imageUrl(null))
        // already-proxied urls pass through untouched
        val proxied = khi.imageUrl(url)
        assertEquals(url, proxied)
    }

    private fun coverEncoded() = java.net.URLEncoder.encode("https://nu.vgmtreasurechest.com/x/cover.jpg", "UTF-8")

    @Test
    fun `filenames are sanitized for storage`() {
        assertEquals("AC_DC - Back In Black.mp3", DownloadManager.sanitize("AC/DC - Back In Black.mp3"))
        assertEquals("a_b", DownloadManager.sanitize("a:b"))
        val long = "x".repeat(400)
        assertTrue(DownloadManager.sanitize("$long.mp3").length <= 180)
        assertEquals("___", DownloadManager.sanitize("???"))
    }

    @Test
    fun `player song parses khi duration text`() {
        val secs = org.squidify.android.player.PlayerSong.parseDuration("1:16")
        assertEquals(76L, secs)
        assertEquals(3723L, org.squidify.android.player.PlayerSong.parseDuration("1:02:03"))
        assertEquals(0L, org.squidify.android.player.PlayerSong.parseDuration(null))
    }
}

