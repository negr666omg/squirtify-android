package org.squidify.android.player

/**
 * Finds the next item belonging to the current album while preserving the
 * queue's order. Other albums are skipped; after the final matching item the
 * sequence wraps to the first matching item.
 */
internal fun nextAlbumQueueIndex(albumIdentities: List<String>, currentIndex: Int): Int? {
    if (currentIndex !in albumIdentities.indices) return null
    val currentAlbum = albumIdentities[currentIndex]
    return ((currentIndex + 1) until albumIdentities.size)
        .firstOrNull { albumIdentities[it] == currentAlbum }
        ?: albumIdentities.indexOfFirst { it == currentAlbum }.takeIf { it >= 0 }
}
