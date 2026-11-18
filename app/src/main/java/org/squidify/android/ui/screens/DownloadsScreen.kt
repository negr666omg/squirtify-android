@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package org.squidify.android.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.squidify.android.App
import org.squidify.android.data.DownloadJob
import org.squidify.android.data.DownloadSource
import org.squidify.android.data.DownloadStatus
import org.squidify.android.data.FavSong
import org.squidify.android.data.HistoryEntry
import org.squidify.android.data.OfflineAlbumSnapshot
import org.squidify.android.data.Song
import org.squidify.android.player.PlayerSong
import org.squidify.android.ui.components.CoverArt
import org.squidify.android.ui.components.EmptyState
import org.squidify.android.ui.theme.SqIcons
import org.squidify.android.util.AppLog
import org.squidify.android.util.Fmt

/**
 * Stable identity for a downloaded album. Album titles alone are not unique:
 * different artists (or providers) can publish albums with the same name.
 */
internal data class DownloadAlbumKey(
    val albumName: String,
    val artist: String?,
    val source: DownloadSource,
    val identity: String? = null,
)

internal fun downloadAlbumKey(
    album: String?,
    artist: String?,
    source: DownloadSource,
    identity: String? = null,
): DownloadAlbumKey = DownloadAlbumKey(
    albumName = album ?: artist ?: "Unknown Album",
    artist = if (identity == null) artist else null,
    source = source,
    identity = identity,
)

internal fun downloadJobAlbumKey(job: DownloadJob, resolvedAlbumId: String? = job.albumId): DownloadAlbumKey {
    val identity = when (job.source) {
        DownloadSource.SQUIDIFY -> resolvedAlbumId
        DownloadSource.KHINSIDER -> job.requestUrl.substringBeforeLast('/')
    }
    return downloadAlbumKey(job.album, job.artist, job.source, identity)
}

/**
 * The Downloads tab is an OFFLINE LIBRARY, not a download history:
 * active transfers on top, then every finished album as a row IDENTICAL to
 * the album rows in search results (square cover, title, subtitle) — tapping
 * expands its tracks, which play straight from the local files.
 */
@Composable
fun DownloadsScreen() {
    val graph = App.graph()
    val jobs by graph.downloads.jobs.collectAsState()
    val historyEntries by graph.history.entries.collectAsState()
    val favSongs by graph.favorites.songs.collectAsState()
    val cachedAlbums by graph.offlineLibrary.albums.collectAsState()
    val settings by graph.settings.state().collectAsState()
    val hasInternet by graph.networkMonitor.hasValidatedInternet.collectAsState()
    val offlineMode = settings.offlineMode || !hasInternet

    val active = jobs.filter { it.status == DownloadStatus.RUNNING }
    val queued = jobs.filter { it.status == DownloadStatus.QUEUED }
    val paused = jobs.filter { it.status == DownloadStatus.PAUSED }
    val pending = queued + paused
    val failed = jobs.filter { it.status == DownloadStatus.FAILED }
    val done = jobs.filter { it.status == DownloadStatus.DONE && it.kind == "audio" }
    val storage = graph.downloads.storageUsedBytes()

    // Compose keys must be unique across the WHOLE LazyColumn. Job ids are
    // unique after the manager's healing, but this is the last line of
    // defence: repeated ids get an occurrence suffix instead of crashing.
    val activeKeys = uniqueKeys(active)
    val pendingKeys = uniqueKeys(pending)
    val failedKeys = uniqueKeys(failed)

    // Squidify tracks downloaded before v1.7.0 carry no cover URL in the
    // persisted job — recover the art id from the local listening history or
    // favourites (both store coverArtId/albumId per song id).
    fun metaFor(job: DownloadJob): Pair<String?, String?> {
        if (job.source != DownloadSource.SQUIDIFY) return null to null
        val id = job.songId ?: return null to job.albumId
        val h: HistoryEntry? = historyEntries.firstOrNull { it.songId == id }
        val f: FavSong? = favSongs.firstOrNull { it.id == id }
        val coverArtId = if (job.coverUrl != null) null else {
            h?.coverArtId ?: f?.coverArtId
                ?: h?.albumId?.let { "al-$it" } ?: f?.albumId?.let { "al-$it" }
        }
        val albumId = job.albumId ?: h?.albumId ?: f?.albumId
        return coverArtId to albumId
    }

    fun coverFor(job: DownloadJob): String? {
        job.coverUrl?.let { return it }
        val (coverArtId, _) = metaFor(job)
        return graph.subsonic.coverUrl(coverArtId)
    }

    // The library: downloaded albums enriched with a persisted full tracklist.
    val cachedByKey = cachedAlbums.associateBy {
        downloadAlbumKey(
            it.albumName,
            it.artist,
            it.source,
            if (it.source == DownloadSource.SQUIDIFY) it.albumId else it.albumUrl,
        )
    }

    data class AlbumGroup(
        val key: DownloadAlbumKey,
        val name: String,
        val coverUrl: String?,
        val artist: String?,
        val jobs: List<DownloadJob>,
        val totalBytes: Long,
        val snapshot: OfflineAlbumSnapshot?,
    )

    val groups = done
        .groupBy { job -> downloadJobAlbumKey(job, metaFor(job).second) }
        .map { (key, list) ->
            val snapshot = cachedByKey[key]
            AlbumGroup(
                key = key,
                name = key.albumName,
                coverUrl = snapshot?.coverUrl ?: list.firstNotNullOfOrNull { coverFor(it) },
                artist = snapshot?.artist ?: list.firstNotNullOfOrNull { it.artist },
                jobs = list.sortedWith(
                    compareBy({ trackNumberFromFileName(it.fileName) }, { it.title }),
                ),
                totalBytes = list.sumOf { it.totalBytes },
                snapshot = snapshot,
            )
        }
        .sortedByDescending { g -> g.jobs.maxOf { it.completedAt ?: it.createdAt } }

    var expandedAlbum by remember { mutableStateOf<DownloadAlbumKey?>(null) }

    fun playGroup(group: AlbumGroup, startIndex: Int = 0, shuffled: Boolean = false) {
        val songs = group.jobs.map { job ->
            when (job.source) {
                DownloadSource.SQUIDIFY -> {
                    val (coverArtId, albumId) = metaFor(job)
                    PlayerSong(
                        id = job.songId ?: job.id,
                        title = job.title,
                        artist = job.artist,
                        album = job.album,
                        durationMs = job.durationMs,
                        coverUrl = job.coverUrl ?: graph.subsonic.coverUrl(coverArtId),
                        coverArtId = coverArtId,
                        albumId = albumId,
                        source = DownloadSource.SQUIDIFY,
                    )
                }

                DownloadSource.KHINSIDER -> PlayerSong(
                    id = "khi:${job.requestUrl}",
                    title = job.title,
                    artist = job.artist,
                    album = job.album,
                    durationMs = job.durationMs,
                    coverUrl = job.coverUrl,
                    source = DownloadSource.KHINSIDER,
                    khiTrackUrl = job.requestUrl,
                    khiDirectUrl = job.fileUrl,
                )
            }
        }
        AppLog.i("Downloads", "Playing album '${group.name}' from local files (${songs.size} tracks)")
        if (shuffled) {
            App.graph().player.playShuffled(songs)
        } else {
            App.graph().player.play(songs, startIndex)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(
                    text = "Downloads",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "${Fmt.bytes(storage)} stored · ${graph.downloads.musicLocationLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (jobs.isEmpty()) {
            item {
                EmptyState(
                    "Nothing here yet.\nDownload songs from the library or whole albums from KHInsider —\nthey become an offline library you can play anywhere.",
                )
            }
        }

        // ---------------- current transfers
        if (active.isNotEmpty() || pending.isNotEmpty()) {
            item {
                SectionRow(
                    "Downloading (${active.size})" +
                        (if (queued.isNotEmpty()) " · Queued (${queued.size})" else "") +
                        (if (paused.isNotEmpty()) " · Paused (${paused.size})" else ""),
                )
            }
        }
        items(active.size, key = { activeKeys[it] }) { i -> DownloadRow(active[i]) }
        items(pending.size, key = { pendingKeys[it] }) { i ->
            DownloadRow(pending[i])
        }

        if (failed.isNotEmpty()) {
            item { SectionRow("Failed (${failed.size}) — tap ↻ to retry") }
        }
        items(failed.size, key = { failedKeys[it] }) { i -> DownloadRow(failed[i]) }

        // ---------------- offline library
        if (groups.isNotEmpty()) {
            item {
                SectionRow(
                    "Library · ${groups.size} albums · ${done.size} tracks",
                )
            }
            groups.forEachIndexed { gi, group ->
                val groupKeys = uniqueKeys(group.jobs)
                item(key = "group-$gi-${group.name}") {
                    DownloadsAlbumRow(
                        name = group.name,
                        artist = group.artist,
                        coverUrl = group.coverUrl,
                        trackCount = group.snapshot?.trackCount?.takeIf { it > 0 } ?: group.jobs.size,
                        totalBytes = group.totalBytes,
                        expanded = expandedAlbum == group.key,
                        onToggle = {
                            expandedAlbum = if (expandedAlbum == group.key) null else group.key
                        },
                        onPlay = { playGroup(group, 0) },
                    )
                }
                if (expandedAlbum == group.key) {
                    when {
                        group.snapshot?.source == DownloadSource.SQUIDIFY &&
                            group.snapshot.tracks.isNotEmpty() -> {
                            val snapshot = group.snapshot
                            items(
                                snapshot.tracks.size,
                                key = { i -> "cached-${group.key.hashCode()}-${snapshot.tracks[i].id}" },
                            ) { i ->
                                val track = snapshot.tracks[i]
                                val downloaded = group.jobs.firstOrNull { it.songId == track.id }
                                CachedLibraryTrackRow(
                                    title = track.title,
                                    number = track.track,
                                    subtitle = if (downloaded != null) {
                                        listOfNotNull(
                                            downloaded.qualityLabel.ifBlank { downloaded.format.uppercase() },
                                            Fmt.bytes(downloaded.totalBytes.takeIf { it > 0 } ?: downloaded.bytes),
                                            track.duration?.let { Fmt.duration(it.toLong()) },
                                        ).joinToString(" · ")
                                    } else {
                                        listOfNotNull(track.suffix?.uppercase(), track.duration?.let { Fmt.duration(it.toLong()) }, "Not downloaded").joinToString(" · ")
                                    },
                                    downloaded = downloaded != null,
                                    disabled = offlineMode && downloaded == null,
                                    onPlay = {
                                        val playerSongs = snapshot.tracks.map { song ->
                                            PlayerSong.from(song).copy(coverUrl = snapshot.coverUrl)
                                        }
                                        graph.player.play(playerSongs, i)
                                    },
                                )
                            }
                        }
                        group.snapshot?.source == DownloadSource.KHINSIDER &&
                            group.snapshot.khiTracks.isNotEmpty() -> {
                            val snapshot = group.snapshot
                            items(
                                snapshot.khiTracks.size,
                                key = { i -> "cached-khi-${group.key.hashCode()}-$i-${snapshot.khiTracks[i].url}" },
                            ) { i ->
                                val track = snapshot.khiTracks[i]
                                val downloaded = group.jobs.firstOrNull { it.requestUrl == track.url }
                                CachedLibraryTrackRow(
                                    title = track.title,
                                    number = track.number.takeIf { it > 0 },
                                    subtitle = listOfNotNull(
                                        track.duration,
                                        downloaded?.let { Fmt.bytes(it.totalBytes.takeIf { n -> n > 0 } ?: it.bytes) },
                                        if (downloaded == null) "Not downloaded" else null,
                                    ).joinToString(" · "),
                                    downloaded = downloaded != null,
                                    disabled = offlineMode && downloaded == null,
                                    onPlay = {
                                        val playerSongs = snapshot.khiTracks.map { item ->
                                            PlayerSong.fromKhi(
                                                snapshot.albumName,
                                                item.title,
                                                item.url,
                                                item.duration,
                                                snapshot.coverUrl,
                                            )
                                        }
                                        graph.player.play(playerSongs, i)
                                    },
                                )
                            }
                        }
                        else -> {
                            items(group.jobs.size, key = { groupKeys[it] }) { i ->
                                LibraryTrackRow(
                                    job = group.jobs[i],
                                    onPlay = { playGroup(group, i) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Track number parsed from a "07 - Title.flac"-style file name. */
private fun trackNumberFromFileName(fileName: String): Int =
    Regex("^\\s*(\\d+)").find(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: 9999

/**
 * Collision-proof LazyColumn keys: the id when unique, "<id>~n" for the n-th
 * repetition — duplicate keys are a hard crash in Compose.
 */
private fun uniqueKeys(jobs: List<DownloadJob>): List<String> {
    val seen = HashMap<String, Int>(jobs.size)
    return jobs.map { job ->
        val n = seen.getOrDefault(job.id, 0)
        seen[job.id] = n + 1
        if (n == 0) job.id else "${job.id}~$n"
    }
}

@Composable
private fun SectionRow(title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * An album of the offline library — laid out EXACTLY like the album rows in
 * search results (72dp square cover, 14dp gap, title + subtitle) so the two
 * screens feel like one app. Tap expands the track list; the play button
 * starts the whole album.
 */
@Composable
private fun DownloadsAlbumRow(
    name: String,
    artist: String?,
    coverUrl: String?,
    trackCount: Int,
    totalBytes: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
) {
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "chevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            url = coverUrl,
            modifier = Modifier
                .width(76.dp)
                .aspectRatio(1f),
            corner = 14.dp,
            contentDescription = name,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    artist,
                    "$trackCount tracks",
                    "${Fmt.bytes(totalBytes)} · offline",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            IconButton(onClick = onPlay) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play album",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .width(26.dp)
                    .rotate(chevron),
            )
        }
    }
}

/** One cached album track; unavailable tracks stay visible but disabled offline. */
@Composable
private fun CachedLibraryTrackRow(
    title: String,
    number: Int?,
    subtitle: String,
    downloaded: Boolean,
    disabled: Boolean,
    onPlay: () -> Unit,
) {
    val titleColor = if (disabled) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
    } else {
        MaterialTheme.colorScheme.onBackground
    }
    val mutedColor = if (disabled) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !disabled, onClick = onPlay)
            .padding(horizontal = 28.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = number?.takeIf { it > 0 }?.let { "%02d".format(it) } ?: "–",
            style = MaterialTheme.typography.bodySmall,
            color = mutedColor,
            modifier = Modifier.width(30.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = mutedColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (downloaded) {
            Icon(
                imageVector = SqIcons.Download,
                contentDescription = "Downloaded",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** One track inside an expanded library album — tap plays it (from that point). */
@Composable
private fun LibraryTrackRow(
    job: DownloadJob,
    onPlay: () -> Unit,
) {
    val queue by App.graph().player.queue.collectAsState()
    val idx by App.graph().player.currentIndex.collectAsState()
    val isBuffering by App.graph().player.isBuffering.collectAsState()
    val currentId = queue.getOrNull(idx)?.id
    val isCurrent = currentId == (job.songId ?: "khi:${job.requestUrl}")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(horizontal = 28.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = (trackNumberFromFileName(job.fileName))
                .takeIf { it != 9999 }?.let { "%02d".format(it) } ?: "–",
            style = MaterialTheme.typography.bodySmall,
            color = if (isCurrent) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(30.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = job.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    job.qualityLabel.ifBlank { job.format.uppercase() },
                    Fmt.bytes(job.totalBytes.takeIf { it > 0 } ?: job.bytes),
                    if (job.durationMs > 0) Fmt.durationMs(job.durationMs) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent && isBuffering) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else if (isCurrent) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Now playing",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(20.dp),
            )
        }
    }
}

@Composable
private fun DownloadRow(job: DownloadJob) {
    val graph = App.graph()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            url = job.coverUrl,
            modifier = Modifier
                .width(48.dp)
                .aspectRatio(1f),
            corner = 6.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = job.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    job.artist ?: job.album,
                    job.qualityLabel.ifBlank { job.format.uppercase() },
                    when (job.status) {
                        DownloadStatus.RUNNING ->
                            if (job.totalBytes > 0) {
                                "${(job.progress * 100).toInt()}% · ${Fmt.bytes(job.bytes)} / ${Fmt.bytes(job.totalBytes)}"
                            } else {
                                "${Fmt.bytes(job.bytes)} · downloading"
                            }
                        DownloadStatus.PAUSED -> "paused · ${Fmt.bytes(job.bytes)}"
                        DownloadStatus.DONE -> Fmt.bytes(job.bytes)
                        DownloadStatus.FAILED -> "failed: ${job.error ?: "error"}"
                        DownloadStatus.QUEUED -> "queued"
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (job.status == DownloadStatus.RUNNING) {
                Spacer(Modifier.height(4.dp))
                if (job.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { job.progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
        }
        when (job.status) {
            DownloadStatus.RUNNING, DownloadStatus.QUEUED -> IconButton(onClick = {
                graph.downloads.pause(job.id)
            }) {
                Icon(
                    imageVector = SqIcons.Pause,
                    contentDescription = "Pause",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DownloadStatus.PAUSED -> IconButton(onClick = {
                graph.downloads.resume(job.id)
            }) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Resume",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DownloadStatus.FAILED -> IconButton(onClick = {
                graph.downloads.retry(job.id)
            }) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Retry",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            DownloadStatus.DONE -> {}
        }
    }
}
