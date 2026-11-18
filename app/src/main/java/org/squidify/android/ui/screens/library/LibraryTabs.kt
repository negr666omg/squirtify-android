package org.squidify.android.ui.screens.library

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.squidify.android.App
import org.squidify.android.data.Album
import org.squidify.android.player.PlayerSong
import org.squidify.android.ui.components.AlbumCard
import org.squidify.android.ui.components.EmptyState
import org.squidify.android.ui.components.SongRow
import org.squidify.android.ui.theme.SqIcons
import org.squidify.android.util.Fmt

// ------------------------------------------------------------ favourite songs

/**
 * Favourite songs are stored locally (Squidify/UserData/favorites.json):
 * no network needed to show them, hearts work for KHInsider tracks too.
 */
@Composable
fun FavouriteSongsTab() {
    val graph = App.graph()
    val scope = rememberCoroutineScope()
    val favorites by graph.favorites.songs.collectAsState()
    val actions = remember { SongActionsHandler() }
    actions.Render()

    if (favorites.isEmpty()) {
        EmptyState("No favourite songs yet — tap ♥ on any track")
        return
    }

    val songs = favorites
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${songs.size} songs",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(
                    onClick = {
                        AppLog2("Shuffling ${songs.size} favourite songs")
                        scope.launch {
                            App.graph().player.playShuffled(songs.map { it.toPlayerSong() })
                        }
                    },
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Icon(
                        imageVector = SqIcons.Shuffle,
                        contentDescription = "Shuffle favourites",
                    )
                }
            }
        }
        items(songs.size, key = { songs[it].id }) { i ->
            val fav = songs[i]
            SongRow(
                song = org.squidify.android.data.Song(
                    id = fav.id,
                    title = fav.title,
                    album = fav.album,
                    artist = fav.artist,
                    duration = (fav.durationMs / 1000L).toInt().takeIf { it > 0 },
                    coverArt = fav.coverArtId,
                    starred = "now",
                    albumId = fav.albumId,
                    artistId = fav.artistId,
                ),
                onClick = {
                    scope.launch {
                        App.graph().player.play(songs.map { it.toPlayerSong() }, i)
                    }
                },
                showAlbum = true,
                coverUrl = fav.coverUrl ?: fav.coverArtId?.let {
                    graph.subsonic.coverUrl(it, 480)
                },
                trailingText = if (fav.durationMs > 0) Fmt.durationMs(fav.durationMs) else null,
                onHeartClick = {
                    scope.launch { graph.favorites.toggleSong(fav.toPlayerSong()) }
                },
            )
        }
    }
}

// ------------------------------------------------------------ favourite albums

/** Favourite albums, stored locally — tap ♥ on any album page. */
@Composable
fun FavouriteAlbumsTab(onAlbumClick: (Album) -> Unit) {
    val graph = App.graph()
    val favorites by graph.favorites.albums.collectAsState()

    if (favorites.isEmpty()) {
        EmptyState("No favourite albums yet — tap ♥ on any album")
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp, end = 12.dp, top = 8.dp, bottom = 140.dp,
        ),
    ) {
        items(favorites, key = { it.id }) { fav ->
            AlbumCard(
                album = Album(
                    id = fav.id,
                    name = fav.name,
                    artist = fav.artist,
                    artistId = fav.artistId,
                    coverArt = fav.coverArtId,
                    year = fav.year,
                    songCount = fav.songCount,
                ),
                onClick = {
                    onAlbumClick(
                        Album(
                            id = fav.id,
                            name = fav.name,
                            artist = fav.artist,
                            artistId = fav.artistId,
                            coverArt = fav.coverArtId,
                            year = fav.year,
                            songCount = fav.songCount,
                        ),
                    )
                },
                coverUrl = fav.coverArtId?.let { graph.subsonic.coverUrl(it, 480) },
                modifier = Modifier.padding(4.dp),
                width = androidx.compose.ui.unit.Dp.Unspecified,
            )
        }
    }
}

private fun AppLog2(message: String) =
    org.squidify.android.util.AppLog.d("UI", message)
