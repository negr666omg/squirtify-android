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
    private var playerSongOverride by mutableStateOf<PlayerSong?>(null)
    private var downloadOverride by mutableStateOf<(() -> Unit)?>(null)

    fun show(s: Song, index: Int = -1, playerSong: PlayerSong? = null, onDownload: (() -> Unit)? = null) {
        song = s
        songIndex = index
        playerSongOverride = playerSong
        downloadOverride = onDownload
    }

    fun dismiss() {
        song = null
        songIndex = -1
        showPlaylistDialog = false
        playerSongOverride = null
        downloadOverride = null
    }

    @Composable
    fun Render() {
        val s = song ?: return
        val scope = rememberCoroutineScope()
        val graph = App.graph()
        // live favourite state: the sheet's Like row reflects (and toggles) it
        val favIds by graph.favorites.songIds.collectAsState()
        val actionSong = playerSongOverride ?: PlayerSong.from(s)

        if (showPlaylistDialog && playerSongOverride == null) {
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
                scope.launch { graph.favorites.toggleSong(actionSong) }
            },
            onDismiss = { dismiss() },
            onPlayNext = {
                scope.launch { graph.player.playNext(listOf(actionSong)) }
            },
            onAddToQueue = {
                scope.launch { graph.player.addToQueue(listOf(actionSong)) }
            },
            onGoToAlbum = onGoToAlbum?.let { cb -> { cb(s) } },
            onGoToArtist = onGoToArtist?.let { cb -> { cb(s) } },
            onDownload = {
                val custom = downloadOverride
                if (custom != null) custom() else showQualityDialog = true
            },
            onAddToPlaylist = { if (playerSongOverride == null) showPlaylistDialog = true },
            showAddToPlaylist = playerSongOverride == null,
            extraAction = extraRemove?.let { rm ->
                "Remove from playlist" to {
                    scope.launch { rm(s, songIndex) }
                }
            },
        )
    }
}
