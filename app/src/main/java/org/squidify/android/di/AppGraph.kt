package org.squidify.android.di

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.squidify.android.data.FavoritesRepository
import org.squidify.android.data.KhiClient
import org.squidify.android.data.ListenHistory
import org.squidify.android.data.LyricsClient
import org.squidify.android.data.NativeClient
import org.squidify.android.data.OfflineLibraryRepository
import org.squidify.android.data.SettingsRepository
import org.squidify.android.data.SubsonicClient
import org.squidify.android.data.UserStorage
import org.squidify.android.downloads.DownloadManager
import org.squidify.android.player.PlayerManager
import org.squidify.android.util.AllFilesAccess
import org.squidify.android.util.AppLog
import org.squidify.android.util.CrashHandler
import org.squidify.android.util.NetworkMonitor
import java.io.File
import java.util.concurrent.TimeUnit

/** Tiny manual DI graph living for the whole process lifetime. */
@UnstableApi
class AppGraph(context: Context) {

    val appContext: Context = context.applicationContext

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** User-visible storage (Squidify folder via SAF, with safe fallbacks). */
    val storage = UserStorage(appContext)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val subsonic = SubsonicClient(http)

    val native = NativeClient(http)

    val khi = KhiClient(http)

    val lyrics = LyricsClient(http, subsonic)

    val networkMonitor = NetworkMonitor(appContext)

    val settings = SettingsRepository(appContext, storage).also { repo ->
        repo.startCollection(scope)
    }

    /** Local favourites — songs and albums, kept in Squidify/UserData. */
    val favorites = FavoritesRepository(storage)

    val history = ListenHistory(appContext, storage)

    val offlineLibrary = OfflineLibraryRepository(storage)

    val downloads = DownloadManager(appContext, http, subsonic, khi, settings, storage)

    val player = PlayerManager(appContext, subsonic, downloads, settings, khi, history)

    // ------------------------------------------------------------ media cache

    @Volatile
    private var simpleCache: SimpleCache? = null

    private val cacheLock = Any()

    /** Process-wide LRU disk cache for streamed audio (ExoPlayer). */
    fun mediaCache(context: Context): SimpleCache = simpleCache ?: synchronized(cacheLock) {
        simpleCache ?: createCache(context.applicationContext).also { simpleCache = it }
    }

    private fun createCache(context: Context): SimpleCache {
        val mb = settings.state().value.audioCacheMb
        val bytes = mb.toLong() * 1024L * 1024L
        // with "All files access" the streamed-audio cache is user-visible in
        // Squidify/cache/exoplayer, otherwise it stays in the internal cache dir
        val dir = AllFilesAccess.subDir("cache/exoplayer")
            ?: File(context.cacheDir, "exoplayer")
        AppLog.i("Cache", "Opening audio cache: $mb MB at ${dir.path}")
        return SimpleCache(
            dir,
            LeastRecentlyUsedCacheEvictor(bytes),
            StandaloneDatabaseProvider(context),
        )
    }

    /** Total bytes currently held by the audio cache. */
    fun mediaCacheBytes(): Long = try {
        simpleCache?.cacheSpace ?: 0L
    } catch (_: Exception) {
        0L
    }

    fun cacheSizeLabel(): String = "${settings.state().value.audioCacheMb} MB"

    /** Drops all cached audio (streams; downloads are untouched). */
    fun clearMediaCache() {
        val cache = simpleCache ?: return
        var removed = 0
        for (key in cache.keys.toList()) {
            try {
                cache.removeResource(key)
                removed++
            } catch (_: Exception) {
            }
        }
        AppLog.i("Cache", "Audio cache cleared: $removed entries removed")
    }

    /** Cover-art disk cache directory (Squidify/cache/image_cache when possible). */
    fun imageCacheDir(context: Context): File =
        AllFilesAccess.subDir("cache/image_cache")
            ?: File(context.cacheDir, "image_cache")

    /** Cached bytes of the cover-art disk cache. */
    fun imageCacheBytes(context: Context): Long = try {
        imageCacheDir(context).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    } catch (_: Exception) {
        0L
    }

    /** Server status for the Settings screen. */
    val serverStatus = kotlinx.coroutines.flow.MutableStateFlow<ServerStatus>(ServerStatus.Connecting)

    init {
        scope.launch {
            while (true) {
                try {
                    val ping = subsonic.ping()
                    serverStatus.value = ServerStatus.Online(
                        version = ping.serverVersion ?: "?",
                        openSubsonic = ping.openSubsonic,
                    )
                } catch (e: Exception) {
                    serverStatus.value = ServerStatus.Offline(e.message ?: "Connection failed")
                    AppLog.w("Server", "Ping failed: ${e.message}")
                }
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    sealed class ServerStatus {
        object Connecting : ServerStatus()
        data class Online(val version: String, val openSubsonic: Boolean) : ServerStatus()
        data class Offline(val error: String) : ServerStatus()
    }

    /**
     * Called after the user grants the Squidify storage folder: migrates the
     * internal user data into the folder and reloads every repository from
     * its new location.
     */
    suspend fun onStorageTreeGranted() {
        withContext(Dispatchers.IO) {
            storage.migrateInternalToTree()
        }
        settings.reload()
        favorites.reload()
        history.reload()
        offlineLibrary.reload()
        downloads.reloadRegistry()
        AppLog.i("Storage", "User data reloaded from the Squidify folder")
    }

    /**
     * Called when the user flips "All files access" on: moves user data and
     * crash logs into Squidify/, reloads every repository from there and
     * switches future downloads/cache/log writes to direct File I/O.
     */
    suspend fun onAllFilesGranted() {
        withContext(Dispatchers.IO) {
            storage.migrateToAllFiles()
            CrashHandler.migrateFiles(appContext)
        }
        settings.reload()
        favorites.reload()
        history.reload()
        downloads.reloadRegistry()
        AppLog.i(
            "Storage",
            "All files access granted — data root is now ${AllFilesAccess.rootLabel()} " +
                "(audio cache location applies after the next restart)",
        )
    }
}
