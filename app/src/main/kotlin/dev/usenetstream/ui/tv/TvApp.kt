@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package dev.usenetstream.ui.tv

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Carousel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import dev.usenetstream.MainViewModel
import dev.usenetstream.Route
import dev.usenetstream.StepState
import dev.usenetstream.Tab
import dev.usenetstream.engine.newznab.NzbResult
import dev.usenetstream.engine.newznab.Playability
import dev.usenetstream.engine.newznab.ReleaseParser
import dev.usenetstream.engine.newznab.SearchCategory
import dev.usenetstream.engine.newznab.TitleGroup
import dev.usenetstream.engine.newznab.key
import dev.usenetstream.engine.newznab.playabilityOn
import dev.usenetstream.engine.tv.GuideEpisode
import dev.usenetstream.engine.tv.NextUp
import dev.usenetstream.history.ResumePolicy
import dev.usenetstream.player.TvPlayerScreen
import dev.usenetstream.settings.SettingsScreen
import dev.usenetstream.ui.DarkPalette
import dev.usenetstream.ui.Poster
import dev.usenetstream.ui.formatAge
import dev.usenetstream.ui.formatLeft
import dev.usenetstream.ui.formatSize
import dev.usenetstream.ui.label
import dev.usenetstream.ui.orderReleases
import dev.usenetstream.ui.releaseBadges
import kotlinx.coroutines.delay

private val P = DarkPalette

@Composable
fun TvApp(vm: MainViewModel, onExit: () -> Unit, setupPanel: @Composable () -> Unit) {
    TvTheme {
        val tab by vm.tab.collectAsState()
        val stack by vm.stack.collectAsState()
        val notice by vm.notice.collectAsState()
        val top = stack.lastOrNull()
        BackHandler { if (!vm.back()) onExit() }
        val accepted by vm.disclaimerOk.collectAsState()
        if (!accepted) return@TvTheme TvDisclaimer(vm::acceptDisclaimer, onExit)

        Box(Modifier.fillMaxSize().background(P.bg)) {
            if (top is Route.Player) {
                TvPlayerScreen(vm, top)
            } else {
                Row(Modifier.fillMaxSize()) {
                    if (top == null) TvRail(tab, vm::selectTab)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        AnimatedContent(
                            targetState = top ?: tab,
                            contentKey = { s ->
                                when (s) {
                                    is Route.Movie -> "m:${s.group.key}"
                                    is Route.Show -> "s:${s.key}"
                                    is Route.Lookup -> "l:${s.key}"
                                    is Route.Opening -> "o"
                                    is Route.Service -> "svc:${s.id}"
                                    is Route.OttTitle -> "ott:${s.item.serviceId}:${s.item.title.id}"
                                    else -> "tab:$s"
                                }
                            },
                            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                            label = "tv-screens",
                        ) { s ->
                            when (s) {
                                Tab.HOME -> TvHome(vm)
                                Tab.SEARCH -> TvSearch(vm)
                                Tab.MY_LIST -> TvMyList(vm)
                                Tab.SETTINGS -> TvSettings(vm, setupPanel)
                                is Route.Movie -> TvMovie(vm, s.group)
                                is Route.Show -> TvShow(vm, s)
                                is Route.Lookup -> TvLoading(s.title, s.coverUrl)
                                is Route.Opening -> TvOpening(s) { vm.back() }
                                is Route.Service -> TvService(vm, s.id)
                                is Route.OttTitle -> TvOttTitle(vm, s.item)
                                else -> Unit
                            }
                        }
                    }
                }
            }
            // Toast at the top: out of the way of what's focused.
            AnimatedVisibility(
                notice != null,
                Modifier.align(Alignment.TopCenter).padding(top = TvSafeV),
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
            ) {
                Text(
                    notice.orEmpty(), fontSize = 16.sp, color = P.bg,
                    modifier = Modifier.background(P.fg, RoundedCornerShape(24.dp)).padding(horizontal = 22.dp, vertical = 10.dp),
                )
            }
            LaunchedEffect(notice) { if (notice != null) { delay(3200); vm.dismissNotice() } }
            dev.usenetstream.link.TvPairRequestOverlay(vm)
        }
    }
}

// ---------------------------------------------------------------- rail

@Composable
private fun TvRail(tab: Tab, onSelect: (Tab) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (open) 210.dp else 84.dp, tween(220), label = "rail")
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
            .onFocusChanged { open = it.hasFocus }
            .focusRestorer()
            .padding(start = 18.dp, top = TvSafeV, bottom = TvSafeV),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Text(if (open) "Grabbit" else "G", color = P.accent, fontWeight = FontWeight.Black, fontSize = 24.sp, modifier = Modifier.padding(start = 12.dp, bottom = 24.dp))
        RailItem(Icons.Filled.Search, "Search", tab == Tab.SEARCH, open) { onSelect(Tab.SEARCH) }
        RailItem(Icons.Filled.Home, "Home", tab == Tab.HOME, open) { onSelect(Tab.HOME) }
        RailItem(Icons.Filled.Add, "My List", tab == Tab.MY_LIST, open) { onSelect(Tab.MY_LIST) }
        RailItem(Icons.Filled.Settings, "Settings", tab == Tab.SETTINGS, open) { onSelect(Tab.SETTINGS) }
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, open: Boolean, onClick: () -> Unit) {
    TvFocusable(
        onClick = onClick,
        modifier = Modifier.height(48.dp).fillMaxWidth().padding(end = 12.dp),
        shape = RoundedCornerShape(24.dp),
        container = if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent,
        focusedContainer = Color.White,
        content = if (selected) P.fg else P.muted,
        focusedContent = Color.Black,
        focusedScale = 1.04f,
    ) {
        Row(Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, label, tint = LocalContentColor.current, modifier = Modifier.size(24.dp))
            if (open) Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = LocalContentColor.current, modifier = Modifier.padding(start = 14.dp), maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- home

@Composable
private fun TvHome(vm: MainViewModel) {
    val feeds by vm.feeds.collectAsState()
    val history by vm.history.collectAsState()
    val myList by vm.myList.collectAsState()
    val ott by vm.ott.collectAsState()
    val heroes = feeds.trending.filter { it.coverUrl != null }.take(5)
    val cw = ResumePolicy.continueWatching(history)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = TvSafeV)) {
        item {
            if (heroes.isNotEmpty()) TvHero(heroes, vm)
            else Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                if (feeds.loading) CircularProgressIndicator(color = P.accent)
                else Text(feeds.error ?: if (vm.indexer == null) "Add your server in Settings" else "Nothing here yet", color = P.muted)
            }
        }
        if (cw.isNotEmpty()) item {
            TvRowTitle("Continue Watching")
            TvPosterRow(cw.map { e ->
                TvTile(e.showKey ?: e.id, e.title, e.coverUrl, if (e.finished) null else e.progress,
                    if (e.isEpisode) "${e.code} · ${if (e.finished) "next up" else formatLeft(e.positionMs, e.durationMs)}" else formatLeft(e.positionMs, e.durationMs)) { vm.resume(e) }
            })
        }
        if (myList.isNotEmpty()) item {
            TvRowTitle("My List")
            TvPosterRow(myList.map { TvTile(it.key, it.title, it.coverUrl, null, it.year?.toString()) { vm.openKey(it.key, it.title, it.coverUrl) } })
        }
        item(key = "update") { TvUpdateCard() }
        tvOttRows(vm, ott)
        fun row(title: String, groups: List<TitleGroup>) {
            if (groups.isEmpty()) return
            item {
                TvRowTitle(title)
                TvPosterRow(groups.map { g -> TvTile(g.key, g.title, g.coverUrl, null, listOfNotNull(g.year?.toString(), g.score?.let { "★ $it" }).joinToString(" · ")) { vm.openTitle(g) } })
            }
        }
        row("Trending Now", feeds.trending)
        feeds.moreLike?.let { (seed, gs) -> row("Because you watched $seed", gs) }
        row("New Movies", feeds.newMovies)
        row("New TV Episodes", feeds.newTv)
        row("Top Rated", feeds.topRated)
    }
}

data class TvTile(val key: String, val title: String, val cover: String?, val progress: Float?, val caption: String?, val onClick: () -> Unit)

@Composable
fun TvPosterRow(tiles: List<TvTile>) {
    LazyRow(
        Modifier.focusRestorer(),
        contentPadding = PaddingValues(horizontal = TvSafeH, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(tiles, key = { it.key }) { t ->
            TvPoster(t.title, t.cover, t.onClick, progress = t.progress, caption = t.caption)
        }
    }
}

@Composable
private fun TvHero(heroes: List<TitleGroup>, vm: MainViewModel) {
    Carousel(
        itemCount = heroes.size,
        modifier = Modifier.fillMaxWidth().height(380.dp),
        autoScrollDurationMillis = 9_000,
    ) { i ->
        val g = heroes[i]
        Box(Modifier.fillMaxSize()) {
            // Portrait cover on a blurred wide copy of itself, faded into the page.
            if (g.coverUrl != null) Poster(g.coverUrl, g.title, Modifier.fillMaxSize().blur(30.dp))
            Box(Modifier.fillMaxSize().tvScrim())
            Poster(g.coverUrl, g.title, Modifier.align(Alignment.CenterEnd).padding(end = 72.dp).width(200.dp).aspectRatio(2f / 3f))
            Column(Modifier.align(Alignment.CenterStart).padding(start = TvSafeH, end = 360.dp)) {
                Text("#${i + 1} TRENDING", color = P.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(g.title, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(g.year?.toString(), g.score?.let { "★ $it" }, g.runtime, g.genres.take(3).joinToString(" • ").ifEmpty { null }).joinToString("   "),
                    color = P.muted, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp),
                )
                g.tagline?.let { Text(it, color = P.muted, fontSize = 16.sp, maxLines = 2, modifier = Modifier.padding(top = 10.dp)) }
                Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvButton("Play", { if (g.isShow) vm.openTitle(g) else vm.playMovie(g) }, primary = true, leading = { Icon(Icons.Filled.PlayArrow, null, tint = LocalContentColor.current) })
                    TvButton("More info", { vm.openTitle(g) }, leading = { Icon(Icons.Filled.Info, null, tint = LocalContentColor.current) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- search

@Composable
private fun TvSearch(vm: MainViewModel) {
    val state by vm.search.collectAsState()
    val remote by vm.remoteSearchFrom.collectAsState()
    var query by rememberSaveable { mutableStateOf(state.query) }
    // Text typed or spoken on the phone lands here.
    LaunchedEffect(state.query) { query = state.query }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { query = it; vm.search(it) }
    }
    val firstResult = remember { FocusRequester() }
    LaunchedEffect(state.groups, remote) {
        if (remote != null && state.groups.isNotEmpty()) {
            delay(150)
            runCatching { firstResult.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize().padding(top = TvSafeV)) {
        Row(Modifier.padding(horizontal = TvSafeH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(
                query, { query = it },
                Modifier.width(340.dp).testTag("tv-search"),
                placeholder = { Text("Search movies & shows") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = P.divider, cursorColor = P.accent),
            )
            TvButton("Voice", {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a movie or show")
                runCatching { voice.launch(i) }.onFailure { vm.notifyUser("Voice search isn't available on this TV — use your phone's mic") }
            })
            SearchCategory.entries.forEach { c -> TvChip(c.label, state.category == c, { vm.setCategory(c); if (query.isNotBlank()) vm.search(query, c) }) }
            if (state.searching) CircularProgressIndicator(Modifier.size(26.dp), color = P.accent, strokeWidth = 3.dp)
        }
        remote?.let { Text("From $it", color = P.muted, fontSize = 14.sp, modifier = Modifier.padding(start = TvSafeH, top = 10.dp)) }
        state.message?.let { Text(it, color = if (it.startsWith("Can't")) P.accent else P.muted, modifier = Modifier.padding(start = TvSafeH, top = 10.dp)) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(horizontal = TvSafeH, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.shown, key = { it.key }) { g ->
                val first = g == state.shown.firstOrNull()
                TvPoster(
                    g.title, g.coverUrl, { vm.openTitle(g) },
                    modifier = if (first) Modifier.focusRequester(firstResult) else Modifier,
                    caption = listOfNotNull(g.year?.toString(), if (g.isShow) "Series" else null).joinToString(" · "),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- my list

@Composable
private fun TvMyList(vm: MainViewModel) {
    val list by vm.myList.collectAsState()
    Column(Modifier.fillMaxSize().padding(top = TvSafeV)) {
        Text("My List", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = TvSafeH))
        if (list.isEmpty()) Text("Press + My List on any title to save it here.", color = P.muted, modifier = Modifier.padding(start = TvSafeH, top = 12.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(horizontal = TvSafeH, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list, key = { it.key }) { e ->
                TvPoster(e.title, e.coverUrl, { vm.openKey(e.key, e.title, e.coverUrl) }, caption = e.year?.toString(),
                    onLongClick = { vm.toggleMyList(e.key, e.title, e.coverUrl, e.year) })
            }
        }
    }
}

// ---------------------------------------------------------------- movie

@Composable
private fun TvMovie(vm: MainViewModel, group: TitleGroup) {
    val history by vm.history.collectAsState()
    val myList by vm.myList.collectAsState()
    val rf by vm.releaseFilter.collectAsState()
    val failed by vm.failed.collectAsState()
    val watch = history.firstOrNull { it.id == group.key }
    val resuming = watch != null && ResumePolicy.inContinueWatching(watch)
    val ordered = orderReleases(vm, group.releases, rf, failed)
    val play = remember { FocusRequester() }
    LaunchedEffect(group.key) { delay(120); runCatching { play.requestFocus() } }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxWidth().height(420.dp)) {
                if (group.coverUrl != null) Poster(group.coverUrl, group.title, Modifier.fillMaxSize().blur(30.dp))
                Box(Modifier.fillMaxSize().tvScrim())
                if (group.coverUrl != null) Poster(group.coverUrl, group.title, Modifier.align(Alignment.CenterEnd).padding(end = 72.dp).width(210.dp).aspectRatio(2f / 3f))
                Column(Modifier.align(Alignment.CenterStart).padding(start = TvSafeH, end = 380.dp)) {
                    Text(group.title, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, maxLines = 2)
                    TvMeta(group.year, group.score, group.runtime, ordered.firstOrNull())
                    if (group.genres.isNotEmpty()) Text(group.genres.joinToString(" • "), color = P.muted, fontSize = 16.sp)
                    group.tagline?.let { Text(it, color = P.muted, fontSize = 17.sp, modifier = Modifier.padding(top = 10.dp)) }
                    if (resuming) Text(formatLeft(watch!!.positionMs, watch.durationMs), color = P.accent, fontSize = 15.sp, modifier = Modifier.padding(top = 10.dp))
                    Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TvButton(if (resuming) "Resume" else "Play", { vm.playMovie(group) }, Modifier.focusRequester(play), primary = true,
                            leading = { Icon(Icons.Filled.PlayArrow, null, tint = LocalContentColor.current) })
                        val inList = myList.any { it.key == group.key }
                        TvButton(if (inList) "In My List" else "My List", { vm.toggleMyList(group.key, group.title, group.coverUrl, group.year) },
                            leading = { Icon(if (inList) Icons.Filled.Check else Icons.Filled.Add, null, tint = LocalContentColor.current) })
                    }
                }
            }
        }
        item { TvRowTitle("Releases · ${ordered.size}") }
        items(ordered, key = { it.key }) { r -> TvReleaseRow(vm, r, r == ordered.firstOrNull(), r.key in failed) { vm.playMovie(group, r) } }
        item { Spacer(Modifier.height(TvSafeV)) }
    }
}

@Composable
private fun TvMeta(year: Int?, score: Double?, runtime: String?, best: NzbResult?) {
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        year?.let { Text("$it", color = P.muted, fontSize = 17.sp) }
        score?.let { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Star, null, tint = Color(0xFFF5C518), modifier = Modifier.size(18.dp)); Text(" $it", color = P.muted, fontSize = 17.sp) } }
        runtime?.let { Text(it, color = P.muted, fontSize = 17.sp) }
        best?.let { r -> releaseBadges(ReleaseParser.parse(r)).take(2).forEach { TvBadge(it) } }
    }
}

@Composable
private fun TvReleaseRow(vm: MainViewModel, r: NzbResult, best: Boolean, failed: Boolean, onClick: () -> Unit) {
    val info = remember(r) { ReleaseParser.parse(r) }
    val play = info.playabilityOn(vm.caps)
    TvFocusable(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = TvSafeH, vertical = 5.dp), focusedScale = 1.02f) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (best && !failed && play == Playability.OK) TvBadge("BEST", accent = true)
                    releaseBadges(info).forEach { TvBadge(it) }
                    if (failed) TvBadge("FAILED BEFORE")
                    if (play == Playability.NO) TvBadge("CAN'T PLAY HERE")
                    if (play == Playability.NO_AUDIO) TvBadge("NO AUDIO HERE")
                }
                Text(r.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = LocalContentColor.current, modifier = Modifier.padding(top = 6.dp))
                Text(listOfNotNull(r.sizeBytes?.let(::formatSize), formatAge(ReleaseParser.ageDays(r.pubDate)), r.grabs?.let { "$it grabs" }).joinToString(" · "), fontSize = 13.sp, color = P.muted)
            }
            Icon(if (r.passworded) Icons.Filled.Lock else if (play != Playability.OK || failed) Icons.Filled.Warning else Icons.Filled.PlayArrow, null, tint = LocalContentColor.current)
        }
    }
}

// ---------------------------------------------------------------- show

@Composable
private fun TvShow(vm: MainViewModel, route: Route.Show) {
    val stateOrNull by vm.show.collectAsState()
    val myList by vm.myList.collectAsState()
    val s = stateOrNull?.takeIf { it.key == route.key }
    val guide = s?.guide
    val seasons = guide?.seasons?.keys?.toList().orEmpty()
    val next = s?.nextUp
    val nextEp = (next as? NextUp.Resume)?.episode ?: (next as? NextUp.Play)?.episode
    var season by rememberSaveable(route.key) { mutableIntStateOf(-1) }
    LaunchedEffect(seasons, nextEp?.season) { if (season == -1 && seasons.isNotEmpty()) season = nextEp?.season ?: seasons.first() }
    LaunchedEffect(season) { if (season > 0) vm.loadSeason(season) }
    val play = remember { FocusRequester() }
    LaunchedEffect(route.key) { delay(150); runCatching { play.requestFocus() } }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(300.dp)) {
            if (guide?.posterUrl ?: route.coverUrl != null) Poster(guide?.posterUrl ?: route.coverUrl, route.title, Modifier.fillMaxSize().blur(30.dp))
            Box(Modifier.fillMaxSize().tvScrim())
            Column(Modifier.align(Alignment.CenterStart).padding(start = TvSafeH, end = 300.dp)) {
                Text(guide?.name ?: route.title, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    guide?.premiered?.take(4)?.let { Text(it, color = P.muted, fontSize = 16.sp) }
                    if (seasons.isNotEmpty()) Text("${seasons.size} Season${if (seasons.size == 1) "" else "s"}", color = P.muted, fontSize = 16.sp)
                    guide?.let { TvBadge("${s.watchedCount}/${it.episodes.size} watched") }
                }
                guide?.summary?.let { Text(it, color = P.muted, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                s?.guideError?.let { Text(it, color = P.accent) }
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val label = when (next) {
                        is NextUp.Resume -> "Resume ${next.episode.code}"
                        is NextUp.Play -> if (s.progress.isEmpty()) "Play ${next.episode.code}" else "Next ${next.episode.code}"
                        NextUp.AllWatched -> "Watch again"
                        null -> "Loading…"
                    }
                    TvButton(label, { nextEp?.let(vm::playEpisode) ?: guide?.episodes?.firstOrNull()?.let(vm::playEpisode) }, Modifier.focusRequester(play), primary = true,
                        leading = { Icon(Icons.Filled.PlayArrow, null, tint = LocalContentColor.current) })
                    val inList = myList.any { it.key == route.key }
                    TvButton(if (inList) "In My List" else "My List", { vm.toggleMyList(route.key, route.title, guide?.posterUrl ?: route.coverUrl, guide?.premiered?.take(4)?.toIntOrNull()) },
                        leading = { Icon(if (inList) Icons.Filled.Check else Icons.Filled.Add, null, tint = LocalContentColor.current) })
                }
            }
        }
        Row(Modifier.fillMaxSize().padding(start = TvSafeH, end = TvSafeH, bottom = TvSafeV)) {
            // Seasons down the left, that season's episodes on the right.
            LazyColumn(Modifier.width(170.dp).focusRestorer(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons) { n -> TvChip("Season $n", n == season, { season = n }, Modifier.fillMaxWidth()) }
            }
            Spacer(Modifier.width(24.dp))
            val eps = guide?.seasons?.get(season).orEmpty()
            val rel = s?.seasons?.get(season)
            LazyColumn(Modifier.weight(1f).focusRestorer(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s != null && season in s.loadingSeasons && rel == null) item { Text("Finding releases for season $season…", color = P.muted) }
                items(eps, key = { it.code }) { e ->
                    val available = rel == null || rel.byEpisode.containsKey(e.number) || rel.packs.isNotEmpty()
                    TvEpisodeRow(e, s?.progress?.get(e.code), available, e == nextEp) { if (available) vm.playEpisode(e) }
                }
            }
        }
    }
}

@Composable
private fun TvEpisodeRow(e: GuideEpisode, p: dev.usenetstream.engine.tv.EpisodeProgress?, available: Boolean, isNext: Boolean, onClick: () -> Unit) {
    TvFocusable(onClick = onClick, modifier = Modifier.fillMaxWidth(), focusedScale = 1.02f, container = if (isNext) P.surface2 else P.surface) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(176.dp).aspectRatio(16f / 9f)) {
                Poster(e.stillUrl, e.code, Modifier.fillMaxSize())
                if (p != null && !p.finished) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth((p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f)).height(4.dp).background(P.accent))
                }
                if (p?.finished == true) Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { TvBadge("✓ WATCHED") }
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text("${e.number}. ${e.name}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = LocalContentColor.current, maxLines = 1)
                Text(
                    listOfNotNull(e.runtimeMin?.let { "${it}m" }, e.airdate, if (!available) "Not on indexer" else null, if (isNext) "Up next" else null).joinToString(" · "),
                    fontSize = 14.sp, color = if (isNext) P.accent else P.muted,
                )
                e.summary?.let { Text(it, fontSize = 14.sp, color = P.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

// ---------------------------------------------------------------- settings / loading / opening

@Composable
private fun TvSettings(vm: MainViewModel, setupPanel: @Composable () -> Unit) {
    val prefs by vm.playPrefs.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = TvSafeH, vertical = TvSafeV), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        item { setupPanel() }
        item {
            Text("Preferred quality", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(dev.usenetstream.engine.newznab.Quality.SD, dev.usenetstream.engine.newznab.Quality.HD, dev.usenetstream.engine.newznab.Quality.FHD, dev.usenetstream.engine.newznab.Quality.UHD).forEach { q ->
                    TvChip(q.label, prefs.quality == q, { vm.setPlayPrefs(prefs.copy(quality = q)) })
                }
            }
            val sw = dev.usenetstream.player.Decoders.softwareAudio
            Text(
                "This TV decodes: " + listOfNotNull("H.264", "HEVC".takeIf { vm.caps.hevc }, "Dolby Vision".takeIf { vm.caps.dolbyVision }, "AV1".takeIf { vm.caps.av1 }).joinToString(", ") +
                    if (sw.isNotEmpty()) " · audio via app: ${sw.joinToString(", ")}" else "",
                color = P.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (vm.relay.enabled) item { TvFeedback(vm) }
        item { TvUpdateSettings() }
        item { TvSupport() }
        item {
            Text("About · Grabbit ${dev.usenetstream.BuildConfig.VERSION_NAME}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            dev.usenetstream.ui.Disclaimer.POINTS.forEach { Text("•  $it", color = P.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp).width(900.dp)) }
        }
        item {
            Text("Server & indexer (or set them up from your phone above)", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.width(560.dp)) { SettingsScreen(vm.config, vm.indexer, vm::saveSettings) }
        }
    }
}

/** "Update ready" on the TV home: one focusable button drives the whole flow. */
@Composable
private fun TvUpdateCard() {
    val updater = dev.usenetstream.update.Updater.get(androidx.compose.ui.platform.LocalContext.current)
    val state by updater.state.collectAsState()
    val r = when (val s = state) {
        is dev.usenetstream.update.UpdateState.Available -> s.release
        is dev.usenetstream.update.UpdateState.Downloading -> s.release
        is dev.usenetstream.update.UpdateState.NeedsPermission -> s.release
        is dev.usenetstream.update.UpdateState.Installing -> s.release
        is dev.usenetstream.update.UpdateState.Failed -> s.release
        else -> null
    } ?: return
    Row(
        Modifier.padding(horizontal = TvSafeH, vertical = 10.dp).background(P.accent.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
            .border(1.dp, P.accent.copy(alpha = 0.6f), RoundedCornerShape(18.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(620.dp)) {
            Text("🐰  Grabbit ${dev.usenetstream.ui.versionOf(r.tag)} is here", fontSize = 20.sp)
            Text(dev.usenetstream.ui.whatsNew(r.notes, 2), color = P.muted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(24.dp))
        when (val s = state) {
            is dev.usenetstream.update.UpdateState.Downloading -> Text("Downloading ${(s.fraction * 100).toInt()}%", color = P.fg, fontSize = 17.sp)
            is dev.usenetstream.update.UpdateState.Installing -> Text("Installing…", color = P.fg, fontSize = 17.sp)
            is dev.usenetstream.update.UpdateState.NeedsPermission -> TvButton("Allow installs", updater::openInstallPermission, primary = true)
            is dev.usenetstream.update.UpdateState.Failed -> TvButton("Try again", { updater.install(r) }, primary = true)
            else -> TvButton("Update now", { updater.install(r) }, primary = true)
        }
    }
}

@Composable
private fun TvUpdateSettings() {
    val updater = dev.usenetstream.update.Updater.get(androidx.compose.ui.platform.LocalContext.current)
    val state by updater.state.collectAsState()
    var auto by remember { mutableStateOf(updater.auto) }
    Text("Updates · you're on ${dev.usenetstream.BuildConfig.VERSION_NAME}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TvButton(
            when (state) {
                dev.usenetstream.update.UpdateState.Checking -> "Checking…"
                dev.usenetstream.update.UpdateState.UpToDate -> "Up to date ✓"
                else -> "Check for updates"
            },
            { updater.check() },
        )
        TvChip(if (auto) "Auto-download: on" else "Auto-download: off", auto, { auto = !auto; updater.auto = auto })
    }
}

/** Tip jar as a QR: open it on a phone (UPI apps live there anyway). */
@Composable
private fun TvSupport() {
    val qr = remember { dev.usenetstream.link.qrBitmap(dev.usenetstream.feedback.Support.URL, 520).asImageBitmap() }
    Text(dev.usenetstream.feedback.Support.TITLE, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(qr, "Support QR code", Modifier.size(170.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp))
        Text(
            dev.usenetstream.feedback.Support.BLURB + "\nScan with your phone to buy me a chai.",
            color = P.muted, fontSize = 15.sp, modifier = Modifier.padding(start = 20.dp).width(460.dp),
        )
    }
}

/** First launch on TV: the beta notice, accepted with OK. */
@Composable
private fun TvDisclaimer(onAccept: () -> Unit, onExit: () -> Unit) {
    val ok = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { ok.requestFocus() } }
    BackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize().background(P.bg), contentAlignment = Alignment.Center) {
        Column(Modifier.width(860.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Grabbit", color = P.accent, fontSize = 30.sp)
            Text(dev.usenetstream.ui.Disclaimer.TITLE, fontSize = 34.sp)
            dev.usenetstream.ui.Disclaimer.POINTS.forEach { Text("•  $it", fontSize = 17.sp, color = P.fg.copy(alpha = 0.9f)) }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton("I understand", onAccept, Modifier.focusRequester(ok), primary = true)
                TvButton("Exit", onExit)
            }
        }
    }
}

/** The feedback form, remote-friendly: mood and topic chips, then the TV keyboard (or its mic) for the message. */
@Composable
private fun TvFeedback(vm: MainViewModel) {
    val f = dev.usenetstream.ui.FeedbackCopy
    var mood by remember { mutableStateOf<String?>(null) }
    var topic by remember { mutableStateOf("bug") }
    var message by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val auto by vm.autoReports.collectAsState()
    val scope = rememberCoroutineScope()
    Text("Feedback", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Text("Bugs, ideas, rants: all welcome. " + f.PRIVACY, color = P.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp).width(900.dp))
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        f.MOODS.forEach { (id, face) -> TvChip(face, mood == id, { mood = if (mood == id) null else id }) }
    }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        f.TOPICS.forEach { (id, label) -> TvChip(label, topic == id, { topic = id }) }
    }
    androidx.compose.material3.OutlinedTextField(
        message, { if (it.length <= f.MAX) message = it },
        Modifier.padding(top = 10.dp).width(720.dp),
        placeholder = { Text(f.prompt(topic), color = P.muted) },
        minLines = 2,
    )
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        TvButton(if (sending) "Sending…" else "Send it 🐰", {
            if (sending || message.trim().length < 3) { status = "Write a little first"; return@TvButton }
            sending = true
            scope.launch {
                val err = vm.relay.sendFeedback(topic, mood, message)
                sending = false
                status = err ?: f.THANKS
                if (err == null) { message = ""; mood = null }
            }
        }, primary = true)
        TvChip(if (auto) "Auto error reports: on" else "Auto error reports: off", auto, { vm.setAutoReports(!auto) })
    }
    status?.let { Text(it, color = P.muted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)) }
}

@Composable
private fun TvLoading(title: String, cover: String?) {
    Box(Modifier.fillMaxSize()) {
        if (cover != null) Poster(cover, title, Modifier.fillMaxSize().blur(30.dp))
        Box(Modifier.fillMaxSize().tvScrim())
        Column(Modifier.align(Alignment.CenterStart).padding(start = TvSafeH)) {
            Text(title, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
            CircularProgressIndicator(Modifier.padding(top = 20.dp), color = P.accent)
        }
    }
}

@Composable
private fun TvOpening(r: Route.Opening, onCancel: () -> Unit) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { cancel.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        if (r.coverUrl != null) Poster(r.coverUrl, r.title, Modifier.fillMaxSize().blur(40.dp))
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(64.dp), color = P.primary, strokeWidth = 4.dp)
            Text(r.title, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 26.dp, bottom = 14.dp))
            r.steps.forEach { st ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (st.state) {
                        StepState.DONE -> Icon(Icons.Filled.Check, null, tint = Color(0xFF46D369), modifier = Modifier.size(20.dp))
                        StepState.FAILED -> Icon(Icons.Filled.Warning, null, tint = P.accent, modifier = Modifier.size(20.dp))
                        else -> CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                    Text(st.label, color = if (st.state == StepState.FAILED) Color(0xFFFF8A80) else P.muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).width(560.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
            TvButton("Cancel", onCancel, Modifier.focusRequester(cancel))
        }
    }
}
