package org.squidify.android.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.squidify.android.data.KhiQuality
import org.squidify.android.data.ListenHistory
import org.squidify.android.data.SettingsRepository
import org.squidify.android.data.StreamQuality
import org.squidify.android.data.SubsonicClient
import org.squidify.android.downloads.DownloadManager
import org.squidify.android.util.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/** Owns two ExoPlayer instances on behalf of [PlaybackService]:
 * the active one plays the queue, the standby one is only used to create a
 * TRUE crossfade (the next track starts playing while the current one fades
 * out — real overlap, not consecutive fades). After each crossfade the two
 * players swap roles; the MediaSession is re-bound to the new active player.
 *
 * The shuffle order is managed here (the player's own shuffle mode stays
 * off), which keeps the queue, the notification and crossfade consistent.
 *
 * Transcoded Squidify streams (mp3/opus) arrive without HTTP Range support,
 * so seeks are replayed through the server's timeOffset parameter; the
 * absolute playback position is then [timeOffsetBaseMs] + player position.
 */
@UnstableApi
class PlayerManager(
    private val context: Context,
    private val subsonic: SubsonicClient,
    private val downloads: DownloadManager,
    private val settings: SettingsRepository,
    private val khi: org.squidify.android.data.KhiClient,
    private val history: ListenHistory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queuePrefs = context.getSharedPreferences("playback_queue", Context.MODE_PRIVATE)

    private var activeP: Player? = null
    private var standbyP: Player? = null

    /** Re-binds the MediaSession when the active player changes. */
    private var sessionRebinder: ((Player) -> Unit)? = null
    private var sessionBoundTo: Player? = null

    /** A play() that arrived before the playback service finished attaching
     *  its players (fast tap right after a cold start) — replayed on attach
     *  instead of being silently dropped. */
    private var pendingPlayRequest: Array<Any?>? = null

    // ------------------------------------------------------------ state

    private val _queue = MutableStateFlow<List<PlayerSong>>(emptyList())
    val queue: StateFlow<List<PlayerSong>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    /** Playback is stalled: the track audibly stopped because data ran out. */
    private val _stalled = MutableStateFlow(false)
    val stalled: StateFlow<Boolean> = _stalled.asStateFlow()

    /** Sanitized user-facing playback failure; detailed diagnostics stay in AppLog. */
    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    fun dismissPlaybackError() {
        _playbackError.value = null
    }

    private data class FailedPlayRequest(
        val songs: List<PlayerSong>,
        val index: Int,
        val shuffled: Boolean,
    )

    private var failedPlayRequest: FailedPlayRequest? = null

    /** Retry the failed selection, refreshing temporary KHInsider URLs. */
    fun retryCurrent() {
        val failed = failedPlayRequest
        if (failed != null) {
            play(failed.songs, failed.index, shuffled = failed.shuffled, forceRefreshKhiUrls = true)
            return
        }
        val index = _currentIndex.value
        if (index !in _queue.value.indices) return
        val retryQueue = _queue.value.map { song ->
            if (song.source == org.squidify.android.data.DownloadSource.KHINSIDER) {
                song.copy(khiDirectUrl = null)
            } else song
        }
        play(retryQueue, index, shuffled = _shuffle.value, forceRefreshKhiUrls = true)
    }

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    /** Whole-track download progress (0..1) keyed by song id; entries are removed when done. */
    private val _bufferProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val bufferProgress: StateFlow<Map<String, Float>> = _bufferProgress.asStateFlow()

    val currentSong: PlayerSong?
        get() = _queue.value.getOrNull(_currentIndex.value)

    /** The player whose position/track the UI should show. */
    private fun positionSource(): Player? =
        if (cfState == CF_OVERLAP) standbyP else activeP

    // ------------------------------------------------------------ internals

    /** Logical (unshuffled) queue order, remembered while shuffle is on. */
    private var baseQueue: List<PlayerSong> = emptyList()
    private val shuffleRng = Random()

    private var ticker: Job? = null
    private var scrobbledCurrent = false
    private var saveJob: Job? = null
    private var localQueueSaveJob: Job? = null
    private var playbackRateSaveJob: Job? = null
    private var pendingSwitch: Job? = null
    private var playGeneration = 0L
    private var nextPrefetched = false
    /** Songs whose cache was already evicted+reloaded this session — the
     *  corrupt-cache self-heal must never loop. */
    private val healedSongs = HashSet<String>()

    /** Absolute-position base when the current transcoded stream was opened
     *  with a server-side timeOffset (player positions are window-relative). */
    private var timeOffsetBaseMs = 0L

    /** A seek tapped before the current track is actually READY on the player.
     *  The UI turns optimistic the instant a track is tapped, but the playlist
     *  swap (KHInsider resolve, full-buffer prefetch, plain main-dispatch
     *  latency) lands a moment later — a seek in that window must NOT touch
     *  the playlist. Parked here and replayed the moment the track becomes
     *  playable. */
    private var pendingSeekMs: Long? = null
    private var pendingSeekSongId: String? = null

    /** mediaId of an item this manager just replaced in place (offset seek,
     *  cache heal, quality swap). The PLAYLIST_CHANGED transition that
     *  replaceMediaItem posts afterwards is OURS — running onTrackChanged on
     *  it used to zero timeOffsetBaseMs right after an offset seek, throwing
     *  the seek bar back to the start while the audio kept playing from the
     *  offset. */
    private var selfReplacedMediaId: String? = null

    /** Crossfade state: none / overlapping / fading the tail of the last track. */
    private var cfState = CF_NONE
    private var cfIncomingIdx = -1
    private var cfOutgoingIdx = -1
    private var cfOutgoingBaseMs = 0L
    private var crossfadeRampJob: Job? = null

    /** True between loading the standby's playlist and its first transition
     *  callback — that initial transition is ours, not a user skip. */
    private var cfStandbyArmed = false

    // ------------------------------------------------------------ audio focus

    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false

    private fun requestAudioFocus() {
        if (focusRequest != null) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(::onAudioFocusChange, Handler(Looper.getMainLooper()))
            .setWillPauseWhenDucked(false)
            .build()
        val res = am.requestAudioFocus(request)
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request
        }
    }

    private fun abandonAudioFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focusRequest = null
        resumeOnFocusGain = false
    }

    private fun onAudioFocusChange(change: Int) {
        val focusName = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
            else -> "?$change"
        }
        AppLog.i(TAG, "Audio focus event: $focusName (was playing=${positionSource()?.isPlaying == true})")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                pauseAll()
                // permanent loss: hand the request back so a later play —
                // from the shade, headset or Bluetooth — re-requests focus
                // instead of playing on a stale grant
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocusGain = positionSource()?.isPlaying == true
                pauseAll()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    positionSource()?.play()
                    refreshTicker()
                    AppLog.d(TAG, "Resuming after transient focus loss")
                }
            }
        }
    }

    private fun pauseAll() {
        activeP?.pause()
        standbyP?.pause()
        refreshTicker()
    }

    // ------------------------------------------------------------ attach

    fun attach(active: Player, standby: Player, rebind: (Player) -> Unit) {
        if (activeP === active) return
        // The service can be recreated while the Application graph (and this
        // manager) survives. Capture the queue before replacing the released refs.
        val preservedQueue = _queue.value
        val preservedIndex = _currentIndex.value
        val preservedPosition = _positionMs.value
        val shouldResume = _isPlaying.value

        activeP = active
        standbyP = standby
        sessionRebinder = rebind
        sessionBoundTo = active
        active.addListener(activeListener)
        standby.addListener(standbyListener)
        applyPlaybackRateToPlayers()
        // Keep the app-managed repeat mode across service recreation. A new
        // ExoPlayer starts in REPEAT_MODE_OFF, so reading its default here
        // would silently erase Repeat Album / Repeat Queue from the snapshot.
        val restoredRepeatPlayerMode = when (_repeatMode.value) {
            REPEAT_TRACK -> Player.REPEAT_MODE_ONE
            REPEAT_QUEUE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        active.repeatMode = restoredRepeatPlayerMode
        standby.repeatMode = restoredRepeatPlayerMode
        AppLog.i(TAG, "Two players attached (active + crossfade standby)")
        scope.launch {
            val pending = pendingPlayRequest
            if (pending != null) {
                AppLog.i(TAG, "Replaying the play request made before the player was ready")
                pendingPlayRequest = null
                @Suppress("UNCHECKED_CAST")
                play(
                    pending[0] as List<PlayerSong>,
                    pending[1] as Int,
                    pending[2] as Boolean,
                    pending.getOrNull(3) as? Boolean ?: false,
                )
            } else if (preservedQueue.isNotEmpty()) {
                restoreAttachedQueue(active, preservedQueue, preservedIndex, preservedPosition, shouldResume)
            } else {
                tryRestoreQueue()
            }
        }
    }

    /**
     * Rebuild the timeline when PlaybackService is recreated in the same app
     * process. The UI queue outlives the service-owned ExoPlayers, so skipping
     * this step leaves a visible but completely inert player.
     */
    private suspend fun restoreAttachedQueue(
        player: Player,
        songs: List<PlayerSong>,
        requestedIndex: Int,
        positionMs: Long,
        shouldResume: Boolean,
    ) {
        if (player !== activeP || songs.isEmpty()) return
        val index = requestedIndex.coerceIn(0, songs.lastIndex)
        // Refresh every KHInsider item, not just the current one: a service
        // recreation can happen after the signed URLs for upcoming queue items
        // have expired too. Stable track-page URLs are the only persisted IDs.
        val restoredSongs = try {
            resolveKhiQueue(songs, forceRefresh = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.w(TAG, "Could not refresh KHInsider URLs for the restored queue: ${error.message}")
            songs
        }
        if (player !== activeP) return
        val queue = restoredSongs
        _queue.value = queue
        if (!_shuffle.value || baseQueue.size != queue.size) {
            baseQueue = queue
        } else {
            // Keep the user's unshuffled order while replacing stale KHInsider
            // URLs with the freshly resolved PlayerSong instances.
            val remaining = queue.toMutableList()
            baseQueue = baseQueue.mapNotNull { old ->
                val refreshedIndex = remaining.indexOfFirst {
                    it.source == old.source && it.id == old.id
                }
                if (refreshedIndex >= 0) remaining.removeAt(refreshedIndex) else null
            } + remaining
        }
        timeOffsetBaseMs = 0L
        player.setMediaItems(queue.map { buildMediaItem(it) }, index, positionMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = shouldResume
        _currentIndex.value = index
        _positionMs.value = positionMs.coerceAtLeast(0L)
        _durationMs.value = queue[index].durationMs
        _isPlaying.value = shouldResume
        _isBuffering.value = shouldResume
        _stalled.value = false
        _playbackError.value = null
        failedPlayRequest = null
        pendingSeekMs = null
        pendingSeekSongId = null
        AppLog.i(TAG, "Reattached in-memory queue (${queue.size} tracks), index=$index, resume=$shouldResume")
    }

    private val activeListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = activeP ?: return
            // Consume the "we replaced this item ourselves" marker FIRST: the
            // transition that replaceMediaItem posts for the current item must
            // not run the onTrackChanged resets (see selfReplacedMediaId).
            if (selfReplacedMediaId != null) {
                val expected = selfReplacedMediaId
                selfReplacedMediaId = null
                if (mediaItem?.mediaId == expected) {
                    AppLog.d(
                        TAG,
                        "Self-inflicted replacement of '${mediaItem?.mediaMetadata?.title ?: "?"}' — skipping onTrackChanged resets",
                    )
                    return
                }
                // different item: a genuine transition — fall through normally
            }
            AppLog.d(
                TAG,
                "onMediaItemTransition → '${mediaItem?.mediaMetadata?.title ?: "?"}' reason=${transitionReasonName(reason)}",
            )
            // A crossfade overlap ends here — regardless of why the outgoing
            // item transitioned (natural end OR a repeat-one loop seam), the
            // standby player must be adopted as the new active one.
            if (cfState == CF_OVERLAP) {
                finalizeCrossfade()
                return
            }
            // ExoPlayer reports REPEAT for both repeat-one and repeat-all.
            // Only repeat-track keeps the same current item; repeat-queue must
            // update the current index/history when it wraps to the first track.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && _repeatMode.value == REPEAT_TRACK) {
                AppLog.d(TAG, "Repeat-track loop: seamless restart of the same track")
                return
            }
            // Repeat-album is app-managed: loop when playback crosses an album boundary.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && _repeatMode.value == REPEAT_ALBUM) {
                val previous = _queue.value.getOrNull(_currentIndex.value)
                val incomingIndex = _queue.value.indexOfFirst { it.id == mediaItem?.mediaId }
                val incoming = _queue.value.getOrNull(incomingIndex)
                if (previous != null && incoming != null && albumIdentity(previous) != albumIdentity(incoming)) {
                    val previousAlbum = albumIdentity(previous)
                    val nextInAlbum = (( _currentIndex.value + 1) until _queue.value.size)
                        .firstOrNull { albumIdentity(_queue.value[it]) == previousAlbum }
                    val targetIndex = nextInAlbum
                        ?: _queue.value.indexOfFirst { albumIdentity(it) == previousAlbum }.takeIf { it >= 0 }
                    if (targetIndex != null) {
                        AppLog.i(TAG, "Repeat album: skipping to '${_queue.value[targetIndex].title}'")
                        p.seekTo(targetIndex, 0L)
                        return
                    }
                }
            }
            onTrackChanged(p)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            AppLog.i(
                TAG,
                "isPlaying=$isPlaying (state=${stateName(activeP?.playbackState)}, playWhenReady=${activeP?.playWhenReady})" +
                    " — '${currentSong?.title ?: "?"}'",
            )
            if (cfState == CF_OVERLAP) {
                _isPlaying.value = standbyP?.isPlaying ?: false
            } else {
                // Authoritative read from the AUDIBLE player, not from the
                // callback parameter: after a crossfade swap this listener may
                // sit on the old (now silent) player, and its events must
                // never overwrite the state of the player the user hears.
                // This is what kept the play/pause button showing a dead
                // triangle after track transitions.
                val src = positionSource()
                _isPlaying.value = src?.isPlaying == true
                _stalled.value =
                    src?.let { !it.isPlaying && it.playbackState == Player.STATE_BUFFERING } ?: false
            }
            refreshTicker()
        }

        override fun onPlaybackStateChanged(state: Int) {
            AppLog.i(
                TAG,
                "Playback state → ${stateName(state)} (isPlaying=${activeP?.isPlaying}, playWhenReady=${activeP?.playWhenReady})" +
                    " — '${currentSong?.title ?: "?"}'",
            )
            if (cfState == CF_OVERLAP) {
                _isBuffering.value = standbyP?.playbackState == Player.STATE_BUFFERING
                return
            }
            // authoritative state of the audible player (see onIsPlayingChanged)
            val src = positionSource()
            val srcState = src?.playbackState ?: state
            _isBuffering.value = srcState == Player.STATE_BUFFERING
            _stalled.value =
                srcState == Player.STATE_BUFFERING && src?.isPlaying != true && src?.playWhenReady == true
            if (srcState == Player.STATE_READY) {
                val metaDur = currentSong?.durationMs ?: 0L
                val streamDur = src?.duration?.takeIf { it > 0 } ?: 0L
                // Only accept the stream's duration when it is CONSISTENT with
                // the track metadata. An offset stream (seek via timeOffset)
                // naturally reports only the TAIL length — adopting it would
                // shrink the seek bar to the tail; a corrupt cache entry
                // reports a tiny length — adopting it would trigger an
                // instant "crossfade to the next track" on a healthy song.
                // Server metadata describes the complete track. A timeOffset
                // stream is only the tail, so its ExoPlayer duration must never
                // replace the full duration shown by the seek bar or crossfade.
                _durationMs.value = metaDur.takeIf { it > 0 }
                    ?: streamDur.takeIf { it > 0 }
                    ?: _durationMs.value
                // Self-heal: the cached stream is dramatically shorter than
                // the real track (truncated/corrupt cache entry). Evict the
                // song's cache entries and reload a fresh stream — otherwise
                // the player would treat the song as "almost over" and
                // crossfade away within seconds of starting it.
                if (streamDur > 0 && metaDur > 20_000L && streamDur < metaDur * 0.5f &&
                    timeOffsetBaseMs == 0L && currentSong?.source == org.squidify.android.data.DownloadSource.SQUIDIFY &&
                    downloads.downloadedUriFor(currentSong?.id ?: "") == null
                ) {
                    healCorruptCache(currentSong!!, streamDur, metaDur)
                }
                // the track is really playable now — replay a seek that was
                // parked while it was still loading
                applyPendingSeekIfAny()
            }
            if (srcState == Player.STATE_ENDED) {
                if (_repeatMode.value == REPEAT_ALBUM) {
                    val current = currentSong
                    val firstInAlbum = current?.let { song ->
                        _queue.value.indexOfFirst { albumIdentity(it) == albumIdentity(song) }
                    } ?: -1
                    if (firstInAlbum >= 0) {
                        AppLog.i(TAG, "Repeat album: restarting '${current?.album ?: current?.title}'")
                        src?.seekTo(firstInAlbum, 0L)
                        src?.prepare()
                        src?.playWhenReady = true
                        return
                    }
                }
                // End of queue: stop playback but retain the queue for replay.
                src?.volume = 1f
                if (cfState == CF_FADEOUT_ONLY) {
                    cfState = CF_NONE
                    crossfadeRampJob?.cancel()
                    crossfadeRampJob = null
                }
                _isPlaying.value = false
                _isBuffering.value = false
                _stalled.value = false
                _positionMs.value = _durationMs.value
                abandonAudioFocus()
                saveQueue()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            AppLog.i(TAG, "playWhenReady=$playWhenReady reason=${playWhenReadyReasonName(reason)} — '${currentSong?.title ?: "?"}'")
            // reactive audio focus: the play may come from the system shade
            // media controls, a headset button, Bluetooth or an assistant —
            // none of which go through the in-app play/toggle paths that
            // request focus themselves. Holding focus keeps ducking and
            // pause-on-call behaviour correct for those entry points too.
            if (playWhenReady && focusRequest == null) {
                requestAudioFocus()
            }
            // recompute the UI state from the audible player so the button
            // reacts even when no separate isPlaying event follows
            if (cfState != CF_OVERLAP) {
                _isPlaying.value = positionSource()?.isPlaying == true
            }
            // System media controls, Bluetooth headsets and audio-focus
            // changes can pause playback without going through togglePlayPause.
            if (!playWhenReady) saveQueue()
        }

        override fun onPlaybackSuppressionReasonChanged(suppressionReason: Int) {
            AppLog.i(TAG, "Playback suppression → $suppressionReason (0=none, 1=transient loss)")
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            AppLog.d(
                TAG,
                "Position discontinuity: ${oldPosition.positionMs}ms → ${newPosition.positionMs}ms " +
                    "(item ${oldPosition.mediaItemIndex}→${newPosition.mediaItemIndex}, reason=${discontinuityReasonName(reason)})",
            )
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            AppLog.i(TAG, "Media3 repeat mode → ${repeatModeName(repeatMode)}")
            val appModePlayerEquivalent = when (_repeatMode.value) {
                REPEAT_TRACK -> Player.REPEAT_MODE_ONE
                REPEAT_QUEUE -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            if (appModePlayerEquivalent == repeatMode) return
            _repeatMode.value = when (repeatMode) {
                Player.REPEAT_MODE_ONE -> REPEAT_TRACK
                Player.REPEAT_MODE_ALL -> REPEAT_QUEUE
                else -> REPEAT_OFF
            }
            saveQueue()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (cfState == CF_OVERLAP) {
                // the outgoing track broke at its tail — treat as finished
                finalizeCrossfade()
                return
            }
            val song = currentSong
            AppLog.e(
                TAG,
                "Playback error on '${song?.title ?: "?"}' [${song?.id ?: "-"}]: " +
                    "${error.errorCodeName} — ${error.message}. " +
                    "Cause: ${error.cause?.javaClass?.simpleName}: ${error.cause?.message}",
                error,
            )
            // Keep raw Media3 details (which can contain signed URLs) out of UI.
            _playbackError.value = when {
                error.errorCodeName.contains("NETWORK") ||
                    error.errorCodeName.contains("HTTP") ||
                    error.errorCodeName.contains("IO_") ->
                    "Connection failed. Check your connection or VPN and retry."
                error.errorCodeName.contains("DECOD") ->
                    "This audio format couldn't be played. Try another quality."
                error.errorCodeName.contains("PARSING") ->
                    "The audio file appears to be invalid."
                else -> "Couldn't play this track. Tap Retry to try again."
            }
            _isPlaying.value = false
            _positionMs.value = positionSource()?.currentPosition?.coerceAtLeast(0L) ?: _positionMs.value
            _isBuffering.value = false
            _stalled.value = false
            pendingSeekMs = null
            pendingSeekSongId = null
        }
    }

    private val standbyListener = object : Player.Listener {
        // The standby only makes noise during a crossfade overlap; anything the
        // user does to it from the media notification mid-overlap is handled here.
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (cfState != CF_OVERLAP) return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
            if (cfStandbyArmed) {
                // the transition caused by our own setMediaItems() — the crossfade
                // overlap itself, not a user action
                cfStandbyArmed = false
                return
            }
            // user skipped forward from the notification during the overlap
            finalizeCrossfadeInterrupted()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (cfState != CF_OVERLAP) return
            AppLog.d(TAG, "Crossfade player isPlaying=$isPlaying")
            _isPlaying.value = isPlaying
            val st = standbyP ?: return
            if (!isPlaying && !st.playWhenReady) {
                // paused from the notification while the old track still overlaps
                finalizeCrossfadeInterrupted()
            }
            refreshTicker()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (cfState != CF_OVERLAP) return
            AppLog.d(TAG, "Crossfade player state → ${stateName(state)}")
            _isBuffering.value = state == Player.STATE_BUFFERING
            _stalled.value = state == Player.STATE_BUFFERING && standbyP?.isPlaying != true
            if (state == Player.STATE_READY) applyPendingSeekIfAny()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (cfState != CF_OVERLAP) return
            AppLog.w(TAG, "Crossfade player error: ${error.errorCodeName} — ${error.message}; cancelling crossfade")
            cancelCrossfade()
        }
    }

    /** Shared "a different track is now current" bookkeeping. */
    private fun onTrackChanged(p: Player) {
        scrobbledCurrent = false
        nextPrefetched = false
        timeOffsetBaseMs = 0L
        // Index resolution: the player's playlist index is USUALLY aligned
        // with the queue, but crossfade swaps and in-place item replacements
        // can desync them for an event or two — the title must always follow
        // the item that is ACTUALLY playing, so trust the media id first and
        // fall back to the playlist index. (Fixes "title shows the next track
        // while the previous one is still playing".)
        val playlistIdx = p.currentMediaItemIndex
        val mediaId = p.currentMediaItem?.mediaId
        val queueIdx = if (playlistIdx in _queue.value.indices && _queue.value[playlistIdx].id == mediaId) {
            playlistIdx
        } else {
            _queue.value.indexOfFirst { it.id == mediaId }.takeIf { it >= 0 }
                // never let a stale player index bleed out of the queue bounds
                ?: playlistIdx.takeIf { it in _queue.value.indices }
                ?: _currentIndex.value.takeIf { it in _queue.value.indices }
                ?: 0
        }
        _currentIndex.value = queueIdx
        _positionMs.value = 0L
        _durationMs.value = currentSong?.durationMs?.takeIf { it > 0 }
            ?: p.duration.takeIf { it > 0 }
            ?: 0L
        // the track that just became current is obviously playable — clear any
        // stale full-buffer progress entry left over for it
        currentSong?.let { song ->
            if (_bufferProgress.value.containsKey(song.id)) {
                _bufferProgress.value = _bufferProgress.value - song.id
            }
        }
        _playbackError.value = null
        failedPlayRequest = null
        AppLog.i(TAG, "Track → ${currentSong?.title ?: "?"} (index ${p.currentMediaItemIndex})")
        currentSong?.let { history.record(it) }
        saveQueue()
        maybePrefetchNext()
    }

    // ------------------------------------------------------------ playback

    fun play(
        songs: List<PlayerSong>,
        startIndex: Int = 0,
        shuffled: Boolean = false,
        forceRefreshKhiUrls: Boolean = false,
    ) {
        if (songs.isEmpty()) return
        cancelPendingSwitch()
        cancelCrossfade()
        playGeneration++
        healedSongs.clear()
        val gen = playGeneration
        failedPlayRequest = FailedPlayRequest(songs, startIndex.coerceIn(0, songs.lastIndex), shuffled)
        val previousQueue = _queue.value
        val previousBaseQueue = baseQueue
        val previousIndex = _currentIndex.value
        val previousPosition = _positionMs.value
        val previousDuration = _durationMs.value
        val previousPlaying = _isPlaying.value
        _playbackError.value = null
        _isBuffering.value = true
        _stalled.value = false
        // ---- OPTIMISTIC UI: the tapped track becomes "current" RIGHT NOW.
        // Even while KHInsider URLs are still resolving or a full-buffer
        // prefetch is running, every open screen highlights the row — the tap
        // is visibly registered the moment it happens.
        _queue.value = songs
        baseQueue = songs
        val optimisticIdx = startIndex.coerceIn(0, songs.lastIndex)
        _currentIndex.value = optimisticIdx
        _positionMs.value = 0L
        _durationMs.value = songs[optimisticIdx].durationMs
        if (activeP == null) {
            // the playback service hasn't attached its players yet (very fast
            // tap after launch) — remember the request and replay it on attach
            AppLog.i(TAG, "Player not ready yet — deferring play of '${songs[optimisticIdx].title}'")
            pendingPlayRequest = arrayOf(songs, optimisticIdx, shuffled, forceRefreshKhiUrls)
            return
        }
        scope.launch {
            try {
                var prepared = songs
                // resolve any unresolved KHInsider tracks (e.g. replay from history)
                if (songs.any { it.source == org.squidify.android.data.DownloadSource.KHINSIDER && it.khiDirectUrl == null }) {
                    prepared = try {
                        resolveKhiQueue(songs, forceRefresh = forceRefreshKhiUrls)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.e(TAG, "Could not resolve KHInsider playback URLs", e)
                        if (gen == playGeneration) {
                            // Nothing has been sent to ExoPlayer yet. Restore
                            // the previous audible queue instead of leaving a
                            // fake optimistic selection on screen.
                            _queue.value = previousQueue
                            baseQueue = previousBaseQueue
                            _currentIndex.value = previousIndex
                            _positionMs.value = previousPosition
                            _durationMs.value = previousDuration
                            _isPlaying.value = previousPlaying
                            _isBuffering.value = false
                            _stalled.value = false
                            _playbackError.value = "KHInsider couldn't load this track. Check your connection and retry."
                        }
                        return@launch
                    }
                    if (gen != playGeneration) return@launch
                }

                val ordered: List<PlayerSong>
                val start: Int
                if (shuffled && prepared.size > 1) {
                    baseQueue = prepared
                    _shuffle.value = true
                    val idx = startIndex.coerceIn(0, prepared.lastIndex)
                    ordered = listOf(prepared[idx]) +
                        prepared.filterIndexed { i, _ -> i != idx }.shuffled(shuffleRng)
                    start = 0
                } else {
                    if (!shuffled) {
                        baseQueue = prepared
                        _shuffle.value = false
                    }
                    ordered = prepared
                    start = startIndex.coerceIn(0, prepared.lastIndex)
                }

                val target = ordered.getOrNull(start) ?: return@launch
                // "download whole track first" mode: fetch it fully before switching
                if (settings.state().value.fullBuffer && playbackUri(target) != null && !isFullyCached(target)) {
                    prefetchWholeTrack(target)
                    if (gen != playGeneration) return@launch
                }
                // The prefetch may have taken minutes — a crossfade could have
                // swapped the two players meanwhile, so ALWAYS re-resolve the
                // current active player instead of a stale captured reference.
                if (cfState != CF_NONE) cancelCrossfade()
                val pNow = activeP ?: return@launch
                applyQueue(pNow, ordered, start, 0L)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "Could not start playback", e)
                if (gen == playGeneration) {
                    _queue.value = previousQueue
                    baseQueue = previousBaseQueue
                    _currentIndex.value = previousIndex
                    _positionMs.value = previousPosition
                    _durationMs.value = previousDuration
                    _isPlaying.value = activeP?.isPlaying ?: previousPlaying
                    _isBuffering.value = false
                    _stalled.value = false
                    _playbackError.value = "Playback couldn't start. Check your connection and retry."
                }
            } finally {
                if (gen == playGeneration && activeP?.playbackState != Player.STATE_BUFFERING) {
                    _isBuffering.value = false
                }
            }
        }.also { pendingSwitch = it }
    }

    /** Starts the given list in shuffled order from a random track. */
    fun playShuffled(songs: List<PlayerSong>) {
        if (songs.isEmpty()) return
        play(songs, (songs.indices).random(), shuffled = true)
    }

    private fun applyQueue(p: Player, songs: List<PlayerSong>, idx: Int, posMs: Long) {
        if (!_shuffle.value) baseQueue = songs
        _queue.value = songs
        timeOffsetBaseMs = 0L
        p.setMediaItems(songs.map { buildMediaItem(it) }, idx, posMs)
        failedPlayRequest = null
        p.prepare()
        p.playWhenReady = true
        _currentIndex.value = idx
        _positionMs.value = posMs
        _durationMs.value = songs.getOrNull(idx)?.durationMs ?: 0L
        _stalled.value = false
        scrobbledCurrent = false
        nextPrefetched = false
        saveQueue()
        requestAudioFocus()
    }

    fun addToQueue(songs: List<PlayerSong>) {
        val p = activeP ?: return
        if (songs.isEmpty()) return
        if (_queue.value.isEmpty()) {
            play(songs, 0)
            return
        }
        _queue.value = _queue.value + songs
        // Keep the unshuffled baseline complete too, so disabling shuffle
        // never silently drops tracks added while shuffle was enabled.
        baseQueue = if (_shuffle.value) baseQueue + songs else _queue.value
        p.addMediaItems(songs.map { buildMediaItem(it) })
        if (cfState == CF_OVERLAP) {
            // The standby contains only the suffix starting at cfIncomingIdx.
            standbyP?.addMediaItems(songs.map { buildMediaItem(it) })
        }
        saveQueue()
    }

    fun playNext(songs: List<PlayerSong>) {
        val p = activeP ?: return
        if (songs.isEmpty()) return
        if (_queue.value.isEmpty()) {
            play(songs, 0)
            return
        }
        val at = (_currentIndex.value + 1).coerceAtMost(_queue.value.size)
        _queue.value = _queue.value.toMutableList().apply { addAll(at, songs) }
        // Preserve every item when shuffle is later disabled. The current
        // shuffled playback order still receives the new items immediately.
        baseQueue = if (_shuffle.value) baseQueue + songs else _queue.value
        p.addMediaItems(at, songs.map { buildMediaItem(it) })
        if (cfState == CF_OVERLAP) {
            val standbyAt = (at - cfIncomingIdx).coerceIn(0, standbyP?.mediaItemCount ?: 0)
            standbyP?.addMediaItems(standbyAt, songs.map { buildMediaItem(it) })
        }
        saveQueue()
    }

    fun jumpTo(index: Int) {
        if (index !in _queue.value.indices) return
        cancelPendingSwitch()
        cancelCrossfade()
        playGeneration++
        healedSongs.clear()
        val gen = playGeneration
        val song = _queue.value[index]
        AppLog.i(TAG, "User tapped track #$index '${song.title}' in the queue")
        _isBuffering.value = true
        _stalled.value = false
        // optimistic: the tapped queue row highlights immediately
        _currentIndex.value = index
        _positionMs.value = 0L
        _durationMs.value = song.durationMs
        saveQueue()
        scope.launch {
            try {
                if (settings.state().value.fullBuffer && playbackUri(song) != null && !isFullyCached(song)) {
                    prefetchWholeTrack(song)
                    if (gen != playGeneration) return@launch
                }
                // re-resolve: players may have swapped during the download
                if (cfState != CF_NONE) cancelCrossfade()
                val p = activeP ?: return@launch
                timeOffsetBaseMs = 0L
                p.seekTo(index, 0L)
                p.prepare()
                p.playWhenReady = true
                _currentIndex.value = index
                _positionMs.value = 0L
                scrobbledCurrent = false
                nextPrefetched = false
                requestAudioFocus()
            } finally {
                if (gen == playGeneration && activeP?.playbackState != Player.STATE_BUFFERING) {
                    _isBuffering.value = false
                }
            }
        }.also { pendingSwitch = it }
    }

    fun removeAt(index: Int) {
        val p = activeP ?: return
        if (index !in _queue.value.indices) return
        if (cfState != CF_NONE) cancelCrossfade()
        val cur = p.currentMediaItemIndex
        val removedSong = _queue.value[index]
        _queue.value = _queue.value.filterIndexed { i, _ -> i != index }
        if (_shuffle.value) {
            val baseIndex = baseQueue.indexOfFirst { it === removedSong }
            if (baseIndex >= 0) baseQueue = baseQueue.toMutableList().also { it.removeAt(baseIndex) }
        } else {
            baseQueue = _queue.value
        }
        p.removeMediaItem(index)
        if (index == cur) {
            _currentIndex.value = p.currentMediaItemIndex
        }
        saveQueue()
    }

    fun move(from: Int, to: Int) {
        val p = activeP ?: return
        val q = _queue.value.toMutableList()
        if (from !in q.indices || to !in q.indices) return
        if (cfState != CF_NONE) cancelCrossfade()
        val item = q.removeAt(from)
        q.add(to, item)
        _queue.value = q
        // A manual reorder becomes the new baseline so turning shuffle off
        // does not unexpectedly undo the user's explicit queue arrangement.
        baseQueue = q
        p.moveMediaItem(from, to)
        _currentIndex.value = p.currentMediaItemIndex
        saveQueue()
    }

    /**
     * The service may have been recreated while PlayerManager kept its queue.
     * Recreate the media timeline before accepting transport commands.
     */
    private fun ensureActiveTimeline(player: Player): Boolean {
        val songs = _queue.value
        if (songs.isEmpty()) return false
        val index = _currentIndex.value.coerceIn(0, songs.lastIndex)
        val expectedId = songs[index].id
        if (player.mediaItemCount == songs.size &&
            player.currentMediaItem?.mediaId == expectedId
        ) return true

        AppLog.w(
            TAG,
            "Player timeline lost or stale (items=${player.mediaItemCount}, current=${player.currentMediaItem?.mediaId}, expected=$expectedId); rebuilding",
        )
        player.setMediaItems(songs.map { buildMediaItem(it) }, index, _positionMs.value.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = _isPlaying.value
        _durationMs.value = songs[index].durationMs
        return true
    }

    fun togglePlayPause() {
        AppLog.d(TAG, "User: play/pause tapped (isPlaying=${_isPlaying.value})")
        if (cfState == CF_OVERLAP) {
            val incoming = standbyP ?: return
            if (incoming.isPlaying) {
                activeP?.pause()
                incoming.pause()
                finalizeCrossfadeInterrupted()
            } else {
                incoming.prepare()
                incoming.play()
                requestAudioFocus()
                _isPlaying.value = true
            }
            saveQueue()
            return
        }
        val p = activeP ?: return
        if (_playbackError.value != null || p.playerError != null) {
            retryCurrent()
            return
        }
        if (!ensureActiveTimeline(p)) return
        if (p.isPlaying) {
            p.pause()
        } else {
            p.prepare()
            p.play()
            requestAudioFocus()
        }
        _isPlaying.value = positionSource()?.isPlaying == true
        saveQueue()
    }

    fun next() {
        AppLog.d(TAG, "User: next track tapped")
        cancelPendingSwitch()
        if (cfState == CF_OVERLAP) {
            finalizeCrossfadeInterrupted()
        } else {
            cancelCrossfade()
        }
        val p = activeP ?: return
        val wasFailed = _playbackError.value != null || p.playerError != null
        if (!ensureActiveTimeline(p)) return
        val previousIndex = p.currentMediaItemIndex
        p.seekToNextMediaItem()
        val moved = p.currentMediaItemIndex != previousIndex
        if (moved && (wasFailed || p.playbackState == Player.STATE_ENDED || p.playbackState == Player.STATE_IDLE)) {
            _playbackError.value = null
            p.prepare()
            p.playWhenReady = true
            requestAudioFocus()
        }
    }

    fun previous() {
        AppLog.d(TAG, "User: previous track tapped")
        cancelPendingSwitch()
        if (cfState == CF_OVERLAP) {
            finalizeCrossfadeInterrupted()
        } else {
            cancelCrossfade()
        }
        val p = activeP ?: return
        val wasFailed = _playbackError.value != null || p.playerError != null
        if (!ensureActiveTimeline(p)) return
        val previousIndex = p.currentMediaItemIndex
        p.seekToPreviousMediaItem()
        val moved = p.currentMediaItemIndex != previousIndex
        if ((moved || p.currentPosition > 3_000L) &&
            (wasFailed || p.playbackState == Player.STATE_ENDED || p.playbackState == Player.STATE_IDLE)
        ) {
            _playbackError.value = null
            p.prepare()
            p.playWhenReady = true
            requestAudioFocus()
        }
    }

    fun seekTo(ms: Long) {
        // During a crossfade the incoming player is the audible source, while
        // activeP still points at the outgoing one. Adopt the audible player
        // before any seek or playlist replacement can occur.
        if (cfState == CF_OVERLAP) finalizeCrossfadeInterrupted()
        if (cfState == CF_FADEOUT_ONLY) {
            crossfadeRampJob?.cancel()
            crossfadeRampJob = null
            cfState = CF_NONE
            activeP?.volume = 1f
        }
        val p = activeP ?: return
        if (_playbackError.value != null || p.playerError != null) return
        if (!ensureActiveTimeline(p)) return
        val song = currentSong
        val dur = song?.durationMs?.takeIf { it > 0 }
            ?: _durationMs.value.takeIf { it > 0 }
            ?: p.duration.takeIf { it > 0 }
            ?: 0L
        val pos = if (dur > 0) ms.coerceIn(0L, dur) else ms.coerceAtLeast(0)

        // A tap can arrive while a newly selected track is still resolving or
        // preparing. Never edit the old playlist during that transition.
        if (
            song == null || p.mediaItemCount == 0 ||
            p.currentMediaItem?.mediaId != song.id ||
            p.playbackState != Player.STATE_READY
        ) {
            if (song == null) return
            pendingSeekMs = pos
            pendingSeekSongId = song.id
            AppLog.i(
                TAG,
                "Seek parked at ${pos}ms — '${song.title}' is not READY yet " +
                    "(state=${stateName(p.playbackState)}, hasItem=${p.currentMediaItem?.mediaId ?: "-"}, want=${song.id})",
            )
            _positionMs.value = pos
            scheduleLocalQueueSave()
            return
        }

        val transcodedStream = song.source ==
            org.squidify.android.data.DownloadSource.SQUIDIFY &&
            settings.state().value.streamingQuality != StreamQuality.ORIGINAL &&
            downloads.downloadedUriFor(song.id) == null
        val metaDur = song.durationMs
        val nativeDur = p.duration.takeIf { it > 0 } ?: 0L
        val durationTrustworthy = timeOffsetBaseMs == 0L &&
            (nativeDur <= 0 || metaDur <= 0 || kotlin.math.abs(nativeDur - metaDur) < 10_000L)
        val seekable = p.isCurrentMediaItemSeekable
        val fullyCached = transcodedStream && isFullyCached(song)
        val nativeSeekSafe = shouldUseNativeSeek(
            isTranscoded = transcodedStream,
            isSeekable = seekable,
            durationTrustworthy = durationTrustworthy,
            isFullyCached = fullyCached,
        )
        AppLog.i(
            TAG,
            "Seek → ${pos}ms (native-seekable=$seekable, full-cache=$fullyCached, native-safe=$nativeSeekSafe, " +
                "durationOk=$durationTrustworthy, timeOffsetBase=$timeOffsetBaseMs) — '${song.title}'",
        )
        if (nativeSeekSafe) {
            p.seekTo(pos)
            _positionMs.value = pos
        } else if (transcodedStream) {
            seekViaTimeOffset(song, pos)
        } else {
            // Local downloads and KHInsider direct files should normally be
            // seekable. Retain the fallback for servers that misreport this.
            p.seekTo(pos)
            _positionMs.value = pos
        }
        scheduleLocalQueueSave()
    }

    /** Replays a parked seek once its track is finally READY; a parked seek
     *  for a track the user has already navigated away from is dropped. */
    private fun applyPendingSeekIfAny() {
        val parkedMs = pendingSeekMs ?: return
        val parkedSongId = pendingSeekSongId ?: return
        val song = currentSong ?: return
        if (song.id != parkedSongId) {
            AppLog.d(TAG, "Dropping parked seek for '$parkedSongId' — now on '${song.title}'")
            pendingSeekMs = null
            pendingSeekSongId = null
            return
        }
        val p = positionSource() ?: return
        if (p.currentMediaItem?.mediaId != parkedSongId) return // not on this track yet
        pendingSeekMs = null
        pendingSeekSongId = null
        AppLog.i(TAG, "Applying parked seek to ${parkedMs}ms — '${song.title}' is READY now")
        seekTo(parkedMs)
    }

    /**
     * Seek on a non-range transcoded stream: rebuild the current MediaItem
     * with ?timeOffset=seconds and keep playing from there. Position math is
     * corrected with [timeOffsetBaseMs] so the UI still shows absolute time.
     */
    private fun seekViaTimeOffset(song: PlayerSong, absoluteMs: Long) {
        val p = activeP ?: return
        if (cfState != CF_NONE) cancelCrossfade()
        val idx = p.currentMediaItemIndex
        // The surgery below is only safe on a prepared, matching playlist —
        // seekTo() guarantees it, but double-check so any future caller can't
        // reintroduce the mid-transition races.
        if (idx < 0 || idx >= p.mediaItemCount || p.currentMediaItem?.mediaId != song.id) {
            AppLog.w(TAG, "seekViaTimeOffset skipped — player not on '${song.title}' (idx=$idx of ${p.mediaItemCount})")
            pendingSeekMs = absoluteMs
            pendingSeekSongId = song.id
            return
        }
        val offsetSec = (absoluteMs / 1000L)
        val wasPlaying = p.playWhenReady
        AppLog.i(TAG, "Seek via timeOffset=${offsetSec}s (transcoded stream has no Range support; wasPlaying=$wasPlaying)")
        timeOffsetBaseMs = offsetSec * 1000L
        val fresh = buildMediaItem(song, timeOffsetMs = timeOffsetBaseMs)
        // Mark the replacement BEFORE it happens: the PLAYLIST_CHANGED
        // transition it posts must not run onTrackChanged (which would zero
        // timeOffsetBaseMs and knock the seek bar back to the start).
        selfReplacedMediaId = song.id
        p.replaceMediaItem(idx, fresh)
        p.prepare()
        // Seeking a paused song must not unexpectedly resume playback.
        p.playWhenReady = wasPlaying
        _positionMs.value = absoluteMs
        // the seek bar ALWAYS shows the full track: the offset stream only
        // contains the tail, so its own duration must never overwrite the
        // metadata duration (that shrank the bar and made opus/mp3 seek
        // targets past the tail unreachable)
        if (song.durationMs > 0) _durationMs.value = song.durationMs
        _stalled.value = false
        if (wasPlaying) requestAudioFocus()
    }

    /**
     * The disk cache holds a truncated/corrupt entry for this song (the
     * stream reports a fraction of the real duration). Evict every cache
     * entry of the song and reload a fresh stream so the track plays in
     * full instead of "starting almost over" and instantly crossfading away.
     */
    private fun healCorruptCache(song: PlayerSong, streamDur: Long, metaDur: Long) {
        if (!healedSongs.add(song.id)) return
        val p = activeP ?: return
        AppLog.e(
            TAG,
            "Corrupt cache detected for '${song.title}': stream=${streamDur}ms vs metadata=${metaDur}ms — " +
                "evicting cache entries and reloading",
        )
        runCatching {
            val cache = org.squidify.android.App.graph().mediaCache(context)
            cache.keys.filter { it == "sq-${song.id}" || it.startsWith("sq-${song.id}-") }
                .forEach { key ->
                    AppLog.d(TAG, "Evicting cache entry $key")
                    cache.removeResource(key)
                }
        }.onFailure { t ->
            AppLog.w(TAG, "Cache eviction failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        // fresh stream, same queue position — playback restarts from the top
        // of the (now healthy) track
        timeOffsetBaseMs = 0L
        val idx = p.currentMediaItemIndex
        if (idx >= 0) {
            selfReplacedMediaId = song.id
            p.replaceMediaItem(idx, buildMediaItem(song))
            p.prepare()
            p.playWhenReady = true
            _positionMs.value = 0L
            _durationMs.value = metaDur
        }
    }

    fun seekBack15() {
        val p = positionSource() ?: return
        seekTo(absolutePositionOf(p) - 15_000)
    }

    fun seekForward15() {
        val p = positionSource() ?: return
        val dur = _durationMs.value.takeIf { it > 0 } ?: p.duration.takeIf { it > 0 } ?: 0L
        val target = absolutePositionOf(p) + 15_000
        seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
    }

    /** Absolute UI position of the player (window position + offset base). */
    private fun absolutePositionOf(p: Player): Long =
        (timeOffsetBaseMs + p.currentPosition).coerceAtLeast(0)

    // ------------------------------------------------------------ playback rate shift

    private fun applyPlaybackRateToPlayers() {
        val saved = settings.state().value
        val parameters = PlaybackParameters(
            saved.playbackTempo.coerceIn(0.25f, 4f),
            saved.playbackPitch.coerceIn(0.25f, 4f),
        )
        activeP?.setPlaybackParameters(parameters)
        standbyP?.setPlaybackParameters(parameters)
    }

    /** Apply immediately to both ExoPlayers; persist after slider input settles. */
    fun setPlaybackRate(tempo: Float, pitch: Float, linked: Boolean) {
        val safeTempo = tempo.takeIf { it.isFinite() }?.coerceIn(0.25f, 4f) ?: 1f
        val safePitch = if (linked) safeTempo else pitch.takeIf { it.isFinite() }?.coerceIn(0.25f, 4f) ?: 1f
        val parameters = PlaybackParameters(safeTempo, safePitch)
        activeP?.setPlaybackParameters(parameters)
        standbyP?.setPlaybackParameters(parameters)
        playbackRateSaveJob?.cancel()
        playbackRateSaveJob = scope.launch {
            delay(350)
            settings.setPlaybackRate(safeTempo, safePitch, linked)
        }
        AppLog.i(TAG, "Playback rate shift: tempo=$safeTempo pitch=$safePitch linked=$linked")
    }

    // ------------------------------------------------------------ shuffle / repeat

    /**
     * Shuffle is managed by reordering the queue itself (the player's own
     * shuffle mode stays off) so queue, notification and crossfade always
     * agree on what "next" means.
     */
    fun toggleShuffle() {
        val p = activeP ?: return
        val newShuffle = !_shuffle.value
        AppLog.i(TAG, "User: shuffle ${if (newShuffle) "ON" else "OFF"}")
        _shuffle.value = newShuffle
        if (_queue.value.isEmpty()) return
        if (cfState == CF_OVERLAP) {
            finalizeCrossfadeInterrupted()
        } else if (cfState != CF_NONE) {
            cancelCrossfade()
        }

        val cur = _currentIndex.value
        if (newShuffle) {
            if (baseQueue.size != _queue.value.size) baseQueue = _queue.value
            val current = _queue.value.getOrNull(cur)
            val rest = _queue.value.filterIndexed { i, _ -> i != cur }
            val target = listOfNotNull(current) + rest.shuffled(shuffleRng)
            reorderPlaylist(p, target)
            _queue.value = target
        } else {
            val base = baseQueue.ifEmpty { _queue.value }
            reorderPlaylist(p, base)
            _queue.value = base
        }
        _currentIndex.value = p.currentMediaItemIndex
        saveQueue()
    }

    /** Rearranges the player's playlist into [target] using move calls (no re-prepare). */
    private fun reorderPlaylist(p: Player, target: List<PlayerSong>) {
        val cur = _queue.value.toMutableList()
        for (i in target.indices) {
            val wanted = target[i]
            var j = -1
            for (k in i until cur.size) {
                if (cur[k] === wanted) {
                    j = k
                    break
                }
            }
            if (j < i) continue
            if (j != i) {
                p.moveMediaItem(j, i)
                cur.removeAt(j)
                cur.add(i, wanted)
            }
        }
    }

    fun cycleRepeat() {
        val nextMode = (_repeatMode.value + 1) % 4
        AppLog.i(TAG, "User: repeat → ${when (nextMode) {
            REPEAT_TRACK -> "TRACK"
            REPEAT_ALBUM -> "ALBUM"
            REPEAT_QUEUE -> "QUEUE"
            else -> "OFF"
        }}")
        setRepeatMode(nextMode)
    }

    fun setRepeatMode(mode: Int) {
        val safeMode = mode.coerceIn(REPEAT_OFF, REPEAT_QUEUE)
        val playerMode = when (safeMode) {
            REPEAT_TRACK -> Player.REPEAT_MODE_ONE
            REPEAT_QUEUE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        activeP?.repeatMode = playerMode
        standbyP?.repeatMode = playerMode
        _repeatMode.value = safeMode
        saveQueue()
    }

    private fun albumIdentity(song: PlayerSong): String {
        val album = song.albumId?.takeIf { it.isNotBlank() }
            ?: song.album?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: song.id
        val artist = song.artistId?.takeIf { it.isNotBlank() }
            ?: song.artist?.trim()?.lowercase().orEmpty()
        return "${song.source.name}:$album:$artist"
    }

    fun stop() {
        AppLog.i(TAG, "Player stopped and queue cleared")
        cancelPendingSwitch()
        cancelCrossfade()
        stopTicker()
        timeOffsetBaseMs = 0L
        pendingSeekMs = null
        pendingSeekSongId = null
        selfReplacedMediaId = null
        activeP?.let {
            it.stop()
            it.clearMediaItems()
            it.volume = 1f
        }
        standbyP?.let {
            it.stop()
            it.clearMediaItems()
            it.volume = 1f
        }
        _queue.value = emptyList()
        clearLocalQueue()
        _currentIndex.value = -1
        _positionMs.value = 0
        _durationMs.value = 0
        _isPlaying.value = false
        _isBuffering.value = false
        _stalled.value = false
        _playbackError.value = null
        abandonAudioFocus()
    }

    // ------------------------------------------------------------ quality

    /**
     * Rebuild the queue when streaming quality changes, preserving position.
     * When a KHInsider track is currently playing, only the Squidify URLs are
     * swapped in place — playback of the audible track is never interrupted.
     */
    fun applyQualityChange(q: StreamQuality) {
        val p = activeP ?: return
        if (_queue.value.isEmpty()) return
        if (cfState != CF_NONE) cancelCrossfade()
        val idx = p.currentMediaItemIndex
        if (idx !in _queue.value.indices) return
        val curSong = _queue.value.getOrNull(idx)
        if (p.currentMediaItem?.mediaId != curSong?.id) {
            AppLog.w(TAG, "Quality change skipped: player timeline is not aligned with the app queue")
            return
        }
        if (curSong?.source == org.squidify.android.data.DownloadSource.KHINSIDER) {
            // refresh every Squidify item URL without touching the playing one
            var refreshed = 0
            _queue.value.forEachIndexed { i, s ->
                if (s.source == org.squidify.android.data.DownloadSource.SQUIDIFY && i != idx) {
                    runCatching { p.replaceMediaItem(i, buildMediaItem(s)) }
                    refreshed++
                }
            }
            AppLog.i(
                TAG,
                "Quality → ${q.shortLabel}: refreshed $refreshed Squidify URLs without interrupting KHInsider playback",
            )
            return
        }
        val pos = absolutePositionOf(p)
        val playing = p.playWhenReady
        AppLog.i(
            TAG,
            "Applying quality change to ${_queue.value.size} tracks → ${q.shortLabel}; " +
                "rebuilding queue at index $idx, pos $pos ms",
        )
        val transcoded = q != StreamQuality.ORIGINAL &&
            curSong?.source == org.squidify.android.data.DownloadSource.SQUIDIFY &&
            downloads.downloadedUriFor(curSong.id) == null
        val items = _queue.value.mapIndexed { i, s ->
            if (i == idx && transcoded) buildMediaItem(s, timeOffsetMs = pos) else buildMediaItem(s)
        }
        timeOffsetBaseMs = if (transcoded) (pos / 1000L) * 1000L else 0L
        // setMediaItems emits PLAYLIST_CHANGED for the current item. Mark it
        // before the call so onTrackChanged cannot reset the offset base to 0.
        selfReplacedMediaId = curSong?.id
        p.setMediaItems(items, idx, if (transcoded) 0L else pos)
        p.prepare()
        p.playWhenReady = playing
        _positionMs.value = pos
        _durationMs.value = curSong?.durationMs?.takeIf { it > 0 } ?: _durationMs.value
        _stalled.value = false
    }

    /** Re-resolve the current KHInsider track at a new quality and swap it in. */
    fun applyKhiQualityChange() {
        val p = activeP ?: return
        if (cfState != CF_NONE) cancelCrossfade()
        val idx = p.currentMediaItemIndex
        val song = _queue.value.getOrNull(idx) ?: return
        if (song.source != org.squidify.android.data.DownloadSource.KHINSIDER) return
        if (song.khiTrackUrl?.let { downloads.downloadedUriForKhi(it) } != null) {
            AppLog.i(TAG, "KHInsider quality change ignored: '" + song.title + "' is playing from a local download")
            return
        }
        val pos = absolutePositionOf(p)
        val playing = p.playWhenReady
        scope.launch {
            _isBuffering.value = true
            try {
                val fresh = resolveKhi(song, forceRefresh = true)
                // Resolving a direct URL can take long enough for the queue
                // or crossfade roles to change. Re-acquire and validate the
                // currently audible player instead of mutating a stale one.
                val active = activeP ?: return@launch
                val currentIdx = active.currentMediaItemIndex
                if (
                    currentSong?.id != song.id ||
                    currentIdx !in _queue.value.indices ||
                    active.currentMediaItem?.mediaId != song.id
                ) return@launch

                val updated = _queue.value.toMutableList().also { it[currentIdx] = fresh }
                _queue.value = updated
                if (!_shuffle.value) baseQueue = updated
                selfReplacedMediaId = fresh.id
                active.replaceMediaItem(currentIdx, buildMediaItem(fresh))
                active.prepare()
                active.seekTo(pos)
                active.playWhenReady = playing
                _playbackError.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "Could not change KHInsider playback quality", e)
                _playbackError.value = "Couldn't change audio quality. Playback was left unchanged."
            } finally {
                if (activeP?.playbackState != Player.STATE_BUFFERING) {
                    _isBuffering.value = false
                }
            }
        }
    }

    // ------------------------------------------------------------ URIs

    private fun buildMediaItem(song: PlayerSong, timeOffsetMs: Long = 0L): MediaItem {
        val quality = settings.state().value.streamingQuality
        val (uri, artwork, cacheKey) = when (song.source) {
            org.squidify.android.data.DownloadSource.SQUIDIFY -> {
                val local = song.id.let { downloads.downloadedUriFor(it) }
                if (local != null) {
                    AppLog.i(TAG, "'${song.title}' plays from local download: $local")
                    Triple(
                        local,
                        song.coverUrl ?: subsonic.coverUrl(song.coverArtId ?: song.albumId?.let { "al-$it" }),
                        null,
                    )
                } else {
                    val u = Uri.parse(subsonic.streamUrl(song.id, quality, timeOffsetMs))
                    // no per-item log: queue building would emit one line per
                    // track in a single millisecond and look like the app is
                    // streaming the entire queue at once; the actual network
                    // open (with quality in the cache key) is logged by
                    // PlaybackIO the moment a stream really starts
                    Triple(
                        u,
                        song.coverUrl ?: subsonic.coverUrl(song.coverArtId ?: song.albumId?.let { "al-$it" }),
                        // offset windows must never share cache entries with full streams
                        if (timeOffsetMs > 0) "sq-${song.id}-${quality.name}-off${timeOffsetMs / 1000L}"
                        else "sq-${song.id}-${quality.name}",
                    )
                }
            }
            org.squidify.android.data.DownloadSource.KHINSIDER -> {
                val local = song.khiTrackUrl?.let { downloads.downloadedUriForKhi(it) }
                if (local != null) {
                    AppLog.i(TAG, "'${song.title}' plays from local KHInsider download: $local")
                    Triple(local, song.coverUrl, null)
                } else {
                    val direct = song.khiDirectUrl
                    Triple(Uri.parse(direct ?: song.khiTrackUrl ?: ""), song.coverUrl, "khi-${song.id.hashCode()}")
                }
            }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(artwork?.let { Uri.parse(it) })
            .build()
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setCustomCacheKey(cacheKey)
            .setMediaMetadata(metadata)
            .build()
    }

    private suspend fun resolveKhi(song: PlayerSong, forceRefresh: Boolean = false): PlayerSong {
        val url = song.khiTrackUrl ?: return song
        val res = khi.resolve(url, forceRefresh = forceRefresh)
        val format = when (settings.state().value.khiQuality) {
            KhiQuality.FLAC -> "flac"
            KhiQuality.MP3 -> "mp3"
            KhiQuality.BEST -> if (res.flac != null) "flac" else "mp3"
        }
        val directUrl = res.urlFor(format)
            ?: throw IllegalStateException("KHInsider resolver returned no playable $format URL")
        return song.copy(khiDirectUrl = directUrl)
    }

    /** Resolve all KHI tracks before starting playback (parallel, bounded). */
    suspend fun resolveKhiQueue(
        songs: List<PlayerSong>,
        forceRefresh: Boolean = false,
    ): List<PlayerSong> = coroutineScope {
        val sem = Semaphore(6)
        songs.map { song ->
            async {
                val isKhi = song.source == org.squidify.android.data.DownloadSource.KHINSIDER
                val hasLocalFile = song.khiTrackUrl?.let { downloads.downloadedUriForKhi(it) } != null
                if (isKhi && !hasLocalFile && (forceRefresh || song.khiDirectUrl == null)) {
                    sem.acquire()
                    try {
                        resolveKhi(song, forceRefresh = forceRefresh)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        AppLog.w(TAG, "Could not refresh KHInsider URL for '${song.title}': ${error.message}")
                        // Keep the old signed URL as a fallback when available;
                        // a missing URL will surface as a normal playback error.
                        song
                    } finally {
                        sem.release()
                    }
                } else song
            }
        }.awaitAll()
    }

    // ------------------------------------------------------------ full-buffer prefetch

    /** The URI + cache key the player would use for this song (null for local files). */
    private fun playbackUri(song: PlayerSong): Pair<Uri, String>? {
        return when (song.source) {
            org.squidify.android.data.DownloadSource.SQUIDIFY -> {
                if (downloads.downloadedUriFor(song.id) != null) return null // local file
                val quality = settings.state().value.streamingQuality
                Uri.parse(subsonic.streamUrl(song.id, quality)) to "sq-${song.id}-${quality.name}"
            }
            org.squidify.android.data.DownloadSource.KHINSIDER -> {
                val direct = song.khiDirectUrl ?: return null
                Uri.parse(direct) to "khi-${song.id.hashCode()}"
            }
        }
    }

    /** True when the file is already (at least partially) in the disk cache. */
    private fun isFullyCached(song: PlayerSong): Boolean {
        val cache = try {
            org.squidify.android.App.graph().mediaCache(context)
        } catch (_: Exception) {
            return false
        }
        val key = playbackUri(song)?.second ?: return false
        return try {
            val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            contentLength > 0L && cache.isCached(key, 0L, contentLength)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Downloads the whole file into the disk cache with visible progress.
     * Cancellation-aware: superseded play() calls abort the download.
     */
    private suspend fun prefetchWholeTrack(song: PlayerSong) {
        val (uri, key) = playbackUri(song) ?: return
        AppLog.i(TAG, "Full-buffer mode: downloading '${song.title}' completely before playback")
        _bufferProgress.value = _bufferProgress.value + (song.id to 0f)
        try {
            withContext(Dispatchers.IO) {
                val job = coroutineContext[Job]
                cacheWholeFile(uri, key, song.id, stillWanted = { job?.isActive != false })
            }
            AppLog.i(TAG, "Full-buffer: '${song.title}' fully cached")
        } catch (e: CancellationException) {
            AppLog.i(TAG, "Full-buffer download of '${song.title}' was superseded")
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Full-buffer prefetch failed for '${song.title}': ${e.message} — falling back to streaming")
        } finally {
            _bufferProgress.value = _bufferProgress.value - song.id
        }
    }

    /**
     * Quietly pre-caches the next queue item so the next switch is instant
     * even on slow connections. Always on: the whole next track lands in the
     * disk cache in the background without any visible loading indicators.
     */
    private fun maybePrefetchNext() {
        // Do not silently download entire following tracks on mobile data.
        // This background full-file prefetch follows the explicit Full-buffer
        // preference; normal streaming remains demand-driven by ExoPlayer.
        if (!settings.state().value.fullBuffer) return
        if (nextPrefetched) return
        if (cfState != CF_NONE) return
        val song = nextIndexAfter(_currentIndex.value)?.let { _queue.value.getOrNull(it) } ?: return
        val (uri, key) = playbackUri(song) ?: return
        nextPrefetched = true
        scope.launch(Dispatchers.IO) {
            try {
                AppLog.d(TAG, "Pre-caching next track '${song.title}' (silent, no UI progress)")
                cacheWholeFile(uri, key, song.id, { true }, reportProgress = false)
                AppLog.d(TAG, "Pre-cached next track '${song.title}'")
            } catch (_: Exception) {
            }
        }
    }

    private fun cacheWholeFile(
        uri: Uri,
        cacheKey: String,
        songId: String,
        stillWanted: () -> Boolean,
        reportProgress: Boolean = true,
    ) {
        val cache = org.squidify.android.App.graph().mediaCache(context)
        val dataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setUserAgent("Squirtify/1.7.4")
                    .setConnectTimeoutMs(10_000)
                    .setReadTimeoutMs(15_000)
                    .setAllowCrossProtocolRedirects(true),
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        val spec = DataSpec.Builder()
            .setUri(uri)
            .setKey(cacheKey)
            .build()
        val writer = CacheWriter(dataSource, spec, null) { contentLength, bytesCached, _ ->
            if (!stillWanted()) throw CancellationException("prefetch superseded")
            if (reportProgress && contentLength > 0) {
                val fraction = (bytesCached.toFloat() / contentLength).coerceIn(0f, 1f)
                if (fraction >= 0.999f) {
                    // all bytes are in: complete the bar immediately instead of
                    // letting a slow CacheWriter return hold it hostage at "100%"
                    _bufferProgress.value = _bufferProgress.value - songId
                } else {
                    _bufferProgress.value = _bufferProgress.value + (songId to fraction)
                }
            }
        }
        writer.cache()
    }

    // ------------------------------------------------------------ crossfade

    private fun crossfadeMs(): Long = settings.state().value.crossfadeSec * 1000L

    /** Next queue index, or null at the end when repeat-all is off. */
    private fun nextIndexAfter(cur: Int): Int? {
        val q = _queue.value
        if (q.isEmpty()) return null
        val current = q.getOrNull(cur) ?: return null
        val next = cur + 1
        return when (_repeatMode.value) {
            REPEAT_TRACK -> null
            REPEAT_ALBUM -> {
                val album = albumIdentity(current)
                (next until q.size).firstOrNull { albumIdentity(q[it]) == album }
                    ?: q.indexOfFirst { albumIdentity(it) == album }.takeIf { it >= 0 }
            }
            REPEAT_QUEUE -> if (next < q.size) next else 0
            else -> next.takeIf { it < q.size }
        }
    }

    private suspend fun maybeCrossfadeTick() {
        val ms = crossfadeMs()
        if (ms <= 0 || cfState != CF_NONE) return
        val p = activeP ?: return
        val repeatOne = _repeatMode.value == Player.REPEAT_MODE_ONE
        // repeat-one loops stay seamless unless the user asked for crossfaded
        // loop seams explicitly
        if (repeatOne && !settings.state().value.crossfadeRepeatLoops) return
        if (!p.playWhenReady || p.playbackState != Player.STATE_READY) return
        // transcoded streams report no duration — fall back to track metadata
        val dur = currentSong?.durationMs?.takeIf { it > 0 }
            ?: _durationMs.value.takeIf { it > 0 }
            ?: (if (timeOffsetBaseMs == 0L) p.duration.takeIf { it > 0 } else null)
            ?: return
        val remaining = dur - absolutePositionOf(p)
        if (remaining in 1..ms) {
            beginCrossfade(remaining)
        }
    }

    /**
     * True crossfade: the standby player starts playing the incoming track
     * from zero while the active one fades out — real overlap. The incoming
     * track is normally the next queue item; with repeat-one and the
     * "crossfade on repeat" option on, the SAME track fades into itself.
     */
    private suspend fun beginCrossfade(remainingMs: Long) {
        if (cfState != CF_NONE) return // never two overlaps at once
        val active = activeP ?: return
        val standby = standbyP ?: return
        val cur = _currentIndex.value
        // The loop decision comes from the APP-level repeat state — the
        // player instance's own repeatMode can briefly desync from it after
        // crossfade player swaps, and taking the "next track" path at a
        // repeat-one seam made the title jump to the next track while the
        // looping track kept playing.
        val repeatOneLoop = _repeatMode.value == Player.REPEAT_MODE_ONE &&
            settings.state().value.crossfadeRepeatLoops
        AppLog.d(
            TAG,
            "Crossfade arming: repeatMode=${repeatModeName(_repeatMode.value)} loopSeam=$repeatOneLoop remaining=${remainingMs}ms",
        )
        val nextIdx = if (repeatOneLoop) cur else nextIndexAfter(cur)
        if (nextIdx == null) {
            // last track of the queue, repeat off — just fade its tail out
            AppLog.d(TAG, "Crossfade: fading out the tail of the last track")
            cfState = CF_FADEOUT_ONLY
            crossfadeRampJob = rampVolumes(1f, 0f, 0f, 0f, remainingMs)
            return
        }
        val incoming = _queue.value.getOrNull(nextIdx) ?: return

        // arm BEFORE setMediaItems: the standby's first transition callback is
        // the launch of the overlap itself and must not finalize anything
        cfStandbyArmed = true
        val suffix = _queue.value.drop(nextIdx)
        standby.setMediaItems(suffix.map { buildMediaItem(it) }, 0, 0L)
        standby.repeatMode = active.repeatMode
        standby.shuffleModeEnabled = false
        standby.volume = 0f
        standby.prepare()
        standby.playWhenReady = true

        cfOutgoingIdx = cur
        cfIncomingIdx = nextIdx
        cfOutgoingBaseMs = timeOffsetBaseMs
        cfState = CF_OVERLAP
        // the standby (now the position source) starts its item from zero
        timeOffsetBaseMs = 0L

        // UI and notification follow the incoming track from the moment it starts
        _currentIndex.value = nextIdx
        _positionMs.value = 0L
        _durationMs.value = incoming.durationMs
        scrobbledCurrent = false
        nextPrefetched = false
        history.record(incoming)
        requestAudioFocus()

        crossfadeRampJob = rampVolumes(1f, 0f, 0f, 1f, remainingMs)
        rebindSessionIfNeeded(standby)
        AppLog.i(
            TAG,
            "Crossfade → '${incoming.title}' overlaps for ${remainingMs}ms" +
                if (repeatOneLoop) " (repeat-one loop seam)" else "",
        )
    }

    private fun rampVolumes(
        fromActive: Float,
        toActive: Float,
        fromStandby: Float,
        toStandby: Float,
        durationMs: Long,
    ): Job = scope.launch {
        val steps = (durationMs / 50L).coerceAtLeast(1)
        var s = 0
        while (s < steps && isActive) {
            val t = (s + 1f) / steps
            activeP?.volume = fromActive + (toActive - fromActive) * t
            standbyP?.volume = fromStandby + (toStandby - fromStandby) * t
            delay(50)
            s++
        }
    }

    /**
     * The outgoing track ended naturally: adopt the standby player (already
     * mid-track) as the active one. Re-inserting the queue prefix keeps the
     * player playlist identical to [_queue] without interrupting playback.
     */
    private fun finalizeCrossfade() {
        if (cfState != CF_OVERLAP) return
        val oldActive = activeP ?: return
        val standby = standbyP ?: return
        crossfadeRampJob?.cancel()
        crossfadeRampJob = null
        cfStandbyArmed = false
        standby.volume = 1f

        val prefix = _queue.value.take(cfIncomingIdx).map { buildMediaItem(it) }
        if (prefix.isNotEmpty()) standby.addMediaItems(0, prefix)

        oldActive.stop()
        oldActive.clearMediaItems()
        oldActive.volume = 1f

        activeP = standby
        standbyP = oldActive
        swapListeners(oldActive, standby)
        _currentIndex.value = standby.currentMediaItemIndex
        _positionMs.value = timeOffsetBaseMs + standby.currentPosition
        _durationMs.value = currentSong?.durationMs ?: standby.duration.coerceAtLeast(0L)
        cfState = CF_NONE
        cfIncomingIdx = -1
        cfOutgoingIdx = -1
        _isPlaying.value = standby.isPlaying
        saveQueue()
        refreshTicker()
        maybePrefetchNext()
        AppLog.i(TAG, "Crossfade complete → '${currentSong?.title}'")
    }

    /**
     * The user acted on the incoming player mid-overlap (skip/pause from the
     * media notification): adopt the standby immediately at whatever state
     * it is in and drop the outgoing track.
     */
    private fun finalizeCrossfadeInterrupted() {
        if (cfState != CF_OVERLAP) return
        val oldActive = activeP ?: return
        val standby = standbyP ?: return
        crossfadeRampJob?.cancel()
        crossfadeRampJob = null
        cfStandbyArmed = false

        val prefix = _queue.value.take(cfIncomingIdx).map { buildMediaItem(it) }
        if (prefix.isNotEmpty()) standby.addMediaItems(0, prefix)

        oldActive.stop()
        oldActive.clearMediaItems()
        oldActive.volume = 1f

        activeP = standby
        standbyP = oldActive
        swapListeners(oldActive, standby)
        _currentIndex.value = standby.currentMediaItemIndex
        _positionMs.value = timeOffsetBaseMs + standby.currentPosition
        _durationMs.value = currentSong?.durationMs ?: standby.duration.coerceAtLeast(0L)
        cfState = CF_NONE
        cfIncomingIdx = -1
        cfOutgoingIdx = -1
        _isPlaying.value = standby.isPlaying
        scrobbledCurrent = false
        nextPrefetched = false
        currentSong?.let { history.record(it) }
        saveQueue()
        refreshTicker()
        AppLog.i(TAG, "Crossfade interrupted by user → '${currentSong?.title}'")
    }

    /**
     * After a crossfade the two players trade roles — their listeners must
     * trade roles too, otherwise the AUDIBLE player ends up with the silent
     * standby listener (whose callbacks are crossfade-only) and every later
     * pause/play becomes invisible to the UI: the button froze on a triangle
     * while the music kept toggling. [activeListener] always tracks whichever
     * player is audible.
     */
    private fun swapListeners(oldActive: Player, newActive: Player) {
        oldActive.removeListener(activeListener)
        oldActive.removeListener(standbyListener)
        newActive.removeListener(activeListener)
        newActive.removeListener(standbyListener)
        newActive.addListener(activeListener)
        oldActive.addListener(standbyListener)
    }

    /** Drops a running crossfade and goes back to plain playback of the old track. */
    private fun cancelCrossfade() {
        if (cfState == CF_NONE) return
        crossfadeRampJob?.cancel()
        crossfadeRampJob = null
        cfStandbyArmed = false
        if (cfState == CF_OVERLAP) {
            standbyP?.let { st ->
                st.stop()
                st.clearMediaItems()
                st.volume = 1f
            }
            // the UI rolls back to the track that is actually audible
            if (cfOutgoingIdx >= 0 && cfOutgoingIdx < _queue.value.size) {
                _currentIndex.value = cfOutgoingIdx
                scrobbledCurrent = false
            }
            // restore the outgoing item's position base
            timeOffsetBaseMs = cfOutgoingBaseMs
            rebindSessionIfNeeded(activeP)
            activeP?.volume = 1f
        } else {
            activeP?.volume = 1f
        }
        cfState = CF_NONE
        cfIncomingIdx = -1
        cfOutgoingIdx = -1
        refreshTicker()
    }

    private fun rebindSessionIfNeeded(p: Player?) {
        val player = p ?: return
        if (sessionBoundTo === player) return
        sessionBoundTo = player
        sessionRebinder?.invoke(player)
    }

    private fun cancelPendingSwitch() {
        pendingSwitch?.cancel()
        pendingSwitch = null
    }

    // ------------------------------------------------------------ foreground

    // NOTE: the app NEVER calls startForegroundService() itself anymore.
    // Media3's MediaSessionService owns the foreground lifecycle: it promotes
    // the service to foreground exactly when playback is ongoing and demotes
    // it on pause. Calling ContextCompat.startForegroundService() manually
    // (as earlier versions did) creates a 5-second obligation to call
    // startForeground() that Media3 only fulfils once audio is actually
    // playing — any slow resolve, playback error or pause in between made the
    // system kill the app with ForegroundServiceDidNotStartInTimeException.

    // ------------------------------------------------------------ ticker

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                val src = positionSource()
                if (src != null) {
                    _positionMs.value = absolutePositionOf(src)
                    val metadataDuration = currentSong?.durationMs?.takeIf { it > 0 }
                    val streamDuration = if (timeOffsetBaseMs == 0L) src.duration.takeIf { it > 0 } else null
                    val dur = metadataDuration ?: streamDuration
                    if (dur != null) _durationMs.value = dur
                    maybeScrobble(src)
                    maybeCrossfadeTick()
                    maybePrefetchNext()
                    throttledSave()
                }
                // ~10 fps position updates: the seek bar glides instead of jumping
                delay(100)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun refreshTicker() {
        val playing = activeP?.isPlaying == true ||
            (cfState == CF_OVERLAP && standbyP?.isPlaying == true)
        if (playing) startTicker() else stopTicker()
    }

    // ------------------------------------------------------------ scrobble

    private fun maybeScrobble(src: Player) {
        if (scrobbledCurrent) return
        val song = currentSong ?: return
        if (song.source != org.squidify.android.data.DownloadSource.SQUIDIFY) return
        val dur = _durationMs.value
        val pos = _positionMs.value
        val threshold = if (dur > 0) dur / 2 else 240_000L
        val limit = minOf(threshold, 240_000L)
        if (limit > 0 && pos >= limit) {
            scrobbledCurrent = true
            scope.launch {
                try {
                    subsonic.scrobble(song.id)
                    AppLog.d(TAG, "Scrobbled '${song.title}'")
                } catch (e: Exception) {
                    AppLog.w(TAG, "Scrobble failed for '${song.title}': ${e.message}")
                }
            }
        }
    }

    private var lastSave = 0L

    private fun throttledSave() {
        val now = System.currentTimeMillis()
        if (now - lastSave > 10_000) {
            lastSave = now
            saveQueue()
        }
    }

    private fun scheduleLocalQueueSave() {
        localQueueSaveJob?.cancel()
        localQueueSaveJob = scope.launch {
            delay(500)
            val songs = _queue.value
            if (songs.isEmpty()) clearLocalQueue() else persistLocalQueue(songs)
        }
    }

    private fun saveQueue() {
        localQueueSaveJob?.cancel()
        val songsNow = _queue.value
        if (songsNow.isEmpty()) {
            clearLocalQueue()
        } else {
            // Local persistence is synchronous and private to this installation.
            // KHInsider signed direct URLs are deliberately never serialized.
            persistLocalQueue(songsNow)
        }
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1500)
            val songs = _queue.value
            if (songs.isEmpty()) return@launch
            val squidIds = songs.filter { it.source == org.squidify.android.data.DownloadSource.SQUIDIFY }
            if (squidIds.isEmpty()) return@launch
            try {
                subsonic.savePlayQueue(
                    squidIds.map { it.id },
                    currentSong?.takeIf { it.source == org.squidify.android.data.DownloadSource.SQUIDIFY }?.id,
                    _positionMs.value,
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun persistLocalQueue(songs: List<PlayerSong>) {
        runCatching {
            val items = JSONArray()
            songs.forEach { song ->
                items.put(JSONObject().apply {
                    put("id", song.id)
                    put("title", song.title)
                    put("artist", song.artist)
                    put("album", song.album)
                    put("durationMs", song.durationMs)
                    put("coverArtId", song.coverArtId)
                    // Do not persist cover URLs: some providers embed auth
                    // query parameters. Stable artwork IDs are sufficient.
                    put("source", song.source.name)
                    put("albumId", song.albumId)
                    put("artistId", song.artistId)
                    put("khiTrackUrl", song.khiTrackUrl)
                    put("starred", song.starred)
                })
            }
            val root = JSONObject()
                .put("version", 1)
                .put("songs", items)
                .put("baseOrder", JSONArray().apply {
                    baseQueue.forEach { put("${it.source.name}|${it.id}") }
                })
                .put("currentIndex", _currentIndex.value)
                .put("positionMs", _positionMs.value.coerceAtLeast(0L))
                .put("shuffle", _shuffle.value)
                .put("repeatMode", _repeatMode.value)
            queuePrefs.edit().putString(KEY_LOCAL_QUEUE, root.toString()).apply()
        }.onFailure { AppLog.w(TAG, "Could not persist local queue: ${it.message}") }
    }

    private fun clearLocalQueue() {
        queuePrefs.edit().remove(KEY_LOCAL_QUEUE).apply()
    }

    private data class LocalQueueSnapshot(
        val songs: List<PlayerSong>,
        val currentIndex: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int,
        val baseOrder: List<String>,
    )

    private fun readLocalQueue(): LocalQueueSnapshot? = runCatching {
        val raw = queuePrefs.getString(KEY_LOCAL_QUEUE, null) ?: return null
        val root = JSONObject(raw)
        val items = root.optJSONArray("songs") ?: return null
        val songs = buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val source = runCatching {
                    org.squidify.android.data.DownloadSource.valueOf(item.optString("source", "SQUIDIFY"))
                }.getOrDefault(org.squidify.android.data.DownloadSource.SQUIDIFY)
                add(PlayerSong(
                    id = id,
                    title = item.optString("title", id),
                    artist = item.optString("artist").takeIf { it.isNotBlank() },
                    album = item.optString("album").takeIf { it.isNotBlank() },
                    durationMs = item.optLong("durationMs").coerceAtLeast(0L),
                    coverArtId = item.optString("coverArtId").takeIf { it.isNotBlank() },
                    coverUrl = null,
                    source = source,
                    albumId = item.optString("albumId").takeIf { it.isNotBlank() },
                    artistId = item.optString("artistId").takeIf { it.isNotBlank() },
                    khiTrackUrl = item.optString("khiTrackUrl").takeIf { it.isNotBlank() },
                    // Never restore a signed KHInsider direct URL; resolve it again.
                    khiDirectUrl = null,
                    starred = item.optBoolean("starred", false),
                ))
            }
        }
        if (songs.isEmpty()) return null
        val baseItems = root.optJSONArray("baseOrder")
        val baseOrder = buildList {
            if (baseItems != null) {
                for (i in 0 until baseItems.length()) {
                    baseItems.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
        LocalQueueSnapshot(
            songs = songs,
            currentIndex = root.optInt("currentIndex", 0).coerceIn(0, songs.lastIndex),
            positionMs = root.optLong("positionMs", 0L).coerceAtLeast(0L),
            shuffle = root.optBoolean("shuffle", false),
            repeatMode = root.optInt("repeatMode", REPEAT_OFF).coerceIn(REPEAT_OFF, REPEAT_QUEUE),
            baseOrder = baseOrder,
        )
    }.onFailure { AppLog.w(TAG, "Could not read local queue: ${it.message}") }.getOrNull()

    private suspend fun tryRestoreQueue() {
        if (_queue.value.isNotEmpty()) return
        val local = readLocalQueue()
        if (local != null) {
            // Re-resolve KHInsider page URLs because signed media URLs are
            // short-lived and are intentionally excluded from local storage.
            val restored = try {
                resolveKhiQueue(local.songs, forceRefresh = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.w(TAG, "Some restored KHInsider links could not be refreshed: ${error.message}")
                local.songs
            }
            if (_queue.value.isNotEmpty() || pendingPlayRequest != null) return
            val p = activeP ?: return
            _queue.value = restored
            _shuffle.value = local.shuffle
            if (local.baseOrder.isNotEmpty()) {
                val remaining = restored.toMutableList()
                baseQueue = local.baseOrder.mapNotNull { key ->
                    val index = remaining.indexOfFirst { "${it.source.name}|${it.id}" == key }
                    if (index >= 0) remaining.removeAt(index) else null
                } + remaining
            } else {
                baseQueue = restored
            }
            _repeatMode.value = local.repeatMode
            p.repeatMode = when (local.repeatMode) {
                REPEAT_TRACK -> Player.REPEAT_MODE_ONE
                REPEAT_QUEUE -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            standbyP?.repeatMode = p.repeatMode
            p.setMediaItems(restored.map { buildMediaItem(it) }, local.currentIndex, local.positionMs)
            p.prepare()
            _currentIndex.value = local.currentIndex
            _positionMs.value = local.positionMs
            _durationMs.value = restored.getOrNull(local.currentIndex)?.durationMs ?: 0L
            AppLog.i(TAG, "Restored local queue: ${restored.size} tracks, index ${local.currentIndex}, pos ${local.positionMs} ms")
            return
        }
        try {
            val state = subsonic.getPlayQueue() ?: run {
                AppLog.d(TAG, "No server play queue to restore")
                return
            }
            // The user may have picked a track WHILE the server queue was
            // loading — their freshly started playback must never be replaced
            // by the restored one (this was the "quick tap at launch gets
            // stomped" bug).
            if (_queue.value.isNotEmpty() || pendingPlayRequest != null) {
                AppLog.d(TAG, "User already started playback — skipping queue restore")
                return
            }
            val songs = state.songs.map { PlayerSong.from(it) }
            if (songs.isEmpty()) return
            val p = activeP ?: return
            AppLog.i(TAG, "Restoring queue: ${songs.size} tracks, index ${state.currentIndex}, pos ${state.positionMs} ms")
            baseQueue = songs
            _queue.value = songs
            p.setMediaItems(songs.map { buildMediaItem(it) }, state.currentIndex, state.positionMs)
            p.prepare()
            // stay paused; user taps play
            _currentIndex.value = state.currentIndex
            _positionMs.value = state.positionMs
            _durationMs.value = songs.getOrNull(state.currentIndex)?.durationMs ?: 0L
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not restore play queue: ${e.message}")
        }
    }

    // ------------------------------------------------------------ replay gain
    // Removed in 1.3.1: volumes are always unity, the servers deliver
    // ready-to-play audio and normalization is not wanted.

    companion object {
        private const val TAG = "Player"
        private const val KEY_LOCAL_QUEUE = "queue_snapshot_v1"
        const val REPEAT_OFF = 0
        const val REPEAT_TRACK = 1
        const val REPEAT_ALBUM = 2
        const val REPEAT_QUEUE = 3
        private const val CF_NONE = 0
        private const val CF_OVERLAP = 1
        private const val CF_FADEOUT_ONLY = 2

        // -------------------------------------------------- log helpers

        fun stateName(state: Int?): String = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            null -> "?"
            else -> "?$state"
        }

        fun playWhenReadyReasonName(reason: Int): String = when (reason) {
            1 -> "audio becoming noisy (headphones unplugged)"
            2 -> "audio focus lost"
            3 -> "remote (notification/assistant)"
            4 -> "user request"
            5 -> "end of media item"
            else -> "?$reason"
        }

        fun transitionReasonName(reason: Int): String = when (reason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto (next track)"
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "playlist changed"
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
            else -> "?$reason"
        }

        fun discontinuityReasonName(reason: Int): String = when (reason) {
            0 -> "auto transition"
            1 -> "seek"
            2 -> "seek adjustment"
            3 -> "old-period transition"
            4 -> "item removed"
            5 -> "internal"
            else -> "?$reason"
        }

        fun repeatModeName(mode: Int): String = when (mode) {
            Player.REPEAT_MODE_OFF -> "OFF"
            Player.REPEAT_MODE_ONE -> "ONE"
            Player.REPEAT_MODE_ALL -> "ALL"
            else -> "?$mode"
        }
    }
}
