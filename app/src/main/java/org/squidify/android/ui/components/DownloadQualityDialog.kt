package org.squidify.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.squidify.android.App
import org.squidify.android.data.Song
import org.squidify.android.data.StreamQuality
import org.squidify.android.util.Fmt

/** Estimated download size for a quality tier, based on server-side metadata. */
private fun estimateBytes(song: Song, q: StreamQuality): String {
    val secs = (song.duration ?: 0).toLong()
    if (secs <= 0) return "unknown size"
    return when (q) {
        StreamQuality.ORIGINAL ->
            song.size?.let { Fmt.bytes(it) } ?: "unknown size"
        else -> Fmt.bytes(((q.maxBitRate ?: 0) * 1000L / 8) * secs)
    }
}

/**
 * Asks which quality to download a Squidify track in.
 * The chosen tier applies to this download only; the global setting stays.
 */
@Composable
fun SquidifyDownloadQualityDialog(
    song: Song,
    onDismiss: () -> Unit,
    onConfirm: (StreamQuality) -> Unit,
) {
    val current = App.graph().settings.state().value.streamingQuality
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download quality") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                song.formatDescription.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "Source: $it" + (song.size?.let { s -> " · ${Fmt.bytes(s)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                StreamQuality.entries.forEach { q ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm(q) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = q == current, onClick = { onConfirm(q) })
                        Column {
                            Text(
                                text = q.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "≈ ${estimateBytes(song, q)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Quality ask for whole-album downloads. */
@Composable
fun SquidifyAlbumDownloadQualityDialog(
    trackCount: Int,
    sourceDescription: String?,
    onDismiss: () -> Unit,
    onConfirm: (StreamQuality) -> Unit,
) {
    val current = App.graph().settings.state().value.streamingQuality
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download album — $trackCount tracks") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                sourceDescription?.let {
                    Text(
                        text = "Source: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                StreamQuality.entries.forEach { q ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm(q) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = q == current, onClick = { onConfirm(q) })
                        Text(
                            text = q.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Quality ask for KHInsider downloads (FLAC / MP3). */
@Composable
fun KhiDownloadQualityDialog(
    trackTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (format: String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download quality") },
        text = {
            Column {
                Text(
                    text = trackTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                listOf("flac" to "FLAC (lossless)", "mp3" to "MP3 (320 kbps)").forEach { (fmt, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm(fmt) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
