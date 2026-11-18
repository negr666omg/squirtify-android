package org.squidify.android.data

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadSource { SQUIDIFY, KHINSIDER }

@Serializable
enum class DownloadStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED }

@Serializable
data class DownloadJob(
    val id: String,
    val source: DownloadSource,
    val songId: String? = null,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    /** Stable album identity for grouping tracks with different performers. */
    val albumId: String? = null,
    val coverUrl: String? = null,
    /** Squidify: full /rest/stream URL (already has quality baked in).
     *  KHInsider: track page URL, resolved at download time. */
    val requestUrl: String,
    val fileUrl: String? = null,
    val format: String,
    val qualityLabel: String = "",
    val fileName: String,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    /** Final playable location: content:// (MediaStore, v1.1+) or file:// (legacy). */
    val contentUri: String? = null,
    /** Track duration in ms, written into MediaStore metadata. */
    val durationMs: Long = 0L,
    /** "audio" (default) or "image" for album cover files. */
    val kind: String = "audio",
) {
    val progress: Float
        get() = if (totalBytes > 0) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}
