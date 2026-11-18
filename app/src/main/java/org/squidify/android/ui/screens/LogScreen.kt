package org.squidify.android.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.squidify.android.util.AppLog
import org.squidify.android.util.CrashHandler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Log tab: every API call, playback error, download event and
 * lyrics-resolution step, live, with level filters, copy/share and clear.
 */
@Composable
fun LogScreen() {
    val entries by AppLog.entries.collectAsState()
    var filter by remember { mutableStateOf(0) } // 0 all, 1 info+, 2 warn+, 3 error
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val shown = remember(entries, filter) {
        // strict filters: "Warnings" shows warnings only, "Errors" errors only
        when (filter) {
            1 -> entries.filter { it.level != AppLog.Level.DEBUG }
            2 -> entries.filter { it.level == AppLog.Level.WARN }
            3 -> entries.filter { it.level == AppLog.Level.ERROR }
            else -> entries
        }.asReversed()
    }
    val errorCount = entries.count { it.level == AppLog.Level.ERROR }
    val warnCount = entries.count { it.level == AppLog.Level.WARN }

    Column(Modifier.fillMaxSize()) {
        // ---------- toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${entries.size} events · $errorCount errors · $warnCount warnings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Newest first · also written to files/applog.txt",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(AppLog.dump())) }) {
                Text("Copy")
            }
            TextButton(onClick = {
                val text = AppLog.dump()
                runCatching {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, text)
                                .putExtra(Intent.EXTRA_TITLE, "Squirtify log"),
                            "Share log",
                        ),
                    )
                }
            }) {
                Text("Share")
            }
            TextButton(onClick = { AppLog.clear() }) {
                Text("Clear")
            }
        }

        // ---------- filters
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text("All") })
            FilterChip(selected = filter == 1, onClick = { filter = 1 }, label = { Text("Info+") })
            FilterChip(selected = filter == 2, onClick = { filter = 2 }, label = { Text("Warnings") })
            FilterChip(selected = filter == 3, onClick = { filter = 3 }, label = { Text("Errors") })
        }
        Spacer(Modifier.height(4.dp))

        // ---------- crash history
        var crashTick by remember { mutableStateOf(0) }
        val crashHistory = remember(crashTick) { CrashHandler.history(context) }
        if (crashHistory.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Crash log — ${crashHistory.size} recorded",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(crashHistory.joinToString("\n")))
                        }) { Text("Copy") }
                        TextButton(onClick = {
                            CrashHandler.clearHistory(context)
                            crashTick++
                        }) { Text("Clear") }
                    }
                    crashHistory.asReversed().take(5).forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No events for this filter yet.\nPlay a track — everything shows up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 140.dp,
                ),
            ) {
                items(shown, key = { it.id }) { e -> LogRow(e) }
            }
        }
    }
}

@Composable
private fun LogRow(e: AppLog.Entry) {
    var expanded by remember { mutableStateOf(false) }
    val time = remember(e.id) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(e.timeMs))
    }
    val (color, bg) = when (e.level) {
        AppLog.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surface
        AppLog.Level.INFO -> MaterialTheme.colorScheme.onSurface to MaterialTheme.colorScheme.surface
        AppLog.Level.WARN -> MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.surface
        AppLog.Level.ERROR -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.errorContainer
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(
                    text = e.level.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = color,
                    modifier = Modifier
                        .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp),
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(
                    text = e.tag,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = e.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (e.level == AppLog.Level.ERROR) MaterialTheme.colorScheme.onErrorContainer else color,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded && e.throwable != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = e.throwable,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Text(
                    text = "tap to collapse",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}
