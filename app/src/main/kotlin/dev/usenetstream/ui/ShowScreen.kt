@file:OptIn(ExperimentalMaterial3Api::class)

package dev.usenetstream.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.usenetstream.MainViewModel
import dev.usenetstream.Route
import dev.usenetstream.ShowState
import dev.usenetstream.engine.newznab.NzbResult
import dev.usenetstream.engine.tv.GuideEpisode
import dev.usenetstream.engine.tv.NextUp
import kotlin.math.abs

@Composable
fun ShowScreen(vm: MainViewModel, route: Route.Show) {
    val stateOrNull by vm.show.collectAsState()
    val myList by vm.myList.collectAsState()
    val s = stateOrNull?.takeIf { it.key == route.key } ?: ShowState(route.key, route.title, route.coverUrl, route.tvdbId)
    val guide = s.guide
    val seasons = guide?.seasons?.keys?.toList().orEmpty()
    val next = s.nextUp
    val nextEp = (next as? NextUp.Resume)?.episode ?: (next as? NextUp.Play)?.episode

    var season by rememberSaveable(route.key) { mutableIntStateOf(-1) }
    // Open on the season of the next-up episode.
    LaunchedEffect(seasons, nextEp?.season) {
        if (season == -1 && seasons.isNotEmpty()) season = nextEp?.season ?: seasons.first()
    }
    LaunchedEffect(season) { if (season > 0) vm.loadSeason(season) }
    var direction by remember { mutableIntStateOf(1) }
    var seasonMenu by remember { mutableStateOf<Int?>(null) }
    var releasesFor by remember { mutableStateOf<GuideEpisode?>(null) }
    val haptic = rememberHaptic()

    fun go(to: Int) {
        if (to == season || to !in seasons) return
        direction = if (to > season) 1 else -1
        season = to
        haptic(Haptic.TAP)
    }

    val haze = remember { dev.chrisbanes.haze.HazeState() }
    Box(Modifier.fillMaxSize().background(Ink)) {
        LazyColumn(Modifier.fillMaxSize().then(Modifier.hazeSource(haze)), contentPadding = PaddingValues(bottom = 120.dp)) {
            item { DetailsHeader(route.key, guide?.posterUrl ?: route.coverUrl, s.title) { vm.back() } }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(guide?.name ?: s.title, style = MaterialTheme.typography.headlineMedium)
                    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        guide?.premiered?.take(4)?.let { Text(it, color = Muted) }
                        if (seasons.isNotEmpty()) Text("${seasons.size} Season${if (seasons.size == 1) "" else "s"}", color = Muted)
                        guide?.let { Badge("${s.watchedCount}/${it.episodes.size} watched", Card2) }
                    }
                    guide?.genres?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" • "), color = Muted, style = MaterialTheme.typography.bodySmall) }
                    guide?.summary?.let { ExpandableText(it) }
                    s.guideError?.let { Text(it, color = Red, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
            if (guide == null && s.guideError == null) {
                item { Column(Modifier.padding(16.dp)) { repeat(5) { ShimmerBox(Modifier.fillMaxWidth().height(80.dp).padding(vertical = 4.dp)) } } }
            }
            if (seasons.isNotEmpty()) {
                item(key = "seasons") {
                    SeasonChips(seasons, season, s, onPick = ::go, onLongPress = { seasonMenu = it; haptic(Haptic.LONG) })
                }
                item(key = "episodes") {
                    AnimatedContent(
                        season,
                        transitionSpec = {
                            (slideInHorizontally(tween(280)) { it * direction / 3 } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(tween(280)) { -it * direction / 3 } + fadeOut(tween(160)))
                        },
                        label = "season",
                    ) { shown ->
                        // Swipe left/right anywhere on the list to change season.
                        Column(
                            Modifier.fillMaxWidth().pointerInput(seasons, shown) {
                                var total = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { total = 0f },
                                    onHorizontalDrag = { _, d -> total += d },
                                    onDragEnd = {
                                        val i = seasons.indexOf(shown)
                                        if (abs(total) > 120) go(seasons.getOrNull(if (total < 0) i + 1 else i - 1) ?: shown)
                                    },
                                )
                            },
                        ) {
                            val eps = guide!!.seasons[shown].orEmpty()
                            val rel = s.seasons[shown]
                            if (shown in s.loadingSeasons && rel == null) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(16.dp), color = Red, strokeWidth = 2.dp)
                                    Text("Finding releases for season $shown…", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                            rel?.packs?.takeIf { it.isNotEmpty() }?.let {
                                Text("${it.size} season pack${if (it.size == 1) "" else "s"} also available", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                            }
                            eps.forEach { e ->
                                key(e.code) {
                                    EpisodeRow(
                                        e, s,
                                        available = rel == null || rel.byEpisode.containsKey(e.number) || rel.packs.isNotEmpty(),
                                        isNext = e == nextEp,
                                        onPlay = { vm.playEpisode(e) },
                                        onMore = { releasesFor = e },
                                        onToggleWatched = { w -> vm.setEpisodesWatched(listOf(e), w) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        BottomActionBar(Modifier.align(Alignment.BottomCenter), blur = haze) {
            if (vm.phoneLink.tv.collectAsState().value != null) IconAction(Icons.Filled.PlayArrow, "TV") { vm.playOnTv(s.key, s.title, guide?.posterUrl ?: s.coverUrl) }
            ListToggle(myList.any { it.key == s.key }) { vm.toggleMyList(s.key, s.title, guide?.posterUrl ?: s.coverUrl, guide?.premiered?.take(4)?.toIntOrNull()) }
            val label = when (next) {
                is NextUp.Resume -> "Resume ${next.episode.code}"
                is NextUp.Play -> if (s.progress.isEmpty()) "Play ${next.episode.code}" else "Next ${next.episode.code}"
                NextUp.AllWatched -> if (guide?.episodes.isNullOrEmpty()) "No episodes" else "Watch again"
                null -> "Loading…"
            }
            PlayButton(label, Modifier.weight(1f)) {
                when (next) {
                    is NextUp.Resume -> vm.playEpisode(next.episode)
                    is NextUp.Play -> vm.playEpisode(next.episode)
                    NextUp.AllWatched -> guide?.episodes?.firstOrNull()?.let(vm::playEpisode)
                    null -> Unit
                }
            }
        }
    }

    seasonMenu?.let { sn ->
        val eps = guide?.seasons?.get(sn).orEmpty()
        ModalBottomSheet({ seasonMenu = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Card) {
            Text("Season $sn", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Column(Modifier.navigationBarsPadding().padding(vertical = 12.dp)) {
                SheetAction(Icons.Filled.Check, "Mark season as watched") { seasonMenu = null; vm.setEpisodesWatched(eps, true) }
                SheetAction(Icons.Filled.Close, "Mark season as unwatched") { seasonMenu = null; vm.setEpisodesWatched(eps, false) }
            }
        }
    }

    releasesFor?.let { e ->
        val rel = s.seasons[e.season]
        val list = rel?.byEpisode?.get(e.number).orEmpty() + rel?.packs.orEmpty()
        EpisodeReleasesSheet(vm, e, list, onDismiss = { releasesFor = null }) { r -> releasesFor = null; vm.playEpisode(e, r) }
    }
}

@Composable
private fun SeasonChips(seasons: List<Int>, selected: Int, s: ShowState, onPick: (Int) -> Unit, onLongPress: (Int) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected) { seasons.indexOf(selected).takeIf { it >= 0 }?.let { listState.animateScrollToItem(it) } }
    LazyRow(state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(seasons) { n ->
            val eps = s.guide?.seasons?.get(n).orEmpty()
            val done = eps.isNotEmpty() && eps.all { s.progress[it.code]?.finished == true }
            val on = n == selected
            val p = LocalPalette.current
            val bg by androidx.compose.animation.animateColorAsState(if (on) p.fg else p.glass, label = "chip")
            Text(
                if (done) "Season $n ✓" else "Season $n",
                color = if (on) p.bg else p.fg,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .glass(fill = bg, elevation = if (on) 4.dp else 1.dp)
                    .pressable(onLongClick = { onLongPress(n) }) { onPick(n) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    e: GuideEpisode,
    s: ShowState,
    available: Boolean,
    isNext: Boolean,
    onPlay: () -> Unit,
    onMore: () -> Unit,
    onToggleWatched: (Boolean) -> Unit,
) {
    val p = s.progress[e.code]
    val watched = p?.finished == true
    val haptic = rememberHaptic()
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.StartToEnd) { haptic(Haptic.CONFIRM); onToggleWatched(!watched) }
            false // snap back; the row itself stays
        },
        positionalThreshold = { it * 0.35f },
    )
    SwipeToDismissBox(
        swipe,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(if (watched) Color(0xFF3A3A3A) else Color(0xFF1E5631)).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (watched) Icons.Filled.Close else Icons.Filled.Check, null, tint = Fg)
                Text(if (watched) "Mark unwatched" else "Mark watched", modifier = Modifier.padding(start = 12.dp))
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (isNext) Card else Ink)
                .pressable(pressedScale = 0.98f, onClick = { if (available) onPlay() })
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .graphicsLayer { alpha = if (available) 1f else 0.45f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(132.dp).height(74.dp)) {
                Poster(e.stillUrl, e.code, Modifier.fillMaxSize())
                if (p != null && !watched) {
                    LinearProgressIndicator(
                        progress = { (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) },
                        color = Red, trackColor = Color(0x99000000), drawStopIndicator = {}, gapSize = 0.dp,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    )
                }
                if (watched) {
                    Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) { Badge("✓", Color(0xFF1E5631)) }
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("${e.number}. ${e.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(e.runtimeMin?.let { "${it}m" }, e.airdate, if (!available) "Not on indexer" else null, if (isNext) "Up next" else null).joinToString(" · "),
                    color = if (isNext) Red else Muted,
                    style = MaterialTheme.typography.labelSmall,
                )
                e.summary?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (available) IconButton(onMore) { Icon(Icons.Filled.MoreVert, "Releases", tint = Muted) }
        }
    }
}

@Composable
private fun EpisodeReleasesSheet(vm: MainViewModel, e: GuideEpisode, releases: List<NzbResult>, onDismiss: () -> Unit, onPlay: (NzbResult) -> Unit) {
    val rf by vm.releaseFilter.collectAsState()
    val failed by vm.failed.collectAsState()
    val ordered = orderReleases(vm, releases, rf, failed)
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Card) {
        Text("${e.code} · ${e.name}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn(Modifier.navigationBarsPadding()) {
            items(ordered, key = { it.nzbUrl }) { r ->
                ReleaseRow(r, best = r == ordered.firstOrNull(), failed = r.key(failed), caps = vm.caps) { onPlay(r) }
            }
            if (ordered.isEmpty()) item { Text("No releases match your filters.", color = Muted, modifier = Modifier.padding(16.dp)) }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

private fun NzbResult.key(failed: Set<String>) = (guid ?: nzbUrl) in failed

@Composable
private fun ExpandableText(text: String) {
    var open by remember { mutableStateOf(false) }
    Text(
        text,
        maxLines = if (open) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 10.dp).animateContentSize().pressable(pressedScale = 1f) { open = !open },
    )
}
