package org.squidify.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.squidify.android.data.Album
import org.squidify.android.data.Artist
import org.squidify.android.data.Genre
import org.squidify.android.data.Playlist
import org.squidify.android.ui.screens.library.FavouriteAlbumsTab
import org.squidify.android.ui.screens.library.FavouriteSongsTab
import org.squidify.android.ui.screens.library.HistoryTab

/**
 * "Library" destination: listening history and saved music.
 * Server browsing stays in Search/Home.
 */
@Composable
fun LibraryScreen(
    onAlbumClick: (Album) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onGenreClick: (Genre) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
) {
    val sections = listOf("History", "Songs", "Albums")
    var selected by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Text(
            text = "Library",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 18.dp),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                sections.forEachIndexed { index, label ->
                    val isSelected = selected == index
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .clickable { selected = index },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 5.dp, vertical = 13.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            when (selected) {
                0 -> HistoryTab(onAlbumClick = onAlbumClick, onArtistClick = onArtistClick)
                1 -> FavouriteSongsTab()
                else -> FavouriteAlbumsTab(onAlbumClick = onAlbumClick)
            }
        }
    }
}
