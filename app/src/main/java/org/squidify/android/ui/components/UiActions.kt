package org.squidify.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.squidify.android.App
import org.squidify.android.data.Playlist
import org.squidify.android.data.Song
import org.squidify.android.ui.theme.SqIcons

/** Bottom sheet with the standard track actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionsSheet(
    song: Song,
    onDismiss: () -> Unit,
    isHearted: Boolean = false,
    onToggleHeart: (() -> Unit)? = null,
    onPlayNext: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
    onDownload: () -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
    extraAction: Pair<String, (() -> Unit)>? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = song.artist ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(12.dp))
            if (onToggleHeart != null) {
                SheetAction(
                    icon = {
                        Icon(
                            if (isHearted) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            null,
                            tint = if (isHearted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    label = if (isHearted) "Remove from favourites" else "Like",
                ) {
                    onToggleHeart(); onDismiss()
                }
            }
            SheetAction(icon = { Icon(Icons.Filled.PlayArrow, null) }, label = "Play next") {
                onPlayNext(); onDismiss()
            }
            SheetAction(icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, label = "Add to queue") {
                onAddToQueue(); onDismiss()
            }
            SheetAction(icon = { Icon(Icons.Filled.Add, null) }, label = "Add to playlist…") {
                onAddToPlaylist(); onDismiss()
            }
            SheetAction(icon = { Icon(SqIcons.Download, null) }, label = "Download") {
                onDownload(); onDismiss()
            }
            if (onGoToAlbum != null) {
                SheetAction(icon = { Icon(SqIcons.Disc, null) }, label = "Go to album") {
                    onGoToAlbum(); onDismiss()
                }
            }
            if (onGoToArtist != null) {
                SheetAction(icon = { Icon(Icons.Filled.Person, null) }, label = "Go to artist") {
                    onGoToArtist(); onDismiss()
                }
            }
            extraAction?.let { (label, action) ->
                SheetAction(icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, label = label) {
                    action(); onDismiss()
                }
            }
        }
    }
}

@Composable
fun SheetAction(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.padding(start = 18.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Dialog listing playlists, plus "New playlist" creation. */
@Composable
fun AddToPlaylistDialog(
    song: Song,
    onDismiss: () -> Unit,
) {
    val graph = App.graph()
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<Playlist>?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            playlists = graph.subsonic.getPlaylists()
        } catch (e: Exception) {
            error = e.message
        }
    }

    if (!creating) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add to playlist") },
            text = {
                Column {
                    when {
                        error != null -> Text("Couldn't load playlists: $error")
                        playlists == null -> Text("Loading…")
                        playlists!!.isEmpty() -> Text("No playlists yet — create one.")
                        else -> Column {
                            playlists!!.forEach { pl ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                try {
                                                    graph.subsonic.updatePlaylist(pl.id, songIdsToAdd = listOf(song.id))
                                                } catch (_: Exception) {
                                                }
                                                onDismiss()
                                            }
                                        }
                                        .padding(vertical = 10.dp),
                                ) {
                                    Text(
                                        text = pl.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.padding(start = 8.dp))
                                    Text(
                                        text = "${pl.songCount ?: 0} songs",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { creating = true }) { Text("New playlist") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("New playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newName.trim()
                        if (name.isEmpty()) return@TextButton
                        scope.launch {
                            try {
                                graph.subsonic.createPlaylist(name, listOf(song.id))
                            } catch (_: Exception) {
                            }
                            onDismiss()
                        }
                    },
                ) { Text("Create & add") }
            },
            dismissButton = {
                TextButton(onClick = { creating = false }) { Text("Back") }
            },
        )
    }
}

/** Toggle heart on a song (locally stored favourite); returns the new state. */
suspend fun toggleSongHeart(song: Song): Boolean {
    return App.graph().favorites.toggleSong(song)
}
