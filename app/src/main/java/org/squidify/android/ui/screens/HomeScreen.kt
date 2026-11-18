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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import org.squidify.android.App
import org.squidify.android.data.Album
import org.squidify.android.data.DownloadSource
import org.squidify.android.data.HistoryEntry
import org.squidify.android.ui.components.CoverArt
import org.squidify.android.ui.components.ErrorRetry
import org.squidify.android.ui.components.LoadingShelf
import org.squidify.android.ui.components.SectionHeader
import org.squidify.android.ui.components.coverArtUrl

/** One album derived from the on-device listening history. */
private data class RecentAlbum(
    val key: String,
    val title: String,
    val artist: String?,
    val coverUrl: String?,
    val albumId: String?,          // Squidify album route
    val khiAlbumUrl: String?,      // KHInsider album route
)

@Composable
fun HomeScreen(
    onAlbumClick: (Album) -> Unit,
    onKhiAlbumClick: (String) -> Unit = {},
) {
    val graph = App.graph()
    val subsonic = graph.subsonic
    val history by graph.history.entries.collectAsState()

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

    // Recently played on THIS device: the local history grouped into albums.
    // (The account is guest, so the server's "recent" list reflects everyone.)
    val localRecent = remember(history) {
        history
            .groupBy { entry -> historyAlbumKey(entry) }
            .map { (_, entries) ->
                val e = entries.first()
                RecentAlbum(
                    key = historyAlbumKey(e),
                    title = e.album ?: e.title,
                    artist = e.artist,
                    coverUrl = e.coverUrl
                        ?: e.coverArtId?.let { App.graph().subsonic.coverUrl(it, 480) },
                    albumId = e.albumId?.takeIf { e.source == DownloadSource.SQUIDIFY },
                    khiAlbumUrl = e.khiTrackUrl
                        ?.takeIf { e.source == DownloadSource.KHINSIDER }
                        ?.substringBeforeLast('/'),
                )
            }
            .take(20)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
    ) {
        item {
            Text(
                text = "Home",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
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
                        RecentShelf(localRecent, onAlbumClick, onKhiAlbumClick)
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

internal fun historyAlbumKey(e: HistoryEntry): String =
    if (e.source == DownloadSource.KHINSIDER) {
        // Different games can publish identically named albums. The page URL
        // is the stable album identity; track URLs share the same parent page.
        val albumUrl = e.khiTrackUrl
            ?.substringBeforeLast('/')
            ?.takeIf { it.isNotBlank() && it != e.khiTrackUrl }
        "khi:${albumUrl ?: "unknown:${e.album ?: e.title}"}"
    } else {
        "sq:${e.albumId ?: e.album ?: e.title}"
    }

@Composable
private fun RecentShelf(
    albums: List<RecentAlbum>,
    onAlbumClick: (Album) -> Unit,
    onKhiAlbumClick: (String) -> Unit,
) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(albums.size, key = { albums[it].key }) { i ->
            val a = albums[i]
            RecentAlbumCard(
                title = a.title,
                artist = a.artist,
                coverUrl = a.coverUrl,
                onClick = {
                    when {
                        a.albumId != null -> onAlbumClick(
                            Album(id = a.albumId, name = a.title, artist = a.artist),
                        )
                        a.khiAlbumUrl != null -> onKhiAlbumClick(a.khiAlbumUrl)
                    }
                },
            )
        }
    }
}

@Composable
private fun RecentAlbumCard(
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
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            text = artist ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ShelfSection(title: String?, albums: List<Album>?, onAlbumClick: (Album) -> Unit) {
    Column {
        if (title != null) SectionHeader(title = title)
        when {
            albums == null -> LoadingShelf(height = 190.dp)
            albums.isEmpty() -> {}
            else -> LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(albums.size, key = { albums[it].id }) { i ->
                    org.squidify.android.ui.components.AlbumCard(
                        album = albums[i],
                        onClick = { onAlbumClick(albums[i]) },
                        coverUrl = albums[i].coverArtUrl(480),
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
