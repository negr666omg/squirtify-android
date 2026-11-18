@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package org.squidify.android.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.squidify.android.App
import org.squidify.android.data.Song
import org.squidify.android.player.PlayerSong
import org.squidify.android.ui.components.AddToPlaylistDialog
import org.squidify.android.ui.components.SongActionsSheet
import org.squidify.android.ui.components.SquidifyDownloadQualityDialog
import org.squidify.android.ui.components.toggleSongHeart

/**
 * Reusable handler that shows the track action sheet and the add-to-playlist
 * dialog for any song. Screens call [show] and mount [Render] once.
 */
class SongActionsHandler(
    private val onGoToAlbum: ((Song) -> Unit)? = null,
    private val onGoToArtist: ((Song) -> Unit)? = null,
    private val extraRemove: (suspend (Song, Int) -> Unit)? = null,
) {
    var song by mutableStateOf<Song?>(null)
        private set
    var songIndex by mutableStateOf(-1)
        private set
    var showPlaylistDialog by mutableStateOf(false)
        private set
    var showQualityDialog by mutableStateOf(false)
        private set

    fun show(s: Song, index: Int = -1) {
        song = s
        songIndex = index
    }

    fun dismiss() {
        song = null
        songIndex = -1
        showPlaylistDialog = false
    }

    @Composable
    fun Render() {
        val s = song ?: return
        val scope = rememberCoroutineScope()
        val graph = App.graph()
        // live favourite state: the sheet's Like row reflects (and toggles) it
        val favIds by graph.favorites.songIds.collectAsState()

        if (showPlaylistDialog) {
            AddToPlaylistDialog(song = s, onDismiss = { showPlaylistDialog = false })
        }

        if (showQualityDialog) {
            SquidifyDownloadQualityDialog(
                song = s,
                onDismiss = { showQualityDialog = false },
                onConfirm = { q ->
                    graph.downloads.enqueueSquidify(s, q)
                    showQualityDialog = false
                },
            )
        }

        SongActionsSheet(
            song = s,
            isHearted = s.id in favIds,
            onToggleHeart = {
                scope.launch { toggleSongHeart(s) }
            },
            onDismiss = { dismiss() },
            onPlayNext = {
                scope.launch { graph.player.playNext(listOf(PlayerSong.from(s))) }
            },
            onAddToQueue = {
                scope.launch { graph.player.addToQueue(listOf(PlayerSong.from(s))) }
            },
            onGoToAlbum = onGoToAlbum?.let { cb -> { cb(s) } },
            onGoToArtist = onGoToArtist?.let { cb -> { cb(s) } },
            onDownload = {
                showQualityDialog = true
            },
            onAddToPlaylist = { showPlaylistDialog = true },
            extraAction = extraRemove?.let { rm ->
                "Remove from playlist" to {
                    scope.launch { rm(s, songIndex) }
                }
            },
        )
    }
}
