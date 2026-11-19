package org.squidify.android.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * "All files access" (MANAGE_EXTERNAL_STORAGE) helper.
 *
 * The user explicitly asked for the app to own a single visible folder:
 * everything it produces — downloads, streamed-audio cache, cover cache,
 * crash logs, user data — lives in Internal storage/Squirtify/.
 *
 * The permission cannot be requested with the normal runtime dialog: the app
 * must deep-link the user into the system "All files access" screen and they
 * flip the switch there. After that [Environment.isExternalStorageManager]
 * returns true and plain [File] I/O works everywhere on the primary volume.
 */
object AllFilesAccess {

    /**
     * Move the previous visible root before caches or repositories create the
     * new one. Conflicting files are never overwritten or discarded.
     */
    fun migrateLegacyRoot(): Boolean {
        if (!isGranted()) return false
        @Suppress("DEPRECATION")
        val volume = Environment.getExternalStorageDirectory()
        val legacy = File(volume, "Squidify")
        val current = File(volume, ROOT_NAME)
        if (!legacy.isDirectory) return false
        if (!current.exists()) return legacy.renameTo(current)

        fun merge(source: File, destination: File): Boolean {
            if (!source.isDirectory || (!destination.exists() && !destination.mkdirs()) ||
                !destination.isDirectory
            ) return false
            var complete = true
            val children = source.listFiles() ?: return false
            for (child in children) {
                val target = File(destination, child.name)
                val moved = when {
                    child.isDirectory -> {
                        if (!target.exists() && !target.mkdirs()) false else merge(child, target)
                    }
                    target.exists() -> false
                    else -> child.renameTo(target) || runCatching {
                        child.copyTo(target, overwrite = false)
                        child.delete()
                    }.getOrDefault(false)
                }
                if (!moved) complete = false
            }
            if (complete) source.delete()
            return complete
        }
        return merge(legacy, current)
    }

    /** True once the user flipped the switch in the system settings (API 30+). */
    fun isGranted(): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /**
     * Intent that opens this app's page in the "All files access" settings.
     * Falls back to the list of all such apps, then to the app details page
     * (pre-Android 11 has no all-files screen; legacy storage permission is
     * granted from the app details page there).
     */
    fun requestIntent(context: Context): Intent {
        val appSpecific = if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
            }.getOrNull()
        } else {
            null
        }
        return (appSpecific ?: runCatching {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }.getOrNull() ?: Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Root of everything the app stores in visible storage:
     * /storage/emulated/0/Squirtify — or null when access is not granted.
     * Never call this on the UI thread in a hot path; mkdirs are lazy.
     */
    fun root(): File? {
        if (!isGranted()) return null
        @Suppress("DEPRECATION") // primary external storage root; exactly what we need
        val volume = Environment.getExternalStorageDirectory()
        return File(volume, ROOT_NAME).also { if (!it.exists()) it.mkdirs() }
    }

    /** Subfolder of Squirtify/ (created when missing), or null without access. */
    fun subDir(name: String): File? = root()?.let { r ->
        File(r, name).also { if (!it.exists()) it.mkdirs() }
    }

    /** Human label shown in the UI. */
    const val ROOT_NAME = "Squirtify"
    fun rootLabel(): String = "Internal storage/$ROOT_NAME"
}
