package org.squidify.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ThemeMode { AUTO, DARK, LIGHT }

@Serializable
enum class LyricsProvider { AUTO, SERVER, LRC_RED, LRCLIB, NETEASE, QQ, OVH }

data class Settings(
    val streamingQuality: StreamQuality = StreamQuality.ORIGINAL,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    val dynamicColor: Boolean = true,
    val khiEnabled: Boolean = true,
    val khiQuality: KhiQuality = KhiQuality.BEST,
    /** Disk cache for streamed audio, MB (10..4096). Applied on player restart. */
    val audioCacheMb: Int = 512,
    /** Disk cache for cover art / images, MB (10..4096). Applied immediately. */
    val imageCacheMb: Int = 128,
    /** Crossfade between tracks, seconds (0 = off, 1..12). */
    val crossfadeSec: Int = 0,
    /** Crossfade the loop seam when a single track repeats. */
    val crossfadeRepeatLoops: Boolean = false,
    /** Download the whole track before playback starts (no chunked streaming). */
    val fullBuffer: Boolean = false,
    /** Default lyrics provider. */
    val lyricsProvider: LyricsProvider = LyricsProvider.AUTO,
    /** Long titles/artists scroll side-to-side instead of being ellipsized. */
    val scrollingText: Boolean = false,
    /** Fully close the app (stop playback + exit) when swiped from Recents. */
    val exitOnTaskRemoved: Boolean = false,
    /** Hide the artist line on Now Playing and show only the album. */
    val hideNowPlayingArtist: Boolean = true,
    /** Manual offline mode; automatic network loss is detected separately. */
    val offlineMode: Boolean = false,
    /** Very light feedback on explicit Play/Pause taps; disabled by default. */
    val hapticsEnabled: Boolean = false,
)

@Serializable
private data class SettingsState(
    val streamingQuality: String? = null,
    val themeMode: String? = null,
    val dynamicColor: Boolean? = null,
    val designSystem: String? = null, // legacy v1.x key, ignored
    val glassEffects: Boolean? = null, // legacy v1.x key, ignored
    val khiEnabled: Boolean? = null,
    val khiQuality: String? = null,
    val replayGain: String? = null, // legacy key, ignored since 1.3.1
    val replayGainPreamp: Float? = null, // legacy key, ignored since 1.3.1
    val searchHistory: List<String> = emptyList(),
    val audioCacheMb: Int? = null,
    val imageCacheMb: Int? = null,
    val crossfadeSec: Int? = null,
    val crossfadeRepeatLoops: Boolean? = null,
    val fullBuffer: Boolean? = null,
    val lyricsProvider: String? = null,
    val scrollingText: Boolean? = null,
    val exitOnTaskRemoved: Boolean? = null,
    val hideNowPlayingArtist: Boolean? = null,
    val offlineMode: Boolean? = null,
    val hapticsEnabled: Boolean? = null,
)

/**
 * Settings + search-history persistence. A tiny atomic JSON store (kept in
 * Squirtify/UserData when the storage folder is granted) exposes a Flow-based
 * API without DataStore variant-resolution issues.
 */
class SettingsRepository(
    @Suppress("unused") private val context: android.content.Context,
    private val storage: UserStorage,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val storeFileName = "settings.json"

    private val defaults = Settings()

    private val _state = MutableStateFlow(defaults)
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())

    private var stateOnDisk: SettingsState = SettingsState()

    val settings: Flow<Settings> = _state.asStateFlow()
    val searchHistory: Flow<List<String>> = _searchHistory.asStateFlow()

    private val ioScope = CoroutineScope(Dispatchers.IO)

    init {
        load()
    }

    fun state(): StateFlow<Settings> = _state.asStateFlow()

    fun startCollection(@Suppress("UNUSED_PARAMETER") scope: CoroutineScope) {
        // no-op: state is loaded eagerly and already a StateFlow
    }

    /** Re-reads the store (e.g. after the storage folder was granted). */
    fun reload() = load()

    @Synchronized
    private fun load() {
        try {
            val text = storage.readUserData(storeFileName)
            if (text != null) {
                stateOnDisk = json.decodeFromString(SettingsState.serializer(), text)
            }
        } catch (_: Exception) {
            stateOnDisk = SettingsState()
        }
        _state.value = stateOnDisk.toSettings()
        _searchHistory.value = stateOnDisk.searchHistory
    }

    private fun SettingsState.toSettings() = Settings(
        streamingQuality = streamingQuality?.let { runCatching { StreamQuality.valueOf(it) }.getOrNull() }
            ?: defaults.streamingQuality,
        themeMode = themeMode?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: defaults.themeMode,
        dynamicColor = dynamicColor ?: defaults.dynamicColor,
        khiEnabled = khiEnabled ?: defaults.khiEnabled,
        khiQuality = khiQuality?.let { runCatching { KhiQuality.valueOf(it) }.getOrNull() } ?: defaults.khiQuality,
        audioCacheMb = (audioCacheMb ?: defaults.audioCacheMb).coerceIn(10, 4096),
        imageCacheMb = (imageCacheMb ?: defaults.imageCacheMb).coerceIn(10, 4096),
        crossfadeSec = (crossfadeSec ?: defaults.crossfadeSec).coerceIn(0, 12),
        crossfadeRepeatLoops = crossfadeRepeatLoops ?: defaults.crossfadeRepeatLoops,
        fullBuffer = fullBuffer ?: defaults.fullBuffer,
        lyricsProvider = lyricsProvider?.let { runCatching { LyricsProvider.valueOf(it) }.getOrNull() }
            ?: defaults.lyricsProvider,
        scrollingText = scrollingText ?: defaults.scrollingText,
        exitOnTaskRemoved = exitOnTaskRemoved ?: defaults.exitOnTaskRemoved,
        hideNowPlayingArtist = hideNowPlayingArtist ?: defaults.hideNowPlayingArtist,
        offlineMode = offlineMode ?: defaults.offlineMode,
        hapticsEnabled = hapticsEnabled ?: defaults.hapticsEnabled,
    )

    @Synchronized
    private fun mutate(transform: (SettingsState) -> SettingsState) {
        stateOnDisk = transform(stateOnDisk)
        _state.value = stateOnDisk.toSettings()
        _searchHistory.value = stateOnDisk.searchHistory
        persist()
    }

    private fun persist() {
        ioScope.launch {
            try {
                storage.writeUserData(
                    storeFileName,
                    json.encodeToString(SettingsState.serializer(), stateOnDisk),
                )
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------- setters

    suspend fun setStreamingQuality(q: StreamQuality) = mutate { it.copy(streamingQuality = q.name) }
    suspend fun setThemeMode(m: ThemeMode) = mutate { it.copy(themeMode = m.name) }
    suspend fun setDynamicColor(b: Boolean) = mutate { it.copy(dynamicColor = b) }
    suspend fun setKhiEnabled(b: Boolean) = mutate { it.copy(khiEnabled = b) }
    suspend fun setKhiQuality(q: KhiQuality) = mutate { it.copy(khiQuality = q.name) }
    suspend fun setAudioCacheMb(mb: Int) = mutate { it.copy(audioCacheMb = mb.coerceIn(10, 4096)) }
    suspend fun setImageCacheMb(mb: Int) = mutate { it.copy(imageCacheMb = mb.coerceIn(10, 4096)) }
    suspend fun setCrossfadeSec(sec: Int) = mutate { it.copy(crossfadeSec = sec.coerceIn(0, 12)) }
    suspend fun setCrossfadeRepeatLoops(b: Boolean) = mutate { it.copy(crossfadeRepeatLoops = b) }
    suspend fun setFullBuffer(b: Boolean) = mutate { it.copy(fullBuffer = b) }
    suspend fun setLyricsProvider(p: LyricsProvider) = mutate { it.copy(lyricsProvider = p.name) }
    suspend fun setScrollingText(b: Boolean) = mutate { it.copy(scrollingText = b) }
    suspend fun setExitOnTaskRemoved(b: Boolean) = mutate { it.copy(exitOnTaskRemoved = b) }
    suspend fun setHideNowPlayingArtist(b: Boolean) = mutate { it.copy(hideNowPlayingArtist = b) }
    suspend fun setOfflineMode(b: Boolean) = mutate { it.copy(offlineMode = b) }
    suspend fun setHapticsEnabled(b: Boolean) = mutate { it.copy(hapticsEnabled = b) }

    // ------------------------------------------------------------ searches

    suspend fun addSearchTerm(term: String) {
        val t = term.trim()
        if (t.isEmpty()) return
        mutate { s ->
            s.copy(
                searchHistory = (listOf(t) + s.searchHistory.filter { it != t }).take(24),
            )
        }
    }

    suspend fun clearSearchHistory() = mutate { it.copy(searchHistory = emptyList()) }
}
