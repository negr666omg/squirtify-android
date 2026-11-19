package org.squidify.android.util

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.squidify.android.util.AllFilesAccess
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app diagnostic logger. Everything the app does — API calls, stream URL
 * construction, playback errors, download progress, lyrics resolution steps —
 * is recorded here and shown on the Settings → Log tab so the user can see
 * exactly why a track failed to load.
 *
 * Entries are kept in memory (ring buffer) and mirrored to
 * Squirtify/logs/applog.txt with "All files access" (files/applog.txt
 * otherwise), rotated at ~512 KB into applog.old.txt, so they survive
 * restarts.
 */
object AppLog {

    enum class Level(val label: String) {
        DEBUG("D"),
        INFO("I"),
        WARN("W"),
        ERROR("E"),
    }

    data class Entry(
        val id: Long,
        val timeMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: String? = null,
    )

    private const val MAX_MEMORY = 600
    private const val MAX_FILE_BYTES = 512L * 1024L

    private val sensitiveQueryParameter = Regex(
        """(?i)([?&](?:u|t|s|p|token|password|pass|auth|authorization|signature|sig|api[_-]?key|key)=)[^&#\s]+""",
    )
    private val bearerToken = Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~+/-]+=*""")
    private val urlCredentials = Regex("""(?i)(https?://)[^/@\s:]+:[^/@\s]+@""")

    /** Keep diagnostics useful without persisting reusable auth or signed URL secrets. */
    internal fun redactSensitiveValues(value: String): String =
        value
            .replace(sensitiveQueryParameter, "$" + "1[REDACTED]")
            .replace(bearerToken, "$" + "1[REDACTED]")
            .replace(urlCredentials, "$" + "1[REDACTED]@")

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var appContext: Context? = null
    private var counter = 0L
    private val lock = Any()

    /** Call once from Application.onCreate(). */
    fun init(context: Context) {
        appContext = context.applicationContext
        ioScope.launch { loadFromFile() }
    }

    // ------------------------------------------------------------- API

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, t: Throwable? = null) = log(Level.WARN, tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = log(Level.ERROR, tag, message, t)

    fun log(level: Level, tag: String, message: String, t: Throwable? = null) {
        val safeMessage = redactSensitiveValues(message)
        val safeThrowable = t?.let { thr ->
            redactSensitiveValues(
                thr.toString() + "\n" + thr.stackTrace.take(8).joinToString("\n") { "    at $it" },
            )
        }
        // Allocate the ID and publish the entry atomically. Logging can be
        // called concurrently by the UI, playback and two download workers;
        // duplicate LazyColumn keys previously could crash Settings → Log.
        val entry = synchronized(lock) {
            Entry(
                id = ++counter,
                timeMs = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = safeMessage,
                throwable = safeThrowable,
            ).also { _entries.value = (_entries.value + it).takeLast(MAX_MEMORY) }
        }
        ioScope.launch { appendToFile(entry) }
    }

    fun clear() {
        synchronized(lock) { _entries.value = emptyList() }
        ioScope.launch {
            val ctx = appContext ?: return@launch
            runCatching {
                listOf(logDir(ctx), ctx.filesDir).distinct().forEach { dir ->
                    File(dir, "applog.txt").delete()
                    File(dir, "applog.old.txt").delete()
                }
            }
            log(Level.INFO, TAG, "Log cleared")
        }
    }

    /** Whole log as plain text, newest last — for share/copy. */
    fun dump(): String = _entries.value.joinToString("\n") { format(it) }

    fun format(e: Entry): String {
        val t = synchronized(timeFormat) { timeFormat.format(Date(e.timeMs)) }
        val base = "$t ${e.level.label}/ ${e.tag}: ${e.message}"
        return if (e.throwable != null) "$base\n${e.throwable}" else base
    }

    // ------------------------------------------------------------- file

    /** Squirtify/logs with all-files access, otherwise the internal files dir. */
    private fun logDir(ctx: Context): File =
        AllFilesAccess.subDir("logs") ?: ctx.filesDir

    private fun loadFromFile() {
        val ctx = appContext ?: return
        try {
            val lines = mutableListOf<String>()
            // legacy internal files first, active location last (newest wins)
            listOf(ctx.filesDir, logDir(ctx)).distinct().forEach { dir ->
                listOf(File(dir, "applog.old.txt"), File(dir, "applog.txt"))
                    .filter { it.exists() }
                    .forEach { f -> f.readLines().let { lines.addAll(it.takeLast(400)) } }
            }
            // Only parse well-formed lines; keep raw text of others as DEBUG
            val parsed = lines.mapNotNull { raw -> parseLine(raw) }
                .mapIndexed { i, e -> e.copy(id = (i + 1).toLong()) }
            synchronized(lock) {
                // AppLog.init loads disk history asynchronously. Do not replace
                // events already emitted in this process while that read was
                // in flight; merge and re-key the combined list atomically.
                val merged = (parsed + _entries.value)
                    .sortedBy { it.timeMs }
                    .takeLast(MAX_MEMORY)
                    .mapIndexed { index, entry -> entry.copy(id = index + 1L) }
                _entries.value = merged
                counter = merged.lastOrNull()?.id ?: 0L
            }
        } catch (_: Exception) {
        }
    }

    internal fun parseLine(raw: String): Entry? {
        // MM-dd HH:mm:ss.SSS L/ TAG: message
        if (raw.length < 24) return null
        val levelCh = raw.getOrNull(19)
        val level = when (levelCh) {
            'D' -> Level.DEBUG
            'I' -> Level.INFO
            'W' -> Level.WARN
            'E' -> Level.ERROR
            else -> return null
        }
        val rest = raw.substring(22) // after timestamp + " L/ "
        val idx = rest.indexOf(": ")
        if (idx <= 0) return null
        val timeMs = try {
            val df = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            val year = java.util.Calendar.getInstance().apply {
                time = Date()
            }.get(java.util.Calendar.YEAR)
            df.parse(raw.take(18))?.let { d ->
                java.util.Calendar.getInstance().apply { time = d }.run {
                    set(java.util.Calendar.YEAR, year)
                    timeInMillis
                }
            } ?: return null
        } catch (_: Exception) {
            return null
        }
        return Entry(
            id = 0L, // renumbered by caller
            timeMs = timeMs,
            level = level,
            tag = rest.take(idx),
            message = redactSensitiveValues(rest.substring(idx + 2)),
        )
    }

    private fun appendToFile(entry: Entry) {
        val ctx = appContext ?: return
        try {
            val dir = logDir(ctx)
            val f = File(dir, "applog.txt")
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                val old = File(dir, "applog.old.txt")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(format(entry) + "\n")
        } catch (_: Exception) {
        }
    }

    private const val TAG = "AppLog"
}
