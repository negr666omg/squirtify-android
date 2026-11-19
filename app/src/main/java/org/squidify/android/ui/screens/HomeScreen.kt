@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package org.squidify.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.squidify.android.App
import org.squidify.android.data.Album
import org.squidify.android.data.HistoryEntry
import org.squidify.android.ui.components.CoverArt
import org.squidify.android.ui.components.ErrorRetry
import org.squidify.android.ui.components.LoadingShelf
import org.squidify.android.ui.components.SectionHeader

@Composable
fun HomeScreen(
    onAlbumClick: (Album) -> Unit,
) {
    val graph = App.graph()
    val subsonic = graph.subsonic
    val history by graph.history.entries.collectAsState()
    val settings by graph.settings.state().collectAsState()
    val hasInternet by graph.networkMonitor.hasValidatedInternet.collectAsState()
    val scope = rememberCoroutineScope()

    val feed by HomeFeedCache.state.collectAsState()

    // The request runs in the process-scoped graph scope, not the screen scope:
    // switching to Settings won't cancel it or force the shelves to reload.
    LaunchedEffect(subsonic) {
        HomeFeedCache.ensureLoaded(graph.scope, subsonic)
    }

    val community = feed.community
    val discover = feed.discover
    val frequent = feed.frequent
    val newest = feed.newest
    val explore = feed.explore
    val error = feed.error

    // Recently played is a track shelf, not an album-grouped history shelf.
    val localRecent = remember(history) { history.distinctBy { it.songId }.take(20) }
    val recentSongs = remember(localRecent) { localRecent.map { it.toPlayerSong() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Home",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Offline",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (settings.offlineMode || !hasInternet) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = settings.offlineMode || !hasInternet,
                    onCheckedChange = { enabled ->
                        scope.launch { graph.settings.setOfflineMode(enabled) }
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        if (error != null) {
            item { ErrorRetry("Couldn't reach the library.\n${error}") { HomeFeedCache.reload(graph.scope, subsonic) } }
        } else {
            // ---------- this device
            if (localRecent.isNotEmpty()) {
                item {
                    Column {
                        SectionHeader(title = "Recently played")
                        Spacer(Modifier.height(4.dp))
                        RecentTrackShelf(localRecent) { index -> graph.player.play(recentSongs, index) }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            // ---------- everyone else (guest account = shared history)
            item {
                Column {
                    SectionHeader(title = "Community")
                    Spacer(Modifier.height(4.dp))
                    ShelfSection(null, community, onAlbumClick)
                    Spacer(Modifier.height(8.dp))
                }
            }
            item { ShelfSection("Discover", discover, onAlbumClick) }
            item { ShelfSection("Most played", frequent, onAlbumClick) }
            item { ShelfSection("Recently added", newest, onAlbumClick) }
            item { ShelfSection("Explore", explore, onAlbumClick) }
        }
    }
}

@Composable
private fun RecentTrackShelf(
    entries: List<HistoryEntry>,
    onTrackClick: (Int) -> Unit,
) {
    val graph = App.graph()
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(entries.size, key = { "${entries[it].playedAt}-${entries[it].songId}" }) { i ->
            val entry = entries[i]
            RecentTrackCard(
                title = entry.title,
                artist = entry.artist ?: entry.album,
                coverUrl = entry.coverUrl
                    ?: entry.coverArtId?.let { graph.subsonic.coverUrl(it, 480) },
                onClick = { onTrackClick(i) },
            )
        }
    }
}

@Composable
private fun RecentTrackCard(
    title: String,
    artist: String?,
    coverUrl: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        CoverArt(
            url = coverUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            corner = 10.dp,
            contentDescription = title,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
        Text(
            text = artist ?: "Unknown artist",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Shared album shelf used by Home and the other library screens. */
@Composable
private fun ShelfSection(title: String?, albums: List<Album>?, onAlbumClick: (Album) -> Unit) {
    Column {
        if (title != null) SectionHeader(title = title)
        when {
            albums == null -> LoadingShelf(height = 190.dp)
            albums.isEmpty() -> Unit
            else -> LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(albums.size, key = { albums[it].id }) { index ->
                    val album = albums[index]
                    org.squidify.android.ui.components.AlbumCard(
                        album = album,
                        onClick = { onAlbumClick(album) },
                        coverUrl = album.coverArtUrl(480),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

fun Album.coverArtUrl(size: Int = 480): String? =
    App.graph().subsonic.coverUrl(coverArt ?: id.let { "al-$it" }, size)

fun org.squidify.android.data.Artist.coverArtUrl(size: Int = 300): String? =
    App.graph().subsonic.coverUrl(coverArt, size)

fun SongCover(song: org.squidify.android.data.Song): String? =
    App.graph().subsonic.coverUrl(song.coverArt ?: song.albumId?.let { "al-$it" }, 480)
