package org.squidify.android.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSeekPolicyTest {

    @Test
    fun `transcoded stream with no full cache always uses offset seeking`() {
        assertFalse(
            shouldUseNativeSeek(
                isTranscoded = true,
                isSeekable = true,
                durationTrustworthy = true,
                isFullyCached = false,
            ),
        )
    }

    @Test
    fun `fully cached transcoded stream may use native seeking`() {
        assertTrue(
            shouldUseNativeSeek(
                isTranscoded = true,
                isSeekable = true,
                durationTrustworthy = true,
                isFullyCached = true,
            ),
        )
    }

    @Test
    fun `unseekable transcoded stream still uses offset seeking when cached`() {
        assertFalse(
            shouldUseNativeSeek(
                isTranscoded = true,
                isSeekable = false,
                durationTrustworthy = true,
                isFullyCached = true,
            ),
        )
    }

    @Test
    fun `native stream requires a trustworthy duration and seekable timeline`() {
        assertFalse(shouldUseNativeSeek(false, true, false, false))
        assertFalse(shouldUseNativeSeek(false, false, true, true))
        assertTrue(shouldUseNativeSeek(false, true, true, false))
    }
}
