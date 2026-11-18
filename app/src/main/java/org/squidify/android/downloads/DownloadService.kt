package org.squidify.android.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.squidify.android.App
import org.squidify.android.R
import org.squidify.android.data.DownloadStatus
import org.squidify.android.util.AppLog

/**
 * Keep-alive service for the download queue with a progress notification.
 *
 * Starting with Android 12 (and stricter on 14/15) the OS may REFUSE
 * foreground-service starts — most notably the dataSync type on Android 15
 * exhausts a ~6 h daily quota after heavy downloading, and every further
 * startForeground() call then THROWS, which used to crash the whole app the
 * moment a new download was queued.
 *
 * The service now treats a rejected foreground promotion as a downgrade, not
 * a crash: downloads simply continue in a plain background service (while the
 * process lives), and the queue resumes from disk on the next launch anyway.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, buildNotification(App.graph().downloads.jobs.value))
        } catch (t: Throwable) {
            // ForegroundServiceStartNotAllowedException / quota exhaustion /
            // notification problems — degrade to a background service instead
            // of crashing. The workers live in the app process, not here.
            AppLog.w(
                TAG,
                "Foreground promotion refused (${t.javaClass.simpleName}: ${t.message}) — " +
                    "downloads continue as a background service",
            )
        }
        observe()
        return START_STICKY
    }

    /** Android 14/15: the dataSync foreground quota ran out mid-run. */
    override fun onTimeout(startId: Int) {
        AppLog.w(TAG, "Foreground download quota exhausted — switching to background")
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun observe() {
        if (started) return
        started = true
        val nm = getSystemService(NotificationManager::class.java)
        val mgr = App.graph().downloads
        scope.launch {
            mgr.jobs.collect { jobs ->
                val running = jobs.count { it.status == DownloadStatus.RUNNING }
                val pending = jobs.count { it.status == DownloadStatus.QUEUED }
                if (running == 0 && pending == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                runCatching { nm.notify(NOTIF_ID, buildNotification(jobs)) }
            }
        }
    }

    private fun buildNotification(jobs: List<org.squidify.android.data.DownloadJob>): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_downloads),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val running = jobs.count { it.status == DownloadStatus.RUNNING }
        val pending = jobs.count { it.status == DownloadStatus.QUEUED }
        val done = jobs.count { it.status == DownloadStatus.DONE }
        val text = when {
            running > 0 -> "$running downloading · $pending queued · $done done"
            pending > 0 -> "$pending queued · $done done"
            else -> "$done completed"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.notif_downloading))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, running > 0)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "squidify_downloads"
        const val NOTIF_ID = 42
        private const val TAG = "DownloadSvc"
    }
}
