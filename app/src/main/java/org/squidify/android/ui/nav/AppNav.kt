package org.squidify.android.ui.nav

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import org.squidify.android.App
import org.squidify.android.data.KhiAlbumSummary
import org.squidify.android.data.Song
import org.squidify.android.downloads.downloadStatusFor
import org.squidify.android.player.PlayerSong
import org.squidify.android.ui.components.KhiDownloadQualityDialog
import org.squidify.android.ui.components.MiniPlayer
import org.squidify.android.ui.components.NowPlayingScreen
import org.squidify.android.ui.components.SheetController
import org.squidify.android.ui.components.SquidifyDownloadQualityDialog
import org.squidify.android.ui.screens.AlbumScreen
import org.squidify.android.ui.screens.ArtistScreen
import org.squidify.android.ui.screens.DownloadsScreen
import org.squidify.android.ui.screens.GenreScreen
import org.squidify.android.ui.screens.HomeScreen
import org.squidify.android.ui.screens.KhiAlbumScreen
import org.squidify.android.ui.screens.LibraryScreen
import org.squidify.android.ui.screens.PlaylistScreen
import org.squidify.android.ui.screens.SearchScreen
import org.squidify.android.ui.screens.SettingsScreen
import org.squidify.android.ui.theme.SqIcons
import org.squidify.android.util.AppLog

object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val ALBUM = "album/{id}?t={t}"
    const val ARTIST = "artist/{id}"
    const val GENRE = "genre/{name}"
    const val PLAYLIST = "playlist/{id}"
    const val KHI_ALBUM = "khi/{url}?t={t}"

    fun album(id: String, highlightTrackId: String? = null): String =
        "album/$id" + (highlightTrackId?.let { "?t=${Uri.encode(it)}" } ?: "")

    fun artist(id: String) = "artist/$id"
    fun genre(name: String) = "genre/${Uri.encode(name)}"
    fun playlist(id: String) = "playlist/$id"

    fun khiAlbum(url: String, highlightTrackUrl: String? = null): String =
        "khi/${Uri.encode(url)}" + (highlightTrackUrl?.let { "?t=${Uri.encode(it)}" } ?: "")
}

private data class BottomDest(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/** Material 3 fade-through: quick fade out, fade + scale in. */
private val NavEnterTransition: androidx.compose.animation.EnterTransition =
    fadeIn(tween(90)) + scaleIn(tween(90), initialScale = 0.96f)
private val NavExitTransition: androidx.compose.animation.ExitTransition = fadeOut(tween(70))

@Composable
fun AppNavHost(onExitApp: () -> Unit) {
    val navController = rememberNavController()
    val graph = App.graph()
    val player = graph.player
    val scope = rememberCoroutineScope()

    // Only low-frequency state is collected HERE (track change, downloads,
    // favourites). The hot 10 Hz position ticker used to be collected at this
    // level and recomposed the whole app root — including the animating
    // Now-Playing sheet — ten times per second, which read as ~20 fps.
    val queue by player.queue.collectAsState()
    val idx by player.currentIndex.collectAsState()
    val downloads by graph.downloads.jobs.collectAsState()

    // The Now-Playing sheet: ONE float drives its open/close glide and its
    // finger drags, and it is read exclusively inside a graphicsLayer in
    // NowPlayingScreen — recomposition-free, layout-free motion.
    val sheet = remember { SheetController(scope) }

    var pendingDownload by remember { mutableStateOf<PlayerSong?>(null) }
    val favSongIds by graph.favorites.songIds.collectAsState()
    val current = queue.getOrNull(idx)

    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // Root-level back: when nothing is left to pop (Home), leave the app.
    // Registered before the NavHost so NavHost's own handler takes priority
    // while the back stack is deeper than the start destination.
    BackHandler(enabled = currentRoute == Routes.HOME && !sheet.composed) {
        AppLog.d("UI", "Back at Home — moving app to background")
        onExitApp()
    }

    val destinations = listOf(
        BottomDest(Routes.HOME, "Home", Icons.Filled.Home),
        BottomDest(Routes.SEARCH, "Search", Icons.Filled.Search),
        BottomDest(Routes.LIBRARY, "Library", SqIcons.Disc),
        BottomDest(Routes.DOWNLOADS, "Downloads", SqIcons.Download),
        BottomDest(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
    )

    // ---- navigation helpers for Now Playing deep links

    fun openAlbumFor(song: PlayerSong, highlight: Boolean) {
        when (song.source) {
            org.squidify.android.data.DownloadSource.SQUIDIFY -> {
                val albumId = song.albumId ?: return
                navController.navigate(Routes.album(albumId, if (highlight) song.id else null))
            }
            org.squidify.android.data.DownloadSource.KHINSIDER -> {
                // derive the album page URL from the track page URL
                val trackUrl = song.khiTrackUrl ?: song.khiDirectUrl ?: return
                val albumUrl = trackUrl.substringBeforeLast('/')
                if (albumUrl.isNotBlank()) {
                    navController.navigate(Routes.khiAlbum(albumUrl, if (highlight) trackUrl else null))
                }
            }
        }
        sheet.close()
    }

    fun openArtistFor(song: PlayerSong) {
        val artistId = song.artistId ?: return
        navController.navigate(Routes.artist(artistId))
        sheet.close()
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    if (current != null) {
                        MiniPlayer(
                            song = current,
                            onTogglePlay = { player.togglePlayPause() },
                            onNext = { player.next() },
                            onOpen = { sheet.open() },
                        )
                        androidx.compose.foundation.layout.Spacer(
                            Modifier.padding(bottom = 4.dp),
                        )
                    }
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp,
                    ) {
                        destinations.forEach { dest ->
                            NavigationBarItem(
                                selected = currentRoute == dest.route,
                                onClick = {
                                    if (currentRoute != dest.route) {
                                        // Coming from a stacked screen (album, artist,
                                        // playlist…): PUSH the tab so Android back
                                        // returns to that screen — back must mean back.
                                        // Switching between tabs keeps the usual
                                        // reset-to-tab-root behaviour.
                                        val fromStackedScreen =
                                            currentRoute != null && currentRoute !in destinations.map { it.route }
                                        navController.navigate(dest.route) {
                                            if (fromStackedScreen) {
                                                launchSingleTop = true
                                            } else {
                                                popUpTo(Routes.HOME) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    }
                                },
                                icon = {
                                    Icon(
                                        dest.icon,
                                        contentDescription = dest.label,
                                        modifier = Modifier.size(24.dp),
                                    )
                                },
                                label = { Text(dest.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                enterTransition = { NavEnterTransition },
                exitTransition = { NavExitTransition },
                popEnterTransition = { NavEnterTransition },
                popExitTransition = { NavExitTransition },
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        onAlbumClick = { navController.navigate(Routes.album(it.id)) },
                        onKhiAlbumClick = { url ->
                            navController.navigate(Routes.khiAlbum(url))
                        },
                    )
                }
                composable(Routes.SEARCH) {
                    SearchScreen(
                        onAlbumClick = { navController.navigate(Routes.album(it.id)) },
                        onArtistClick = { navController.navigate(Routes.artist(it.id)) },
                        onKhiAlbumClick = { summary: KhiAlbumSummary ->
                            navController.navigate(Routes.khiAlbum(summary.url))
                        },
                    )
                }
                composable(Routes.LIBRARY) {
                    LibraryScreen(
                        onAlbumClick = { navController.navigate(Routes.album(it.id)) },
                        onArtistClick = { navController.navigate(Routes.artist(it.id)) },
                        onGenreClick = { navController.navigate(Routes.genre(it.value)) },
                        onPlaylistClick = { navController.navigate(Routes.playlist(it.id)) },
                    )
                }
                composable(Routes.DOWNLOADS) { DownloadsScreen() }
                composable(Routes.SETTINGS) { SettingsScreen() }

                composable(
                    Routes.ALBUM,
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("t") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { entry ->
                    val id = entry.arguments?.getString("id") ?: return@composable
                    val highlight = entry.arguments?.getString("t")
                    AlbumScreen(
                        albumId = id,
                        onBack = { navController.popBackStack() },
                        onArtistClick = { navController.navigate(Routes.artist(it)) },
                        highlightTrackId = highlight,
                    )
                }
                composable(
                    Routes.ARTIST,
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    val id = entry.arguments?.getString("id") ?: return@composable
                    ArtistScreen(
                        artistId = id,
                        onBack = { navController.popBackStack() },
                        onAlbumClick = { navController.navigate(Routes.album(it.id)) },
                    )
                }
                composable(
                    Routes.GENRE,
                    arguments = listOf(navArgument("name") { type = NavType.StringType }),
                ) { entry ->
                    val name = entry.arguments?.getString("name") ?: return@composable
                    GenreScreen(
                        genreName = name,
                        onBack = { navController.popBackStack() },
                        onAlbumClick = { navController.navigate(Routes.album(it.id)) },
                    )
                }
                composable(
                    Routes.PLAYLIST,
                    arguments = listOf(navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    val id = entry.arguments?.getString("id") ?: return@composable
                    PlaylistScreen(
                        playlistId = id,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    Routes.KHI_ALBUM,
                    arguments = listOf(
                        navArgument("url") { type = NavType.StringType },
                        navArgument("t") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { entry ->
                    val url = entry.arguments?.getString("url") ?: return@composable
                    val highlight = entry.arguments?.getString("t")
                    KhiAlbumScreen(
                        albumUrl = url,
                        onBack = { navController.popBackStack() },
                        highlightTrackUrl = highlight,
                    )
                }
            }
        }

        // Full-screen now-playing overlay. Composed while it is open OR still
        // animating; the motion itself lives in NowPlayingScreen's root
        // graphicsLayer reading sheet.progress — no AnimatedVisibility, so a
        // mid-flight reopen simply reverses the same glide (content is never
        // torn down and re-created mid-animation).
        if (sheet.composed) {
            // Back closes the overlay first — never the whole app.
            BackHandler { sheet.close() }
            NowPlayingScreen(
                sheet = sheet,
                onClose = { sheet.close() },
                onToggleHeart = { song ->
                    // favourites are local: they work offline and for KHInsider too
                    val nowFav = graph.favorites.toggleSong(song)
                    AppLog.i("Favorites", "${if (nowFav) "Added" else "Removed"} '${song.title}'")
                },
                isHearted = current?.id in favSongIds,
                onDownload = { song -> pendingDownload = song },
                downloadStatus = current?.let { downloadStatusFor(it, downloads) },
                lyricsProvider = { song, provider ->
                    if (song.source == org.squidify.android.data.DownloadSource.SQUIDIFY) {
                        graph.lyrics.forSong(
                            Song(
                                id = song.id,
                                title = song.title,
                                artist = song.artist,
                                album = song.album,
                                duration = (song.durationMs / 1000).toInt(),
                            ),
                            provider,
                        )
                    } else null
                },
                onOpenAlbum = { song, highlight -> openAlbumFor(song, highlight) },
                onOpenArtist = { song -> openArtistFor(song) },
            )
        }
    }

    // Download-quality ask for the Now Playing download button
    pendingDownload?.let { song ->
        if (song.source == org.squidify.android.data.DownloadSource.SQUIDIFY) {
            val s = Song(
                id = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                duration = (song.durationMs / 1000).toInt(),
                // cover art + album id must survive the conversion: the
                // download job bakes its cover URL from them, and without
                // them downloaded Squidify tracks used to show no artwork
                coverArt = song.coverArtId,
                albumId = song.albumId,
            )
            SquidifyDownloadQualityDialog(
                song = s,
                onDismiss = { pendingDownload = null },
                onConfirm = { q ->
                    graph.downloads.enqueueSquidify(s, q)
                    pendingDownload = null
                },
            )
        } else {
            val tUrl = song.khiTrackUrl
            if (tUrl == null) {
                pendingDownload = null
            } else {
                KhiDownloadQualityDialog(
                    trackTitle = song.title,
                    onDismiss = { pendingDownload = null },
                    onConfirm = { fmt ->
                        graph.downloads.enqueueKhi(
                            albumName = song.album ?: "KHInsider",
                            trackTitle = song.title,
                            trackUrl = tUrl,
                            format = fmt,
                            qualityLabel = fmt.uppercase(),
                            coverUrl = song.coverUrl,
                        )
                        pendingDownload = null
                    },
                )
            }
        }
    }
}
