package org.squidify.android.downloads

/** Parsed HTTP Content-Range header (for example: bytes 100-199/1000). */
internal data class HttpContentRange(
    val start: Long,
    val endInclusive: Long,
    val total: Long?,
)

internal fun parseHttpContentRange(header: String?): HttpContentRange? {
    if (header.isNullOrBlank()) return null
    val match = Regex("""^bytes (\d+)-(\d+)/(\d+|\*)$""").matchEntire(header.trim())
        ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
    if (end < start || (total != null && (total <= 0L || end >= total))) return null
    return HttpContentRange(start, end, total)
}
