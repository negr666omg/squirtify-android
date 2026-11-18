package org.squidify.android.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.squidify.android.App
import org.squidify.android.MainActivity
import org.squidify.android.R
import org.squidify.android.util.AppLog

/**
 * Media3 session service. Owns TWO ExoPlayer instances (the active queue
 * player and the crossfade standby) and one MediaSession. The session is
 * re-bound to whichever player is currently audible, so the system media
 * notification / shade controls / scrobblers always follow the real audio.
 *
 * The shade notification carries two custom buttons next to the standard
 * prev/play/next controls: a favourite (heart) toggle for the current track
 * and a repeat-mode toggle. They arrive as custom session commands and are
 * handled by [SquidSessionCallback]; their icons/states are refreshed through
 * [MediaSession.setMediaButtonPreferences] whenever the track, the favourite
 * list or the repeat mode changes.
 *
 * Audio focus is managed centrally by [PlayerManager] (both players are
 * built with handleAudioFocus = false, otherwise the standby's focus
 * request would pause the active player mid-crossfade).
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var activePlayer: ExoPlayer? = null
    private var standbyPlayer: ExoPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val cache = App.graph().mediaCache(this)
        AppLog.i(TAG, "Playback service created (audio cache: ${App.graph().cacheSizeLabel()})")

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Squirtify/1.7.4")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)

        // Serves cached bytes when available, otherwise fetches and stores them.
        // Cache keys come from MediaItem.customCacheKey (stable per song+quality),
        // because the raw stream URL carries a random salt on every build.
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setCacheKeyFactory { dataSpec ->
                dataSpec.key ?: run {
                    val uri = dataSpec.uri
                    if (uri.host?.endsWith("squidify.org") == true && uri.path?.startsWith("/rest/stream") == true) {
                        val id = uri.getQueryParameter("id")
                        if (id != null) {
                            "sq-$id-${uri.getQueryParameter("format") ?: "orig"}-${uri.getQueryParameter("maxBitRate") ?: "0"}"
                        } else uri.toString()
                    } else uri.toString()
                }
            }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val plainFactory = DefaultDataSource.Factory(this, httpFactory)

        val dataSourceFactory = DataSource.Factory {
            CachingOrPlainDataSource(
                cached = cacheFactory.createDataSource(),
                plain = plainFactory.createDataSource(),
            )
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        fun newPlayer(): ExoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(15_000)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()

        val active = newPlayer()
        val standby = newPlayer()
        activePlayer = active
        standbyPlayer = standby

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val session = MediaSession.Builder(this, active)
            .setSessionActivity(sessionActivity)
            .setCallback(SquidSessionCallback())
            .build()
        mediaSession = session

        // Explicit MediaStyle notification provider: channel, compact controls,
        // play/pause buttons — this is what appears in the shade's media area.
        // The small icon is the Squirtify squid (top-left of the notification).
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(PLAYBACK_CHANNEL_ID)
                .setChannelName(R.string.playback_channel)
                .build()
                .also { it.setSmallIcon(R.drawable.ic_stat_squirt) },
        )

        // THE link that makes the shade controls exist.
        //
        // Media3 only starts managing the media notification (MediaStyle
        // token, play/pause/next/prev buttons, artwork, foreground promotion
        // with type mediaPlayback) once the session is ADDED to the service —
        // normally that happens automatically when a MediaController binds
        // through onGetSession(). This app deliberately drives the players
        // in-process via PlayerManager and never creates a MediaController,
        // so without an explicit addSession() nothing would ever bind: the
        // session stays orphaned, no player listener is registered and the
        // notification is never posted — the player is invisible in the
        // shade. addSession() makes the service create its internal
        // media-notification controller, which observes the player and
        // posts/updates the notification on every state or metadata change.
        addSession(session)

        // Initial state of the heart / repeat buttons in the notification.
        refreshMediaButtonPreferences()

        // Keep the notification's heart + repeat buttons in sync with the app:
        // any track change, favourite toggle (from ANY screen) or repeat-mode
        // change rebuilds the media button preferences and the system
        // re-renders the shade controls.
        val player = App.graph().player
        serviceScope.launch {
            combine(
                player.queue,
                player.currentIndex,
                player.repeatMode,
                App.graph().favorites.songIds,
            ) { _, _, _, _ -> refreshMediaButtonPreferences() }
                .collect { /* refresh already ran in the transform */ }
        }

        App.graph().player.attach(active, standby) { newActive ->
            // crossfade swap: the session (and notification) follow the audible player
            mediaSession?.setPlayer(newActive)
            AppLog.d(TAG, "MediaSession re-bound to player $newActive")
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * Builds the custom button list shown in the shade next to the standard
     * prev/play/pause/next controls (the provider always adds the transport
     * controls itself — these preferences only carry the extras).
     */
    private fun refreshMediaButtonPreferences() {
        val session = mediaSession ?: return
        val player = App.graph().player
        val song = player.currentSong
        val favourite = song != null && App.graph().favorites.isSongFavorite(song.id)
        val repeat = player.repeatMode.value

        val heart = CommandButton.Builder(
            if (favourite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
        )
            .setDisplayName(if (favourite) "Remove from favourites" else "Add to favourites")
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_FAVOURITE, Bundle.EMPTY))
            .setEnabled(song != null)
            .build()

        val repeatButton = CommandButton.Builder(
            when (repeat) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            },
        )
            .setDisplayName(
                when (repeat) {
                    Player.REPEAT_MODE_ONE -> "Repeat: one track"
                    Player.REPEAT_MODE_ALL -> "Repeat: all"
                    else -> "Repeat: off"
                },
            )
            .setSessionCommand(SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY))
            .build()

        session.setMediaButtonPreferences(listOf(heart, repeatButton))
    }

    /**
     * Accepts controller connections with the two custom session commands
     * (heart + repeat) and handles them when pressed in the shade, from a
     * headset, Bluetooth or Android Auto — the same code path for all.
     */
    private inner class SquidSessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = SessionCommands.Builder()
                .add(SessionCommand(ACTION_TOGGLE_FAVOURITE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            // controllers connecting after the preferences were first set need
            // the buttons re-sent, otherwise they see them as unavailable
            refreshMediaButtonPreferences()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_TOGGLE_FAVOURITE -> {
                    val song = App.graph().player.currentSong
                        ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                    val nowFav = App.graph().favorites.toggleSong(song)
                    AppLog.i(TAG, "Shade: '${song.title}' ${if (nowFav) "added to" else "removed from"} favourites")
                    refreshMediaButtonPreferences()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                ACTION_CYCLE_REPEAT -> {
                    val player = App.graph().player
                    val next = when (player.repeatMode.value) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    player.setRepeatMode(next)
                    AppLog.i(TAG, "Shade: repeat mode cycled")
                    refreshMediaButtonPreferences()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Optional full exit: some people don't want music surviving a swipe
        // away from Recents — pause everything and let the service die.
        if (App.graph().settings.state().value.exitOnTaskRemoved) {
            AppLog.i(TAG, "Task removed — closing the app completely (per setting)")
            pauseAllPlayersAndStopSelf()
            return
        }
        // media3 default: the task swipe only kills the service when playback
        // is not ongoing; while playing, the notification and audio survive.
        if (!isPlaybackOngoing()) {
            stopSelf()
        }
    }

    /** Android 14/15: mediaPlayback foreground quota exhausted — downgrade,
     * don't die (the session keeps working while the process lives). */
    override fun onTimeout(startId: Int) {
        AppLog.w(TAG, "Media playback foreground quota exhausted — continuing in background")
    }

    override fun onDestroy() {
        AppLog.i(TAG, "Playback service destroyed")
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
        // release BOTH players: the session only references whichever player
        // is currently bound to it — the crossfade standby would otherwise
        // leak together with its wake lock.
        activePlayer?.release()
        standbyPlayer?.release()
        activePlayer = null
        standbyPlayer = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlaybackSvc"
        private const val PLAYBACK_CHANNEL_ID = "squidify_playback"
        private const val ACTION_TOGGLE_FAVOURITE = "org.squidify.android.TOGGLE_FAVOURITE"
        private const val ACTION_CYCLE_REPEAT = "org.squidify.android.CYCLE_REPEAT"
    }
}

/**
 * Routes network URIs through the disk cache and local files directly,
 * so downloaded tracks are never duplicated into the cache.
 */
@UnstableApi
class CachingOrPlainDataSource(
    private val cached: DataSource,
    private val plain: DataSource,
) : DataSource {

    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        plain.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme
        val isNetwork = scheme == "http" || scheme == "https"
        active = if (isNetwork) cached else plain
        val len = active!!.open(dataSpec)
        AppLog.d(
            "PlaybackIO",
            "open ${dataSpec.uri.host ?: dataSpec.uri.scheme} key=${dataSpec.key ?: "-"} via ${if (isNetwork) "disk-cache" else "local"} — ${if (len >= 0) "$len bytes" else "unknown length"}",
        )
        return len
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
