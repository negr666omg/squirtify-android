package org.squidify.android.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.squidify.android.App
import org.squidify.android.data.Album
import org.squidify.android.ui.components.AlbumCard
import org.squidify.android.ui.components.EmptyState
import org.squidify.android.ui.components.ErrorRetry
import org.squidify.android.ui.components.LoadingShelf

@Composable
fun GenreScreen(genreName: String, onBack: () -> Unit, onAlbumClick: (Album) -> Unit) {
    val graph = App.graph()
    var albums by remember { mutableStateOf<List<Album>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(genreName, reload) {
        try {
            albums = graph.subsonic.getAlbumList2("byGenre", size = 60, genre = genreName)
            error = null
        } catch (e: Exception) {
            error = e.message
        }
    }

    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        TopBar(title = genreName, onBack = onBack)
        when {
            error != null -> ErrorRetry("Couldn't load genre: $error") { reload++ }
            albums == null -> LoadingShelf(200.dp)
            albums!!.isEmpty() -> EmptyState("No albums in this genre")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp, top = 8.dp, bottom = 140.dp,
                ),
            ) {
                items(albums!!, key = { it.id }) { album ->
                    AlbumCard(
                        album = album,
                        onClick = { onAlbumClick(album) },
                        coverUrl = album.coverArtUrl(),
                        modifier = Modifier.padding(4.dp),
                        width = androidx.compose.ui.unit.Dp.Unspecified,
                    )
                }
            }
        }
    }
}
