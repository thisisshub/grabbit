@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.usenetstream.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.usenetstream.MainViewModel
import dev.usenetstream.engine.newznab.SearchCategory
import dev.usenetstream.engine.newznab.TitleFilter
import dev.usenetstream.engine.newznab.TitleSort
import kotlin.math.roundToInt

@Composable
fun SearchScreen(vm: MainViewModel) {
    val state by vm.search.collectAsState()
    val recent by vm.recentSearches.collectAsState()
    var query by rememberSaveable { mutableStateOf(state.query) }
    var showFilters by remember { mutableStateOf(false) }
    val pairedTv by vm.phoneLink.tv.collectAsState()
    val speakToTv = rememberSpeakToTv(vm) { text -> query = text; vm.search(text) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit(q: String = query, cat: SearchCategory = state.category) {
        query = q
        keyboard?.hide()
        vm.search(q, cat)
    }

    Column(Modifier.fillMaxSize().background(Ink)) {
        // ---- results (top, where the eye goes) ----
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val shown = state.shown
            when {
                state.groups.isEmpty() && !state.searching -> RecentSearches(recent, state.message, vm::clearRecentSearches) { submit(it) }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize().statusBarsPadding(),
                ) {
                    if (state.searching && state.groups.isEmpty()) {
                        items(12) { ShimmerBox(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) }
                    }
                    items(shown, key = { it.key }) { g ->
                        Column(Modifier.animateItem().testTag("result:${g.key}").pressable { vm.openTitle(g) }) {
                            Poster(g.coverUrl, g.title, Modifier.fillMaxWidth().aspectRatio(2f / 3f).sharedPoster(g.key))
                            Text(g.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            Text(
                                listOfNotNull(g.year?.toString(), if (g.isShow) "Series" else null, g.score?.let { "★$it" }).joinToString(" · ")
                                    .ifEmpty { "${g.releases.size} releases" },
                                style = MaterialTheme.typography.labelSmall,
                                color = Muted,
                            )
                        }
                    }
                }
            }
            if (state.groups.isNotEmpty() && shown.isEmpty()) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No titles match your filters", color = Muted)
                    TextButton({ vm.setTitleFilter(TitleFilter()) }) { Text("Clear filters", color = Red) }
                }
            }
        }

        state.message?.takeIf { state.groups.isNotEmpty() || it.startsWith("Can't") }?.let {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = Red, modifier = Modifier.weight(1f), maxLines = 2)
                if (it.startsWith("Can't")) IconButton({ submit() }) { Icon(Icons.Filled.Refresh, "Retry", tint = Fg) }
            }
        }

        // ---- controls (bottom, in thumb reach, riding on the keyboard) ----
        Column(
            Modifier
                .fillMaxWidth()
                // Sit above the floating nav bar, or above the keyboard when it is up.
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars.add(WindowInsets(bottom = 64.dp))))
                .glass(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), fill = LocalPalette.current.bar, elevation = 0.dp, blur = LocalHaze.current)
                .padding(top = 10.dp, bottom = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (c in SearchCategory.entries) {
                    GlassChip(
                        selected = state.category == c,
                        onClick = { if (query.isNotBlank()) submit(cat = c) else vm.setCategory(c) },
                        label = { Text(c.label) },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (pairedTv != null) {
                    GlassChip(false, { if (query.isBlank()) speakToTv() else vm.sendSearchToTv(query) }, { Text(if (query.isBlank()) "🎤 TV" else "→ TV") })
                }
                FilterButton(active = state.filter.active, count = state.shown.size.takeIf { state.groups.isNotEmpty() }) { showFilters = true }
            }
            TextField(
                query, { query = it },
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Movies, shows, releases…") },
                leadingIcon = {
                    if (state.searching) CircularProgressIndicator(Modifier.size(20.dp), color = Red, strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Search, null)
                },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = LocalPalette.current.glass,
                    unfocusedContainerColor = LocalPalette.current.glass,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Red,
                ),
                shape = MaterialTheme.shapes.medium,
            )
        }
    }

    if (showFilters) TitleFilterSheet(state, vm::setTitleFilter) { showFilters = false }
}

@Composable
private fun RecentSearches(recent: List<String>, message: String?, onClear: () -> Unit, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        message?.let { Text(it, color = if (it.startsWith("Can't")) Red else Muted, modifier = Modifier.padding(bottom = 12.dp)) }
        if (recent.isEmpty()) {
            Spacer(Modifier.height(120.dp))
            Text("Find something to watch", style = MaterialTheme.typography.headlineSmall)
            Text("Search below. Tip: dots work as spaces, like Big.Buck.Bunny.", color = Muted)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent searches", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                TextButton(onClear) { Text("Clear", color = Muted) }
            }
            recent.forEach { q ->
                Row(
                    Modifier.fillMaxWidth().pressable(pressedScale = 0.98f) { onPick(q) }.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, null, tint = Muted, modifier = Modifier.size(20.dp))
                    Text(q, modifier = Modifier.padding(start = 14.dp))
                }
            }
        }
    }
}

@Composable
fun FilterButton(active: Boolean, count: Int?, onClick: () -> Unit) {
    val p = LocalPalette.current
    val on = if (active) p.bg else p.fg
    Box(Modifier.glass(fill = if (active) p.fg else p.glass).pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Settings, null, tint = on, modifier = Modifier.size(18.dp))
            Text(
                if (active && count != null) "Filters · $count" else "Filters",
                color = on,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        AnimatedVisibility(active, Modifier.align(Alignment.TopEnd), enter = fadeIn() + scaleIn(), exit = fadeOut()) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Red))
        }
    }
}


@Composable
private fun TitleFilterSheet(state: dev.usenetstream.SearchState, onApply: (TitleFilter) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(state.filter) }
    val allGenres = state.groups.flatMap { it.genres }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(16)
    val years = state.groups.mapNotNull { it.year }
    val minY = (years.minOrNull() ?: 1950).coerceAtMost(2000)
    val maxY = (years.maxOrNull() ?: 2026).coerceAtLeast(minY + 1)
    val preview = f.apply(state.groups).size

    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Card) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            SheetHeader("Filter titles") { f = TitleFilter() }
            Label("Sort by")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TitleSort.entries.forEach { s -> GlassChip(f.sort == s, { f = f.copy(sort = s) }, { Text(s.label) }) }
            }
            if (allGenres.isNotEmpty()) {
                Label("Genres")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    allGenres.forEach { g ->
                        GlassChip(g in f.genres, { f = f.copy(genres = if (g in f.genres) f.genres - g else f.genres + g) }, { Text(g) })
                    }
                }
            }
            Label("Year  ${f.minYear ?: minY} – ${f.maxYear ?: maxY}")
            RangeSlider(
                value = (f.minYear ?: minY).toFloat()..(f.maxYear ?: maxY).toFloat(),
                onValueChange = { r ->
                    val lo = r.start.roundToInt(); val hi = r.endInclusive.roundToInt()
                    f = f.copy(minYear = lo.takeIf { it > minY }, maxYear = hi.takeIf { it < maxY })
                },
                valueRange = minY.toFloat()..maxY.toFloat(),
                colors = SliderDefaults.colors(thumbColor = Fg, activeTrackColor = Red),
            )
            Label("Minimum rating  ${f.minRating?.let { "★ %.1f".format(it) } ?: "any"}")
            Slider(
                value = (f.minRating ?: 0.0).toFloat(),
                onValueChange = { v ->
                    val half = (v * 2).roundToInt() / 2.0 // half-star steps
                    f = f.copy(minRating = half.takeIf { it > 0 })
                },
                valueRange = 0f..9f,
                steps = 17,
                colors = SliderDefaults.colors(thumbColor = Fg, activeTrackColor = Red),
            )
            Spacer(Modifier.height(16.dp))
        }
        ApplyBar("Show $preview titles") { onApply(f); onDismiss() }
    }
}

@Composable
fun SheetHeader(title: String, onReset: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onReset) { Text("Reset", color = Red) }
    }
}

@Composable
fun Label(text: String) {
    Text(text, color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

/** The sheet's confirm button, full width at the very bottom. */
@Composable
fun ApplyBar(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        PlayButton(label, Modifier.fillMaxWidth(), icon = null, onClick = onClick)
    }
}

val SearchCategory.label: String
    get() = when (this) {
        SearchCategory.ALL -> "All"
        SearchCategory.MOVIES -> "Movies"
        SearchCategory.TV -> "TV"
    }
