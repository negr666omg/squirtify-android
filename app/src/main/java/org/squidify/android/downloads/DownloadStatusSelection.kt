package org.squidify.android.downloads

import org.squidify.android.data.DownloadJob
import org.squidify.android.data.DownloadSource
import org.squidify.android.data.DownloadStatus
import org.squidify.android.player.PlayerSong

/**
 * Finds the download state belonging to the exact playing track.
 *
 * A track can have several downloads in different qualities. Prefer an active
 * transfer, then a queued transfer, then an already available offline copy.
 * This prevents an old FAILED job from hiding a newer successful download.
 */
internal fun downloadStatusFor(
    song: PlayerSong,
    jobs: List<DownloadJob>,
): DownloadStatus? {
    val matching = jobs.filter { job ->
        job.kind == "audio" && when (song.source) {
            DownloadSource.SQUIDIFY ->
                job.source == DownloadSource.SQUIDIFY && job.songId == song.id
            DownloadSource.KHINSIDER ->
                job.source == DownloadSource.KHINSIDER &&
                    song.khiTrackUrl != null &&
                    job.requestUrl == song.khiTrackUrl
        }
    }

    return matching.maxByOrNull { job ->
        when (job.status) {
            DownloadStatus.RUNNING -> 5
            DownloadStatus.QUEUED -> 4
            DownloadStatus.DONE -> 3
            DownloadStatus.PAUSED -> 2
            DownloadStatus.FAILED -> 1
        }
    }?.status
}
