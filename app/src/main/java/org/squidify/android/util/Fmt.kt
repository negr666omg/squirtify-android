package org.squidify.android.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

object Fmt {
    fun duration(totalSeconds: Long?): String {
        if (totalSeconds == null || totalSeconds <= 0) return "0:00"
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun durationMs(ms: Long): String = duration(ms / 1000)

    fun bytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        val digit = (kotlin.math.ln(bytes.toDouble()) / kotlin.math.ln(1024.0)).toInt()
        val idx = digit.coerceIn(0, units.size - 1)
        val value = bytes / 1024.0.pow(idx.toDouble())
        return if (value >= 100 || idx == 0) "%.0f %s".format(Locale.US, value, units[idx])
        else "%.1f %s".format(Locale.US, value, units[idx])
    }

    /** Compact total-duration like "1 hr 23 min" / "45 min". */
    fun totalDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        return when {
            h > 0 && m > 0 -> "$h hr $m min"
            h > 0 -> "$h hr"
            m > 0 -> "$m min"
            else -> "${totalSeconds}s"
        }
    }

    fun qualityBadge(suffix: String?, bitRate: Int?): String {
        val fmt = suffix?.uppercase() ?: "?"
        return if (bitRate != null && bitRate > 0) "$fmt · $bitRate kbps" else fmt
    }

    fun relativeDate(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            val instant = java.time.Instant.parse(iso)
            val now = java.time.Instant.now()
            val days = java.time.Duration.between(instant, now).toDays()
            when {
                days < 0 -> iso.take(10)
                days == 0L -> "today"
                days == 1L -> "yesterday"
                days < 7 -> "$days days ago"
                days < 30 -> "${days / 7} wk ago"
                days < 365 -> "${days / 30} mo ago"
                else -> "${days / 365} yr ago"
            }
        } catch (_: Exception) {
            iso.take(10)
        }
    }

    fun year(iso: String?): Int? = iso?.take(4)?.toIntOrNull()

    fun songCount(n: Int): String = if (n == 1) "1 song" else "$n songs"

    fun initials(name: String?): String {
        if (name.isNullOrBlank()) return "?"
        return name.split(Regex("\\s+")).take(2)
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .joinToString("")
    }
}

fun <T> MutableList<T>.move(from: Int, to: Int) {
    if (from == to || from !in indices || to !in indices) return
    val item = removeAt(from)
    add(to, item)
}
