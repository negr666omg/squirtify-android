package org.squidify.android.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.squidify.android.data.DownloadJob
import org.squidify.android.data.DownloadSource
import org.squidify.android.data.DownloadStatus
import org.squidify.android.data.KhiAlbum
import org.squidify.android.data.KhiClient
import org.squidify.android.data.SettingsRepository
import org.squidify.android.data.Song
import org.squidify.android.data.StreamQuality
import org.squidify.android.data.SubsonicClient
import org.squidify.android.data.UserStorage
import org.squidify.android.util.AppLog
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Persistent, resumable download queue with two parallel workers.
 * Files land in Squidify/Downloads/<Album>/ when the storage folder is
 * granted (SAF), otherwise Music/Squidify/Downloads/<Album> via MediaStore
 * (API 29+) or the legacy public path on older devices.
 * Track files get the album cover embedded; full-album downloads also save
 * the album's cover images into the album folder in original quality.
 */
class DownloadManager(
    private val context: Context,
    private val http: OkHttpClient,
    private val subsonic: SubsonicClient,
    private val khi: KhiClient,
    private val settings: SettingsRepository,
    private val storage: UserStorage,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storeFileName = "downloads.json"

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val activeCalls = ConcurrentHashMap<String, okhttp3.Call>()
    private val cancellations = ConcurrentHashMap.newKeySet<String>()
    // IDs are reserved atomically before a worker starts I/O.
    private val activeJobs = ConcurrentHashMap.newKeySet<String>()
    // Defer a resume requested while the cancelled worker is closing .part.
    private val resumeAfterPause = ConcurrentHashMap.newKeySet<String>()
    private var workersStarted = false
    private val semaphore = Semaphore(2)

    companion object {
        private const val TAG = "Download"
        fun sanitize(name: String): String {
            val cleaned = name.replace(Regex("""[\\/:*?"<>|\u0000]"""), "_").trim()
            return if (cleaned.length > 180) cleaned.take(180) else cleaned.ifBlank { "track" }
        }

        fun mimeTypeFor(ext: String): String = when (ext.lowercase()) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "opus" -> "audio/ogg"
            "ogg" -> "audio/ogg"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "wav" -> "audio/x-wav"
            else -> "application/octet-stream"
        }

        fun imageMimeFor(ext: String): String = when (ext.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
    }

    // ------------------------------------------------------------- dirs

    /** Staging area for in-progress .part files (Squidify/cache when possible). */
    private fun stagingDir(): File {
        val dir = org.squidify.android.util.AllFilesAccess.subDir("cache/staging")
            ?: File(context.filesDir, "download-parts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Each job owns a distinct staging file. Output file names are only unique
     * within an album, so using fileName alone lets parallel jobs overwrite
     * each other's partial bytes.
     *
     * Migrate a legacy .part file only when no other job shares that output
     * name; otherwise its owner is ambiguous and it must not be reused.
     */
    @Synchronized
    private fun stagingPartFile(job: DownloadJob): File {
        val dir = stagingDir()
        val target = File(dir, stagingPartName(job.id, job.fileName))
        if (!target.exists()) {
            val legacy = File(dir, job.fileName + ".part")
            val ambiguous = _jobs.value.any { it.id != job.id && it.fileName == job.fileName }
            if (!ambiguous && legacy.isFile) {
                if (!legacy.renameTo(target)) {
                    runCatching { legacy.copyTo(target, overwrite = false) }
                }
                if (target.isFile) legacy.delete()
            }
        }
        return target
    }

    /** Human-readable location shown in the UI. */
    fun musicLocationLabel(): String = storage.downloadsLocationLabel()

    /**
     * Older versions stored Android 8/9 downloads in the public Music folder
     * but wrote no contentUri into the registry. Keep those downloads playable
     * and deletable after upgrading; new versions persist a file:// URI.
     */
    private fun finishedFileFor(job: DownloadJob): File? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val album = UserStorage.sanitizeSegment(job.album ?: job.artist ?: "Unknown Album")
            val publicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "Squidify/Downloads/" + album,
            )
            val publicFile = File(publicDir, job.fileName)
            if (publicFile.isFile && publicFile.length() > 0L) return publicFile
        }
        return File(storage.legacyDir(), job.fileName)
            .takeIf { it.isFile && it.length() > 0L }
    }

    // ------------------------------------------------------------- lifecycle

    @Synchronized
    fun start() {
        if (workersStarted) return
        // Load the registry exactly once. Calling start() after each enqueue
        // must never replace newer in-memory jobs with an older disk snapshot.
        load()
        workersStarted = true
        AppLog.i(TAG, "Download manager started (${_jobs.value.size} jobs on disk)")
        scope.launch { workerLoop(0) }
        scope.launch { workerLoop(1) }
        scope.launch { verifyLibrary() }
    }

    /** Re-reads the registry (e.g. after the storage folder was granted). */
    @Synchronized
    fun reloadRegistry() {
        // Re-read data after a storage migration, but do not discard jobs
        // currently owned by a worker (their newest byte counts live in RAM).
        val inFlight = _jobs.value.filter { it.id in activeJobs }
        load()
        if (inFlight.isNotEmpty()) {
            val ids = inFlight.map { it.id }.toSet()
            _jobs.value = _jobs.value.filterNot { it.id in ids } + inFlight
            persistNow()
        }
    }

    private fun load() {
        val text = try {
            storage.readUserData(storeFileName)
        } catch (_: Exception) {
            null
        } ?: return
        try {
            val list = json.decodeFromString(ListSerializer(DownloadJob.serializer()), text)
            _jobs.value = list
                .map {
                    if (it.status == DownloadStatus.RUNNING) it.copy(status = DownloadStatus.PAUSED) else it
                }
                // HEAL duplicate ids: older builds could append a second job
                // with the same id after the first one failed (duplicate
                // LazyColumn keys crashed the app). Keep the most advanced
                // entry per id so the registry is always id-unique.
                .let { jobs ->
                    if (jobs.size == jobs.distinctBy { it.id }.size) jobs
                    else {
                        AppLog.w(TAG, "Registry contains duplicate job ids — healing (${jobs.size} → ${jobs.distinctBy { it.id }.size})")
                        jobs.groupBy { it.id }.map { (_, same) ->
                            same.maxByOrNull { statusRank(it.status) } ?: same.first()
                        }
                    }
                }
        } catch (e: Exception) {
            AppLog.e(TAG, "Could not restore download queue: ${e.message}")
        }
    }

    private fun statusRank(s: DownloadStatus): Int = when (s) {
        DownloadStatus.DONE -> 4
        DownloadStatus.QUEUED -> 3
        DownloadStatus.PAUSED -> 2
        DownloadStatus.RUNNING -> 1
        DownloadStatus.FAILED -> 0
    }

    private var lastPersist = 0L
    private var pendingPersistJob: kotlinx.coroutines.Job? = null

    @Synchronized
    private fun persist() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastPersist
        if (elapsed >= 400L) {
            lastPersist = now
            persistNow()
            pendingPersistJob?.cancel()
            pendingPersistJob = null
        } else {
            pendingPersist = true
            // Coalesce rapid byte-count updates, but guarantee the final
            // state is written even when no later worker event calls flush().
            if (pendingPersistJob?.isActive != true) {
                pendingPersistJob = scope.launch {
                    kotlinx.coroutines.delay(400L - elapsed)
                    synchronized(this@DownloadManager) {
                        if (pendingPersist) {
                            lastPersist = System.currentTimeMillis()
                            persistNow()
                        }
                        pendingPersistJob = null
                    }
                }
            }
        }
    }

    @Volatile
    private var pendingPersist = false

    @Synchronized
    private fun persistNow() {
        pendingPersist = false
        try {
            storage.writeUserData(
                storeFileName,
                json.encodeToString(ListSerializer(DownloadJob.serializer()), _jobs.value),
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "Persisting download queue failed: ${e.message}")
        }
    }

    fun flush() {
        persistNow()
    }

    @Synchronized
    private fun update(id: String, transform: (DownloadJob) -> DownloadJob) {
        _jobs.value = _jobs.value.map { if (it.id == id) transform(it) else it }
        persist()
    }

    // ---------------------------------------------------------------- enqueues

    @Synchronized
    fun enqueueSquidify(song: Song, quality: StreamQuality) {
        runCatching {
            enqueueSquidifyInner(song, quality)
        }.onFailure { t ->
            AppLog.e(TAG, "Queuing '${song.title}' failed: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    private fun enqueueSquidifyInner(song: Song, quality: StreamQuality) {
        if (_jobs.value.any { it.source == DownloadSource.SQUIDIFY && it.songId == song.id && it.status != DownloadStatus.FAILED }) {
            AppLog.w(TAG, "Download of '${song.title}' skipped — already queued or done")
            return
        }
        val ext = if (quality == StreamQuality.ORIGINAL) (song.suffix ?: "flac") else quality.extension
        val url = subsonic.streamUrl(song.id, quality)
        val job = DownloadJob(
            id = "sq-${song.id}-${quality.name}",
            source = DownloadSource.SQUIDIFY,
            songId = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            albumId = song.albumId,
            coverUrl = subsonic.coverUrl(song.coverArt ?: song.albumId?.let { "al-$it" }, 480),
            requestUrl = url,
            format = ext,
            qualityLabel = quality.shortLabel,
            fileName = trackFileName(song, ext),
            durationMs = (song.duration ?: 0) * 1000L,
        )
        AppLog.i(TAG, "Queued download: '${song.title}' @ ${quality.shortLabel} → ${storage.downloadsLocationLabel()}/${song.album ?: "Unknown Album"}/${job.fileName}")
        // replace a stale FAILED entry instead of appending a second job with
        // the same id — duplicate ids crash the downloads list keys
        _jobs.value = _jobs.value.filterNot {
            it.source == DownloadSource.SQUIDIFY && it.songId == song.id && it.status == DownloadStatus.FAILED
        } + job
        persist()
        start()
        kickService()
    }

    @Synchronized
    fun enqueueSquidifyAll(songs: List<Song>, quality: StreamQuality) {
        runCatching {
            enqueueSquidifyAllInner(songs, quality)
        }.onFailure { t ->
            AppLog.e(TAG, "Queuing album downloads failed: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    private fun enqueueSquidifyAllInner(songs: List<Song>, quality: StreamQuality) {
        val existing = _jobs.value
        val albumName = songs.firstOrNull()?.album
        val albumCoverArt = songs.firstOrNull()?.let { it.coverArt ?: it.albumId?.let { a -> "al-$a" } }
        val newJobs = songs.mapNotNull { song ->
            if (existing.any { it.source == DownloadSource.SQUIDIFY && it.songId == song.id && it.status != DownloadStatus.FAILED }) return@mapNotNull null
            val ext = if (quality == StreamQuality.ORIGINAL) (song.suffix ?: "flac") else quality.extension
            DownloadJob(
                id = "sq-${song.id}-${quality.name}",
                source = DownloadSource.SQUIDIFY,
                songId = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                coverUrl = subsonic.coverUrl(song.coverArt ?: song.albumId?.let { "al-$it" }, 480),
                requestUrl = subsonic.streamUrl(song.id, quality),
                format = ext,
                qualityLabel = quality.shortLabel,
                fileName = trackFileName(song, ext),
                durationMs = (song.duration ?: 0) * 1000L,
            )
        }
        if (newJobs.isEmpty()) return
        AppLog.i(TAG, "Queued ${newJobs.size} downloads @ ${quality.shortLabel} → ${storage.downloadsLocationLabel()}/<album>/")
        // drop stale FAILED entries whose ids collide with the fresh jobs
        val newIds = newJobs.map { it.id }.toSet()
        _jobs.value = _jobs.value.filterNot { it.id in newIds && it.status == DownloadStatus.FAILED } + newJobs

        // full-album download → also save the album cover into the folder
        if (albumCoverArt != null) {
            enqueueCoverImage(
                album = albumName ?: "Unknown Album",
                url = subsonic.coverUrl(albumCoverArt, 0),
                fileName = "cover.jpg",
            )
        }
        persist()
        start()
        kickService()
    }

    @Synchronized
    fun enqueueKhi(
        albumName: String,
        trackTitle: String,
        trackUrl: String,
        format: String,
        qualityLabel: String,
        coverUrl: String?,
    ) {
        runCatching {
            enqueueKhiInner(albumName, trackTitle, trackUrl, format, qualityLabel, coverUrl)
        }.onFailure { t ->
            AppLog.e(TAG, "Queuing KHInsider '$trackTitle' failed: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    private fun enqueueKhiInner(
        albumName: String,
        trackTitle: String,
        trackUrl: String,
        format: String,
        qualityLabel: String,
        coverUrl: String?,
    ) {
        val id = "khi-${hash(trackUrl)}-$format"
        if (_jobs.value.any { it.id == id && it.status != DownloadStatus.FAILED }) return
        val job = DownloadJob(
            id = id,
            source = DownloadSource.KHINSIDER,
            title = trackTitle,
            album = albumName,
            coverUrl = coverUrl,
            requestUrl = trackUrl,
            format = format,
            qualityLabel = qualityLabel,
            fileName = sanitize("$trackTitle.$format"),
        )
        AppLog.i(TAG, "Queued KHInsider download: '$trackTitle' ($format) → ${storage.downloadsLocationLabel()}/$albumName/")
        // a previous failed attempt for the same file is REPLACED, never kept
        // next to the new job — duplicate ids crash the downloads list keys
        _jobs.value = _jobs.value.filterNot { it.id == id } + job
        persist()
        start()
        kickService()
    }

    /** Full KHInsider album download: save every cover image in original format. */
    @Synchronized
    fun enqueueKhiAlbumArt(album: KhiAlbum) {
        if (album.albumImages.isEmpty()) return
        album.albumImages.forEach { imageUrl ->
            val rawName = java.net.URLDecoder.decode(imageUrl.substringAfterLast('/'), "UTF-8")
            var name = sanitize(rawName)
            if (!name.contains('.')) name += ".jpg"
            enqueueCoverImage(album = album.name, url = khi.imageUrl(imageUrl), fileName = name)
        }
        // Album art can be the only requested work (e.g. all audio tracks
        // already exist). Persist and wake the workers even in that case.
        persist()
        start()
        kickService()
    }

    private fun enqueueCoverImage(album: String, url: String?, fileName: String) {
        if (url == null) return
        val id = "img-${hash(url)}"
        if (_jobs.value.any { it.id == id && it.status != DownloadStatus.FAILED }) return
        val ext = fileName.substringAfterLast('.', "jpg").lowercase()
        val job = DownloadJob(
            id = id,
            source = DownloadSource.KHINSIDER,
            title = fileName,
            album = album,
            requestUrl = url,
            format = ext,
            qualityLabel = "cover",
            fileName = fileName,
            kind = "image",
        )
        AppLog.i(TAG, "Queued cover image: $fileName → ${storage.downloadsLocationLabel()}/$album/")
        // replace a stale FAILED entry — duplicate ids crash the list keys
        _jobs.value = _jobs.value.filterNot { it.id == id } + job
    }

    /** File name inside the album folder: "07 - Title.ext" when the track number is known. */
    private fun trackFileName(song: Song, ext: String): String {
        val base = when (val t = song.track) {
            null -> song.title
            else -> String.format(java.util.Locale.US, "%02d - %s", t, song.title)
        }
        return sanitize("$base.$ext")
    }

    // ---------------------------------------------------------------- controls

    @Synchronized
    fun pause(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (job.status != DownloadStatus.QUEUED && job.status != DownloadStatus.RUNNING) return
        AppLog.i(TAG, "Pausing: '${job.title}' at ${FmtSafe.bytes(job.bytes)}")
        if (job.status == DownloadStatus.RUNNING && activeJobs.contains(id)) {
            cancellations += id
            activeCalls[id]?.cancel()
        }
        update(id) { it.copy(status = DownloadStatus.PAUSED, error = null) }
    }

    @Synchronized
    fun resume(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (job.status != DownloadStatus.PAUSED) return
        AppLog.i(TAG, "Resume requested: '${job.title}'")
        if (activeJobs.contains(id)) {
            // Let the old worker unwind before this job becomes QUEUED again.
            resumeAfterPause += id
            return
        }
        cancellations.remove(id)
        update(id) { it.copy(status = DownloadStatus.QUEUED, error = null) }
        kickService()
    }

    @Synchronized
    fun cancel(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id }
        cancellations += id
        activeCalls[id]?.cancel()
        _jobs.value = _jobs.value.filterNot { it.id == id }
        job?.let {
            stagingPartFile(it).delete()
            File(storage.legacyDir(), it.fileName).delete()
        }
        AppLog.i(TAG, "Cancelled: '${job?.title ?: id}'")
        persist()
    }

    @Synchronized
    fun retry(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        AppLog.i(TAG, "Retrying: '${job.title}'")
        stagingPartFile(job).delete()
        update(id) {
            it.copy(status = DownloadStatus.QUEUED, error = null, bytes = 0, totalBytes = 0, contentUri = null)
        }
        start()
        kickService()
    }

    @Synchronized
    fun clearCompleted() {
        _jobs.value = _jobs.value.filterNot { it.status == DownloadStatus.DONE }
        persist()
    }

    @Synchronized
    fun clearFailed() {
        _jobs.value = _jobs.value.filterNot { it.status == DownloadStatus.FAILED }
        persist()
    }

    @Synchronized
    fun deleteDownloaded(job: DownloadJob): Boolean {
        var ok = false
        val uri = job.contentUri
        if (uri != null) {
            ok = storage.deleteDocument(uri)
            if (!ok) {
                AppLog.w(TAG, "Delete failed for '${job.title}' ($uri)")
            }
        } else {
            ok = finishedFileFor(job)?.delete() == true
        }
        _jobs.value = _jobs.value.filterNot { it.id == job.id }
        persist()
        AppLog.i(TAG, "Deleted file of '${job.title}' (ok=$ok)")
        return ok
    }

    /** Local playable Uri for a downloaded Squidify song (any quality). */
    fun downloadedUriFor(songId: String): Uri? {
        val job = _jobs.value.firstOrNull {
            it.source == DownloadSource.SQUIDIFY && it.songId == songId && it.status == DownloadStatus.DONE
        } ?: return null
        val content = job.contentUri
        if (content != null && storage.documentExists(content)) return Uri.parse(content)
        return finishedFileFor(job)?.let(Uri::fromFile)
    }

    /** Local playable Uri for a downloaded KHInsider track (matched by track page URL). */
    fun downloadedUriForKhi(trackUrl: String): Uri? {
        val job = _jobs.value.firstOrNull {
            it.source == DownloadSource.KHINSIDER && it.kind == "audio" &&
                it.requestUrl == trackUrl && it.status == DownloadStatus.DONE
        } ?: return null
        val content = job.contentUri
        if (content != null && storage.documentExists(content)) return Uri.parse(content)
        return finishedFileFor(job)?.let(Uri::fromFile)
    }

    /** All finished audio downloads (the offline library shown in the UI). */
    fun completedAudio(): List<DownloadJob> =
        _jobs.value.filter { it.status == DownloadStatus.DONE && it.kind == "audio" }

    // ---------------------------------------------------------------- integrity

    /**
     * Reconciles the registry with the real disk state on startup.
     *
     * A crash (or Android cleaning pending MediaStore rows) could leave a job
     * marked DONE whose file is actually gone — the offline library then shows
     * a phantom track that fails on play. Every DONE job's target is checked;
     * missing ones are marked FAILED with a clear reason, so they leave the
     * library and show up in the failed section with a Retry button.
     */
    private suspend fun verifyLibrary() {
        withContext(Dispatchers.IO) {
            // A granted-but-unreachable tree would make EVERYTHING look missing
            // (e.g. the SD card was pulled) — don't verify in that case.
            if (!storage.treeReachable()) {
                AppLog.w(TAG, "Storage tree unreachable — skipping library verification")
                return@withContext
            }
            val doneJobs = _jobs.value.filter { it.status == DownloadStatus.DONE }
            if (doneJobs.isEmpty()) return@withContext
            var missing = 0
            for (job in doneJobs) {
                val exists = when {
                    job.contentUri != null && storage.documentExists(job.contentUri) -> true
                    else -> finishedFileFor(job) != null
                }
                if (!exists) {
                    missing++
                    AppLog.w(TAG, "Library check: '${job.title}' is marked done but the file is gone — marking failed")
                    runCatching {
                        update(job.id) {
                            it.copy(
                                status = DownloadStatus.FAILED,
                                error = "File missing on disk (interrupted download?) — tap retry",
                            )
                        }
                    }
                }
            }
            if (missing > 0) {
                AppLog.i(TAG, "Library check finished: $missing of ${doneJobs.size} finished downloads are missing")
            } else {
                AppLog.d(TAG, "Library check finished: all ${doneJobs.size} finished downloads are intact")
            }
        }
    }

    fun storageUsedBytes(): Long {
        // finished jobs carry their final size; plus anything in the legacy dir
        var total = _jobs.value.filter { it.status == DownloadStatus.DONE }.sumOf { it.totalBytes }
        total += storage.legacyDir().listFiles()?.sumOf { it.length() } ?: 0L
        return total
    }

    // ---------------------------------------------------------------- workers

    private suspend fun workerLoop(slot: Int) {
        while (true) {
            val job = try {
                pickNext()
            } catch (t: Throwable) {
                AppLog.e(TAG, "Queue scan failed: ${t.javaClass.simpleName}: ${t.message}", t)
                null
            }
            if (job == null) {
                if (slot == 0) maybeStopService()
                kotlinx.coroutines.delay(700)
                continue
            }
            try {
                semaphore.withPermit {
                    runJob(job)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce // scope shutdown — let the worker die quietly
            } catch (t: Throwable) {
                // a dying worker used to kill the whole app; now it is logged
                // and the worker keeps serving the rest of the queue
                AppLog.e(
                    TAG,
                    "Worker $slot crashed on '${job.title}': ${t.javaClass.simpleName}: ${t.message}",
                    t,
                )
                runCatching {
                    update(job.id) {
                        it.copy(
                            status = DownloadStatus.FAILED,
                            error = "${t.javaClass.simpleName}: ${t.message}".take(300),
                        )
                    }
                }
                kotlinx.coroutines.delay(1_000)
            }
        }
    }

    @Synchronized
    private fun pickNext(): DownloadJob? {
        // A failed job can be retried while its previous worker is still
        // unwinding. Never let another worker reserve that same ID until the
        // previous run has released its reservation in finally.
        val next = _jobs.value.firstOrNull {
            it.status == DownloadStatus.QUEUED && it.id !in activeJobs
        } ?: return null
        // Reserve before returning; a second worker cannot select the same job.
        _jobs.value = _jobs.value.map {
            if (it.id == next.id) it.copy(status = DownloadStatus.RUNNING, error = null) else it
        }
        activeJobs += next.id
        persist()
        return next.copy(status = DownloadStatus.RUNNING, error = null)
    }

    private suspend fun runJob(job: DownloadJob) {
        val id = job.id
        AppLog.i(TAG, "Starting: '${job.title}' [${job.qualityLabel}]")
        var partFile: File? = null
        try {
            // File/permission failures here must still pass through finally so
            // activeJobs cannot retain a dead reservation forever.
            val part = stagingPartFile(job)
            partFile = part
            if (cancellations.contains(id)) throw kotlinx.coroutines.CancellationException("paused before start")
            // resolve KHInsider track page -> direct file URL (audio only)
            // Always resolve a fresh KHInsider URL when a job starts.
            // A persisted signed/direct URL may have expired while queued or
            // while the previous partial download was paused.
            var url = if (job.source == DownloadSource.KHINSIDER && job.kind == "audio") null else job.fileUrl
            if (url == null && job.source == DownloadSource.KHINSIDER && job.kind == "audio") {
                AppLog.d(TAG, "Resolving KHInsider track page → direct URL…")
                val res = khi.resolve(job.requestUrl, forceRefresh = true)
                // Preserve the format selected when this job was queued.
                // A later global quality change must not change the downloaded
                // bytes while leaving the old extension and MIME type behind.
                val requestedFormat = job.format.lowercase()
                val exactUrl = when (requestedFormat) {
                    "flac" -> res.flac
                    "mp3" -> res.mp3
                    else -> null
                }
                url = exactUrl ?: res.directUrl
                if (url == null) {
                    AppLog.e(TAG, "KHInsider resolve has no $requestedFormat URL for '${job.title}'")
                    throw IOException("Requested $requestedFormat is unavailable for this track")
                }
                val pathFormat = url.substringBefore('?').substringAfterLast('.', "").lowercase()
                val reportedType = res.type?.lowercase().orEmpty()
                val actualFormat = when {
                    pathFormat in setOf("flac", "mp3") -> pathFormat
                    "flac" in reportedType -> "flac"
                    "mp3" in reportedType -> "mp3"
                    else -> null
                }
                if (actualFormat != null && actualFormat != requestedFormat) {
                    throw IOException("Requested $requestedFormat, but the resolver returned $actualFormat")
                }
                AppLog.d(TAG, "Resolved to: $url")
                update(id) { it.copy(fileUrl = url) }
            }
            if (url == null) url = job.requestUrl
            if (cancellations.contains(id)) throw kotlinx.coroutines.CancellationException("paused before network request")

            var startByte = if (part.exists()) part.length() else 0L
            if (startByte > 0) AppLog.d(TAG, "Resuming '${job.title}' from byte $startByte")
            val rb = Request.Builder().url(url).header("Accept", "*/*")
            if (startByte > 0) rb.header("Range", "bytes=$startByte-")
            val call = http.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(20, TimeUnit.SECONDS)
                .build()
                .newCall(rb.build())
            activeCalls[id] = call
            if (cancellations.contains(id)) throw kotlinx.coroutines.CancellationException("paused before network request")

            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.e(TAG, "Download of '${job.title}' failed: HTTP ${resp.code} ${resp.message} — ${url.take(120)}")
                    throw IOException("HTTP ${resp.code}")
                }
                val totalFromServer = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                val isRange = resp.code == 206
                var rangeTotal = -1L
                if (isRange) {
                    val parsedRange = parseHttpContentRange(resp.header("Content-Range"))
                    val rangeStart = parsedRange?.start
                    val rangeEnd = parsedRange?.endInclusive
                    rangeTotal = parsedRange?.total ?: -1L
                    if (rangeStart != startByte || rangeEnd == null || rangeEnd < startByte) {
                        part.delete()
                        throw IOException("Server returned an invalid byte range while resuming")
                    }
                    if (totalFromServer > 0 && rangeEnd - startByte + 1L != totalFromServer) {
                        part.delete()
                        throw IOException("Server returned a mismatched Content-Length for the byte range")
                    }
                } else if (startByte > 0) {
                    AppLog.w(TAG, "Server ignored Range for '${job.title}' — restarting from zero")
                    part.delete()
                    startByte = 0
                }
                val contentType = resp.header("Content-Type")
                AppLog.d(TAG, "'${job.title}': HTTP ${resp.code}, ${FmtSafe.bytes(totalFromServer)}, type=$contentType")
                val total = when {
                    rangeTotal > 0 -> rangeTotal
                    isRange && totalFromServer > 0 -> startByte + totalFromServer
                    isRange -> -1L
                    else -> totalFromServer
                }
                update(id) { it.copy(bytes = startByte, totalBytes = if (total > 0) total else 0L) }

                val body = resp.body ?: throw IOException("Empty body")
                val output = if (isRange && part.exists())
                    java.io.FileOutputStream(part, true)
                else
                    java.io.FileOutputStream(part, false)
                val input = body.byteStream()
                val buf = ByteArray(64 * 1024)
                var lastUpdate = 0L
                var lastLoggedPercent = 0
                try {
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        startByte += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 300) {
                            lastUpdate = now
                            update(id) { it.copy(bytes = startByte, totalBytes = if (total > 0) total else 0L) }
                            if (total > 0) {
                                val pct = ((startByte * 100) / total).toInt()
                                if (pct >= lastLoggedPercent + 25) {
                                    lastLoggedPercent = pct
                                    AppLog.d(TAG, "'${job.title}': $pct% (${FmtSafe.bytes(startByte)} / ${FmtSafe.bytes(total)})")
                                }
                            }
                        }
                        if (cancellations.contains(id)) throw kotlinx.coroutines.CancellationException("paused")
                    }
                    output.flush()
                } finally {
                    try { output.close() } catch (_: Exception) {}
                }

                if (cancellations.contains(id)) throw kotlinx.coroutines.CancellationException("paused after transfer")
                if (total > 0 && startByte != total) {
                    throw IOException("Incomplete download: received ${startByte} of ${total} bytes")
                }

                // Embed the album cover into single track files
                if (job.kind == "audio") {
                    embedCoverArt(job, part)
                }

                if (cancellations.contains(id)) throw kotlinx.coroutines.CancellationException("paused before publishing")
                val finalSize = part.length()
                val finalUri = publishToMusic(job, part)
                // Publication copies the staged file into SAF/MediaStore/public
                // storage. Pause/cancel can arrive during that suspend call, so
                // serialize the final state transition with the UI controls.
                val committed = synchronized(this) {
                    val current = _jobs.value.firstOrNull { it.id == id }
                    val cancelled = cancellations.contains(id) ||
                        current == null ||
                        current.status == DownloadStatus.PAUSED
                    if (!cancelled) {
                        part.delete()
                        update(id) {
                            it.copy(
                                status = DownloadStatus.DONE,
                                bytes = finalSize,
                                totalBytes = finalSize,
                                completedAt = System.currentTimeMillis(),
                                contentUri = finalUri?.toString(),
                            )
                        }
                    }
                    !cancelled
                }
                if (!committed) {
                    finalUri?.let { storage.deleteDocument(it.toString()) }
                    throw kotlinx.coroutines.CancellationException("paused while publishing")
                }
                val where = finalUri?.toString() ?: File(storage.legacyDir(), job.fileName).absolutePath
                AppLog.i(TAG, "Completed: '${job.title}' — ${FmtSafe.bytes(finalSize)} → $where")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            val bytes = partFile?.length() ?: job.bytes
            update(id) { it.copy(status = DownloadStatus.PAUSED, bytes = bytes, error = null) }
            AppLog.i(TAG, "Paused: '${job.title}' — ${FmtSafe.bytes(bytes)} kept for resume")
        } catch (e: IOException) {
            if (cancellations.contains(id) || _jobs.value.firstOrNull { it.id == id }?.status == DownloadStatus.PAUSED) {
                val bytes = partFile?.length() ?: job.bytes
                update(id) { it.copy(status = DownloadStatus.PAUSED, bytes = bytes, error = null) }
                AppLog.i(TAG, "Paused: '${job.title}' — ${FmtSafe.bytes(bytes)} kept for resume")
            } else {
                val msg = "${e.javaClass.simpleName}: ${e.message ?: "I/O error"}"
                AppLog.e(TAG, "Failed: '${job.title}' — $msg", e)
                update(id) { it.copy(status = DownloadStatus.FAILED, error = msg.take(300)) }
            }
        } catch (e: Throwable) {
            // Catch fatal worker failures without confusing a user pause with
            // a failed download.
            if (cancellations.contains(id) || _jobs.value.firstOrNull { it.id == id }?.status == DownloadStatus.PAUSED) {
                val bytes = partFile?.length() ?: job.bytes
                update(id) { it.copy(status = DownloadStatus.PAUSED, bytes = bytes, error = null) }
                AppLog.i(TAG, "Paused: '${job.title}' — ${FmtSafe.bytes(bytes)} kept for resume")
            } else {
                val msg = "${e.javaClass.simpleName}: ${e.message ?: "Unknown error"}"
                AppLog.e(TAG, "Failed: '${job.title}' — $msg", e)
                runCatching { update(id) { it.copy(status = DownloadStatus.FAILED, error = msg.take(300)) } }
            }
        } finally {
            activeCalls.remove(id)
            // Synchronize with pause()/resume(): otherwise a resume arriving
            // between reading the flag and removing activeJobs could be lost.
            val shouldResume = synchronized(this) {
                val requested = resumeAfterPause.remove(id)
                cancellations.remove(id)
                activeJobs.remove(id)
                if (requested) {
                    update(id) {
                        if (it.status == DownloadStatus.PAUSED) it.copy(status = DownloadStatus.QUEUED, error = null) else it
                    }
                }
                requested
            }
            if (shouldResume) kickService()
            persistNow()
        }
    }

    // ---------------------------------------------------------------- cover art

    /**
     * Embeds artwork only into formats with an implementation we know is safe.
     * Writing an ID3 header to Ogg/Opus, WAV or MP4/M4A changes the container
     * signature and can make a valid download unreadable.
     */
    private fun embedCoverArt(job: DownloadJob, part: File) {
        val coverUrl = job.coverUrl ?: return
        if (part.length() < 512) return
        val format = job.format.lowercase()
        if (format !in setOf("flac", "mp3")) {
            AppLog.d(TAG, "'${job.title}': cover embedding is not implemented for $format; preserving original file")
            return
        }
        try {
            val bytes = downloadBytes(coverUrl)
            if (bytes.isEmpty()) return
            val mime = sniffImageMime(bytes)
            val head = ByteArray(4)
            java.io.RandomAccessFile(part, "r").use { it.readFully(head) }

            val isFlac = head.contentEquals(byteArrayOf(
                'f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(),
            ))
            val hasId3 = head[0] == 'I'.code.toByte() &&
                head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()

            when (format) {
                "flac" -> {
                    if (!isFlac) {
                        AppLog.w(TAG, "'${job.title}': expected FLAC but file signature did not match; skipped cover embedding")
                        return
                    }
                    if (flacHasPicture(part)) {
                        AppLog.d(TAG, "'${job.title}': FLAC already has embedded art — skipping")
                        return
                    }
                    insertFlacPicture(part, bytes, mime)
                    AppLog.i(TAG, "'${job.title}': embedded cover (${FmtSafe.bytes(bytes.size.toLong())}, $mime) into FLAC")
                }
                "mp3" -> {
                    if (isFlac || head[0] == 'O'.code.toByte()) {
                        AppLog.w(TAG, "'${job.title}': expected MP3 but file signature did not match; skipped cover embedding")
                        return
                    }
                    if (hasId3) {
                        AppLog.d(TAG, "'${job.title}': MP3 already has an ID3 tag — skipping cover embed")
                        return
                    }
                    prependId3Apic(part, bytes, mime)
                    AppLog.i(TAG, "'${job.title}': embedded cover (${FmtSafe.bytes(bytes.size.toLong())}, $mime) as ID3 APIC")
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Cover embedding skipped for '${job.title}': ${e.message}")
        }
    }

    private fun downloadBytes(url: String): ByteArray = try {
        val req = Request.Builder().url(url).header("Accept", "*/*").build()
        http.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
            .newCall(req)
            .execute()
            .use { r -> if (r.isSuccessful) r.body?.bytes() ?: ByteArray(0) else ByteArray(0) }
    } catch (_: Exception) {
        ByteArray(0)
    }

    private fun sniffImageMime(bytes: ByteArray): String = when {
        bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8 ->
            "image/jpeg"
        bytes.size >= 8 && (bytes[0].toInt() and 0xFF) == 0x89 && bytes[1] == 'P'.code.toByte() ->
            "image/png"
        else -> "image/jpeg"
    }

    /** Walks the FLAC metadata blocks looking for an existing PICTURE (type 6). */
    private fun flacHasPicture(f: File): Boolean {
        java.io.RandomAccessFile(f, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (!(magic[0] == 'f'.code.toByte() && magic[1] == 'L'.code.toByte() &&
                    magic[2] == 'a'.code.toByte() && magic[3] == 'C'.code.toByte())
            ) return false
            while (true) {
                val header = raf.readInt()
                val isLast = (header and 0x80000000.toInt()) != 0
                val type = (header shr 24) and 0x7F
                val size = header and 0x00FFFFFF
                if (type == 6) return true
                if (isLast) return false
                raf.seek(raf.filePointer + size)
            }
        }
    }

    /**
     * Inserts a PICTURE block right after STREAMINFO (always valid FLAC),
     * writing a fresh file and swapping it in.
     */
    private fun insertFlacPicture(part: File, image: ByteArray, mime: String) {
        val body = java.io.ByteArrayOutputStream()
        fun u32(v: Int) { body.write((v ushr 24) and 0xFF); body.write((v ushr 16) and 0xFF); body.write((v ushr 8) and 0xFF); body.write(v and 0xFF) }
        u32(3) // front cover
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
        u32(mimeBytes.size); body.write(mimeBytes)
        val desc = ByteArray(0)
        u32(desc.size); body.write(desc)
        u32(0); u32(0); u32(0); u32(0) // width, height, depth, colors
        u32(image.size); body.write(image)
        val blockBody = body.toByteArray()

        val tmp = File(part.parentFile, part.name + ".pic")
        java.io.RandomAccessFile(part, "r").use { raf ->
            java.io.FileOutputStream(tmp).use { out ->
                // "fLaC" magic + STREAMINFO block (4-byte header + 34-byte body)
                val magic = ByteArray(4)
                raf.readFully(magic)
                out.write(magic)
                val streamInfo = ByteArray(4 + 34)
                raf.readFully(streamInfo)
                val streamInfoWasLast = (streamInfo[0].toInt() and 0x80) != 0
                if (streamInfoWasLast) {
                    // clear the last-block flag: the PICTURE we append becomes the last one
                    out.write(streamInfo[0].toInt() and 0x7F)
                    out.write(streamInfo, 1, streamInfo.size - 1)
                } else {
                    out.write(streamInfo)
                }
                // PICTURE block: type 6, 24-bit size; last flag set when it ends the chain
                out.write(if (streamInfoWasLast) 0x86 else 0x06)
                out.write((blockBody.size ushr 16) and 0xFF)
                out.write((blockBody.size ushr 8) and 0xFF)
                out.write(blockBody.size and 0xFF)
                out.write(blockBody)
                // copy the remaining metadata blocks + audio frames
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = raf.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        part.delete()
        if (!tmp.renameTo(part)) {
            tmp.copyTo(part, overwrite = true)
            tmp.delete()
        }
    }

    /** Prepends an ID3v2.4 tag containing a single APIC frame (front cover). */
    private fun prependId3Apic(part: File, image: ByteArray, mime: String) {
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
        // frame body: encoding(1) + mime + \0 + pictype(1) + desc + \0 + data
        val frameBodySize = 1 + mimeBytes.size + 1 + 1 + 1 + image.size

        val tag = java.io.ByteArrayOutputStream()
        tag.write("ID3".toByteArray())
        tag.write(0x04); tag.write(0x00); tag.write(0x00) // v2.4, no flags
        fun syncsafe(v: Int) {
            tag.write((v ushr 21) and 0x7F); tag.write((v ushr 14) and 0x7F)
            tag.write((v ushr 7) and 0x7F); tag.write(v and 0x7F)
        }
        // tag size = frame header (10) + frame body, excluding the 10-byte tag header
        syncsafe(10 + frameBodySize)
        tag.write("APIC".toByteArray())
        syncsafe(frameBodySize)
        tag.write(0x00); tag.write(0x00) // frame flags
        tag.write(0x00) // text encoding: ISO-8859-1
        tag.write(mimeBytes); tag.write(0x00)
        tag.write(0x03) // picture type: front cover
        tag.write(0x00) // empty description
        tag.write(image)

        val tmp = File(part.parentFile, part.name + ".tag")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(tag.toByteArray())
            part.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        part.delete()
        if (!tmp.renameTo(part)) {
            tmp.copyTo(part, overwrite = true)
            tmp.delete()
        }
    }

    // ---------------------------------------------------------------- publish

    /**
     * Move the finished .part file into Squidify/Downloads/<Album>/ via the
     * storage layer (SAF tree, MediaStore or legacy path).
     * Returns the playable Uri, or null when the legacy path was used.
     */
    private suspend fun publishToMusic(job: DownloadJob, part: File): Uri? {
        val albumDir = job.album ?: job.artist ?: "Unknown Album"
        val isImage = job.kind == "image"
        return storage.publishDownload(
            album = albumDir,
            fileName = job.fileName,
            mimeType = if (isImage) imageMimeFor(job.format) else mimeTypeFor(job.format),
            isImage = isImage,
            durationMs = job.durationMs,
            title = job.title,
            artist = job.artist,
            albumTitle = job.album,
            source = part,
        )
    }

    // ---------------------------------------------------------------- service

    private fun kickService() {
        val active = _jobs.value.count { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
        if (active > 0) {
            val ctx = context.applicationContext
            try {
                ctx.startService(Intent(ctx, DownloadService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private fun maybeStopService() {
        val active = _jobs.value.count { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
        if (active == 0) {
            val ctx = context.applicationContext
            try {
                ctx.stopService(Intent(ctx, DownloadService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    fun hash(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        var h = 0xcbf29ce484222325UL
        for (b in bytes) {
            h = h xor b.toULong()
            h = h * 0x100000001b3UL
        }
        return h.toString(16)
    }
}

/** Tiny byte formatter local to this file (Fmt is a @Composable helper). */
private object FmtSafe {
    fun bytes(v: Long): String = when {
        v >= 1L shl 30 -> "%.2f GB".format(v.toDouble() / (1L shl 30))
        v >= 1L shl 20 -> "%.1f MB".format(v.toDouble() / (1L shl 20))
        v >= 1024 -> "%.0f KB".format(v.toDouble() / 1024)
        else -> "$v B"
    }
}
