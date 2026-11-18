package org.squidify.android.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StagingPartNameTest {

    @Test
    fun `same job receives a stable staging name for resume`() {
        assertEquals(
            stagingPartName("job-123", "01 - Intro.flac"),
            stagingPartName("job-123", "01 - Intro.flac"),
        )
    }

    @Test
    fun `different jobs with same output name never share staging file`() {
        assertNotEquals(
            stagingPartName("album-a-track-1", "01 - Intro.flac"),
            stagingPartName("album-b-track-1", "01 - Intro.flac"),
        )
    }

    @Test
    fun `staging file retains destination name and part suffix`() {
        val name = stagingPartName("job-123", "01 - Intro.flac")
        assertTrue(name.endsWith("-01 - Intro.flac.part"))
    }
}
