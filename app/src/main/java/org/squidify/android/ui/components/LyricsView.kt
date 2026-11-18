package org.squidify.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.squidify.android.App
import org.squidify.android.data.LyricsResult
import kotlin.math.abs

/**
 * Apple-Music-style karaoke lyrics: big centered lines, the active line
 * highlighted; word-level tint when the source provides word timestamps.
 * Collects the playback position itself — the 10 Hz ticker updates never
 * recompose anything above this component.
 */
@Composable
fun LyricsView(
    lyrics: LyricsResult?,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    onSeekTo: (Double) -> Unit,
) {
    val positionMs by App.graph().player.positionMs.collectAsState()
    if (lyrics == null) {
        Box(modifier = modifier.fillMaxWidth().padding(40.dp)) {
            Text(
                text = if (isLoading) "Searching for lyrics…" else "No lyrics found for this track.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().alpha(0.7f),
            )
        }
        return
    }

    val timed = lyrics.lines.any { it.seconds != null }
    val listState = rememberLazyListState()

    val pos = positionMs / 1000.0
    var activeIndex = -1
    if (timed) {
        for ((i, line) in lyrics.lines.withIndex()) {
            val s = line.seconds ?: continue
            if (s <= pos + 0.2) activeIndex = i else break
        }
    }

    LaunchedEffect(activeIndex, lyrics) {
        if (activeIndex >= 0 && timed) {
            try {
                listState.animateScrollToItem(
                    index = activeIndex,
                    scrollOffset = -400,
                )
            } catch (_: Exception) {
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 24.dp, vertical = 80.dp,
        ),
    ) {
        items(lyrics.lines.size, key = { it }) { i ->
            val line = lyrics.lines[i]
            val active = timed && i == activeIndex
            val near = timed && abs(i - activeIndex) <= 1 && !active

            if (line.words.isNotEmpty() && active) {
                // word-by-word tint
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp),
                ) {
                    for (w in line.words) {
                        val sung = (w.start ?: line.seconds ?: 0.0) <= pos
                        Text(
                            text = w.text,
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (sung) MaterialTheme.colorScheme.onBackground
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        )
                    }
                }
            } else {
                Text(
                    text = line.text,
                    style = if (active) MaterialTheme.typography.headlineMedium
                    else MaterialTheme.typography.titleLarge,
                    color = when {
                        active -> MaterialTheme.colorScheme.onBackground
                        near -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    },
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (timed && line.seconds != null) {
                                Modifier.clickable { onSeekTo(line.seconds ?: 0.0) }
                            } else Modifier
                        )
                        .padding(vertical = 8.dp),
                )
            }
        }
        item {
            Text(
                text = "Lyrics — ${lyrics.source}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}
