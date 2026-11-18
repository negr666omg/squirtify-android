package org.squidify.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import coil.compose.LocalImageLoader
import coil.ImageLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import org.squidify.android.downloads.DownloadService
import org.squidify.android.player.PlaybackService
import org.squidify.android.ui.nav.AppNavHost
import org.squidify.android.ui.theme.SquidTheme
import org.squidify.android.util.AllFilesAccess
import org.squidify.android.util.AppLog
import org.squidify.android.util.CrashHandler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var allFilesMigrationInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppLog.i("UI", "MainActivity created")
        // ensure the media session service is alive so notifications work
        try {
            startService(Intent(this, PlaybackService::class.java))
        } catch (t: Throwable) {
            AppLog.w("UI", "Could not start the playback service: ${t.javaClass.simpleName}: ${t.message}")
        }

        // Android 13+: the media notification needs the runtime notification
        // permission. Without it the player is invisible in the shade AND the
        // system media carousel never appears. Re-ask on every cold start:
        // Android only shows the dialog twice, then silently denies — harmless.
        requestNotificationPermissionIfNeeded()

        // data may already live in fallback locations from before the user
        // granted "All files access" (e.g. granted while the app was dead)
        ensureAllFilesMigration()

        val graph = App.graph()
        val settings = graph.settings.state()

        // The previous run crashed: show the report with a "copy log" action.
        val crashed = CrashHandler.pendingReport(this)

        setContent {
            val s by settings.collectAsState()
            val scope = rememberCoroutineScope()

            // "All files access": asked once per launch until granted; when
            // the user flips the switch and returns, data migrates into
            // Internal storage/Squidify and the repositories reload.
            var allFilesGranted by remember { mutableStateOf(AllFilesAccess.isGranted()) }
            var allFilesPromptDismissed by remember { mutableStateOf(false) }
            DisposableEffect(Unit) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        val now = AllFilesAccess.isGranted()
                        if (now && !allFilesGranted) ensureAllFilesMigration(showToast = true)
                        allFilesGranted = now
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            // Cover-art loader sized by the user setting; rebuilt live on change.
            var imageLoader by remember {
                mutableStateOf(App.get().buildImageLoader(s.imageCacheMb))
            }
            var appliedMb by remember { mutableStateOf(s.imageCacheMb) }
            androidx.compose.runtime.LaunchedEffect(s.imageCacheMb) {
                if (s.imageCacheMb != appliedMb) {
                    val old = imageLoader
                    appliedMb = s.imageCacheMb
                    imageLoader = App.get().buildImageLoader(s.imageCacheMb)
                    // let in-flight requests finish, then retire the old loader
                    scope.launch {
                        delay(30_000)
                        runCatching { old.shutdown() }
                    }
                }
            }

            CompositionLocalProvider(LocalImageLoader provides imageLoader) {
                SquidTheme(
                    themeMode = s.themeMode,
                    dynamicColor = s.dynamicColor,
                ) {
                    AppNavHost(onExitApp = { moveTaskToBack(true) })

                    if (crashed != null) {
                        val clipboard = LocalClipboardManager.current
                        CrashDialog(
                            report = crashed,
                            onCopy = {
                                clipboard.setText(AnnotatedString(crashed.fullLog))
                                Toast.makeText(
                                    this@MainActivity,
                                    "Crash log copied to clipboard",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            onClose = { CrashHandler.clearPending(this@MainActivity) },
                        )
                    }

                    if (!allFilesGranted && !allFilesPromptDismissed) {
                        AllFilesAccessDialog(
                            onOpenSettings = {
                                allFilesPromptDismissed = true
                                runCatching {
                                    startActivity(AllFilesAccess.requestIntent(this@MainActivity))
                                }
                            },
                            onLater = { allFilesPromptDismissed = true },
                        )
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                AppLog.i("UI", "Requesting POST_NOTIFICATIONS permission")
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001,
                )
            }
        }
    }

    /**
     * One-time move of every piece of app data into Internal storage/Squidify
     * after "All files access" is granted (flag-guarded, idempotent).
     */
    private fun ensureAllFilesMigration(showToast: Boolean = false) {
        if (!AllFilesAccess.isGranted()) return
        val sp = getSharedPreferences("squidify_storage", MODE_PRIVATE)
        // Version 2 fixes an old precedence bug: SAF files must be copied
        // before internal fallbacks, otherwise stale favorites/settings can
        // overwrite the data the app actually read before migration.
        val migrationVersion = sp.getInt("all_files_migration_version", 0)
        val legacyFlag = sp.getBoolean("all_files_migrated", false)
        if ((!legacyFlag || migrationVersion < 2) && !allFilesMigrationInProgress) {
            allFilesMigrationInProgress = true
            lifecycleScope.launch {
                try {
                    App.graph().onAllFilesGranted()
                    // Mark complete only after copying and repository reloads
                    // return. A process death during migration can now retry.
                    sp.edit()
                        .putBoolean("all_files_migrated", true)
                        .putInt("all_files_migration_version", 2)
                        .apply()
                    if (showToast) {
                        Toast.makeText(
                            this@MainActivity,
                            "All app data now lives in Internal storage/Squidify",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                } catch (t: Throwable) {
                    AppLog.e("Storage", "All-files migration failed; it will retry next launch", t)
                } finally {
                    allFilesMigrationInProgress = false
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            if (App.graph().downloads.jobs.value.any {
                    it.status == org.squidify.android.data.DownloadStatus.QUEUED ||
                        it.status == org.squidify.android.data.DownloadStatus.RUNNING
                }
            ) {
                startService(Intent(this, DownloadService::class.java))
            }
        } catch (t: Throwable) {
            AppLog.w("UI", "Could not start the download service: ${t.javaClass.simpleName}")
        }
    }
}

/**
 * "The app crashed" dialog: the exception summary plus everything the app was
 * doing before it died, with a one-tap copy of the full log to the clipboard.
 */
@androidx.compose.runtime.Composable
private fun CrashDialog(
    report: CrashHandler.CrashReport,
    onCopy: () -> Unit,
    onClose: () -> Unit,
) {
    var visible by androidx.compose.runtime.remember { mutableStateOf(true) }
    if (!visible) return
    val time = remember(report.timeMs) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(report.timeMs))
    }
    AlertDialog(
        onDismissRequest = { visible = false; onClose() },
        title = { Text("The application crashed") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Last crash: $time",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        text = report.summary,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Text(
                    text = "The full log (stack trace + the last events before the crash) " +
                        "is copied with the button below — send it to the developer to fix the crash.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCopy() }) { Text("Copy log") }
        },
        dismissButton = {
            TextButton(onClick = { visible = false; onClose() }) { Text("Close") }
        },
    )
}

/**
 * "All files access" request: explains the single visible data root and
 * deep-links into the system permission screen (the only way this
 * permission can be granted).
 */
@androidx.compose.runtime.Composable
private fun AllFilesAccessDialog(
    onOpenSettings: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Access to all files") },
        text = {
            Text(
                "Squirtify keeps every piece of its data in one folder you can see: " +
                    "Internal storage/Squidify —\n" +
                    "Downloads/ (offline music), cache/ (music + covers), logs/, " +
                    "UserData/ (settings, favourites, history).\n\n" +
                    "Android requires the one-time \"All files access\" permission for that. " +
                    "Flip the switch for Squirtify on the next screen. " +
                    "Nothing outside the Squidify folder is ever touched.",
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text("Open settings") }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text("Later") }
        },
    )
}
