@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package org.squidify.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.squidify.android.App
import org.squidify.android.data.Album
import org.squidify.android.data.Artist
import org.squidify.android.data.NativeSong
import org.squidify.android.player.PlayerSong
import org.squidify.android.ui.components.AlbumCard
import org.squidify.android.ui.components.ErrorRetry
import org.squidify.android.ui.components.LoadingShelf
import org.squidify.android.ui.components.SectionHeader
import org.squidify.android.ui.components.SongRow
import org.squidify.android.ui.screens.library.SongActionsHandler
import org.squidify.android.util.Fmt

@Composable
fun ArtistScreen(
    artistId: String,
    onBack: () -> Unit,
    onAlbumClick: (Album) -> Unit,
) {
    val graph = App.graph()
    val scope = rememberCoroutineScope()
    val player = graph.player
    val queue by player.queue.collectAsState()
    val currentIndex by player.currentIndex.collectAsState()
    val isBuffering by player.isBuffering.collectAsState()
    var artist by remember { mutableStateOf<Artist?>(null) }
    var albums by remember { mutableStateOf<List<Album>?>(null) }
    var topSongs by remember { mutableStateOf<List<org.squidify.android.data.Song>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    val actions = remember { SongActionsHandler(onGoToArtist = null) }
    actions.Render()

    LaunchedEffect(artistId, reload) {
        error = null
        try {
            val (a, al) = graph.subsonic.getArtist(artistId)
            artist = a
            albums = al
            try {
                val nativeTop = graph.native.songs(
                    sort = "playCount", order = "desc", start = 0, end = 10, artistId = artistId,
                )
                topSongs = nativeTop.map { it.toSong() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                topSongs = emptyList()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            error = e.message
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = artist?.name ?: "Artist",
            onBack = onBack,
            starState = artist?.starred != null,
            onStar = {
                val a = artist ?: return@TopBar
                scope.launch {
                    try {
                        if (a.starred != null) {
                            graph.subsonic.unstarArtist(a.id)
                            artist = a.copy(starred = null)
                        } else {
                            graph.subsonic.starArtist(a.id)
                            artist = a.copy(starred = "now")
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                    }
                }
            },
        )

        when {
            error != null -> ErrorRetry("Couldn't load artist: $error") { reload++ }
            artist == null -> LoadingShelf(240.dp)
            else -> {
                val a = artist!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
                ) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Text(
                                text = a.name,
                                style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = Fmt.songCount(a.albumCount ?: 0),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (!albums.isNullOrEmpty()) {
                        item { SectionHeader("Albums") }
                        item {
                            LazyRow(
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(albums!!.size) { i ->
                                    AlbumCard(
                                        album = albums!![i],
                                        onClick = { onAlbumClick(albums!![i]) },
                                        coverUrl = albums!![i].coverArtUrl(),
                                    )
                                }
                            }
                        }
                    }

                    if (!topSongs.isNullOrEmpty()) {
                        item { SectionHeader("Top Songs") }
                        items(topSongs!!.size, key = { topSongs!![it].id }) { i ->
                            val song = topSongs!![i]
                            val isCurrent = queue.getOrNull(currentIndex)?.id == song.id
                            SongRow(
                                song = song,
                                onClick = {
                                    scope.launch {
                                        App.graph().player.play(
                                            topSongs!!.map { PlayerSong.from(it) }, i,
                                        )
                                    }
                                },
                                coverUrl = SongCover(song),
                                isCurrent = isCurrent,
                                isLoading = isCurrent && isBuffering,
                                showAlbum = true,
                                trailingText = Fmt.duration(song.duration?.toLong()),
                                onMenuClick = { actions.show(song) },
                            )
                        }
                    }
                }
            }
        }
    }
}
