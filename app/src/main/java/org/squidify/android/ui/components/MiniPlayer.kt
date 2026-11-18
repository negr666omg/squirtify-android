@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package org.squidify.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.squidify.android.App
import org.squidify.android.player.PlayerSong
import org.squidify.android.ui.components.marqueeIf
import org.squidify.android.ui.components.pressScale
import org.squidify.android.ui.theme.SqIcons

/**
 * Persistent bottom mini player. Collects the play state and the position
 * ticker ITSELF (and reads the progress in the DRAW phase), so the ~10 Hz
 * position updates cost one tiny redraw here — previously the numbers were
 * collected in AppNavHost and every tick recomposed the entire app root,
 * including the animating Now-Playing sheet.
 */
@Composable
fun MiniPlayer(
    song: PlayerSong?,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
) {
    if (song == null) return
    val player = App.graph().player
    val isPlaying by player.isPlaying.collectAsState()
    val isBuffering by player.isBuffering.collectAsState()
    val playbackError by player.playbackError.collectAsState()
    // plain States (no `by`): they are read inside the progress lambda below,
    // i.e. during DRAW — zero recomposition while a track plays
    val position = player.positionMs.collectAsState()
    val duration = player.durationMs.collectAsState()

    val scroll = App.graph().settings.state().value.scrollingText
    val playIsr = remember { MutableInteractionSource() }
    val nextIsr = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onOpen),
    ) {
        Column {
            LinearProgressIndicator(
                progress = {
                    val d = duration.value
                    if (d > 0) (position.value.toFloat() / d).coerceIn(0f, 1f) else 0f
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(
                    url = song.coverArtUrl(),
                    modifier = Modifier.size(48.dp),
                    corner = 12.dp,
                    contentDescription = song.album,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.marqueeIf(scroll),
                    )
                    Text(
                        text = song.artist ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.marqueeIf(scroll),
                    )
                    playbackError?.let { message ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = { player.retryCurrent() },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            ) { Text("Retry") }
                            IconButton(
                                onClick = { player.dismissPlaybackError() },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Dismiss playback error",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
                IconButton(
                    onClick = onTogglePlay,
                    interactionSource = playIsr,
                    modifier = Modifier.pressScale(playIsr, pressedScale = 0.88f),
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    } else if (isPlaying) {
                        Icon(
                            imageVector = SqIcons.Pause,
                            contentDescription = "Pause",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Play",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                IconButton(
                    onClick = onNext,
                    interactionSource = nextIsr,
                    modifier = Modifier.pressScale(nextIsr, pressedScale = 0.88f),
                ) {
                    Icon(
                        imageVector = SqIcons.SkipNext,
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

fun PlayerSong.coverArtUrl(size: Int = 300): String? {
    if (coverUrl != null) return coverUrl
    val coverArtId = coverArtId ?: return null
    return App.graph().subsonic.coverUrl(coverArtId, size)
}
