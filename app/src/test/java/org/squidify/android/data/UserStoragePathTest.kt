package org.squidify.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class UserStoragePathTest {

    @Test
    fun `dot path components cannot escape the album directory`() {
        assertEquals("Unknown", UserStorage.sanitizeSegment("."))
        assertEquals("Unknown", UserStorage.sanitizeSegment(".."))
        assertEquals("Unknown", UserStorage.sanitizeSegment("  ..  "))
    }

    @Test
    fun `path separators and reserved characters become safe filename characters`() {
        assertEquals("Album_2026", UserStorage.sanitizeSegment("Album/2026"))
        assertEquals("A_B_C", UserStorage.sanitizeSegment("A:B*C"))
    }

    @Test
    fun `album directory names are capped at 120 characters`() {
        assertEquals(120, UserStorage.sanitizeSegment("A".repeat(140)).length)
    }

    @Test
    fun `atomic migration copy replaces stale file and removes temporary file`() {
        val directory = Files.createTempDirectory("squirtify-migration-test").toFile()
        try {
            val destination = java.io.File(directory, "favorites.json")
            destination.writeText("stale")
            copyStreamAtomically(
                ByteArrayInputStream("fresh-data".toByteArray()),
                destination,
            )

            assertEquals("fresh-data", destination.readText())
            assertTrue(destination.exists())
            assertFalse(java.io.File(directory, ".favorites.json.migrating").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `atomic migration copy cleans temporary file after source failure`() {
        val directory = Files.createTempDirectory("squirtify-migration-failure").toFile()
        try {
            val destination = java.io.File(directory, "history.json")
            destination.writeText("original")
            val brokenInput = object : java.io.InputStream() {
                private var first = true
                override fun read(): Int {
                    if (first) {
                        first = false
                        return 'x'.code
                    }
                    throw java.io.IOException("simulated read failure")
                }
            }

            try {
                copyStreamAtomically(brokenInput, destination)
                throw AssertionError("Expected copy to fail")
            } catch (_: java.io.IOException) {
            }

            assertEquals("original", destination.readText())
            assertFalse(java.io.File(directory, ".history.json.migrating").exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
