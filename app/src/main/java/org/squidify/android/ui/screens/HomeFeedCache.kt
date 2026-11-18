package org.squidify.android.ui.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.squidify.android.data.Album
import org.squidify.android.data.SubsonicClient

/**
 * Process-lifetime cache for the home shelves.
 *
 * A NavHost destination can be disposed when switching tabs. Keeping the feed
 * here prevents a return to Home from issuing the same five requests again.
 */
internal data class HomeFeedState(
    val community: List<Album>? = null,
    val discover: List<Album>? = null,
    val frequent: List<Album>? = null,
    val newest: List<Album>? = null,
    val explore: List<Album>? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

internal object HomeFeedCache {
    private val lock = Any()
    private val _state = MutableStateFlow(HomeFeedState())
    val state: StateFlow<HomeFeedState> = _state.asStateFlow()

    private var request: Job? = null
    private var generation = 0L

    fun ensureLoaded(scope: CoroutineScope, subsonic: SubsonicClient) {
        start(scope, subsonic, force = false)
    }

    fun reload(scope: CoroutineScope, subsonic: SubsonicClient) {
        start(scope, subsonic, force = true)
    }

    private fun start(scope: CoroutineScope, subsonic: SubsonicClient, force: Boolean) {
        val token: Long
        synchronized(lock) {
            if (!force && (_state.value.loaded || request?.isActive == true)) return
            if (force) request?.cancel()
            generation += 1
            token = generation
            _state.value = _state.value.copy(loading = true, error = null)
            request = scope.launch {
                try {
                    val lists = coroutineScope {
                        listOf(
                            async { subsonic.getAlbumList2("recent", size = 20) },
                            async { subsonic.getAlbumList2("random", size = 20) },
                            async { subsonic.getAlbumList2("frequent", size = 20) },
                            async { subsonic.getAlbumList2("newest", size = 20) },
                            async { subsonic.getAlbumList2("random", size = 20) },
                        ).map { it.await() }
                    }
                    synchronized(lock) {
                        if (generation == token) {
                            _state.value = HomeFeedState(
                                community = lists[0],
                                discover = lists[1],
                                frequent = lists[2],
                                newest = lists[3],
                                explore = lists[4],
                                loaded = true,
                            )
                            request = null
                        }
                    }
                } catch (cancelled: CancellationException) {
                    synchronized(lock) {
                        if (generation == token) {
                            _state.value = _state.value.copy(loading = false)
                            request = null
                        }
                    }
                    throw cancelled
                } catch (error: Exception) {
                    synchronized(lock) {
                        if (generation == token) {
                            _state.value = _state.value.copy(
                                loading = false,
                                error = error.message ?: "Connection failed",
                            )
                            request = null
                        }
                    }
                }
            }
        }
    }
}
