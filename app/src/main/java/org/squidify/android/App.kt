@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package org.squidify.android

import android.app.Application
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import org.squidify.android.di.AppGraph
import org.squidify.android.util.AllFilesAccess
import org.squidify.android.util.AppLog
import org.squidify.android.util.CrashHandler

class App : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Crash interception FIRST — anything that explodes below is captured
        // into files/last_crash.txt (stack trace + the app log up to that point)
        // and surfaced as a "copy log" dialog on the next launch.
        CrashHandler.install(this)
        // Rename the previous visible root before logs, caches or repositories
        // can create the new folder. Existing user data stays on-device.
        if (AllFilesAccess.isGranted()) AllFilesAccess.migrateLegacyRoot()
        AppLog.init(this)
        AppLog.i("App", "Squirtify starting (pid ${android.os.Process.myPid()}, Android ${android.os.Build.VERSION.RELEASE})")
        graph = try {
            AppGraph(this)
        } catch (t: Throwable) {
            AppLog.e("App", "Dependency graph failed to initialize", t)
            throw t
        }
        try {
            graph.downloads.start()
        } catch (t: Throwable) {
            // a broken persisted queue must never take the whole app down
            AppLog.e("App", "Download manager failed to start", t)
        }
    }

    /** Coil loader with a user-configurable cover-art disk cache. */
    fun buildImageLoader(cacheMb: Int): ImageLoader {
        val dir = runCatching { graph.imageCacheDir(this) }.getOrNull()
            ?: cacheDir.resolve("image_cache")
        AppLog.i("Cache", "Building image loader with $cacheMb MB disk cache at ${dir.path}")
        return ImageLoader.Builder(this)
            .diskCache {
                DiskCache.Builder()
                    .directory(dir)
                    .maxSizeBytes(cacheMb.toLong() * 1024L * 1024L)
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(this).maxSizePercent(0.15).build()
            }
            .crossfade(true)
            .build()
    }

    companion object {
        private lateinit var instance: App
        fun graph(): AppGraph = instance.graph
        fun get(): App = instance
    }
}
