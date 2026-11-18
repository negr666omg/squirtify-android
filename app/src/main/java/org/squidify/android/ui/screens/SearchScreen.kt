package org.squidify.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.squidify.android.App
import org.squidify.android.data.Album
import org.squidify.android.data.Artist
import org.squidify.android.data.KhiAlbumSummary
import org.squidify.android.data.Song
import org.squidify.android.player.PlayerSong
import org.squidify.android.ui.components.ArtistRow
import org.squidify.android.ui.components.CoverArt
import org.squidify.android.ui.components.EmptyState
import org.squidify.android.ui.components.LoadingShelf
import org.squidify.android.ui.components.SectionHeader
import org.squidify.android.ui.components.SongRow
import org.squidify.android.ui.components.coverArtUrl
import org.squidify.android.ui.screens.library.SongActionsHandler
import org.squidify.android.util.Fmt

/** Search source filter shown under the search bar. */
private enum class SearchSource { ALL, KHINSIDER, SQUIDIFY }

@Composable
fun SearchScreen(
    onAlbumClick: (Album) -> Unit,
    onArtistClick: (Artist) -> Unit,
    onKhiAlbumClick: (KhiAlbumSummary) -> Unit,
) {
    val graph = App.graph()
    val settings by graph.settings.state().collectAsState()

    var query by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(SearchSource.ALL) }
    var artists by remember { mutableStateOf<List<Artist>?>(null) }
    var albums by remember { mutableStateOf<List<Album>?>(null) }
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var khiAlbums by remember { mutableStateOf<List<KhiAlbumSummary>?>(null) }
    var khiTotal by remember { mutableStateOf<Int?>(null) }
    var khiPage by remember { mutableStateOf(1) }
    var khiLoadingMore by remember { mutableStateOf(false) }
    var khiError by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var loadMoreJob by remember { mutableStateOf<Job?>(null) }
    var searchGeneration by remember { mutableStateOf(0) }

    val songActions = remember { SongActionsHandler() }
    songActions.Render()
    val favSongIds by graph.favorites.songIds.collectAsState()
    val player = graph.player
    val playerQueue by player.queue.collectAsState()
    val playerIndex by player.currentIndex.collectAsState()
    val playerBuffering by player.isBuffering.collectAsState()

    LaunchedEffect(Unit) {
        graph.settings.searchHistory.collect { history = it }
    }

    fun runSearch(q: String) {
        searchJob?.cancel()
        loadMoreJob?.cancel()
        khiPage = 1
        khiTotal = null
        val generation = ++searchGeneration
        if (q.isBlank()) {
            artists = null
            albums = null
            songs = null
            khiAlbums = null
            khiError = null
            searching = false
            khiLoadingMore = false
            return
        }

        val selectedSource = source
        val includeKhi = settings.khiEnabled
        searching = true
        searchJob = scope.launch {
            try {
                delay(300)
                if (generation != searchGeneration) return@launch

                if (selectedSource != SearchSource.KHINSIDER) {
                    try {
                        val (a, al, s) = graph.subsonic.search3(q)
                        if (generation != searchGeneration) return@launch
                        artists = a
                        albums = al
                        songs = s
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (generation != searchGeneration) return@launch
                        artists = emptyList()
                        albums = emptyList()
                        songs = emptyList()
                    }
                } else {
                    artists = null
                    albums = null
                    songs = null
                }

                if (includeKhi && selectedSource != SearchSource.SQUIDIFY) {
                    try {
                        val res = graph.khi.searchAlbums(q, page = 1)
                        if (generation != searchGeneration) return@launch
                        khiAlbums = res.items
                        khiTotal = res.totalMatches
                        khiError = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        if (generation != searchGeneration) return@launch
                        khiAlbums = emptyList()
                        khiTotal = null
                        khiError = e.message
                    }
                } else {
                    khiAlbums = null
                    khiTotal = null
                    khiError = null
                }

                if (generation == searchGeneration) {
                    graph.settings.addSearchTerm(q)
                }
            } finally {
                // A cancelled older search must not clear the spinner for a
                // newer query that is still loading.
                if (generation == searchGeneration) searching = false
            }
        }
    }

    fun loadMoreKhi() {
        val q = query
        if (q.isBlank() || khiLoadingMore) return
        val generation = searchGeneration
        val nextPage = khiPage + 1
        khiLoadingMore = true
        loadMoreJob?.cancel()
        loadMoreJob = scope.launch {
            try {
                val res = graph.khi.searchAlbums(q, page = nextPage)
                if (generation != searchGeneration || q != query) return@launch
                val existing = khiAlbums ?: emptyList()
                val fresh = res.items.filter { candidate ->
                    existing.none { it.url == candidate.url }
                }
                khiAlbums = existing + fresh
                khiPage = nextPage
                khiTotal = res.totalMatches
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The already loaded pages remain usable; a later tap can retry.
            } finally {
                if (generation == searchGeneration) khiLoadingMore = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    runSearch(it)
                },
                placeholder = { Text("Search...") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            artists = null; albums = null; songs = null; khiAlbums = null
                        }) { Icon(Icons.Filled.Close, "Clear") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        // ---------- source toggle under the search bar
        if (query.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = source == SearchSource.ALL,
                        onClick = { source = SearchSource.ALL; runSearch(query) },
                        label = { Text("All") },
                    )
                    if (settings.khiEnabled) {
                        FilterChip(
                            selected = source == SearchSource.KHINSIDER,
                            onClick = { source = SearchSource.KHINSIDER; runSearch(query) },
                            label = { Text("KHInsider") },
                        )
                    }
                    FilterChip(
                        selected = source == SearchSource.SQUIDIFY,
                        onClick = { source = SearchSource.SQUIDIFY; runSearch(query) },
                        label = { Text("Squidify") },
                    )
                }
            }
        }

        if (query.isBlank()) {
            if (history.isNotEmpty()) {
                item { SectionHeader("Recent searches", actionLabel = "Clear") { scope.launch { graph.settings.clearSearchHistory() } } }
                item {
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(history.size) { i ->
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.clickable {
                                    query = history[i]
                                    runSearch(history[i])
                                },
                            ) {
                                Text(
                                    text = history[i],
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    EmptyState("Search the Squidify library" + if (settings.khiEnabled) " and KHInsider" else "")
                }
            }
        } else {
            // ---------- KHInsider albums (list, not carousel)
            if (settings.khiEnabled && source != SearchSource.SQUIDIFY) {
                item {
                    SectionHeader(
                        title = "KHInsider",
                        actionLabel = khiTotal?.let { "$it albums" },
                        onAction = null,
                    )
                }
                when {
                    khiAlbums == null -> item { LoadingShelf(height = 80.dp) }
                    khiAlbums!!.isEmpty() -> item {
                        Text(
                            text = khiError?.let { "KHInsider unavailable: $it" } ?: "No matching albums",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    else -> {
                        val list = khiAlbums!!
                        // In ALL mode show a preview of 5 with "See all"
                        val shown = if (source == SearchSource.ALL && list.size > 5) list.take(5) else list
                        // index-suffixed keys guard against repeated URLs in results
                        items(shown.size, key = { "khi-$it-${shown[it].url}" }) { i ->
                            KhiAlbumRow(summary = shown[i], onClick = { onKhiAlbumClick(shown[i]) })
                        }
                        if (source == SearchSource.ALL && list.size > 5) {
                            item {
                                SeeAllRow(label = "See all ${list.size} KHInsider albums") {
                                    source = SearchSource.KHINSIDER
                                }
                            }
                        }
                        if (source == SearchSource.KHINSIDER &&
                            khiTotal != null && list.size < khiTotal!!
                        ) {
                            item {
                                SeeAllRow(
                                    label = if (khiLoadingMore) "Loading more…" else "Load more (${list.size} / $khiTotal)",
                                ) { loadMoreKhi() }
                            }
                        }
                    }
                }
            }

            // ---------- Squidify albums (list, not carousel)
            if (source != SearchSource.KHINSIDER && !albums.isNullOrEmpty()) {
                item { SectionHeader("Squidify") }
                val list = albums!!
                val shown = if (source == SearchSource.ALL && list.size > 5) list.take(5) else list
                items(shown.size, key = { "sq-alb-${shown[it].id}" }) { i ->
                    SquidifyAlbumRow(album = shown[i], onClick = { onAlbumClick(shown[i]) })
                }
                if (source == SearchSource.ALL && list.size > 5) {
                    item {
                        SeeAllRow(label = "See all ${list.size} Squidify albums") {
                            source = SearchSource.SQUIDIFY
                        }
                    }
                }
            }

            // ---------- Artists
            if (source != SearchSource.KHINSIDER && !artists.isNullOrEmpty()) {
                item { SectionHeader("Artists") }
                items(artists!!.size, key = { "art-${artists!![it].id}" }) { i ->
                    ArtistRow(
                        artist = artists!![i],
                        onClick = { onArtistClick(artists!![i]) },
                        coverUrl = artists!![i].coverArtUrl(),
                    )
                }
            }

            // ---------- Songs
            if (source != SearchSource.KHINSIDER && !songs.isNullOrEmpty()) {
                item { SectionHeader("Tracks") }
                items(songs!!.size, key = { j -> songs!![j].id }) { i ->
                    val song = songs!![i]
                    val isCurrent = playerQueue.getOrNull(playerIndex)?.id == song.id
                    SongRow(
                        // hearts are local favourites
                        song = song.copy(starred = if (song.id in favSongIds) "now" else null),
                        onClick = {
                            scope.launch {
                                val list = songs!!.map { PlayerSong.from(it) }
                                player.play(list, i)
                            }
                        },
                        coverUrl = SongCover(song),
                        isCurrent = isCurrent,
                        isLoading = isCurrent && playerBuffering,
                        showAlbum = true,
                        trailingText = Fmt.duration(song.duration?.toLong()),
                        onHeartClick = {
                            scope.launch {
                                org.squidify.android.ui.components.toggleSongHeart(song)
                            }
                        },
                        onMenuClick = { songActions.show(song) },
                    )
                }
            }

            if (searching) {
                item { LoadingShelf(height = 60.dp) }
            }

            if (!searching && artists != null && albums != null && songs != null &&
                artists!!.isEmpty() && albums!!.isEmpty() && songs!!.isEmpty() &&
                (khiAlbums == null || khiAlbums!!.isEmpty())
            ) {
                item { EmptyState("Nothing found for \"$query\"") }
            }
        }
    }
}

@Composable
private fun SeeAllRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = ">",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** KHInsider album row: square cover, upgraded to the full-quality artwork. */
@Composable
private fun KhiAlbumRow(summary: KhiAlbumSummary, onClick: () -> Unit) {
    val graph = App.graph()
    // The search API only hands out tiny icons; fetch the album's real cover
    // in the background (cached server-side in KhiClient) and swap it in.
    var coverUrl by remember(summary.url) {
        mutableStateOf(graph.khi.imageUrl(summary.icon))
    }
    LaunchedEffect(summary.url) {
        if (summary.icon != null) {
            try {
                val album = graph.khi.album(summary.url)
                album.coverUrl?.let { coverUrl = graph.khi.imageUrl(it) }
            } catch (_: Exception) {
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            url = coverUrl,
            modifier = Modifier
                .width(72.dp)
                .aspectRatio(1f),
            corner = 8.dp,
            contentDescription = summary.title,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = summary.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(summary.year, summary.albumType, "KHInsider").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Squidify album row: square 480px cover. */
@Composable
private fun SquidifyAlbumRow(album: Album, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            url = album.coverArtUrl(480),
            modifier = Modifier
                .width(72.dp)
                .aspectRatio(1f),
            corner = 8.dp,
            contentDescription = album.name,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(album.artist, album.year?.toString(), album.songCount?.let { Fmt.songCount(it) })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
