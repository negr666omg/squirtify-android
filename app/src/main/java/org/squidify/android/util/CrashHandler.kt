package org.squidify.android.util

import android.content.Context
import org.squidify.android.util.AllFilesAccess
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global crash handler.
 *
 * The very first thing [org.squidify.android.App.onCreate] installs is an
 * uncaught-exception interceptor: every crash (any thread) is written to
 * Squirtify/logs/last_crash.txt (internal files/ when "All files access" is
 * not granted) together with the last portion of the AppLog — so the next
 * launch can show a dialog with a "copy log" button containing the exact
 * stack trace AND everything the app was doing right before it died
 * (API calls, download steps, playback events).
 *
 * A short one-line summary of every crash is also appended to
 * Squirtify/logs/crash_history.txt (last 12 kept), viewable from
 * Settings → Log.
 */
object CrashHandler {

    data class CrashReport(
        val timeMs: Long,
        /** "FATAL: IllegalStateException: boom" — first line of the report. */
        val summary: String,
        /** Full report: header + stack trace + recent app log. */
        val fullLog: String,
    )

    private const val LAST_CRASH = "last_crash.txt"
    private const val HISTORY = "crash_history.txt"
    private const val MAX_HISTORY = 12
    private const val LOG_CONTEXT_LINES = 350

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Squirtify/logs with all-files access, otherwise the internal files dir. */
    private fun logDir(ctx: Context): File =
        AllFilesAccess.subDir("logs") ?: ctx.filesDir

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                android.util.Log.e("SquirtifyCrash", "Uncaught exception on ${thread.name}", throwable)
                writeReport(appContext, thread, throwable)
            } catch (_: Throwable) {
                // never let the handler itself explode
            } finally {
                // hand over to the system's normal crash processing
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    // ------------------------------------------------------------- writing

    private fun writeReport(ctx: Context, thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val stamp = synchronized(timeFormat) { timeFormat.format(Date(now)) }
        val header = buildString {
            appendLine("Squirtify crash report")
            appendLine("Time: $stamp")
            appendLine(
                "App: ${appVersionLabel(ctx)} · Android ${android.os.Build.VERSION.RELEASE} " +
                    "(API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MODEL}",
            )
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine("----- EXCEPTION -----")
            appendLine(throwable.toString())
            throwable.stackTrace.forEach { appendLine("    at $it") }
            var cause = throwable.cause
            var depth = 0
            while (cause != null && depth < 6) {
                appendLine("Caused by: $cause")
                cause.stackTrace.take(14).forEach { appendLine("    at $it") }
                cause = cause.cause
                depth++
            }
        }

        val logContext = buildString {
            appendLine()
            appendLine("----- LAST APP LOG (${LOG_CONTEXT_LINES} lines before the crash) -----")
            val entries = AppLog.entries.value
            val start = (entries.size - LOG_CONTEXT_LINES).coerceAtLeast(0)
            for (e in entries.subList(start, entries.size)) {
                appendLine(AppLog.format(e))
            }
        }

        val full = AppLog.redactSensitiveValues(header + logContext)

        // 1) the file the next launch reads and offers to copy
        File(logDir(ctx), LAST_CRASH).writeText(full)

        // 2) one-line history entry (kept across restarts). Message-less
        // exceptions ("no message") carry no clue about WHERE they came from —
        // the top stack frames make the history line diagnosable on its own.
        val hist = File(logDir(ctx), HISTORY)
        val frames = throwable.stackTrace.take(2)
            .joinToString(" ← ") { "${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
        val line = AppLog.redactSensitiveValues(
            "$stamp  ${throwable.javaClass.simpleName}: ${throwable.message?.take(140) ?: "no message"}" +
                if (frames.isNotBlank()) " @ $frames" else "",
        )
        runCatching {
            val existing = if (hist.exists()) hist.readLines() else emptyList()
            (existing + line).takeLast(MAX_HISTORY).joinToString("\n", postfix = "\n")
                .let { hist.writeText(it) }
        }
    }

    private fun appVersionLabel(ctx: Context): String = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "v${pi.versionName} (${if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()})"
    } catch (_: Exception) {
        "unknown version"
    }

    // ------------------------------------------------------------- reading

    /** The crash from the previous run, or null when the app closed cleanly. */
    fun pendingReport(ctx: Context): CrashReport? {
        val dir = logDir(ctx)
        return try {
            // prefer Squirtify/logs, fall back to a legacy internal report
            val f = listOf(File(dir, LAST_CRASH), File(ctx.filesDir, LAST_CRASH))
                .firstOrNull { it.isFile } ?: return null
            val text = f.readText()
            val timeMs = run {
                val m = Regex("Time: (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})").find(text)
                m?.let {
                    synchronized(timeFormat) { timeFormat.parse(it.groupValues[1]) }?.time
                } ?: f.lastModified()
            }
            val summary = buildString {
                appendLine(
                    text.lineSequence()
                        .dropWhile { !it.startsWith("----- EXCEPTION") }
                        .drop(1)
                        .firstOrNull { it.isNotBlank() }
                        ?.trim() ?: "unknown exception",
                )
                // where it happened — message-less exceptions otherwise give
                // no hint at all in the crash dialog
                text.lineSequence()
                    .dropWhile { !it.startsWith("----- EXCEPTION") }
                    .drop(1)
                    .filter { it.trim().startsWith("at ") }
                    .take(3)
                    .forEach { appendLine(it.trim()) }
            }.trim()
            CrashReport(timeMs = timeMs, summary = summary, fullLog = text)
        } catch (_: Exception) {
            null
        }
    }

    /** Deletes the pending report (after the user closed the crash dialog). */
    fun clearPending(ctx: Context) {
        runCatching { File(logDir(ctx), LAST_CRASH).delete() }
        runCatching { File(ctx.filesDir, LAST_CRASH).delete() }
    }

    /** Previous crash summaries, newest last. */
    fun history(ctx: Context): List<String> = try {
        val dir = logDir(ctx)
        val lines = mutableListOf<String>()
        // legacy internal history first, then the active location
        if (dir != ctx.filesDir) {
            File(ctx.filesDir, HISTORY).takeIf { it.exists() }?.readLines()?.let { lines.addAll(it) }
        }
        File(dir, HISTORY).takeIf { it.exists() }?.readLines()?.let { lines.addAll(it) }
        lines.filter { it.isNotBlank() }.takeLast(MAX_HISTORY * 2)
    } catch (_: Exception) {
        emptyList()
    }

    fun clearHistory(ctx: Context) {
        runCatching { File(logDir(ctx), HISTORY).delete() }
        runCatching { File(ctx.filesDir, HISTORY).delete() }
    }

    /** Moves legacy internal crash files into Squirtify/logs after the grant. */
    fun migrateFiles(ctx: Context) {
        val dir = logDir(ctx)
        if (dir == ctx.filesDir) return
        runCatching {
            listOf(LAST_CRASH, HISTORY).forEach { name ->
                val src = File(ctx.filesDir, name)
                val dst = File(dir, name)
                if (src.isFile && !dst.exists()) src.copyTo(dst)
            }
        }
    }
}
