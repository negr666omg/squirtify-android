package org.squidify.android.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.squidify.android.util.AllFilesAccess
import org.squidify.android.util.AppLog
import java.io.File
import java.io.IOException

/**
 * User-visible storage layer.
 *
 * Preferred layout ("All files access" granted — the default the app asks for):
 *   /storage/emulated/0/Squidify/UserData/          — settings.json, favorites.json…
 *   /storage/emulated/0/Squidify/Downloads/<Album>/ — downloaded tracks + covers
 *   /storage/emulated/0/Squidify/Downloaded Images/ — covers saved from the viewer
 *
 * Secondary layout (SAF tree picked before/without all-files access):
 *   <Squidify tree>/UserData/ · <Squidify tree>/Downloads/<Album>/ · …
 *
 * Fallback without either (scoped storage safe):
 *   internal files: <filesDir>/UserData/
 *   audio:     MediaStore Music/Squidify/Downloads/<Album> (API 29+)
 *   images:    MediaStore Pictures/Squidify/Downloaded Images (API 29+)
 *   pre-29:    public Music dir via legacy write permission
 */
/**
 * Copies a stream via a same-directory temporary file, then atomically
 * replaces the destination. Readers never observe a half-written JSON file.
 */
internal fun copyStreamAtomically(input: java.io.InputStream, destination: File) {
    val parent = destination.parentFile ?: throw IOException("Destination has no parent directory")
    if (!parent.exists() && !parent.mkdirs()) throw IOException("Cannot create destination directory")
    val temporary = File(parent, "." + destination.name + ".migrating")
    try {
        input.use { source ->
            temporary.outputStream().buffered().use { output ->
                source.copyTo(output, 64 * 1024)
                output.flush()
            }
        }
        try {
            java.nio.file.Files.move(
                temporary.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temporary.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    } finally {
        temporary.delete()
    }
}

class UserStorage(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("squidify_storage", Context.MODE_PRIVATE)

    private val resolver = context.contentResolver

    /** Granted SAF tree for the "Squidify" folder, or null. */
    var treeUri: String?
        get() = prefs.getString(KEY_TREE, null)
        private set(value) {
            prefs.edit().putString(KEY_TREE, value).apply()
        }

    val hasTree: Boolean get() = treeUri != null

    /**
     * True when it is safe to check tree files: either no tree is granted
     * (MediaStore/legacy checks are always safe) or the granted tree is
     * actually reachable (SD card present, folder not deleted).
     */
    fun treeReachable(): Boolean {
        val uri = treeUri ?: return true
        return runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(uri))?.isDirectory == true
        }.getOrDefault(false)
    }

    private val dirCache = mutableMapOf<String, DocumentFile>()

    // ------------------------------------------------------------- tree mgmt

    /**
     * Adopts the picked folder as the Squidify root, creates the standard
     * subfolders and migrates internal user data into UserData/.
     * Returns false when the folder is not usable.
     */
    fun adoptTree(uri: Uri): Boolean {
        return try {
            // persist permission for future launches
            resolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val root = DocumentFile.fromTreeUri(context, uri) ?: return false
            if (!root.isDirectory) return false
            treeUri = uri.toString()
            dirCache.clear()
            listOf(DIR_USERDATA, DIR_DOWNLOADS, DIR_IMAGES).forEach { ensureDir(root, it) }
            AppLog.i(TAG, "Storage tree adopted: $uri")
            true
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not adopt storage tree: ${e.message}")
            false
        }
    }

    fun releaseTree() {
        treeUri?.let {
            runCatching {
                resolver.releasePersistableUriPermission(
                    Uri.parse(it),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        treeUri = null
        dirCache.clear()
    }

    private fun rootDir(): DocumentFile? {
        val uri = treeUri ?: return null
        if (uri !in dirCache) {
            val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri)) }.getOrNull()
            if (root?.isDirectory == true) dirCache[uri] = root
        }
        return dirCache[uri]
    }

    private fun ensureDir(root: DocumentFile, name: String): DocumentFile {
        val existing = root.findFile(name)
        if (existing != null && existing.isDirectory) return existing
        val created = root.createDirectory(name)
            ?: throw IOException("Cannot create folder '$name' in the Squidify folder")
        return created
    }

    /** Subfolder of the tree (or null without a tree). Creates when missing. */
    private fun treeSubDir(name: String): DocumentFile? {
        val root = rootDir() ?: return null
        synchronized(dirCache) { dirCache.getOrPut(name) { ensureDir(root, name) } }
        return dirCache[name]
    }

    // ------------------------------------------------------------- all-files mode

    /**
     * Direct File I/O is enabled only after the current all-files permission
     * grant has completed its data migration. While migration is pending, keep
     * using SAF/internal storage so writes cannot diverge from the source data.
     */
    val allFilesMode: Boolean
        get() = AllFilesAccess.isGranted() &&
            prefs.getBoolean("all_files_migrated", false) &&
            prefs.getInt("all_files_migration_version", 0) >= 2

    /** Squidify/UserData via all-files access, or null. */
    private fun afUserDir(): File? = AllFilesAccess.subDir(DIR_USERDATA)

    /** Reads a user-data file (all-files → SAF tree → internal fallback). */
    fun readUserData(fileName: String): String? {
        // 0) direct File I/O in Squidify/UserData ("All files access" granted)
        if (allFilesMode) {
            runCatching {
                val f = File(afUserDir(), fileName)
                if (f.isFile) return f.readText()
            }
        }
        // 1) SAF tree
        treeSubDir(DIR_USERDATA)?.let { dir ->
            runCatching {
                val f = dir.findFile(fileName)
                if (f != null && f.isFile) {
                    resolver.openInputStream(f.uri)?.use { it.readBytes().decodeToString() }
                        ?.also { return it }
                }
            }
        }
        // 2) internal fallback
        return runCatching { internalUserDir().let { File(it, fileName) } }
            .getOrNull()
            ?.takeIf { it.isFile }
            ?.readText()
    }

    /** Writes a user-data file (all-files → SAF tree → internal fallback). */
    fun writeUserData(fileName: String, text: String) {
        // 0) direct File I/O in Squidify/UserData ("All files access" granted)
        if (allFilesMode) {
            runCatching {
                val dir = afUserDir() ?: throw IOException("Squidify folder unavailable")
                File(dir, fileName).writeText(text)
                return
            }.onFailure {
                AppLog.w(TAG, "Writing $fileName to ${AllFilesAccess.rootLabel()}/UserData failed: ${it.message}")
            }
        }
        // 1) SAF tree
        val dir = treeSubDir(DIR_USERDATA)
        if (dir != null) {
            val ok = runCatching {
                val existing = dir.findFile(fileName)
                val target = when {
                    existing != null && existing.isFile -> existing
                    existing != null -> { // a directory with that name — hopeless, bail out
                        throw IOException("$fileName exists as a folder")
                    }
                    else -> dir.createFile("application/json", fileName)
                        ?: throw IOException("Cannot create $fileName")
                }
                resolver.openOutputStream(target.uri, "w")?.use { it.write(text.toByteArray()) }
                    ?: throw IOException("Cannot open $fileName for writing")
                true
            }.getOrDefault(false)
            if (ok) return
            AppLog.w(TAG, "Writing $fileName to the tree failed — falling back to internal storage")
        }
        // 2) internal fallback
        runCatching {
            val dir = internalUserDir()
            File(dir, fileName).writeText(text)
        }.onFailure { AppLog.w(TAG, "Writing $fileName failed: ${it.message}") }
    }

    private fun internalUserDir(): File =
        File(context.filesDir, DIR_USERDATA).also { if (!it.exists()) it.mkdirs() }

    /**
     * Copies internal user data into the freshly granted SAF tree. A temporary
     * document is renamed only after the copy completes, so an interrupted
     * migration cannot leave a truncated final JSON file.
     */
    fun migrateInternalToTree() {
        val dir = treeSubDir(DIR_USERDATA)
            ?: throw IOException("The selected Squidify folder is unavailable")
        listOf("settings.json", "favorites.json", "favorite-albums.json", "history.json", "downloads.json").forEach { name ->
            val src = File(internalUserDir(), name)
            if (!src.isFile) return@forEach
            val existing = dir.findFile(name)
            if (existing?.isFile == true) return@forEach
            if (existing != null) throw IOException("$name exists as a folder in the selected storage tree")

            val temporaryName = "$name.migrating"
            dir.findFile(temporaryName)?.delete()
            val temporary = dir.createFile("application/octet-stream", temporaryName)
                ?: throw IOException("Cannot create a temporary copy for $name")
            try {
                resolver.openOutputStream(temporary.uri, "w")?.use { output ->
                    src.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                } ?: throw IOException("Cannot open the temporary copy for $name")
                if (!temporary.renameTo(name)) throw IOException("Cannot finalize the migrated copy of $name")
                AppLog.i(TAG, "Migrated $name into the Squidify/UserData folder")
            } catch (failure: Exception) {
                temporary.delete()
                throw failure
            }
        }
    }

    /**
     * Moves user data into Squidify/UserData after "All files access" is
     * granted. SAF is copied FIRST because readUserData() checked it before
     * the internal fallback; internal files are used only when SAF has no
     * corresponding file. The selected SAF tree must be reachable or the
     * migration fails and MainActivity will retry it on the next launch.
     */
    fun migrateToAllFiles() {
        if (!AllFilesAccess.isGranted()) throw IOException("All-files access is no longer granted")
        val target = afUserDir() ?: throw IOException("Squidify/UserData is unavailable")
        val supportedFiles = setOf(
            "settings.json",
            "favorites.json",
            "favorite-albums.json",
            "history.json",
            "downloads.json",
        )
        val copiedFromTree = mutableSetOf<String>()

        if (treeUri != null) {
            if (!treeReachable()) throw IOException("The previously selected Squidify folder is unavailable")
            val sourceDir = treeSubDir(DIR_USERDATA)
                ?: throw IOException("Cannot access UserData in the selected Squidify folder")
            sourceDir.listFiles().forEach { document ->
                if (!document.isFile) return@forEach
                val name = document.name ?: return@forEach
                // Ignore interrupted *.migrating files and anything outside
                // the app's known user-data registry.
                if (name !in supportedFiles) return@forEach
                val input = resolver.openInputStream(document.uri)
                    ?: throw IOException("Cannot read $name from the selected Squidify folder")
                input.use { copyStreamAtomically(it, File(target, name)) }
                copiedFromTree += name
                AppLog.i(TAG, "Migrated $name from the selected folder into " + AllFilesAccess.rootLabel() + "/" + DIR_USERDATA)
            }
        }

        // Keep the previous readUserData precedence: SAF first, then internal
        // fallback. Internal files fill only names absent from the selected
        // tree; they never overwrite a file that was read from SAF.
        File(context.filesDir, DIR_USERDATA).listFiles()?.forEach { source ->
            if (!source.isFile || source.name !in supportedFiles || source.name in copiedFromTree) return@forEach
            copyStreamAtomically(source.inputStream(), File(target, source.name))
            AppLog.i(TAG, "Migrated " + source.name + " from internal fallback into " + AllFilesAccess.rootLabel() + "/" + DIR_USERDATA)
        }

        // Do not switch UserStorage into direct File I/O mode until every
        // source has been copied successfully. A failed/unavailable SAF source
        // leaves the old storage path active and can safely be retried.
        prefs.edit()
            .putBoolean("all_files_migrated", true)
            .putInt("all_files_migration_version", 2)
            .apply()
        AppLog.i(TAG, "User data migrated into " + AllFilesAccess.rootLabel() + "/" + DIR_USERDATA)
    }

    // ------------------------------------------------------------- downloads

    fun downloadsLocationLabel(): String =
        if (allFilesMode) "${AllFilesAccess.rootLabel()}/Downloads/<Album>"
        else if (hasTree) "Squidify/Downloads/<Album>"
        else if (Build.VERSION.SDK_INT >= 29) "Music/Squidify/Downloads/<Album>"
        else File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),
            "Squidify/Downloads",
        ).absolutePath

    fun imagesLocationLabel(): String =
        if (allFilesMode) "${AllFilesAccess.rootLabel()}/Downloaded Images"
        else if (hasTree) "Squidify/Downloaded Images"
        else if (Build.VERSION.SDK_INT >= 29) "Pictures/Squidify/Downloaded Images"
        else File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
            "Squidify/Downloaded Images",
        ).absolutePath

    /**
     * Publishes a finished download into Squidify/Downloads/<Album>/.
     * Returns the playable Uri, or null when the legacy path was used.
     */
    suspend fun publishDownload(
        album: String,
        fileName: String,
        mimeType: String,
        isImage: Boolean,
        durationMs: Long,
        title: String,
        artist: String?,
        albumTitle: String?,
        source: File,
    ): Uri? = withContext(Dispatchers.IO) {
        val safeAlbum = sanitizeSegment(album)
        // 0) direct File I/O in Squidify/Downloads/<Album> ("All files access")
        if (allFilesMode) {
            runCatching {
                val albumDir = File(AllFilesAccess.subDir(DIR_DOWNLOADS), safeAlbum)
                    .also { if (!it.exists()) it.mkdirs() }
                val target = File(albumDir, fileName)
                source.copyTo(target, overwrite = true)
                AppLog.i(TAG, "Saved $fileName → ${AllFilesAccess.rootLabel()}/$DIR_DOWNLOADS/$safeAlbum")
                return@withContext Uri.fromFile(target)
            }.onFailure {
                AppLog.w(TAG, "Direct publish of $fileName failed: ${it.message} — trying SAF/MediaStore")
            }
        }
        // 1) SAF tree
        val downloads = treeSubDir(DIR_DOWNLOADS)
        if (downloads != null) {
            runCatching {
                val albumDir = synchronized(dirCache) {
                    dirCache.getOrPut("$DIR_DOWNLOADS/$safeAlbum") { ensureDir(downloads, safeAlbum) }
                }
                val existing = albumDir.findFile(fileName)
                val target = when {
                    existing != null && existing.isFile -> existing
                    existing != null -> throw IOException("'$fileName' exists as a folder")
                    else -> albumDir.createFile(mimeType, fileName)
                        ?: throw IOException("Cannot create $fileName")
                }
                resolver.openOutputStream(target.uri, "w")?.use { out ->
                    source.inputStream().use { it.copyTo(out, 64 * 1024) }
                } ?: throw IOException("Cannot open $fileName")
                AppLog.i(TAG, "Saved $fileName → ${dirPathLabel(DIR_DOWNLOADS)}/$safeAlbum")
                return@withContext target.uri
            }.onFailure {
                AppLog.w(TAG, "SAF publish of $fileName failed: ${it.message} — trying MediaStore")
            }
        }
        // 2) MediaStore / legacy
        publishViaMediaStore(
            safeAlbum, fileName, mimeType, isImage, durationMs, title, artist, albumTitle, source,
        )
    }

    private fun publishViaMediaStore(
        album: String,
        fileName: String,
        mimeType: String,
        isImage: Boolean,
        durationMs: Long,
        title: String,
        artist: String?,
        albumTitle: String?,
        source: File,
    ): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/Squidify/Downloads/$album")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (!isImage) {
                    if (durationMs > 0) put(MediaStore.Audio.Media.DURATION, durationMs)
                    if (!artist.isNullOrBlank()) put(MediaStore.Audio.Media.ARTIST, artist)
                    if (!albumTitle.isNullOrBlank()) put(MediaStore.Audio.Media.ALBUM, albumTitle)
                    put(MediaStore.Audio.Media.TITLE, title)
                }
            }
            val collection =
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
                ?: throw IOException("MediaStore rejected the insert")
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    source.inputStream().use { it.copyTo(out, 64 * 1024) }
                } ?: throw IOException("Cannot open MediaStore output")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            val pub = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC),
                "Squidify/Downloads/$album",
            )
            val dir = if (pub.canWrite() || pub.mkdirs()) pub else legacyDir()
            val target = File(dir, fileName)
            if (target.exists()) target.delete()
            source.copyTo(target, overwrite = true)
            // Persist a concrete URI so offline lookup and deletion use the
            // exact public file path on Android 8/9, not the app-private fallback.
            return Uri.fromFile(target)
        }
    }

    /** Pre-29 app-external dir, still used as the very last fallback. */
    fun legacyDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "Downloads").also { if (!it.exists()) it.mkdirs() }
    }

    // ------------------------------------------------------------- images

    /**
     * Saves an image (cover art) into Squidify/Downloaded Images/.
     * Returns a human-readable location for the toast.
     */
    suspend fun saveImage(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        // 0) direct File I/O in Squidify/Downloaded Images ("All files access")
        if (allFilesMode) {
            runCatching {
                val dir = AllFilesAccess.subDir(DIR_IMAGES) ?: throw IOException("Squidify folder unavailable")
                File(dir, fileName).writeBytes(bytes)
                return@withContext "${AllFilesAccess.rootLabel()}/$DIR_IMAGES/$fileName"
            }.onFailure {
                AppLog.w(TAG, "Direct image save failed: ${it.message} — trying SAF/MediaStore")
            }
        }
        // 1) SAF tree
        treeSubDir(DIR_IMAGES)?.let { dir ->
            runCatching {
                val existing = dir.findFile(fileName)
                val target = when {
                    existing != null && existing.isFile -> existing
                    existing != null -> throw IOException("'$fileName' exists as a folder")
                    else -> dir.createFile(mimeType, fileName)
                        ?: throw IOException("Cannot create $fileName")
                }
                resolver.openOutputStream(target.uri, "w")?.use { it.write(bytes) }
                    ?: throw IOException("Cannot open $fileName")
                return@withContext "Squidify/Downloaded Images/$fileName"
            }.onFailure {
                AppLog.w(TAG, "SAF image save failed: ${it.message} — trying MediaStore")
            }
        }
        // 2) MediaStore Pictures (29+)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Squidify/Downloaded Images")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values,
            ) ?: throw IOException("MediaStore rejected the image insert")
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                ?: throw IOException("Cannot open image output")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return@withContext "Pictures/Squidify/Downloaded Images/$fileName"
        }
        // 3) legacy direct write
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
            "Squidify/Downloaded Images",
        )
        if (dir.canWrite() || dir.mkdirs()) {
            File(dir, fileName).writeBytes(bytes)
            return@withContext dir.absolutePath + "/" + fileName
        }
        throw IOException("No writable location for images")
    }

    // ------------------------------------------------------------- helpers

    /** True when the Uri still exists and can be opened. */
    fun documentExists(uri: String): Boolean {
        if (!uri.startsWith("content://")) return Uri.parse(uri).let { File(it.path ?: "").exists() }
        return runCatching {
            DocumentFile.fromSingleUri(context, Uri.parse(uri))?.exists() == true
        }.getOrDefault(false)
    }

    fun deleteDocument(uri: String): Boolean {
        if (!uri.startsWith("content://")) {
            return Uri.parse(uri).let { File(it.path ?: "").delete() }
        }
        return runCatching {
            resolver.delete(Uri.parse(uri), null, null) > 0 ||
                DocumentFile.fromSingleUri(context, Uri.parse(uri))?.delete() == true
        }.getOrDefault(false)
    }

    private fun dirPathLabel(dir: String): String = when (dir) {
        DIR_DOWNLOADS -> "Squidify/Downloads"
        DIR_USERDATA -> "Squidify/UserData"
        DIR_IMAGES -> "Squidify/Downloaded Images"
        else -> dir
    }

    companion object {
        private const val TAG = "Storage"
        private const val KEY_TREE = "tree_uri"
        const val DIR_USERDATA = "UserData"
        const val DIR_DOWNLOADS = "Downloads"
        const val DIR_IMAGES = "Downloaded Images"

        fun sanitizeSegment(name: String): String {
            val cleaned = name.replace(Regex("""[\\/:*?"<>|\u0000]"""), "_").trim()
            // A dot-only path component resolves to the current/parent folder,
            // not to a child album directory.
            val safe = cleaned.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "Unknown"
            return if (safe.length > 120) safe.take(120) else safe
        }
    }
}
