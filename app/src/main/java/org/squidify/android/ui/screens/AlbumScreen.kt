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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.squidify.android.App
import org.squidify.android.data.Album
import org.squidify.android.data.Song
import org.squidify.android.player.PlayerSong
import org.squidify.android.ui.components.CoverArt
import org.squidify.android.ui.components.CoverViewer
import org.squidify.android.ui.components.DownloadStateIcon
import org.squidify.android.ui.components.EmptyState
import org.squidify.android.ui.components.ErrorRetry
import org.squidify.android.ui.components.LoadingShelf
import org.squidify.android.ui.components.SongRow
import org.squidify.android.ui.components.SquidifyAlbumDownloadQualityDialog
import org.squidify.android.ui.components.pressScale
import org.squidify.android.ui.screens.library.SongActionsHandler
import org.squidify.android.ui.theme.SqIcons
import org.squidify.android.util.Fmt

@Composable
fun AlbumScreen(
    albumId: String,
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit,
    highlightTrackId: String? = null,
) {
    val graph = App.graph()
    val scope = rememberCoroutineScope()
    var album by remember { mutableStateOf<Album?>(null) }
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    val actions = remember { SongActionsHandler(onGoToArtist = { s -> s.artistId?.let(onArtistClick) }) }
    actions.Render()
    var showAlbumDownloadDialog by remember { mutableStateOf(false) }
    var showCoverViewer by remember { mutableStateOf(false) }
    val favAlbumIds by graph.favorites.albumIds.collectAsState()

    LaunchedEffect(albumId, reload) {
        error = null
        try {
            val (a, s) = graph.subsonic.getAlbum(albumId)
            album = a
            songs = s
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            error = e.message
        }
    }

    val player = graph.player
    val queue by player.queue.collectAsState()
    val idx by player.currentIndex.collectAsState()
    val downloads by graph.downloads.jobs.collectAsState()
    val bufferProgress by player.bufferProgress.collectAsState()
    val isBufferingNow by player.isBuffering.collectAsState()

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
                    // favourites are stored locally in Squidify/UserData
                    graph.favorites.toggleAlbum(a)
                }
            },
        )

        when {
            error != null -> ErrorRetry("Couldn't load album: $error") { reload++ }
            album == null || songs == null -> LoadingShelf(240.dp)
            else -> {
                val a = album!!
                val list = songs!!
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            CoverArt(
                                // 600px — plenty for a 170dp header and the
                                // SAME asset the lists already cached; the
                                // original (size=0) re-downloaded megabytes
                                // of artwork on every album open
                                url = a.coverArtUrl(600),
                                modifier = Modifier
                                    .size(170.dp)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(24.dp))
                                    .clickable { showCoverViewer = true },
                                corner = 24.dp,
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
                                    text = a.artist ?: "",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .padding(top = 4.dp)
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
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 12.dp),
                                ) {
                                    val playIsr = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                    val shufIsr = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                    val dlIsr = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                                    FilledIconButton(
                                        onClick = {
                                            scope.launch {
                                                player.play(list.map { PlayerSong.from(it) }, 0)
                                            }
                                        },
                                        interactionSource = playIsr,
                                        modifier = Modifier
                                            .size(52.dp)
                                            .pressScale(playIsr, pressedScale = 0.92f),
                                        colors = IconButtonDefaults.filledIconButtonColors(),
                                    ) {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            contentDescription = "Play",
                                            modifier = Modifier.size(28.dp),
                                        )
                                    }
                                    FilledTonalIconButton(
                                        onClick = {
                                            scope.launch {
                                                // shuffle order is handled by the player itself
                                                player.playShuffled(list.map { PlayerSong.from(it) })
                                            }
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
                                        onClick = { showAlbumDownloadDialog = true },
                                        interactionSource = dlIsr,
                                        modifier = Modifier
                                            .size(52.dp)
                                            .pressScale(dlIsr, pressedScale = 0.92f),
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

                    items(list.size, key = { list[it].id }) { i ->
                        val song = list[i]
                        val currentId = queue.getOrNull(idx)?.id
                        val isCurrent = currentId == song.id
                        val downloadStatus = downloads.firstOrNull {
                            it.source == org.squidify.android.data.DownloadSource.SQUIDIFY && it.songId == song.id
                        }?.status
                        // Loading bar under the row: whole-track prefetch for
                        // playback, or an active download job
                        val progress = bufferProgress[song.id]
                            ?: downloads.firstOrNull {
                                it.source == org.squidify.android.data.DownloadSource.SQUIDIFY &&
                                    it.songId == song.id && it.status == org.squidify.android.data.DownloadStatus.RUNNING
                            }?.progress
                        // The navigation highlight is TRANSIENT: it marks the track
                        // this screen was opened for (deep link from Now Playing)
                        // and yields as soon as playback actually moves — the
                        // frozen nav argument used to keep that row lit FOREVER
                        // next to the really-playing one ("two highlighted
                        // tracks at once").
                        val highlighted = highlightTrackId == song.id &&
                            (currentId == null || currentId == song.id)
                        // Loading feedback lives ON the tapped row (spinner at
                        // its trailing edge), not on the album header — the eye
                        // stays where the tap happened.
                        val loading = (isCurrent || highlighted) &&
                            (isBufferingNow || bufferProgress.containsKey(song.id))
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
        SquidifyAlbumDownloadQualityDialog(
            trackCount = list.size,
            sourceDescription = list.firstOrNull()?.formatDescription?.takeIf { it.isNotBlank() },
            onDismiss = { showAlbumDownloadDialog = false },
            onConfirm = { q ->
                graph.downloads.enqueueSquidifyAll(list, q)
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
fun TopBar(
    title: String,
    onBack: () -> Unit,
    starState: Boolean = false,
    onStar: (() -> Unit)? = null,
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
