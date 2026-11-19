@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package org.squidify.android.ui.components

import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.squidify.android.App
import org.squidify.android.data.DownloadStatus
import org.squidify.android.data.KhiQuality
import org.squidify.android.data.LyricsProvider
import org.squidify.android.data.LyricsResult
import org.squidify.android.data.Song
import org.squidify.android.data.StreamQuality
import org.squidify.android.player.PlayerSong
import org.squidify.android.ui.theme.SqIcons
import org.squidify.android.util.Fmt
import org.squidify.android.util.AppLog
import kotlin.math.roundToLong

/** Full-screen now-playing experience with lyrics and queue tabs.
 *  [sheet] drives the open/close/drag motion — the whole overlay hangs off
 *  a single graphicsLayer that reads sheet.progress, so the glide never
 *  recomposes or re-lays-out this tree. */
@Composable
fun NowPlayingScreen(
    sheet: SheetController,
    onClose: () -> Unit,
    onToggleHeart: (PlayerSong) -> Unit,
    isHearted: Boolean,
    onDownload: (PlayerSong) -> Unit,
    downloadStatus: DownloadStatus?,
    lyricsProvider: suspend (PlayerSong, LyricsProvider) -> LyricsResult?,
    onOpenAlbum: (PlayerSong, Boolean) -> Unit,
    onOpenArtist: (PlayerSong) -> Unit,
) {
    val graph = App.graph()
    val player = graph.player
    val queue by player.queue.collectAsState()
    val idx by player.currentIndex.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()
    val stalled by player.stalled.collectAsState()
    val isBuffering by player.isBuffering.collectAsState()
    val playbackError by player.playbackError.collectAsState()
    val shuffle by player.shuffle.collectAsState()
    val repeatMode by player.repeatMode.collectAsState()

    var tab by remember { mutableStateOf(0) }
    var lyrics by remember { mutableStateOf<LyricsResult?>(null) }
    var lyricsLoading by remember { mutableStateOf(false) }
    var lyricsFor by remember { mutableStateOf<String?>(null) }
    var showQualitySheet by remember { mutableStateOf(false) }
    var showPlaybackRateSheet by remember { mutableStateOf(false) }
    var showCoverViewer by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settings by graph.settings.state().collectAsState()
    val hapticView = LocalView.current

    val current = queue.getOrNull(idx) ?: run {
        // never leave the overlay "transparent": with an empty queue it must
        // still swallow every tap so nothing behind reacts — and it still
        // rides the sheet motion so it never floats mid-air
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = (1f - sheet.progress) * size.height
                    alpha = (sheet.progress / 0.6f).coerceIn(0f, 1f)
                }
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) { detectTapGestures { } }
                .dragToDismiss(sheet),
        )
        return
    }

    LaunchedEffect(current.id, tab, settings.lyricsProvider) {
        val requestKey = "${current.id}/${settings.lyricsProvider}"
        if (tab == 1 && lyricsFor != requestKey) {
            lyricsFor = requestKey
            lyricsLoading = true
            lyrics = null
            try {
                val result = lyricsProvider(current, settings.lyricsProvider)
                if (lyricsFor == requestKey) lyrics = result
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // A track/provider/tab change cancels the old request. Clear
                // only its marker so returning to the same track can retry.
                if (lyricsFor == requestKey) lyricsFor = null
                throw cancelled
            } catch (_: Exception) {
                if (lyricsFor == requestKey) lyrics = null
            } finally {
                if (lyricsFor == requestKey) lyricsLoading = false
            }
        }
    }

    // ---- swipe-down dismissal: the sheet follows the finger like a piece of
    // paper lying on top; release beyond a threshold (or fast enough) closes
    // it. The drag writes SheetController.progress SYNCHRONOUSLY (no
    // coroutine hop per delta) and the root graphicsLayer below reads it —
    // one layer update per frame, nothing else.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // open/close glide + finger drag share this single transform:
                // 1 = fully in place, 0 = fully off-screen
                translationY = (1f - sheet.progress) * size.height
                // fade only near the very ends of the motion: fully opaque
                // through most of the drop, quick dissolve at the last stretch
                alpha = (sheet.progress / 0.6f).coerceIn(0f, 1f)
            }
            .background(MaterialTheme.colorScheme.background)
            // the Now-Playing overlay sits above the app content: consume
            // taps on empty areas so they never hit the screens behind it
            .pointerInput(Unit) {
                detectTapGestures { }
            }
            .dragToDismiss(sheet),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            // ---------- top bar with inline tabs (saves a whole row)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                // Keep the tab group geometrically centered in the screen.
                // The close button is overlaid at the start and no longer
                // shifts Lyrics / Up Next to the right.
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TabPill("Playing", tab == 0) { tab = 0 }
                    Spacer(Modifier.width(6.dp))
                    TabPill("Lyrics", tab == 1) { tab = 1 }
                    Spacer(Modifier.width(6.dp))
                    TabPill("Up Next", tab == 2) { tab = 2 }
                }
                IconButton(
                    modifier = Modifier.align(Alignment.CenterStart),
                    onClick = {
                        AppLog.d("UI", "Now Playing closed via chevron")
                        onClose()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }

            playbackError?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = {
                            player.retryCurrent()
                        }) { Text("Retry") }
                        IconButton(onClick = { player.dismissPlaybackError() }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Dismiss playback error",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            when (tab) {
                0 -> PlayingTab(
                    current = current,
                    isPlaying = isPlaying,
                    // the cover only "breathes" when playback audibly stopped;
                    // background loads and track preloads never blink it
                    buffering = isBuffering || stalled,
                    shuffle = shuffle,
                    repeatMode = repeatMode,
                    playbackTempo = settings.playbackTempo,
                    isHearted = isHearted,
                    downloadStatus = downloadStatus,
                    scrollingText = settings.scrollingText,
                    hideArtist = settings.hideNowPlayingArtist,
                    onTogglePlay = {
                        if (settings.hapticsEnabled) hapticView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        player.togglePlayPause()
                    },
                    onPrevious = { player.previous() },
                    onNext = { player.next() },
                    onSeek = { player.seekTo(it) },
                    onToggleShuffle = { player.toggleShuffle() },
                    onCycleRepeat = { player.cycleRepeat() },
                    onPlaybackRateTap = { showPlaybackRateSheet = true },
                    onToggleHeart = { onToggleHeart(current) },
                    onDownload = { onDownload(current) },
                    onQualityTap = { showQualitySheet = true },
                    onCoverTap = { showCoverViewer = true },
                    onTitleTap = { onOpenAlbum(current, true) },
                    onAlbumTap = { onOpenAlbum(current, false) },
                    onArtistTap = { onOpenArtist(current) },
                )

                1 -> Column(modifier = Modifier.weight(1f)) {
                    LyricsProviderSelector(
                        selected = settings.lyricsProvider,
                        onSelect = { p ->
                            scope.launch { graph.settings.setLyricsProvider(p) }
                        },
                    )
                    // LyricsView collects the position flow itself: the 10 Hz
                    // ticker updates only its own small subtree, never this
                    // whole screen
                    LyricsView(
                        lyrics = lyrics,
                        isLoading = lyricsLoading,
                        modifier = Modifier.weight(1f),
                        onSeekTo = { secs -> player.seekTo((secs * 1000).roundToLong()) },
                    )
                }

                2 -> QueueTab(modifier = Modifier.weight(1f))
            }
        }
    }

    if (showQualitySheet) {
        QualityInfoSheet(
            song = current,
            downloadStatus = downloadStatus,
            onDismiss = { showQualitySheet = false },
        )
    }

    if (showPlaybackRateSheet) {
        PlaybackRateSheet(onDismiss = { showPlaybackRateSheet = false })
    }

    if (showCoverViewer) {
        CoverViewer(
            url = current.coverArtUrl(0),
            title = current.album ?: current.title,
            onDismiss = { showCoverViewer = false },
        )
    }
}

private fun Modifier.dragToDismiss(sheet: SheetController): Modifier = pointerInput(sheet) {
    val velocityTracker = VelocityTracker()
    detectVerticalDragGestures(
        onDragStart = { offset ->
            velocityTracker.resetTracking()
            velocityTracker.addPosition(android.os.SystemClock.uptimeMillis(), offset)
        },
        onVerticalDrag = { change, dragAmount ->
            velocityTracker.addPosition(change.uptimeMillis, change.position)
            change.consume()
            sheet.dragDelta(dragAmount, size.height.toFloat())
        },
        onDragEnd = {
            sheet.dragEnd(velocityTracker.calculateVelocity().y, size.height.toFloat())
        },
        onDragCancel = { sheet.dragEnd() },
    )
}

/** Compact selector for the default lyrics provider, shown on the Lyrics tab. */
@Composable
private fun LyricsProviderSelector(
    selected: LyricsProvider,
    onSelect: (LyricsProvider) -> Unit,
) {
    val labels = listOf(
        "Auto" to LyricsProvider.AUTO,
        "Server" to LyricsProvider.SERVER,
        "lrc.red" to LyricsProvider.LRC_RED,
        "LRCLIB" to LyricsProvider.LRCLIB,
        "NetEase" to LyricsProvider.NETEASE,
        "QQ" to LyricsProvider.QQ,
        "lyrics.ovh" to LyricsProvider.OVH,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Source:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            items(labels.size) { i ->
                val (label, provider) = labels[i]
                val isSel = provider == selected
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isSel) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.bouncyClickable(pressedScale = 0.93f) { onSelect(provider) },
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSel) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        // M3 press physics: the pill shrinks under the finger and springs back
        modifier = Modifier.bouncyClickable(pressedScale = 0.93f) {
            AppLog.d("UI", "Now Playing tab → $label")
            onClick()
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

/**
 * Playing tab. The cover sits in a weight(1f) slot, so on narrow/tall (9:16)
 * screens the artwork shrinks and the transport controls are always on-screen
 * without scrolling.
 */
@Composable
private fun PlayingTab(
    current: PlayerSong,
    isPlaying: Boolean,
    buffering: Boolean,
    shuffle: Boolean,
    repeatMode: Int,
    playbackTempo: Float,
    isHearted: Boolean,
    downloadStatus: DownloadStatus?,
    scrollingText: Boolean,
    hideArtist: Boolean,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onPlaybackRateTap: () -> Unit,
    onToggleHeart: () -> Unit,
    onDownload: () -> Unit,
    onQualityTap: () -> Unit,
    onCoverTap: () -> Unit,
    onTitleTap: () -> Unit,
    onAlbumTap: () -> Unit,
    onArtistTap: () -> Unit,
) {
    val graph = App.graph()
    val settings = graph.settings.state().value

    // interaction sources for M3 press physics on the transport controls
    val playIsr = remember { MutableInteractionSource() }
    val prevIsr = remember { MutableInteractionSource() }
    val nextIsr = remember { MutableInteractionSource() }
    val playCorner by animateDpAsState(
        targetValue = if (isPlaying) 20.dp else 36.dp,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "playButtonShape",
    )

    // Soft breathing dim while the track is still loading — the cover gently
    // darkens and brightens so buffering is obvious without a harsh overlay.
    // The pulse value is read inside the graphicsLayer block (draw phase):
    // a composition-phase read recomposed this whole tab at 60 fps and
    // dragged the sheet animation down with it.
    val loading = buffering
    val pulseAlpha = if (loading) {
        val transition = rememberInfiniteTransition(label = "bufferPulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.55f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "coverAlpha",
        )
    } else {
        null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ---------- artwork: absorbs whatever height is left
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints {
                val side = minOf(maxWidth, maxHeight).coerceAtLeast(120.dp)
                Box(
                    modifier = Modifier
                        .size(side)
                        .clip(RoundedCornerShape(26.dp))
                        .clickable(onClick = onCoverTap),
                ) {
                    CoverArt(
                        // 1000px fills the big square; the original
                        // (size=0) pulled multi-MB art every track change
                        url = current.coverArtUrl(1000),
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = pulseAlpha?.value ?: 1f },
                        corner = 26.dp,
                        contentDescription = current.album,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = current.title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .marqueeIf(scrollingText)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onTitleTap),
        )
        if (hideArtist) {
            current.album?.let { album ->
                Text(
                    text = album,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .marqueeIf(scrollingText)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onAlbumTap),
                )
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = current.artist ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .marqueeIf(scrollingText)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onArtistTap),
                )
                current.album?.let { album ->
                    Text(
                        text = " — ",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        text = album,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .marqueeIf(scrollingText)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onAlbumTap),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val qualityLabel = when (current.source) {
                org.squidify.android.data.DownloadSource.SQUIDIFY ->
                    if (downloadStatus == DownloadStatus.DONE) "Squidify · Downloaded"
                    else "Squidify · ${settings.streamingQuality.shortLabel}"
                org.squidify.android.data.DownloadSource.KHINSIDER -> "KHInsider · ${settings.khiQuality.label}"
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .bouncyClickable(pressedScale = 0.94f) { onQualityTap() },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = qualityLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = " ⓘ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable(onClick = onPlaybackRateTap),
            ) {
                Text(
                    text = "${playbackTempo.formatRate()}×",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleHeart) {
                Icon(
                    imageVector = if (isHearted) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isHearted) "Unfavourite" else "Favourite",
                    tint = if (isHearted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDownload) {
                when (downloadStatus) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED ->
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    DownloadStatus.DONE -> Icon(
                        SqIcons.Download,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    else -> Icon(
                        SqIcons.Download,
                        contentDescription = "Download",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---------- seek bar (own composable: the 10 Hz position ticker
        // recomposes ONLY this small subtree, never the whole tab)
        SeekSection(onSeek = onSeek)

        Spacer(Modifier.height(8.dp))

        // ---------- transport (M3 press physics on every control)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleShuffle, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = SqIcons.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (shuffle) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onPrevious,
                interactionSource = prevIsr,
                modifier = Modifier.size(64.dp).pressScale(prevIsr),
            ) {
                Icon(
                    imageVector = SqIcons.SkipPrevious,
                    contentDescription = "Previous",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .pressScale(playIsr, pressedScale = 0.90f)
                    .clip(RoundedCornerShape(playCorner))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(interactionSource = playIsr, indication = androidx.compose.material3.ripple(), onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                if (buffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(46.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                if (isPlaying) {
                    Icon(
                        imageVector = SqIcons.Pause,
                        contentDescription = if (buffering) "Buffering — pause" else "Pause",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(34.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = if (buffering) "Buffering" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(38.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onNext,
                interactionSource = nextIsr,
                modifier = Modifier.size(64.dp).pressScale(nextIsr),
            ) {
                Icon(
                    imageVector = SqIcons.SkipNext,
                    contentDescription = "Next",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = onCycleRepeat, modifier = Modifier.weight(1f)) {
                // Player.REPEAT_MODE_ONE = 1, Player.REPEAT_MODE_ALL = 2
                val icon = when (repeatMode) {
                    1 -> SqIcons.RepeatOne
                    2 -> SqIcons.Disc
                    else -> SqIcons.Repeat
                }
                Icon(
                    imageVector = icon,
                    contentDescription = when (repeatMode) {
                        1 -> "Repeat track"
                        2 -> "Repeat album"
                        3 -> "Repeat queue"
                        else -> "Repeat off"
                    },
                    tint = if (repeatMode != 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Seek bar + elapsed / remaining time. Collects the position and duration
 * flows ITSELF so the ~10 Hz ticker recomposes only this small subtree —
 * previously the numbers were collected at the top of the whole Now-Playing
 * screen and every tick recomposed (and re-laid-out) the entire sheet,
 * which is exactly why the open/close animation felt like ~20 fps.
 */
@Composable
private fun SeekSection(onSeek: (Long) -> Unit) {
    val player = App.graph().player
    val positionMs by player.positionMs.collectAsState()
    val durationMs by player.durationMs.collectAsState()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    val sliderValue = if (dragging) dragValue
    else if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

    Slider(
        value = sliderValue.coerceIn(0f, 1f),
        onValueChange = {
            dragging = true
            dragValue = it
        },
        onValueChangeFinished = {
            dragging = false
            if (durationMs > 0) {
                val target = (dragValue * durationMs).roundToLong()
                AppLog.d("UI", "User seeked to ${target}ms of ${durationMs}ms via slider")
                onSeek(target)
            }
        },
        valueRange = 0f..1f,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = Fmt.durationMs(if (dragging) (dragValue * durationMs).roundToLong() else positionMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "-" + Fmt.durationMs(
                (durationMs - (if (dragging) (dragValue * durationMs).roundToLong() else positionMs)).coerceAtLeast(0),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- quality sheet

/** Data probed from a KHInsider direct URL: size + real format + decoded name. */
private data class KhiFileInfo(
    val url: String?,
    val sizeBytes: Long?,
    val format: String?,
    val acceptsRanges: Boolean?,
    val durationMs: Long = 0L,
)

/**
 * Detailed quality info for the current track: the source (Squidify or
 * KHInsider), the server-side file (format, bitrate, sample rate, size), a
 * clickable direct link that opens in the browser, and an inline picker to
 * change the global streaming quality.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityInfoSheet(
    song: PlayerSong,
    downloadStatus: DownloadStatus?,
    onDismiss: () -> Unit,
) {
    val graph = App.graph()
    val scope = rememberCoroutineScope()
    val settings by graph.settings.state().collectAsState()
    val context = LocalContext.current
    var info by remember { mutableStateOf<Song?>(null) }
    var infoError by remember { mutableStateOf<String?>(null) }
    var khiInfo by remember { mutableStateOf<KhiFileInfo?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    val isSquidify = song.source == org.squidify.android.data.DownloadSource.SQUIDIFY

    // Direct file URL the player is actually hitting right now
    val directUrl: String? = if (isSquidify) {
        if (downloadStatus == DownloadStatus.DONE) null
        else graph.subsonic.streamUrl(song.id, settings.streamingQuality)
    } else {
        khiInfo?.url ?: song.khiDirectUrl
    }

    LaunchedEffect(song.id) {
        if (isSquidify) {
            info = null
            infoError = null
            try {
                info = graph.subsonic.getSong(song.id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                infoError = e.message ?: e.javaClass.simpleName
            }
        } else {
            khiInfo = null
            try {
                val trackUrl = song.khiTrackUrl ?: song.khiDirectUrl
                if (trackUrl != null) {
                    val res = graph.khi.resolve(trackUrl)
                    val format = when (settings.khiQuality) {
                        KhiQuality.FLAC -> "flac"
                        KhiQuality.MP3 -> "mp3"
                        KhiQuality.BEST -> if (res.flac != null) "flac" else "mp3"
                    }
                    val url = res.urlFor(format)
                    khiInfo = KhiFileInfo(
                        url = url,
                        sizeBytes = res.sizeBytes,
                        format = url?.substringAfterLast('.', "")?.lowercase(),
                        acceptsRanges = res.acceptsRanges,
                        durationMs = song.durationMs,
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
    }

    fun openInBrowser(url: String?) {
        if (url == null) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(song.artist, song.album).joinToString(" — "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))

            // ---------------- source, always first
            QualitySection(title = "Source") {
                InfoRow("Provider", if (isSquidify) "Squidify" else "KHInsider")
                if (downloadStatus == DownloadStatus.DONE && isSquidify) {
                    InfoRow("Mode", "Local download — no network used")
                } else if (downloadStatus == DownloadStatus.DONE) {
                    InfoRow("Mode", "Local download — no network used")
                }
                // Clickable direct link to the actual audio file
                val link = directUrl
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = link != null) { openInBrowser(link) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (link != null) "Open file in browser ↗" else "No direct link (local file)",
                        style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                        color = if (link != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (isSquidify) {
                QualitySection(title = "Source file on server") {
                    when {
                        info == null && infoError == null -> Text(
                            "Loading file details…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        infoError != null -> Text(
                            "Couldn't read file details: $infoError",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> {
                            val s = info!!
                            InfoRow("Format", s.formatDescription.ifBlank { "unknown" })
                            s.size?.let { InfoRow("File size", Fmt.bytes(it)) }
                            s.duration?.let { InfoRow("Duration", Fmt.duration(it.toLong())) }
                            if (!s.path.isNullOrBlank()) InfoRow("Path", s.path!!)
                        }
                    }
                }

                if (downloadStatus != DownloadStatus.DONE) {
                    QualitySection(title = "Playing now") {
                        val q = settings.streamingQuality
                        if (q == StreamQuality.ORIGINAL) {
                            val src = info?.formatDescription
                            InfoRow(
                                "Mode",
                                "Original — streamed byte-for-byte, no transcoding" +
                                    (src?.let { " ($it)" } ?: ""),
                            )
                            InfoRow("Data usage", "≈ ${Fmt.bytes(((info?.bitRate ?: 1000) * 1000L / 8) * 60)} / min")
                        } else {
                            val capped = (info?.bitRate ?: 0) in 1 until (q.maxBitRate ?: 0)
                            InfoRow(
                                "Mode",
                                "Transcoded by the server: ${q.format?.uppercase()} ${q.maxBitRate} kbps" +
                                    if (capped) " — source is only ${info?.bitRate} kbps, server caps at source quality" else "",
                            )
                            InfoRow("Data usage", "≈ ${Fmt.bytes(((q.maxBitRate ?: 0) * 1000L / 8) * 60)} / min")
                        }
                        InfoRow("Cached", "yes")
                    }
                }
            } else {
                QualitySection(title = "KHInsider file") {
                    khiInfo?.let { k ->
                        InfoRow("Mode", "Direct file from downloads.khinsider.com — no transcoding")
                        InfoRow("Preferred format", settings.khiQuality.label)
                        k.format?.let { InfoRow("Resolved format", it.uppercase()) }
                        song.album?.let { InfoRow("Album", it) }
                        if (k.durationMs > 0) InfoRow("Duration", Fmt.durationMs(k.durationMs))
                        k.sizeBytes?.let { bytes ->
                            InfoRow("File size", Fmt.bytes(bytes))
                            if (k.durationMs > 0) {
                                val kbps = (bytes * 8 / (k.durationMs / 1000L)).toInt()
                                InfoRow("Bitrate", "≈ $kbps kbps (from file size)")
                            }
                        }
                        k.acceptsRanges?.let {
                            InfoRow("Seekable", if (it) "yes — server supports byte ranges" else "no — restarts on seek")
                        }
                        // file name with URL-escaped characters (%20 etc.) decoded
                        k.url?.let {
                            InfoRow(
                                "File name",
                                Uri.decode(it.substringAfterLast('/')).take(90),
                            )
                        }
                    } ?: run {
                        InfoRow("Mode", "Direct file from downloads.khinsider.com")
                        InfoRow("Preferred format", settings.khiQuality.label)
                        Text(
                            "Probing file details…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))

            if (!showPicker) {
                TextButton(onClick = {
                    AppLog.i("Quality", "Quality picker opened from Now Playing")
                    showPicker = true
                }) {
                    Text("Change quality")
                }
            } else {
                Text(
                    text = if (isSquidify) "Streaming quality (global)" else "KHInsider quality (global)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                if (isSquidify) {
                    StreamQuality.entries.forEach { q ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch {
                                        graph.settings.setStreamingQuality(q)
                                        graph.player.applyQualityChange(q)
                                    }
                                    onDismiss()
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = q == settings.streamingQuality, onClick = null)
                            Text(
                                text = q.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                } else {
                    KhiQuality.entries.forEach { q ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch {
                                        graph.settings.setKhiQuality(q)
                                        graph.player.applyKhiQualityChange()
                                    }
                                    onDismiss()
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = q == settings.khiQuality, onClick = null)
                            Text(
                                text = q.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QualitySection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        content()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun InfoRow(key: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(104.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QueueTab(modifier: Modifier = Modifier) {
    val player = App.graph().player
    val queue by player.queue.collectAsState()
    val currentIndex by player.currentIndex.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()
    val shuffle by player.shuffle.collectAsState()
    val repeatMode by player.repeatMode.collectAsState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${queue.size} tracks",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { player.toggleShuffle() }) {
                Icon(
                    SqIcons.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (shuffle) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { player.cycleRepeat() }) {
                Icon(
                    // Player.REPEAT_MODE_ONE = 1, Player.REPEAT_MODE_ALL = 2
                    imageVector = when (repeatMode) {
                        1 -> SqIcons.RepeatOne
                        2 -> SqIcons.Disc
                        else -> SqIcons.Repeat
                    },
                    contentDescription = when (repeatMode) {
                        1 -> "Repeat track"
                        2 -> "Repeat album"
                        3 -> "Repeat queue"
                        else -> "Repeat off"
                    },
                    tint = if (repeatMode != 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            items(queue.size, key = { i ->
                val song = queue[i]
                "${song.source.name}:${song.id}:${queue.take(i).count { it.id == song.id }}"
            }) { i ->
                val song = queue[i]
                val currentItemIndex by rememberUpdatedState(i)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { player.jumpTo(i) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (i == currentIndex) {
                        Text(
                            text = "♪",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(32.dp),
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Text(
                            text = "${i + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(32.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (i == currentIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = song.artist ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = Fmt.durationMs(song.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                    Text(
                        text = "⠿",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (draggingIndex == i) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .pointerInput(player) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingIndex = currentItemIndex
                                        dragOffset = 0f
                                    },
                                    onDragEnd = {
                                        draggingIndex = null
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggingIndex = null
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        val from = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                        val visible = listState.layoutInfo.visibleItemsInfo
                                        val dragged = visible.firstOrNull { it.index == from } ?: return@detectDragGesturesAfterLongPress
                                        val center = dragged.offset + dragged.size / 2f + dragOffset + amount.y
                                        val target = visible.firstOrNull {
                                            center >= it.offset && center < it.offset + it.size
                                        }
                                        if (target != null && target.index != from) {
                                            player.move(from, target.index)
                                            draggingIndex = target.index
                                            dragOffset = 0f
                                        } else {
                                            dragOffset += amount.y
                                        }
                                    },
                                )
                            },
                    )
                    IconButton(onClick = { player.removeAt(i) }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}


// ---------------------------------------------------------------- playback rate shift

private fun Float.formatRate(): String = "%.2f".format(java.util.Locale.US, this)

/** Gramophone-inspired rate controls; tap a value to enter an exact decimal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackRateSheet(onDismiss: () -> Unit) {
    val graph = App.graph()
    val settings by graph.settings.state().collectAsState()
    var tempo by remember { mutableStateOf(settings.playbackTempo) }
    var pitch by remember { mutableStateOf(settings.playbackPitch) }
    var linked by remember { mutableStateOf(settings.playbackRateLinked) }
    var editTarget by remember { mutableStateOf<String?>(null) }
    var exactInput by remember { mutableStateOf("") }
    var exactInputError by remember { mutableStateOf(false) }
    val exactInputFocusRequester = remember { FocusRequester() }

    LaunchedEffect(editTarget) {
        if (editTarget != null) exactInputFocusRequester.requestFocus()
    }
    val exactFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(editTarget) {
        if (editTarget != null) {
            exactFocusRequester.requestFocus()
            keyboard?.show()
        }
    }

    fun apply(nextTempo: Float, nextPitch: Float, nextLinked: Boolean) {
        tempo = nextTempo
        pitch = if (nextLinked) nextTempo else nextPitch
        linked = nextLinked
        graph.player.setPlaybackRate(tempo, pitch, linked)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Playback rate shift", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Adjust tempo and pitch independently or keep them linked.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = linked,
                    onCheckedChange = { apply(tempo, pitch, it) },
                )
                Column(Modifier.weight(1f)) {
                    Text("Link pitch and tempo", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "When linked, both values change together.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                if (linked) "Tempo & pitch" else "Tempo",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            Slider(
                value = tempo.coerceIn(0.25f, 4f),
                onValueChange = { apply(it, if (linked) it else pitch, linked) },
                valueRange = 0.25f..4f,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("0.25×", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        editTarget = "tempo"
                        exactInput = tempo.formatRate()
                        exactInputError = false
                    },
                ) {
                    Text("${tempo.formatRate()}×", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.weight(1f))
                Text("4.00×", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            if (!linked) {
                Text("Pitch", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                Slider(
                    value = pitch.coerceIn(0.25f, 4f),
                    onValueChange = { apply(tempo, it, false) },
                    valueRange = 0.25f..4f,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("0.25×", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            editTarget = "pitch"
                            exactInput = pitch.formatRate()
                            exactInputError = false
                        },
                    ) {
                        Text("${pitch.formatRate()}×", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("4.00×", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(
                onClick = { apply(1f, 1f, linked) },
                modifier = Modifier.align(Alignment.Start),
            ) { Text("Reset to normal") }
        }
    }

    val target = editTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = {
                Text(
                    when (target) {
                        "tempo" -> if (linked) "Set tempo & pitch" else "Set tempo"
                        else -> "Set pitch"
                    },
                )
            },
            text = {
                OutlinedTextField(
                    value = exactInput,
                    onValueChange = {
                        exactInput = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }
                        exactInputError = false
                    },
                    label = { Text("Value") },
                    suffix = { Text("×") },
                    singleLine = true,
                    isError = exactInputError,
                    supportingText = {
                        Text(
                            if (exactInputError) "Enter a value from 0.25 to 4.00."
                            else "Allowed range: 0.25×–4.00×",
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.focusRequester(exactInputFocusRequester),
                    modifier = Modifier.focusRequester(exactFocusRequester),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = exactInput.replace(',', '.').toFloatOrNull()
                        if (value != null && value.isFinite() && value in 0.25f..4f) {
                            if (target == "tempo") {
                                apply(value, if (linked) value else pitch, linked)
                            } else {
                                apply(tempo, value, false)
                            }
                            editTarget = null
                        } else {
                            exactInputError = true
                        }
                    },
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text("Cancel") }
            },
        )
    }
}
