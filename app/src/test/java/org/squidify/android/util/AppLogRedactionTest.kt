package org.squidify.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLogRedactionTest {

    @Test
    fun `redacts Subsonic auth query values but keeps useful stream parameters`() {
        val input = "https://music.example/rest/stream?u=Guest&t=abc123&s=salt987&id=song42&format=opus"

        assertEquals(
            "https://music.example/rest/stream?u=[REDACTED]&t=[REDACTED]&s=[REDACTED]&id=song42&format=opus",
            AppLog.redactSensitiveValues(input),
        )
    }

    @Test
    fun `redacts bearer tokens and URL user credentials`() {
        val input = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9 https://user:secret@example.test/audio"

        assertEquals(
            "Authorization: Bearer [REDACTED] https://[REDACTED]@example.test/audio",
            AppLog.redactSensitiveValues(input),
        )
    }

    @Test
    fun `does not alter ordinary messages`() {
        val input = "Playback state changed to READY for song42"

        assertEquals(input, AppLog.redactSensitiveValues(input))
    }
}
