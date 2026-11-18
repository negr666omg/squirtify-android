@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package org.squidify.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.squidify.android.App
import org.squidify.android.data.KhiAlbum
import org.squidify.android.data.KhiTrack
import org.squidify.android.player.PlayerSong
import org.squidify.android.ui.components.CoverArt
import org.squidify.android.ui.components.CoverViewer
import org.squidify.android.ui.components.ErrorRetry
import androidx.compose.foundation.layout.Column
import org.squidify.android.ui.components.KhiDownloadQualityDialog
import org.squidify.android.ui.components.LoadingShelf
import org.squidify.android.ui.components.pressScale
import org.squidify.android.ui.theme.SqIcons
import org.squidify.android.util.Fmt

/** KHInsider album browser: browse, play, and download whole albums. */
@Composable
fun KhiAlbumScreen(
    albumUrl: String,
    onBack: () -> Unit,
    highlightTrackUrl: String? = null,
) {
    val graph = App.graph()
    val scope = rememberCoroutineScope()
    var album by remember { mutableStateOf<KhiAlbum?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showCoverViewer by remember { mutableStateOf(false) }
    var pendingTrack by remember { mutableStateOf<KhiTrack?>(null) }
    val settings by graph.settings.state().collectAsState()
    val player = graph.player
    val queue by player.queue.collectAsState()
    val idx by player.currentIndex.collectAsState()
    val isBufferingNow by player.isBuffering.collectAsState()
    val bufferProgress by player.bufferProgress.collectAsState()

    val listState = rememberLazyListState()

    LaunchedEffect(albumUrl, reload) {
        error = null
        try {
            val loadedAlbum = graph.khi.album(albumUrl)
            graph.offlineLibrary.cacheKhi(
                albumUrl,
                loadedAlbum,
                graph.khi.imageUrl(loadedAlbum.coverUrl),
            )
            album = loadedAlbum
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            error = e.message
        }
    }

    LaunchedEffect(album, highlightTrackUrl) {
        if (album != null && highlightTrackUrl != null) {
            val index = album!!.tracks.indexOfFirst { it.url == highlightTrackUrl }
            if (index >= 0) listState.scrollToItem(index + 1)
        }
    }

    fun playAll(startIndex: Int = 0, shuffled: Boolean = false) {
        val a = album ?: return
        val players = a.tracks.map {
            PlayerSong.fromKhi(a.name, it.title, it.url, it.duration, graph.khi.imageUrl(a.coverUrl))
        }
        // PlayerManager owns URL resolution and publishes the optimistic queue
        // before resolving. This lets the tapped row highlight immediately
        // and show its spinner instead of appearing unresponsive for seconds.
        player.play(players, startIndex, shuffled)
    }

    // Quality ask for a single KHInsider track download
    pendingTrack?.let { track ->
        KhiDownloadQualityDialog(
            trackTitle = track.title,
            onDismiss = { pendingTrack = null },
            onConfirm = { fmt ->
                val a = album
                if (a != null) {
                    graph.downloads.enqueueKhi(
                        albumName = a.name,
                        trackTitle = track.title,
                        trackUrl = track.url,
                        format = fmt,
                        qualityLabel = fmt.uppercase(),
                        coverUrl = graph.khi.imageUrl(a.coverUrl),
                    )
                }
                pendingTrack = null
            },
        )
    }

    if (showDownloadDialog && album != null) {
        val a = album!!
        val formats = a.availableFormats.filter { it.lowercase() != "cd" }.ifEmpty { listOf("mp3") }
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text("Download album") },
            text = {
                Column {
                    Text("${a.tracks.size} tracks · ${a.totalFilesize ?: "unknown size"}")
                    if (a.albumImages.size > 1) {
                        Text(
                            "Includes ${a.albumImages.size} cover images in original quality",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    formats.forEach { fmt ->
                        TextButton(onClick = {
                            val format = fmt.lowercase()
                            a.tracks.forEach { t ->
                                graph.downloads.enqueueKhi(
                                    albumName = a.name,
                                    trackTitle = t.title,
                                    trackUrl = t.url,
                                    format = format,
                                    qualityLabel = fmt.uppercase(),
                                    coverUrl = graph.khi.imageUrl(a.coverUrl),
                                )
                            }
                            // full album → save every cover image into the album folder
                            graph.downloads.enqueueKhiAlbumArt(a)
                            showDownloadDialog = false
                        }) { Text("Download ${fmt.uppercase()} — all tracks") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showDownloadDialog = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = album?.name ?: "KHInsider", onBack = onBack)
        when {
            error != null -> ErrorRetry("Couldn't load album: $error") { reload++ }
            album == null -> LoadingShelf(280.dp)
            else -> {
                val a = album!!
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
                ) {
                    item {
                        Row(Modifier.padding(16.dp)) {
                            CoverArt(
                                url = graph.khi.imageUrl(a.coverUrl),
                                modifier = Modifier
                                    .size(170.dp)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { showCoverViewer = true },
                                corner = 12.dp,
                                contentDescription = a.name,
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = a.name,
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = listOfNotNull(
                                        a.year,
                                        a.albumType,
                                        "${a.tracks.size} tracks",
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                if (!a.totalFilesize.isNullOrBlank()) {
                                    Text(
                                        text = a.totalFilesize,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 12.dp),
                                ) {
                                    val playIsr = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                    val shufIsr = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                    val dlIsr = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                    FilledIconButton(
                                        onClick = { playAll(0) },
                                        interactionSource = playIsr,
                                        modifier = Modifier
                                            .size(52.dp)
                                            .pressScale(playIsr, pressedScale = 0.92f),
                                    ) {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            contentDescription = "Play",
                                            modifier = Modifier.size(28.dp),
                                        )
                                    }
                                    FilledTonalIconButton(
                                        onClick = {
                                            // shuffle order is handled by the player itself
                                            playAll(shuffled = true)
                                        },
                                        interactionSource = shufIsr,
                                        modifier = Modifier
                                            .size(52.dp)
                                            .pressScale(shufIsr, pressedScale = 0.92f),
                                    ) {
                                        Icon(
                                            SqIcons.Shuffle,
                                            contentDescription = "Shuffle",
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                    FilledTonalIconButton(
                                        onClick = { showDownloadDialog = true },
                                        interactionSource = dlIsr,
                                        modifier = Modifier
                                            .size(56.dp)
                                            .pressScale(dlIsr, pressedScale = 0.92f),
                                        shape = RoundedCornerShape(16.dp),
                                    ) {
                                        Icon(
                                            SqIcons.Download,
                                            contentDescription = "Download album",
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // index-suffixed keys: multi-disc KHInsider albums can list
                    // the same file URL twice — duplicate keys crash Compose
                    items(a.tracks.size, key = { "t$it-${a.tracks[it].url}" }) { i ->
                        val t = a.tracks[i]
                        val currentQueueSong = queue.getOrNull(idx)
                        val isCurrent = currentQueueSong?.id == "khi:${t.url}"
                        // transient navigation highlight — yields to the real
                        // current track instead of staying lit forever
                        val highlighted = highlightTrackUrl == t.url &&
                            (currentQueueSong == null || isCurrent)
                        // per-row loading spinner: the tapped track visibly
                        // reacts right where the finger landed
                        val loading = (isCurrent || highlighted) &&
                            (isBufferingNow || currentQueueSong?.let { bufferProgress.containsKey(it.id) } == true)
                        // The badge shows the format that will ACTUALLY play with the
                        // current quality setting — "best" prefers FLAC when the
                        // album offers one, and a missing FLAC falls back to MP3.
                        val albumFormats = a.availableFormats.map { it.lowercase() }
                            .filter { it != "cd" }
                        val actualFormat = when (settings.khiQuality) {
                            org.squidify.android.data.KhiQuality.MP3 -> "MP3"
                            else -> if ("flac" in albumFormats) "FLAC" else "MP3"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { playAll(i) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = t.number.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCurrent || highlighted) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(36.dp),
                            )
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = t.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isCurrent || highlighted) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = listOfNotNull(
                                        t.duration,
                                        t.fileSize,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = actualFormat,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            IconButton(onClick = { pendingTrack = t }) {
                                Icon(
                                    SqIcons.Download,
                                    contentDescription = "Download track",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCoverViewer) {
        CoverViewer(
            url = album?.let { graph.khi.imageUrl(it.coverUrl) },
            title = album?.name,
            onDismiss = { showCoverViewer = false },
        )
    }
}
