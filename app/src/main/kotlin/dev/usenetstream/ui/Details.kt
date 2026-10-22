@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.usenetstream.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.usenetstream.MainViewModel
import dev.usenetstream.Route
import dev.usenetstream.StepState
import dev.usenetstream.engine.newznab.AudioLayout
import dev.usenetstream.engine.newznab.Codec
import dev.usenetstream.engine.newznab.DeviceCaps
import dev.usenetstream.engine.newznab.NzbResult
import dev.usenetstream.engine.newznab.Playability
import dev.usenetstream.engine.newznab.Quality
import dev.usenetstream.engine.newznab.ReleaseFilter
import dev.usenetstream.engine.newznab.ReleaseInfo
import dev.usenetstream.engine.newznab.ReleaseParser
import dev.usenetstream.engine.newznab.TitleGroup
import dev.usenetstream.engine.newznab.key
import dev.usenetstream.engine.newznab.playabilityOn
import dev.usenetstream.history.ResumePolicy
import kotlin.math.roundToInt

// ---------------------------------------------------------------- movie page

@Composable
fun MovieScreen(vm: MainViewModel, group: TitleGroup) {
    val history by vm.history.collectAsState()
    val myList by vm.myList.collectAsState()
    val rf by vm.releaseFilter.collectAsState()
    val failed by vm.failed.collectAsState()
    val watch = history.firstOrNull { it.id == group.key }
    val resuming = watch != null && ResumePolicy.inContinueWatching(watch)
    var showFilter by remember { mutableStateOf(false) }
    val ordered = orderReleases(vm, group.releases, rf, failed)

    val haze = remember { dev.chrisbanes.haze.HazeState() }
    Box(Modifier.fillMaxSize().background(Ink)) {
        LazyColumn(Modifier.fillMaxSize().then(Modifier.hazeSource(haze)), contentPadding = PaddingValues(bottom = 120.dp)) {
            item { DetailsHeader(group.key, group.coverUrl, group.title) { vm.back() } }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(group.title, style = MaterialTheme.typography.headlineMedium)
                    MetaRow(group.year, group.score, group.runtime, best = ordered.firstOrNull()?.let { ReleaseParser.parse(it) })
                    if (group.genres.isNotEmpty()) Text(group.genres.joinToString(" • "), color = Muted, style = MaterialTheme.typography.bodySmall)
                    if (resuming) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { watch!!.progress }, color = Red, trackColor = Card2,
                            drawStopIndicator = {}, gapSize = 0.dp, modifier = Modifier.fillMaxWidth().height(3.dp),
                        )
                        Text(formatLeft(watch!!.positionMs, watch.durationMs), color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    group.tagline?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 14.dp)) }
                }
            }
            releaseSection(vm, group.releases.size, ordered, rf, failed, vm.caps, onFilter = { showFilter = true }) { vm.playMovie(group, it) }
        }

        BottomActionBar(Modifier.align(Alignment.BottomCenter), blur = haze) {
            if (vm.phoneLink.tv.collectAsState().value != null) IconAction(Icons.Filled.PlayArrow, "TV") { vm.playOnTv(group.key, group.title, group.coverUrl) }
            ListToggle(myList.any { it.key == group.key }) { vm.toggleMyList(group.key, group.title, group.coverUrl, group.year) }
            PlayButton(if (resuming) "Resume" else "Play", Modifier.weight(1f)) { vm.playMovie(group) }
        }
    }
    if (showFilter) ReleaseFilterSheet(group.releases, rf, vm.caps, vm::setReleaseFilter) { showFilter = false }
}

/** Filtered by the sticky filter, then best-first; failed ones sink to the bottom. */
@Composable
fun orderReleases(vm: MainViewModel, releases: List<NzbResult>, rf: ReleaseFilter, failed: Set<String>): List<NzbResult> =
    remember(releases, rf, failed) {
        val kept = releases.filter { rf.matches(it, ReleaseParser.parse(it), vm.caps) }
        vm.rank(kept) + kept.filter { it.passworded }
    }

fun LazyListScope.releaseSection(
    vm: MainViewModel,
    total: Int,
    ordered: List<NzbResult>,
    rf: ReleaseFilter,
    failed: Set<String>,
    caps: DeviceCaps,
    onFilter: () -> Unit,
    onPlay: (NzbResult) -> Unit,
) {
    item {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Releases", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(if (ordered.size == total) "$total available" else "${ordered.size} of $total shown", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            FilterButton(rf.copy(hideLocked = true, hideUnplayable = false).active, ordered.size, onFilter)
        }
    }
    items(ordered, key = { it.key }) { r ->
        ReleaseRow(r, best = r == ordered.firstOrNull(), failed = r.key in failed, caps = caps, modifier = Modifier.animateItem()) { onPlay(r) }
    }
    if (ordered.isEmpty()) {
        item { Text("No releases match your filters.", color = Muted, modifier = Modifier.padding(16.dp)) }
    }
}

@Composable
fun ReleaseRow(r: NzbResult, best: Boolean, failed: Boolean, caps: DeviceCaps, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val info = remember(r) { ReleaseParser.parse(r) }
    val play = info.playabilityOn(caps)
    Column(modifier.fillMaxWidth().pressable(pressedScale = 0.98f, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (best && !failed && play == Playability.OK) Badge("BEST", Red, LocalPalette.current.onAccent)
                    releaseBadges(info).forEach { Badge(it) }
                    when {
                        failed -> Badge("FAILED BEFORE", Color(0xFF5A1A1A), Color(0xFFFF8A80))
                        play == Playability.NO -> Badge("CAN'T PLAY HERE", Color(0xFF5A4A1A), Color(0xFFFFD180))
                        play == Playability.NO_AUDIO -> Badge("NO AUDIO HERE", Color(0xFF5A4A1A), Color(0xFFFFD180))
                    }
                }
                Text(
                    r.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) Muted else Fg,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    listOfNotNull(r.sizeBytes?.let(::formatSize), formatAge(ReleaseParser.ageDays(r.pubDate)), r.grabs?.let { "$it grabs" }).joinToString(" · "),
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Icon(
                when {
                    r.passworded -> Icons.Filled.Lock
                    play != Playability.OK || failed -> Icons.Filled.Warning
                    else -> Icons.Filled.PlayArrow
                },
                null,
                tint = Muted,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
    HorizontalDivider(color = Divider)
}

fun releaseBadges(i: ReleaseInfo): List<String> = buildList {
    if (i.quality != Quality.UNKNOWN) add(i.quality.label)
    if (i.codec != Codec.UNKNOWN) add(i.codec.label)
    if (i.dolbyVision) add("DV") else if (i.hdr) add("HDR")
    if (i.audio != AudioLayout.UNKNOWN) add(i.audio.label)
    i.languages.forEach { add(it.uppercase()) }
    if (i.seasonPack) add("SEASON PACK")
}

@Composable
fun DetailsHeader(key: String, coverUrl: String?, title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(440.dp)) {
        // Blurred poster as backdrop, sharp poster on top: covers are portrait, the header is wide.
        Poster(coverUrl, title, Modifier.fillMaxSize().blur(28.dp))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x99000000), 0.6f to Color(0x55000000), 1f to Ink)))
        Poster(coverUrl, title, Modifier.align(Alignment.Center).padding(top = 24.dp).size(width = 184.dp, height = 276.dp).sharedPoster(key))
        IconButton(onBack, Modifier.statusBarsPadding().padding(4.dp).clip(CircleShape).background(Color(0x66000000))) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Fg)
        }
    }
}

@Composable
fun MetaRow(year: Int?, score: Double?, runtime: String?, best: ReleaseInfo? = null) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        year?.let { Text("$it", color = Muted) }
        score?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, null, tint = Color(0xFFF5C518), modifier = Modifier.size(16.dp))
                Text(" $it", color = Muted)
            }
        }
        runtime?.let { Text(it, color = Muted) }
        best?.takeIf { it.quality != Quality.UNKNOWN }?.let { Badge(it.quality.label, Card2) }
        best?.takeIf { it.hdr }?.let { Badge(if (it.dolbyVision) "DV" else "HDR", Card2) }
    }
}

// ---------------------------------------------------------------- release filter sheet

@Composable
fun ReleaseFilterSheet(releases: List<NzbResult>, current: ReleaseFilter, caps: DeviceCaps, onApply: (ReleaseFilter) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(current) }
    val infos = remember(releases) { releases.map { it to ReleaseParser.parse(it) } }
    val shown = infos.count { (r, i) -> f.matches(r, i, caps) }
    val languages = (listOf("English") + infos.flatMap { it.second.languages }).distinct()
    // Slider in 0.1 GB steps between the smallest and largest release (a 1-1 GB range would be a dead slider).
    val sizes = releases.mapNotNull { it.sizeBytes }.ifEmpty { listOf(1_000_000_000L) }
    val minGb = (kotlin.math.floor(sizes.min() / 1e8) / 10).toFloat()
    val maxGb = (kotlin.math.ceil(sizes.max() / 1e8) / 10).toFloat().coerceAtLeast(minGb + 0.1f)

    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Card) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            SheetHeader("Filter releases") { f = ReleaseFilter(hideLocked = current.hideLocked, hideUnplayable = current.hideUnplayable) }
            Label("Quality")
            ChipSet(listOf(Quality.SD, Quality.HD, Quality.FHD, Quality.UHD), f.qualities, { it.label }) { f = f.copy(qualities = it) }
            Label("Video codec")
            ChipSet(listOf(Codec.H264, Codec.HEVC, Codec.AV1), f.codecs, { it.label }) { f = f.copy(codecs = it) }
            Label("Picture")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip(f.hdr == null, { f = f.copy(hdr = null) }, { Text("Any") })
                GlassChip(f.hdr == true, { f = f.copy(hdr = true) }, { Text("HDR") })
                GlassChip(f.hdr == false, { f = f.copy(hdr = false) }, { Text("SDR") })
            }
            Label("Audio")
            ChipSet(listOf(AudioLayout.STEREO, AudioLayout.SURROUND, AudioLayout.ATMOS), f.audio, { it.label }) { f = f.copy(audio = it) }
            Label("Language")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip(f.language == null, { f = f.copy(language = null) }, { Text("Any") })
                languages.forEach { l -> GlassChip(f.language == l, { f = f.copy(language = l) }, { Text(l) }) }
            }
            val gb = (f.maxBytes ?: 0) / 1e9f
            Label("Max size  ${if (gb <= 0f) "any" else "%.1f GB".format(gb)}")
            Slider(
                value = if (gb <= 0f) maxGb else gb.coerceIn(minGb, maxGb),
                onValueChange = { v ->
                    val tenths = (v * 10).roundToInt() / 10f
                    f = f.copy(maxBytes = if (tenths >= maxGb) null else (tenths * 1e9).toLong())
                },
                valueRange = minGb..maxGb,
                colors = SliderDefaults.colors(thumbColor = Fg, activeTrackColor = Red, inactiveTrackColor = Card2),
            )
            Label("Posted within")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "Any", 7L to "1 week", 30L to "1 month", 365L to "1 year").forEach { (d, l) ->
                    GlassChip(f.maxAgeDays == d, { f = f.copy(maxAgeDays = d) }, { Text(l) })
                }
            }
            Spacer(Modifier.height(8.dp))
            ToggleRow("Hide releases this phone can't play", f.hideUnplayable) { f = f.copy(hideUnplayable = it) }
            ToggleRow("Hide password-locked", f.hideLocked) { f = f.copy(hideLocked = it) }
        }
        ApplyBar(if (shown == 0) "No releases match" else "Show $shown releases") { onApply(f); onDismiss() }
    }
}

@Composable
private fun <T> ChipSet(options: List<T>, selected: Set<T>, label: (T) -> String, onChange: (Set<T>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            GlassChip(o in selected, { onChange(if (o in selected) selected - o else selected + o) }, { Text(label(o)) })
        }
    }
}

// ---------------------------------------------------------------- lookup + opening

@Composable
fun LookupScreen(r: Route.Lookup) {
    Column(Modifier.fillMaxSize().background(Ink)) {
        DetailsHeader(r.key, r.coverUrl, r.title) {}
        Column(Modifier.padding(16.dp)) {
            Text(r.title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            ShimmerBox(Modifier.width(180.dp).height(14.dp))
            Spacer(Modifier.height(24.dp))
            repeat(4) { ShimmerBox(Modifier.fillMaxWidth().height(56.dp).padding(vertical = 4.dp)) }
        }
    }
}

@Composable
fun OpeningScreen(r: Route.Opening, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        Poster(r.coverUrl, r.title, Modifier.fillMaxSize().blur(36.dp))
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)))
        Column(
            Modifier.align(Alignment.Center).padding(32.dp).animateContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = Red, strokeWidth = 3.dp, modifier = Modifier.size(52.dp))
            Text(
                r.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp, bottom = 16.dp),
            )
            r.steps.forEach { s ->
                AnimatedContent(s, transitionSpec = { (fadeIn(tween(250)) + slideInVertically { it / 2 }) togetherWith fadeOut(tween(150)) }, label = "step") { st ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        when (st.state) {
                            StepState.DONE -> Icon(Icons.Filled.Check, null, tint = Color(0xFF46D369), modifier = Modifier.size(16.dp))
                            StepState.FAILED -> Icon(Icons.Filled.Warning, null, tint = Red, modifier = Modifier.size(16.dp))
                            else -> CircularProgressIndicator(Modifier.size(14.dp), color = Fg, strokeWidth = 2.dp)
                        }
                        Text(
                            st.label,
                            color = if (st.state == StepState.FAILED) Color(0xFFFF8A80) else Muted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
        BottomActionBar(Modifier.align(Alignment.BottomCenter)) {
            Spacer(Modifier.weight(1f))
            PlayButton("Cancel", icon = null, onClick = onCancel)
            Spacer(Modifier.weight(1f))
        }
    }
}
