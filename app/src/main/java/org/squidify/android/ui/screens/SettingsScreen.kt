package org.squidify.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import org.squidify.android.App
import org.squidify.android.data.KhiQuality
import org.squidify.android.data.StreamQuality
import org.squidify.android.data.ThemeMode
import org.squidify.android.util.AllFilesAccess
import org.squidify.android.util.AppLog
import org.squidify.android.util.Fmt

@Composable
fun SettingsScreen() {
    val graph = App.graph()
    val settings by graph.settings.state().collectAsState()
    val status by graph.serverStatus.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) } // 0 general, 1 log
    var showQualityDialog by remember { mutableStateOf(false) }
    var showKhiDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }

    if (showQualityDialog) {
        RadioDialog(
            title = "Squidify streaming quality",
            subtitle = "Transcoding happens on the server — the phone never converts audio",
            options = StreamQuality.entries.map { it.label },
            selected = StreamQuality.entries.indexOf(settings.streamingQuality),
            onDismiss = { showQualityDialog = false },
        ) { i ->
            val q = StreamQuality.entries[i]
            scope.launch {
                graph.settings.setStreamingQuality(q)
                App.graph().player.applyQualityChange(q)
            }
            showQualityDialog = false
        }
    }
    if (showKhiDialog) {
        RadioDialog(
            title = "KHInsider quality",
            subtitle = "Files are fetched exactly as they are hosted",
            options = KhiQuality.entries.map { it.label },
            selected = KhiQuality.entries.indexOf(settings.khiQuality),
            onDismiss = { showKhiDialog = false },
        ) { i ->
            scope.launch { graph.settings.setKhiQuality(KhiQuality.entries[i]) }
            showKhiDialog = false
        }
    }
    if (showThemeDialog) {
        RadioDialog(
            title = "Theme",
            subtitle = null,
            options = listOf("Auto (follow system)", "Dark", "Light"),
            selected = ThemeMode.entries.indexOf(settings.themeMode),
            onDismiss = { showThemeDialog = false },
        ) { i ->
            scope.launch { graph.settings.setThemeMode(ThemeMode.entries[i]) }
            showThemeDialog = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ---------- header with tabs
        Text(
            text = "Settings",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            SettingsTabPill("General", tab == 0) { tab = 0 }
            SettingsTabPill("Log", tab == 1) { tab = 1 }
        }

        when (tab) {
            0 -> GeneralSettings(
                settings = settings,
                status = status,
                onShowQuality = { showQualityDialog = true },
                onShowKhi = { showKhiDialog = true },
                onShowTheme = { showThemeDialog = true },
                onKhiEnabled = { on -> scope.launch { graph.settings.setKhiEnabled(on) } },
                onTheme = { m -> scope.launch { graph.settings.setThemeMode(m) } },
                onDynamicColor = { on -> scope.launch { graph.settings.setDynamicColor(on) } },
                onScrollingText = { on -> scope.launch { graph.settings.setScrollingText(on) } },
                onExitOnTaskRemoved = { on -> scope.launch { graph.settings.setExitOnTaskRemoved(on) } },
                onCrossfade = { sec -> scope.launch { graph.settings.setCrossfadeSec(sec) } },
                onCrossfadeRepeatLoops = { on -> scope.launch { graph.settings.setCrossfadeRepeatLoops(on) } },
                onFullBuffer = { on -> scope.launch { graph.settings.setFullBuffer(on) } },
                onAudioCache = { mb -> scope.launch { graph.settings.setAudioCacheMb(mb) } },
                onImageCache = { mb -> scope.launch { graph.settings.setImageCacheMb(mb) } },
                onClearAudioCache = { graph.clearMediaCache() },
            )
            1 -> LogScreen()
        }
    }
}

@Composable
private fun SettingsTabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun GeneralSettings(
    settings: org.squidify.android.data.Settings,
    status: org.squidify.android.di.AppGraph.ServerStatus,
    onShowQuality: () -> Unit,
    onShowKhi: () -> Unit,
    onShowTheme: () -> Unit,
    onKhiEnabled: (Boolean) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onScrollingText: (Boolean) -> Unit,
    onExitOnTaskRemoved: (Boolean) -> Unit,
    onCrossfade: (Int) -> Unit,
    onCrossfadeRepeatLoops: (Boolean) -> Unit,
    onFullBuffer: (Boolean) -> Unit,
    onAudioCache: (Int) -> Unit,
    onImageCache: (Int) -> Unit,
    onClearAudioCache: () -> Unit,
) {
    val graph = App.graph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var storageGranted by remember { mutableStateOf(graph.storage.hasTree) }
    // "All files access" — refreshed when the user returns from the system screen
    var allFilesGranted by remember { mutableStateOf(AllFilesAccess.isGranted()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesGranted = AllFilesAccess.isGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                if (graph.storage.adoptTree(uri)) {
                    // move user data over and re-read every repository
                    graph.onStorageTreeGranted()
                    storageGranted = graph.storage.hasTree
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // ---------------- audio & quality
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                SettingsCardTitle("Audio & quality")
                ClickableRow(
                    title = "Streaming quality (Squidify)",
                    value = settings.streamingQuality.label,
                    onClick = {
                        AppLog.d("UI", "Settings: quality picker opened")
                        onShowQuality()
                    },
                )
                ClickableRow(
                    title = "Quality (KHInsider)",
                    value = settings.khiQuality.label,
                    onClick = onShowKhi,
                )
                Text(
                    text = "All transcoding is done by the servers — this phone never converts tracks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow(
                    title = "Download whole track before playing",
                    subtitle = "No chunked streaming — the song starts only after it's fully loaded",
                    checked = settings.fullBuffer,
                    onChange = onFullBuffer,
                )
                CrossfadeRow(
                    seconds = settings.crossfadeSec,
                    repeatLoops = settings.crossfadeRepeatLoops,
                    onRepeatLoops = onCrossfadeRepeatLoops,
                    onChange = onCrossfade,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow(
                    title = "Close app when swiped away",
                    subtitle = "Removing Squirtify from Recents stops playback and fully exits the app — " +
                        "off by default (music keeps playing in the background)",
                    checked = settings.exitOnTaskRemoved,
                    onChange = onExitOnTaskRemoved,
                )
            }
        }

        // ---------------- storage & cache
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                SettingsCardTitle("Storage & cache")
                CacheSizeRow(
                    title = "Music cache (streamed tracks)",
                    subtitle = "Replays cost no data · ${"%.0f".format(
                        graph.mediaCacheBytes() / 1024f / 1024f,
                    )} MB used · applies after app restart",
                    currentMb = settings.audioCacheMb,
                    onChange = onAudioCache,
                )
                Spacer(Modifier.padding(6.dp))
                CacheSizeRow(
                    title = "Cover art cache",
                    subtitle = "${"%.0f".format(
                        graph.imageCacheBytes(context) / 1024f / 1024f,
                    )} MB used · applies immediately",
                    currentMb = settings.imageCacheMb,
                    onChange = onImageCache,
                )
                Spacer(Modifier.padding(6.dp))
                ClickableRow(
                    title = "Access to all files",
                    value = if (allFilesGranted) "Granted ✓ — data in Internal storage/Squidify"
                    else "Not granted — tap to allow",
                    onClick = {
                        AppLog.d("UI", "Settings: opening the all-files-access system screen")
                        runCatching { context.startActivity(AllFilesAccess.requestIntent(context)) }
                    },
                )
                Text(
                    text = "With full access every piece of app data lives in one visible place — " +
                        "Internal storage/Squidify: Downloads/ (offline music), cache/ (music + covers), " +
                        "logs/ and UserData/ (settings, favourites). Flip the switch for Squirtify " +
                        "on the screen that opens; cache folders apply after the next restart.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                if (!allFilesGranted) {
                    HorizontalDivider(
                        Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    ClickableRow(
                        title = "Storage folder (fallback)",
                        value = if (storageGranted) "Squidify folder ✓" else "Not set — default locations",
                        onClick = { treeLauncher.launch(null) },
                    )
                    Text(
                        text = "Alternative without all-files access: pick (or create) a \"Squidify\" folder " +
                            "via the system picker — downloads go to Squidify/Downloads, favourites and " +
                            "settings live in Squidify/UserData. Without it the app falls back to " +
                            "Music/ and Pictures/.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                SettingRow("Downloads saved to", graph.downloads.musicLocationLabel())
                SettingRow("Downloads size", Fmt.bytes(graph.downloads.storageUsedBytes()))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClearAudioCache) { Text("Clear music cache") }
                }
            }
        }

        // ---------------- appearance
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                SettingsCardTitle("Appearance")
                ClickableRow(
                    title = "Theme",
                    value = when (settings.themeMode) {
                        ThemeMode.AUTO -> "Auto"
                        ThemeMode.DARK -> "Dark"
                        ThemeMode.LIGHT -> "Light"
                    },
                    onClick = onShowTheme,
                )
                SwitchRow(
                    title = "Dynamic color (Material You)",
                    subtitle = "Tint the app with your wallpaper — Android 12+",
                    checked = settings.dynamicColor,
                    onChange = onDynamicColor,
                )
                SwitchRow(
                    title = "Scrolling titles",
                    subtitle = "Long names scroll side-to-side instead of being cut off",
                    checked = settings.scrollingText,
                    onChange = onScrollingText,
                )
            }
        }

        // ---------------- sources
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                SettingsCardTitle("Sources")
                SwitchRow(
                    title = "Show KHInsider in search",
                    subtitle = "Adds a second source for game soundtracks",
                    checked = settings.khiEnabled,
                    onChange = onKhiEnabled,
                )
            }
        }

        // ---------------- about
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                SettingsCardTitle("About")
                SettingRow("App", "Squirtify 1.7.3")
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    val notifGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.POST_NOTIFICATIONS,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ClickableRow(
                        title = "Media notification",
                        value = if (notifGranted) "enabled" else "blocked — tap to enable",
                        onClick = {
                            // If Android already auto-denied the runtime dialog
                            // twice, only the system settings page can fix it.
                            runCatching {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                                )
                            }
                        },
                    )
                }
                ClickableRow(
                    title = "Donate to squidify.org",
                    value = "support the server",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.squidify.org/donate")),
                            )
                        }
                    },
                )
                ClickableRow(
                    title = "KHInsider source",
                    value = "khinsider.squid.wtf",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://khinsider.squid.wtf/home")),
                            )
                        }
                    },
                )
            }
        }

        // ---------------- account, at the very bottom
        SettingsCard {
            Column(Modifier.padding(16.dp)) {
                SettingsCardTitle("Account")
                SettingRow("User", "guest — full access, no login needed")
                SettingRow("Server", "www.squidify.org")
                SettingRow(
                    "Status",
                    when (status) {
                        is org.squidify.android.di.AppGraph.ServerStatus.Online ->
                            "online · Navidrome ${status.version}" + if (status.openSubsonic) " · OpenSubsonic" else ""
                        is org.squidify.android.di.AppGraph.ServerStatus.Offline -> "offline — ${status.error}"
                        else -> "connecting…"
                    },
                )
            }
        }

        Spacer(Modifier.padding(bottom = 140.dp))
    }
}

@Composable
private fun SettingsCardTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.padding(4.dp))
}

@Composable
private fun CrossfadeRow(
    seconds: Int,
    repeatLoops: Boolean,
    onRepeatLoops: (Boolean) -> Unit,
    onChange: (Int) -> Unit,
) {
    var value by remember(seconds) { mutableStateOf(seconds.toFloat()) }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Crossfade between tracks",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (seconds == 0) "Off" else "${seconds}s",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onChange(value.toInt()) },
            valueRange = 0f..12f,
            steps = 11,
        )
        Text(
            text = "Smoothly blends the end of one track into the next — real overlap",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (seconds > 0) {
            SwitchRow(
                title = "Crossfade when a track repeats",
                subtitle = "Blend the loop seam of a repeating track too",
                checked = repeatLoops,
                onChange = onRepeatLoops,
            )
        }
    }
}

private val CACHE_STEPS_MB = intArrayOf(10, 25, 50, 100, 200, 350, 500, 750, 1024, 1536, 2048, 3072, 4096)

private fun mbLabel(mb: Int): String =
    if (mb >= 1024) {
        val gb = mb / 1024.0
        if (gb == gb.toInt().toDouble()) "${gb.toInt()} GB" else "%.1f GB".format(gb)
    } else "$mb MB"

@Composable
private fun CacheSizeRow(
    title: String,
    subtitle: String,
    currentMb: Int,
    onChange: (Int) -> Unit,
) {
    val idx = CACHE_STEPS_MB.indexOfFirst { it >= currentMb }.let { if (it < 0) CACHE_STEPS_MB.lastIndex else it }
    var sel by remember(currentMb) { mutableStateOf(idx) }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = mbLabel(CACHE_STEPS_MB[sel]),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = sel.toFloat(),
            onValueChange = { sel = it.toInt() },
            onValueChangeFinished = { onChange(CACHE_STEPS_MB[sel]) },
            valueRange = 0f..(CACHE_STEPS_MB.lastIndex.toFloat()),
            steps = CACHE_STEPS_MB.lastIndex - 1,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        content()
    }
}

@Composable
private fun SettingRow(title: String, value: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ClickableRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = ">",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Radio dialog with a scrollable option list — nothing gets cut off on small screens.
 *  Closes on the Android back gesture/key, on an outside tap and via Cancel. */
@Composable
private fun RadioDialog(
    title: String,
    subtitle: String?,
    options: List<String>,
    selected: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                options.forEachIndexed { i, opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(i) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = i == selected, onClick = { onSelect(i) })
                        Text(
                            text = opt,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
