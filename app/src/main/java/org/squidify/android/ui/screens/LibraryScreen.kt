package org.squidify.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.squidify.android.data.Album
import org.squidify.android.data.Artist
import org.squidify.android.data.Genre
import org.squidify.android.data.Playlist
import org.squidify.android.ui.screens.library.FavouriteAlbumsTab
import org.squidify.android.ui.screens.library.FavouriteSongsTab
import org.squidify.android.ui.screens.library.HistoryTab

/**
 * "Library" destination: what YOU listened to on this device, plus your
 * favourites. Server browsing lives in Search/Home.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onAlbumClick: (Album) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onGenreClick: (Genre) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
) {
    val sections = listOf("History", "Favourite songs", "Favourite albums")
    var selected by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = "COLLECTION",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Your library",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Everything you have saved and played.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PrimaryTabRow(selectedTabIndex = selected) {
            sections.forEachIndexed { index, label ->
                Tab(
                    selected = selected == index,
                    onClick = { selected = index },
                    text = { Text(label) },
                )
            }
        }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            when (selected) {
                0 -> HistoryTab(onAlbumClick = onAlbumClick, onArtistClick = onArtistClick)
                1 -> FavouriteSongsTab()
                else -> FavouriteAlbumsTab(onAlbumClick = onAlbumClick)
            }
        }
    }
}
