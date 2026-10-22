@file:OptIn(ExperimentalMaterial3Api::class)

package dev.usenetstream.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.usenetstream.MainViewModel
import dev.usenetstream.Tab
import dev.usenetstream.engine.newznab.TitleGroup
import dev.usenetstream.history.ResumePolicy
import dev.usenetstream.history.WatchEntry
import kotlinx.coroutines.delay

/** One poster in a row, plus what its long-press sheet offers. */
data class Tile(
    val key: String,
    val title: String,
    val coverUrl: String?,
    val progress: Float? = null,
    val caption: String? = null,
    val year: Int? = null,
    val onClick: () -> Unit,
    val onPlay: (() -> Unit)? = null,
    val onRemove: (() -> Unit)? = null,
)

@Composable
fun HomeScreen(vm: MainViewModel) {
    val history by vm.history.collectAsState()
    val myList by vm.myList.collectAsState()
    val feeds by vm.feeds.collectAsState()
    val ott by vm.ott.collectAsState()
    var sheet by remember { mutableStateOf<Tile?>(null) }
    val listState = rememberLazyListState()

    val continueWatching = ResumePolicy.continueWatching(history)
    val cards = ResumePolicy.cards(history)
    val heroes = feeds.trending.filter { it.coverUrl != null }.take(5)

    fun entryTile(e: WatchEntry, cw: Boolean) = Tile(
        key = e.showKey ?: e.id,
        title = e.title,
        coverUrl = e.coverUrl,
        progress = if (e.finished) null else e.progress,
        caption = when {
            e.isEpisode && e.finished -> "${e.code} ✓ · Next up"
            e.isEpisode -> "${e.code} · ${formatLeft(e.positionMs, e.durationMs)}"
            cw -> formatLeft(e.positionMs, e.durationMs)
            e.finished -> "Watched"
            else -> null
        },
        onClick = { if (cw) vm.resume(e) else vm.openKey(e.showKey ?: e.id, e.title, e.coverUrl) },
        onPlay = { vm.resume(e) },
        onRemove = { vm.forget(e) },
    )

    fun groupTile(g: TitleGroup) = Tile(g.key, g.title, g.coverUrl, year = g.year, onClick = { vm.openTitle(g) })

    PullToRefreshBox(
        isRefreshing = feeds.loading && feeds.loadedAt > 0,
        onRefresh = { vm.loadFeeds(force = true); vm.loadOtt(force = true) },
        modifier = Modifier.fillMaxSize().background(Ink),
    ) {
        val used = HashSet<String>().apply { heroes.forEach { add(it.key) } }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = navInset() + 16.dp)) {
            item(key = "hero") {
                when {
                    heroes.isNotEmpty() -> HeroCarousel(heroes, myList.map { it.key }.toSet(), listState, vm)
                    feeds.loading -> ShimmerBox(Modifier.fillMaxWidth().height(520.dp))
                    else -> EmptyHero(vm.indexer != null, feeds.error, vm)
                }
            }
            posterRow("Continue Watching", continueWatching.map { entryTile(it, cw = true) }, wide = true, used = used) { sheet = it }
            posterRow("My List", myList.map { e ->
                Tile(e.key, e.title, e.coverUrl, year = e.year, onClick = { vm.openKey(e.key, e.title, e.coverUrl) },
                    onRemove = { vm.toggleMyList(e.key, e.title, e.coverUrl, e.year) })
            }, used = used) { sheet = it }
            item(key = "update") { UpdateCard(dev.usenetstream.update.Updater.get(androidx.compose.ui.platform.LocalContext.current)) }
            ottHomeRows(vm, ott)
            if (feeds.loading && feeds.trending.isEmpty()) {
                repeat(3) { i -> item(key = "shimmer$i") { ShimmerRow() } }
            }
            posterRow("Trending Now", feeds.trending.drop(heroes.size).map(::groupTile), numbered = true, used = used) { sheet = it }
            feeds.moreLike?.let { (seed, gs) -> posterRow("Because you watched $seed", gs.map(::groupTile), used = used) { sheet = it } }
            posterRow("New Movies", feeds.newMovies.map(::groupTile), used = used) { sheet = it }
            posterRow("New TV Episodes", feeds.newTv.map(::groupTile), used = used) { sheet = it }
            posterRow("Top Rated", feeds.topRated.map(::groupTile), used = used) { sheet = it }
            posterRow("Recently Watched", cards.map { entryTile(it, cw = false) }, used = used) { sheet = it }
        }

        // Wordmark bar fades in as the hero scrolls away.
        val dividerColor = Divider
        val barAlpha by remember { derivedStateOf { if (listState.firstVisibleItemIndex > 0) 1f else (listState.firstVisibleItemScrollOffset / 600f).coerceIn(0f, 1f) } }
        Row(
            Modifier
                .fillMaxWidth()
                // Over the hero: a soft gradient. Once scrolled: a solid frosted bar so rows never show through the logo.
                .background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.35f * (1 - barAlpha)), Color.Transparent)))
                .background(Ink.copy(alpha = 0.94f * barAlpha))
                .drawBehind { if (barAlpha > 0.01f) drawLine(dividerColor.copy(alpha = barAlpha), Offset(0f, size.height), Offset(size.width, size.height), 1f) }
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Wordmark(Modifier.weight(1f))
        }
    }

    sheet?.let { t -> TileSheet(t, vm.inMyList(t.key), vm, onDismiss = { sheet = null }) }
}

// ---------------------------------------------------------------- hero

@Composable
private fun HeroCarousel(heroes: List<TitleGroup>, listKeys: Set<String>, listState: androidx.compose.foundation.lazy.LazyListState, vm: MainViewModel) {
    val pager = rememberPagerState { heroes.size }
    // Auto-advance; pauses while the user drags.
    LaunchedEffect(pager, heroes.size) {
        while (true) {
            delay(8_000)
            if (!pager.isScrollInProgress && heroes.size > 1) pager.animateScrollToPage((pager.currentPage + 1) % heroes.size, animationSpec = tween(700))
        }
    }
    Box(Modifier.fillMaxWidth().height(560.dp)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val g = heroes[page]
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    // Parallax: the art scrolls at half speed and fades as the page scrolls up.
                    val off = if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset.toFloat() else size.height
                    translationY = off * 0.5f
                    alpha = 1f - (off / size.height).coerceIn(0f, 1f) * 0.6f
                },
            ) {
                Poster(g.coverUrl, g.title, Modifier.fillMaxSize().sharedPoster(g.key).pressable(pressedScale = 0.99f) { vm.openTitle(g) })
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x55000000), 0.4f to Color.Transparent, 0.75f to Ink.copy(alpha = 0.7f), 1f to Ink)))
        val g = heroes[pager.currentPage.coerceIn(0, heroes.lastIndex)]
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedContent(g, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(250)) }, label = "hero-text") { cur ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("#${heroes.indexOf(cur) + 1} TRENDING", color = Red, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelLarge)
                    Text(cur.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, maxLines = 2)
                    Text(
                        listOfNotNull(cur.year?.toString(), cur.score?.let { "★ $it" }, cur.genres.take(3).joinToString(" • ").ifEmpty { null }).joinToString("   "),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                ListToggle(g.key in listKeys) { vm.toggleMyList(g.key, g.title, g.coverUrl, g.year) }
                PlayButton("Play", Modifier.width(150.dp)) { if (g.isShow) vm.openTitle(g) else vm.playMovie(g) }
                IconAction(Icons.Filled.Info, "Info") { vm.openTitle(g) }
            }
            Spacer(Modifier.height(12.dp))
            PagerDots(heroes.size, pager.currentPage)
        }
    }
}

@Composable
private fun PagerDots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val w by androidx.compose.animation.core.animateDpAsState(if (i == current) 18.dp else 6.dp, label = "dot")
            Box(Modifier.height(6.dp).width(w).clip(CircleShape).background(if (i == current) Fg else Fg.copy(alpha = 0.3f)))
        }
    }
}

@Composable
private fun EmptyHero(hasIndexer: Boolean, error: String?, vm: MainViewModel) {
    Column(
        Modifier.fillMaxWidth().height(460.dp).statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Watch anything on Usenet", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            error ?: if (hasIndexer) "Search your indexer and it plays straight from your server." else "Add an indexer in Settings to browse.",
            color = if (error != null) Red else Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        when {
            error != null -> PlayButton("Retry", icon = null) { vm.loadFeeds(force = true) }
            hasIndexer -> PlayButton("Search", icon = null) { vm.selectTab(Tab.SEARCH) }
            else -> PlayButton("Settings", icon = null) { vm.selectTab(Tab.SETTINGS) }
        }
    }
}

// ---------------------------------------------------------------- rows

private fun LazyListScope.posterRow(
    title: String,
    tiles: List<Tile>,
    wide: Boolean = false,
    numbered: Boolean = false,
    used: MutableSet<String>,
    onLongPress: (Tile) -> Unit,
) {
    if (tiles.isEmpty()) return
    // A title can sit in several rows; only its first poster may fly into the details page.
    val shared = tiles.associate { it.key to used.add(it.key) }
    item(key = "title:$title") { RowTitle(title) }
    item(key = "row:$title") {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(if (numbered) 4.dp else 8.dp)) {
            items(tiles, key = { it.key }) { t ->
                val i = tiles.indexOf(t)
                Row(verticalAlignment = Alignment.Bottom) {
                    if (numbered && i < 10) {
                        Text(
                            "${i + 1}",
                            color = Muted,
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.displayLarge.copy(
                                drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f),
                            ),
                            modifier = Modifier.padding(end = 2.dp).graphicsLayer { alpha = 0.9f },
                        )
                    }
                    Column(Modifier.width(if (wide) 128.dp else 112.dp).pressable(onLongClick = { onLongPress(t) }, onClick = t.onClick)) {
                        PosterCard(t.coverUrl, t.title, t.progress, width = if (wide) 128.dp else 112.dp, sharedKey = t.key.takeIf { shared[it] == true })
                        t.caption?.let {
                            Text(it, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShimmerRow() {
    Column(Modifier.padding(start = 16.dp, top = 20.dp)) {
        ShimmerBox(Modifier.width(140.dp).height(16.dp))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(4) { ShimmerBox(Modifier.width(112.dp).height(168.dp)) }
        }
    }
}

@Composable
fun RowTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 8.dp),
    )
}

// ---------------------------------------------------------------- buttons + sheet

@Composable
fun PlayButton(label: String, modifier: Modifier = Modifier, icon: ImageVector? = Icons.Filled.PlayArrow, onClick: () -> Unit) {
    GlassButton(onClick, modifier) {
        icon?.let { Icon(it, null, Modifier.size(26.dp)) }
        // 18sp Bold: WCAG "large text", so white on Netflix red passes.
        Text(label, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(horizontal = 6.dp))
    }
}

@Composable
fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.pressable(onClick = onClick).padding(6.dp)) {
        Icon(icon, label, tint = Fg, modifier = Modifier.size(26.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Fg)
    }
}

/** "+ My List" that morphs into "✓". */
@Composable
fun ListToggle(inList: Boolean, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .semantics(mergeDescendants = true) { contentDescription = if (inList) "Remove from My List" else "Add to My List" }
            .pressable { haptic(Haptic.CONFIRM); onClick() }
            .padding(6.dp),
    ) {
        AnimatedContent(inList, transitionSpec = {
            (fadeIn(tween(200)) + androidx.compose.animation.scaleIn(tween(250), initialScale = 0.5f)) togetherWith fadeOut(tween(120))
        }, label = "list-icon") { on ->
            Icon(if (on) Icons.Filled.Check else Icons.Filled.Add, null, tint = Fg, modifier = Modifier.size(26.dp))
        }
        Text("My List", style = MaterialTheme.typography.labelSmall, color = Fg)
    }
}

@Composable
private fun TileSheet(t: Tile, inList: Boolean, vm: MainViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Card) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(t.coverUrl, t.title, Modifier.width(64.dp).height(96.dp))
            Column(Modifier.padding(start = 14.dp)) {
                Text(t.title, style = MaterialTheme.typography.titleLarge)
                t.caption?.let { Text(it, color = Muted) }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Actions at the bottom of the screen: the thumb's home.
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            t.onPlay?.let { play -> SheetAction(Icons.Filled.PlayArrow, "Play") { onDismiss(); play() } }
            SheetAction(Icons.Filled.Info, "Details") { onDismiss(); t.onClick() }
            SheetAction(if (inList) Icons.Filled.Check else Icons.Filled.Add, if (inList) "Remove from My List" else "Add to My List") {
                onDismiss(); vm.toggleMyList(t.key, t.title, t.coverUrl, t.year)
            }
            t.onRemove?.let { rm -> SheetAction(Icons.Filled.Delete, "Remove from row") { onDismiss(); rm() } }
        }
    }
}

@Composable
fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(pressedScale = 0.98f, onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Fg)
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 18.dp))
    }
}
