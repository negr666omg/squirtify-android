package org.squidify.android.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.squidify.android.App
import org.squidify.android.data.Album
import org.squidify.android.data.DownloadSource
import org.squidify.android.data.HistoryEntry
import org.squidify.android.player.PlayerSong
import org.squidify.android.ui.components.CoverArt
import org.squidify.android.ui.components.EmptyState
import org.squidify.android.ui.components.SongRow
import org.squidify.android.util.Fmt

/** On-device listening history: exactly what was played on this phone. */
@Composable
fun HistoryTab(
    onAlbumClick: (Album) -> Unit,
    onArtistClick: (org.squidify.android.data.Artist) -> Unit,
) {
    val graph = App.graph()
    val scope = rememberCoroutineScope()
    val entries by graph.history.entries.collectAsState()
    val player = graph.player
    val queue by player.queue.collectAsState()
    val idx by player.currentIndex.collectAsState()
    val isBuffering by player.isBuffering.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Played on this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = {
                    scope.launch { graph.history.clear() }
                }) { Text("Clear") }
            }
        }
        if (entries.isEmpty()) {
            EmptyState("Nothing played yet.\nYour listening history on this device will appear here.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
            ) {
                items(entries.size, key = { "${entries[it].playedAt}-${entries[it].songId}" }) { i ->
                    val e = entries[i]
                    val cover = when (e.source) {
                        DownloadSource.SQUIDIFY ->
                            App.graph().subsonic.coverUrl(e.coverArtId ?: e.albumId?.let { "al-$it" }, 480)
                        DownloadSource.KHINSIDER -> e.coverUrl?.let { graph.khi.imageUrl(it) }
                    }
                    val isCurrent = queue.getOrNull(idx)?.id == e.songId
                    HistoryRow(
                        entry = e,
                        coverUrl = cover,
                        isCurrent = isCurrent,
                        isLoading = isCurrent && isBuffering,
                        onClick = {
                            scope.launch {
                                val songs = entries.map { it.toPlayerSong() }
                                player.play(songs, i)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    coverUrl: String?,
    isCurrent: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            url = coverUrl,
            modifier = Modifier.size(48.dp),
            corner = 6.dp,
            contentDescription = entry.album,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    entry.artist ?: entry.album,
                    when (entry.source) {
                        DownloadSource.SQUIDIFY -> "Squidify"
                        DownloadSource.KHINSIDER -> "KHInsider"
                    },
                    Fmt.relativeDate(java.time.Instant.ofEpochMilli(entry.playedAt).toString()),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isLoading) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        if (entry.durationMs > 0) {
            Text(
                text = Fmt.durationMs(entry.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}
