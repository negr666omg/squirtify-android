@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package org.squidify.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.squidify.android.App
import org.squidify.android.data.Album
import org.squidify.android.data.Song
import org.squidify.android.data.StreamQuality
import org.squidify.android.player.PlayerSong
import org.squidify.android.ui.components.CoverArt
import org.squidify.android.ui.components.CoverViewer
import org.squidify.android.ui.components.adaptiveArtworkSize
import org.squidify.android.ui.components.DownloadStateIcon
import org.squidify.android.ui.components.EmptyState
import org.squidify.android.ui.components.ErrorRetry
import org.squidify.android.ui.components.LoadingShelf
import org.squidify.android.ui.components.SongRow
import org.squidify.android.ui.components.pressScale
import org.squidify.android.ui.screens.library.SongActionsHandler
import org.squidify.android.ui.theme.SqIcons
import org.squidify.android.util.Fmt

private object AlbumScreenMemoryCache {
    private const val MAX_ENTRIES = 12
    private const val MAX_AGE_MS = 20 * 60 * 1000L

    data class Snapshot(
        val album: Album,
        val songs: List<Song>,
        val savedAtMs: Long,
    )

    private val entries = LinkedHashMap<String, Snapshot>(16, 0.75f, true)

    @Synchronized
    fun get(albumId: String): Snapshot? {
        val snapshot = entries[albumId] ?: return null
        if (System.currentTimeMillis() - snapshot.savedAtMs > MAX_AGE_MS) {
            entries.remove(albumId)
            return null
        }
        return snapshot
    }

    @Synchronized
    fun put(albumId: String, album: Album, songs: List<Song>) {
        entries[albumId] = Snapshot(album, songs, System.currentTimeMillis())
        while (entries.size > MAX_ENTRIES) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }
}

@Composable
fun AlbumScreen(
    albumId: String,
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit,
    highlightTrackId: String? = null,
) {
    val graph = App.graph()
    val scope = rememberCoroutineScope()
    val cachedAlbum = remember(albumId) { AlbumScreenMemoryCache.get(albumId) }
    var album by remember(albumId) { mutableStateOf(cachedAlbum?.album) }
    var songs by remember(albumId) { mutableStateOf(cachedAlbum?.songs) }
    var error by remember(albumId) { mutableStateOf<String?>(null) }
    var reload by remember(albumId) { mutableStateOf(0) }
    var searchOpen by remember(albumId) { mutableStateOf(false) }
    var searchQuery by remember(albumId) { mutableStateOf("") }

    val actions = remember { SongActionsHandler(onGoToArtist = { s -> s.artistId?.let(onArtistClick) }) }
    actions.Render()
    var showAlbumDownloadDialog by remember { mutableStateOf(false) }
    var showCoverViewer by remember { mutableStateOf(false) }
    val favAlbumIds by graph.favorites.albumIds.collectAsState()

    LaunchedEffect(albumId, reload) {
        if (reload == 0 && album != null && songs != null) return@LaunchedEffect
        error = null
        try {
            val (a, s) = graph.subsonic.getAlbum(albumId)
            graph.offlineLibrary.cacheSquidify(a, s, a.coverArtUrl(600))
            album = a
            songs = s
            AlbumScreenMemoryCache.put(albumId, a, s)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            error = e.message
        }
    }

    val player = graph.player
    val queue by player.queue.collectAsState()
    val idx by player.currentIndex.collectAsState()
    val isBufferingNow by player.isBuffering.collectAsState()
    val currentSongId = queue.getOrNull(idx)?.id

    val listState = rememberLazyListState()

    // Scroll to (and mark) the highlighted track when opened from Now Playing
    LaunchedEffect(songs, highlightTrackId) {
        if (songs != null && highlightTrackId != null) {
            val index = songs!!.indexOfFirst { it.id == highlightTrackId }
            if (index >= 0) listState.scrollToItem(index + 1) // +1 for the header item
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = album?.name ?: "Album",
            onBack = onBack,
            starState = album?.id in favAlbumIds,
            onStar = {
                val a = album ?: return@TopBar
                scope.launch {
                    // favourites are stored locally in Squirtify/UserData
                    graph.favorites.toggleAlbum(a)
                }
            },
            onSearch = {
                searchOpen = !searchOpen
                if (!searchOpen) searchQuery = ""
            },
            searchActive = searchOpen,
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
            album == null || songs == null -> LoadingShelf(240.dp)
            else -> {
                val a = album!!
                val list = songs!!
                val visibleIndices = remember(list, searchQuery) {
                    list.indices.filter { index ->
                        val song = list[index]
                        searchQuery.isBlank() ||
                            song.title.contains(searchQuery, ignoreCase = true) ||
                            song.artist.orEmpty().contains(searchQuery, ignoreCase = true)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
                ) {
                    item {
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
                                    url = a.coverArtUrl(adaptiveArtworkSize(216.dp)),
                                    modifier = Modifier
                                        .size(216.dp)
                                        .clickable { showCoverViewer = true },
                                    corner = 32.dp,
                                    contentDescription = a.name,
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = "ALBUM",
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
                                    text = a.artist ?: "",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(top = 5.dp)
                                        .clickable { a.artistId?.let(onArtistClick) },
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = listOfNotNull(
                                        a.year?.toString(),
                                        Fmt.songCount(a.songCount ?: list.size),
                                        Fmt.totalDuration(a.duration?.toLong() ?: list.sumOf { (it.duration ?: 0).toLong() }),
                                        a.genre,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val shufIsr = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            val dlIsr = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            androidx.compose.material3.FilledTonalButton(
                                onClick = {
                                    scope.launch { player.play(list.map { PlayerSong.from(it) }, 0) }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Play")
                            }
                            FilledTonalIconButton(
                                onClick = {
                                    scope.launch { player.playShuffled(list.map { PlayerSong.from(it) }) }
                                },
                                interactionSource = shufIsr,
                                modifier = Modifier
                                    .size(52.dp)
                                    .pressScale(shufIsr, pressedScale = 0.92f),
                            ) {
                                Icon(SqIcons.Shuffle, contentDescription = "Shuffle", modifier = Modifier.size(24.dp))
                            }
                            androidx.compose.material3.FilledTonalButton(
                                onClick = {
                                    scope.launch { player.addToQueue(list.map { PlayerSong.from(it) }) }
                                },
                                modifier = Modifier.height(52.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 13.dp),
                            ) {
                                Icon(
                                    SqIcons.PlaylistAdd,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Queue")
                            }
                            FilledTonalIconButton(
                                onClick = { showAlbumDownloadDialog = true },
                                interactionSource = dlIsr,
                                modifier = Modifier
                                    .size(52.dp)
                                    .pressScale(dlIsr, pressedScale = 0.92f),
                            ) {
                                Icon(SqIcons.Download, contentDescription = "Download album", modifier = Modifier.size(24.dp))
                            }
                        }
                    }

                    if (visibleIndices.isEmpty() && searchQuery.isNotBlank()) {
                        item { EmptyState("No matching tracks") }
                    }
                    items(visibleIndices.size, key = { list[visibleIndices[it]].id }) { visibleIndex ->
                        val i = visibleIndices[visibleIndex]
                        val song = list[i]
                        // Subscribe to only THIS track's prefetch progress. The
                        // global map updates while a full track buffers; collecting
                        // it at screen level forced the whole album list to recompose.
                        val trackBufferProgress by remember(song.id) {
                            player.bufferProgress
                                .map { it[song.id] }
                                .distinctUntilChanged()
                        }.collectAsState(initial = null)
                        val isCurrent = currentSongId == song.id
                        val download by remember(song.id) {
                            graph.downloads.jobs
                                .map { jobs ->
                                    jobs.firstOrNull {
                                        it.source == org.squidify.android.data.DownloadSource.SQUIDIFY &&
                                            it.songId == song.id
                                    }
                                }
                                .distinctUntilChanged()
                        }.collectAsState(initial = null)
                        val downloadStatus = download?.status
                        // Loading bar under the row: whole-track prefetch for
                        // playback, or an active download job
                        val progress = trackBufferProgress
                            ?: download?.takeIf {
                                it.status == org.squidify.android.data.DownloadStatus.RUNNING
                            }?.progress
                        // The navigation highlight is TRANSIENT: it marks the track
                        // this screen was opened for (deep link from Now Playing)
                        // and yields as soon as playback actually moves — the
                        // frozen nav argument used to keep that row lit FOREVER
                        // next to the really-playing one ("two highlighted
                        // tracks at once").
                        val highlighted = highlightTrackId == song.id &&
                            (currentSongId == null || currentSongId == song.id)
                        // Loading feedback lives ON the tapped row (spinner at
                        // its trailing edge), not on the album header — the eye
                        // stays where the tap happened.
                        val loading = (isCurrent || highlighted) &&
                            (isBufferingNow || trackBufferProgress != null)
                        SongRow(
                            song = song,
                            index = i,
                            showIndex = true,
                            showCover = false,
                            isCurrent = isCurrent || highlighted,
                            isLoading = loading,
                            onClick = {
                                scope.launch { player.play(list.map { PlayerSong.from(it) }, i) }
                            },
                            // long-press → track menu (like / queue / cache…);
                            // the row itself stays clean: number, title, duration
                            onLongClick = { actions.show(song) },
                            downloadIcon = { DownloadStateIcon(downloadStatus) },
                            trailingText = Fmt.duration(song.duration?.toLong()),
                            progress = progress,
                        )
                    }
                }
            }
        }
    }

    if (showAlbumDownloadDialog && songs != null) {
        val list = songs!!
        AlbumDownloadQualityPicker(
            trackCount = list.size,
            sourceDescription = list.firstOrNull()?.formatDescription?.takeIf { it.isNotBlank() },
            onDismiss = { showAlbumDownloadDialog = false },
            onConfirm = { q ->
                graph.downloads.enqueueSquirtifyAll(list, q)
                showAlbumDownloadDialog = false
            },
        )
    }

    if (showCoverViewer) {
        CoverViewer(
            url = album?.coverArtUrl(0),
            title = album?.name,
            onDismiss = { showCoverViewer = false },
        )
    }
}


@Composable
private fun AlbumDownloadQualityPicker(
    trackCount: Int,
    sourceDescription: String?,
    onDismiss: () -> Unit,
    onConfirm: (StreamQuality) -> Unit,
) {
    val current = App.graph().settings.state().value.streamingQuality
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download album — $trackCount tracks") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                sourceDescription?.let {
                    Text(
                        text = "Source: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                StreamQuality.entries.forEach { quality ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm(quality) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = quality == current,
                            onClick = { onConfirm(quality) },
                        )
                        Text(
                            text = quality.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun TopBar(
    title: String,
    onBack: () -> Unit,
    starState: Boolean = false,
    onStar: (() -> Unit)? = null,
    searchActive: Boolean = false,
    onSearch: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
            if (onSearch != null) {
                IconButton(onClick = onSearch) {
                    Icon(
                        imageVector = if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = if (searchActive) "Close track search" else "Search tracks",
                        tint = if (searchActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onStar != null) {
                IconButton(onClick = onStar) {
                    Icon(
                        imageVector = if (starState) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (starState) "Unfavourite" else "Favourite",
                        tint = if (starState) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
