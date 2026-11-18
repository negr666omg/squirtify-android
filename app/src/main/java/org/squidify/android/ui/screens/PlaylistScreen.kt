package org.squidify.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import org.squidify.android.data.Song
import org.squidify.android.player.PlayerSong
import org.squidify.android.ui.components.CoverArt
import org.squidify.android.ui.components.EmptyState
import org.squidify.android.ui.components.ErrorRetry
import org.squidify.android.ui.components.LoadingShelf
import org.squidify.android.ui.components.PlayButton
import org.squidify.android.ui.components.ShuffleButton
import org.squidify.android.ui.components.SongRow
import org.squidify.android.ui.screens.library.SongActionsHandler
import org.squidify.android.util.Fmt

@Composable
fun PlaylistScreen(
    playlistId: String,
    onBack: () -> Unit,
) {
    val graph = App.graph()
    val scope = rememberCoroutineScope()
    var playlist by remember { mutableStateOf<org.squidify.android.data.Playlist?>(null) }
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }

    val actions = remember {
        SongActionsHandler(extraRemove = { song, index ->
            scope.launch {
                try {
                    graph.subsonic.updatePlaylist(playlistId, songIndexesToRemove = listOf(index))
                    songs = songs?.filterIndexed { i, _ -> i != index }
                } catch (_: Exception) {
                }
            }
        })
    }
    actions.Render()

    LaunchedEffect(playlistId, reload) {
        error = null
        try {
            val (p, s) = graph.subsonic.getPlaylist(playlistId)
            playlist = p
            songs = s
        } catch (e: Exception) {
            error = e.message
        }
    }

    if (showRename && playlist != null) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename playlist") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = renameValue.trim()
                    if (name.isEmpty()) return@TextButton
                    scope.launch {
                        try {
                            graph.subsonic.updatePlaylist(playlistId, name = name)
                            playlist = playlist?.copy(name = name)
                        } catch (_: Exception) {
                        }
                        showRename = false
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete playlist?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            graph.subsonic.deletePlaylist(playlistId)
                        } catch (_: Exception) {
                        }
                        onBack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = playlist?.name ?: "Playlist",
            onBack = onBack,
            onStar = null,
        )
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = {
                playlist?.let { showRename = true; renameValue = it.name }
            }) { Text("Rename") }
            TextButton(onClick = { showDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.tertiary) }
        }

        when {
            error != null -> ErrorRetry("Couldn't load playlist: $error") { reload++ }
            songs == null -> LoadingShelf(200.dp)
            songs!!.isEmpty() -> EmptyState("This playlist is empty")
            else -> {
                val list = songs!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
                ) {
                    item {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            PlayButton(onClick = {
                                scope.launch { App.graph().player.play(list.map { PlayerSong.from(it) }, 0) }
                            })
                            Spacer(Modifier.padding(start = 10.dp))
                            ShuffleButton(onClick = {
                                scope.launch {
                                    // shuffle order is handled by the player itself
                                    App.graph().player.playShuffled(list.map { PlayerSong.from(it) })
                                }
                            })
                        }
                    }
                    items(list.size, key = { list[it].id }) { i ->
                        val song = list[i]
                        SongRow(
                            song = song,
                            onClick = {
                                scope.launch { App.graph().player.play(list.map { PlayerSong.from(it) }, i) }
                            },
                            coverUrl = SongCover(song),
                            showAlbum = true,
                            trailingText = Fmt.duration(song.duration?.toLong()),
                            onMenuClick = { actions.show(song, i) },
                        )
                    }
                }
            }
        }
    }
}
