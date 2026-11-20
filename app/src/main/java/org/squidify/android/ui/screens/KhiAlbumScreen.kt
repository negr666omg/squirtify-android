@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package org.squidify.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.squidify.android.App
import org.squidify.android.data.KhiAlbum
import org.squidify.android.data.KhiTrack
import org.squidify.android.player.PlayerSong
import org.squidify.android.ui.components.CoverArt
import org.squidify.android.ui.components.CoverViewer
import org.squidify.android.ui.components.EmptyState
import org.squidify.android.ui.components.ErrorRetry
import org.squidify.android.ui.components.KhiDownloadQualityDialog
import org.squidify.android.ui.components.LoadingShelf
import org.squidify.android.ui.components.adaptiveArtworkSize
import org.squidify.android.ui.components.pressScale
import org.squidify.android.ui.theme.SqIcons

/** KHInsider album browser, using the same album layout as Squidify albums. */
@Composable
fun KhiAlbumScreen(
    albumUrl: String,
    onBack: () -> Unit,
    highlightTrackUrl: String? = null,
) {
    val graph = App.graph()
    val scope = rememberCoroutineScope()
    var album by remember(albumUrl) { mutableStateOf<KhiAlbum?>(null) }
    var error by remember(albumUrl) { mutableStateOf<String?>(null) }
    var reload by remember(albumUrl) { mutableStateOf(0) }
    var showDownloadDialog by remember(albumUrl) { mutableStateOf(false) }
    var showCoverViewer by remember(albumUrl) { mutableStateOf(false) }
    var pendingTrack by remember(albumUrl) { mutableStateOf<KhiTrack?>(null) }
    var searchOpen by remember(albumUrl) { mutableStateOf(false) }
    var searchQuery by remember(albumUrl) { mutableStateOf("") }

    val settings by graph.settings.state().collectAsState()
    val player = graph.player
    val queue by player.queue.collectAsState()
    val idx by player.currentIndex.collectAsState()
    val isBufferingNow by player.isBuffering.collectAsState()
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
        // PlayerManager publishes the optimistic queue before resolving the URL.
        player.play(players, startIndex, shuffled)
    }

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
                            graph.downloads.enqueueKhiAlbumArt(a)
                            showDownloadDialog = false
                        }) {
                            Text("Download ${fmt.uppercase()} — all tracks")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDownloadDialog = false }) { Text("Cancel") }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = album?.name ?: "KHInsider",
            onBack = onBack,
            searchActive = searchOpen,
            onSearch = {
                searchOpen = !searchOpen
                if (!searchOpen) searchQuery = ""
            },
        )

        if (searchOpen) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search tracks") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        when {
            error != null -> ErrorRetry("Couldn't load album: $error") { reload++ }
            album == null -> LoadingShelf(240.dp)
            else -> {
                val a = album!!
                val visibleIndices = remember(a.tracks, searchQuery) {
                    a.tracks.indices.filter { index ->
                        searchQuery.isBlank() ||
                            a.tracks[index].title.contains(searchQuery, ignoreCase = true)
                    }
                }
                val albumFormats = remember(a.availableFormats) {
                    a.availableFormats.map { it.lowercase() }.filter { it != "cd" }
                }
                val actualFormat = remember(settings.khiQuality, albumFormats) {
                    when (settings.khiQuality) {
                        org.squidify.android.data.KhiQuality.MP3 -> "MP3"
                        else -> if ("flac" in albumFormats) "FLAC" else "MP3"
                    }
                }
                val displayFormats = remember(a.availableFormats) {
                    a.availableFormats.filter { it.lowercase() != "cd" }
                        .map { it.uppercase() }
                        .distinct()
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 140.dp),
                ) {
                    item(key = "album-header") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(
                                topStart = 38.dp,
                                topEnd = 38.dp,
                                bottomEnd = 38.dp,
                                bottomStart = 18.dp,
                            ),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CoverArt(
                                    url = graph.khi.imageUrl(a.coverUrl),
                                    modifier = Modifier
                                        .size(216.dp)
                                        .clickable { showCoverViewer = true },
                                    corner = 32.dp,
                                    contentDescription = a.name,
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = a.albumType?.uppercase() ?: "ALBUM",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = a.name,
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = listOfNotNull(
                                        a.year,
                                        "${a.tracks.size} tracks",
                                        a.totalFilesize?.takeIf { it.isNotBlank() },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 5.dp),
                                )
                                if (displayFormats.isNotEmpty()) {
                                    Spacer(Modifier.height(5.dp))
                                    Text(
                                        text = displayFormats.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val playIsr = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            val shuffleIsr = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            val queueIsr = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            val downloadIsr = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

                            FilledTonalButton(
                                onClick = { playAll(0) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Play")
                            }
                            FilledTonalIconButton(
                                onClick = { playAll(shuffled = true) },
                                interactionSource = shuffleIsr,
                                modifier = Modifier
                                    .size(52.dp)
                                    .pressScale(shuffleIsr, pressedScale = 0.92f),
                            ) {
                                Icon(SqIcons.Shuffle, contentDescription = "Shuffle", modifier = Modifier.size(24.dp))
                            }
                            FilledTonalIconButton(
                                onClick = {
                                    val songs = a.tracks.map {
                                        PlayerSong.fromKhi(a.name, it.title, it.url, it.duration, graph.khi.imageUrl(a.coverUrl))
                                    }
                                    scope.launch { player.addToQueue(songs) }
                                },
                                interactionSource = queueIsr,
                                modifier = Modifier
                                    .size(52.dp)
                                    .pressScale(queueIsr, pressedScale = 0.92f),
                            ) {
                                Icon(SqIcons.PlaylistAdd, contentDescription = "Add album to queue", modifier = Modifier.size(24.dp))
                            }
                            FilledTonalIconButton(
                                onClick = { showDownloadDialog = true },
                                interactionSource = downloadIsr,
                                modifier = Modifier
                                    .size(52.dp)
                                    .pressScale(downloadIsr, pressedScale = 0.92f),
                            ) {
                                Icon(SqIcons.Download, contentDescription = "Download album", modifier = Modifier.size(24.dp))
                            }
                        }
                    }

                    if (visibleIndices.isEmpty() && searchQuery.isNotBlank()) {
                        item(key = "no-matching-tracks") { EmptyState("No matching tracks") }
                    }

                    // Keep original indices for playback and stable unique keys
                    // (multi-disc albums can list the same file URL more than once).
                    items(
                        count = visibleIndices.size,
                        key = { visibleIndex -> "t$visibleIndex-${a.tracks[visibleIndices[visibleIndex]].url}" },
                    ) { visibleIndex ->
                        val trackIndex = visibleIndices[visibleIndex]
                        val track = a.tracks[trackIndex]
                        val currentQueueSong = queue.getOrNull(idx)
                        val isCurrent = currentQueueSong?.id == "khi:${track.url}"
                        val highlighted = highlightTrackUrl == track.url &&
                            (currentQueueSong == null || isCurrent)

                        // Subscribe each row only to its own buffer progress, not
                        // the whole map, so buffering doesn't recompose the list.
                        val trackBufferProgress by remember(track.url) {
                            player.bufferProgress
                                .map { it["khi:${track.url}"] }
                                .distinctUntilChanged()
                        }.collectAsState(initial = null)
                        val loading = (isCurrent || highlighted) &&
                            (isBufferingNow || trackBufferProgress != null)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { playAll(trackIndex) }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = track.number.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCurrent || highlighted) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(34.dp),
                            )
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isCurrent || highlighted) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val trackDetails = listOfNotNull(
                                    track.duration?.takeIf { it.isNotBlank() },
                                    track.fileSize?.takeIf { it.isNotBlank() },
                                    track.bitrate?.takeIf { it.isNotBlank() },
                                ).joinToString(" · ")
                                if (trackDetails.isNotEmpty()) {
                                    Text(
                                        text = trackDetails,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.padding(horizontal = 6.dp),
                            ) {
                                Text(
                                    text = actualFormat,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                )
                            }
                            IconButton(onClick = { pendingTrack = track }) {
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
