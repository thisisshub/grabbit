@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.usenetstream.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.offset
import dev.usenetstream.engine.newznab.playabilityOn
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import dev.usenetstream.MainViewModel
import dev.usenetstream.OttMatch
import dev.usenetstream.OttState
import dev.usenetstream.engine.ott.Brand
import dev.usenetstream.engine.ott.OttItem
import dev.usenetstream.engine.ott.OttService

// ---------------------------------------------------------------- brand bits

fun OttService.brush(): Brush = Brush.linearGradient(listOf(Color(color), Color(color2)))
fun OttService.onBrand(): Color = Color(Brand.onColor(color))

/** "NEW · 2d", "NEW · today" from a unix-seconds timestamp. */
fun sinceLabel(since: Long?, nowMs: Long = System.currentTimeMillis()): String {
    val days = since?.let { (nowMs / 1000 - it) / 86_400 } ?: return "NEW"
    return when {
        days <= 0 -> "NEW · today"
        days == 1L -> "NEW · yesterday"
        else -> "NEW · ${days}d ago"
    }
}

/** "Leaves in 3d" for an upcoming timestamp. */
fun leavesLabel(at: Long?, nowMs: Long = System.currentTimeMillis()): String? {
    val days = at?.let { (it - nowMs / 1000) / 86_400 } ?: return null
    return if (days <= 0) "Leaves today" else "Leaves in ${days}d"
}

/**
 * The brand colour, nudged until it reads as text on [over] (Apple's graphite would vanish on a dark page).
 * Lightens on dark backgrounds, darkens on light ones; Netflix red and Prime blue pass unchanged.
 */
fun brandInk(s: OttService, over: Color): Color {
    val toward = if (over.luminance() < 0.5f) Color.White else Color.Black
    var c = Color(s.color)
    var t = 0f
    while (contrast(c, over) < 3.2 && t < 1f) {
        t += 0.1f
        c = androidx.compose.ui.graphics.lerp(Color(s.color), toward, t)
    }
    return c
}

/** The service's name, spelled out in its colour. */
@Composable
fun ServiceName(s: OttService, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier, over: Color = Ink, color: Color? = null) {
    Text(
        s.name,
        color = color ?: brandInk(s, over),
        fontSize = fontSize,
        letterSpacing = 0.2.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * Empty pill for a service: a faint wash of its colour, a soft glow, a crisp brand border, the name centered.
 * [over] is what's behind it (for text contrast); [scrim] darkens it when it sits on artwork.
 */
@Composable
fun BrandPill(
    s: OttService,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 17.sp,
    over: Color = Ink,
    scrim: Boolean = false,
    glow: Boolean = true,
    label: String? = null,
) {
    val ink = brandInk(s, if (scrim) Color(0xFF000000) else over)
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .let { if (glow && !LocalLowEndUi.current) it.shadow(14.dp, shape, ambientColor = ink, spotColor = ink) else it }
            .clip(shape)
            .background(if (scrim) Color(0xB3000000) else over)
            .background(Color(s.color).copy(alpha = 0.16f))
            .border(1.5.dp, ink, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label ?: s.name, color = ink, fontSize = fontSize, letterSpacing = 0.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp))
    }
}

@Composable
fun ServicePill(service: OttService, onClick: () -> Unit) {
    BrandPill(service, Modifier.height(44.dp).widthIn(min = 120.dp).pressable(pressedScale = 0.95f, onClick = onClick))
}

/** Small brand badge for a card corner (sits on artwork, so it gets a dark scrim). */
@Composable
fun ServiceChip(service: OttService, modifier: Modifier = Modifier, onArt: Boolean = true) {
    BrandPill(service, modifier.height(24.dp), fontSize = 11.sp, scrim = onArt, glow = false)
}

/** 16:9 key art with the service badge; caption under it. */
@Composable
fun WideOttCard(item: OttItem, service: OttService?, caption: String?, width: Dp, onClick: () -> Unit) {
    val t = item.title
    Column(Modifier.width(width).pressable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Card)) {
            val art = t.wide ?: t.backdrop
            if (art != null) Art(art, t.title, Modifier.fillMaxSize()) else NoArt(t.title, service)
            service?.let { ServiceChip(it, Modifier.align(Alignment.TopStart).padding(8.dp)) }
        }
        Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        caption?.let { Text(it, color = service?.let { s -> brandText(s) } ?: Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
    }
}

/** No key art: the title set on the service's colours. */
@Composable
fun NoArt(title: String, service: OttService?, fontSize: androidx.compose.ui.unit.TextUnit = 22.sp) {
    Box(
        Modifier.fillMaxSize().background(service?.brush() ?: Brush.linearGradient(listOf(Card2, Card))).padding(16.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(title, color = service?.onBrand() ?: Fg, fontSize = fontSize, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A brand colour that stays readable as small text on the page (falls back to the accent). */
@Composable
fun brandText(s: OttService): Color {
    val c = Color(s.color)
    return if (contrast(c, Ink) >= 3.0) c else Red
}

/** Netflix-style Top 10 tile: an outlined rank numeral tucked behind the poster. */
@Composable
fun RankedPoster(item: OttItem, rank: Int, service: OttService?, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.pressable(onClick = onClick)) {
        Text(
            "$rank",
            color = service?.let { Color(it.color) }?.takeIf { contrast(it, Ink) >= 2.0 } ?: Fg.copy(alpha = 0.85f),
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 96.sp, drawStyle = Stroke(width = 5f), letterSpacing = (-6).sp),
            modifier = Modifier.padding(end = 0.dp),
        )
        Box(Modifier.width(112.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Card)) {
            Art(item.title.poster, item.title.title, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Art(url: String?, title: String, modifier: Modifier) {
    if (url == null) return Poster(null, title, modifier)
    val context = LocalContext.current
    val lowEnd = LocalLowEndUi.current
    if (lowEnd) {
        AsyncImage(remember(url) { coil.request.ImageRequest.Builder(context).data(url).allowRgb565(true).build() }, title, modifier, contentScale = ContentScale.Crop)
    } else {
        SubcomposeAsyncImage(
            model = remember(url) { coil.request.ImageRequest.Builder(context).data(url).crossfade(220).build() },
            contentDescription = title,
            contentScale = ContentScale.Crop,
            loading = { ShimmerBox(Modifier.fillMaxSize()) },
            modifier = modifier,
        )
    }
}

// ---------------------------------------------------------------- home rows

/** Brand pills, "New on your services", and one Top 10 per service; nothing when no guide key is set. */
fun LazyListScope.ottHomeRows(vm: MainViewModel, ott: OttState) {
    if (ott.services.isEmpty()) {
        ott.error?.let { msg ->
            item(key = "ott:error") {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    GlassButton({ vm.loadOtt(force = true) }, primary = false, height = 40.dp) { Text("Retry") }
                }
            }
        }
        return
    }
    item(key = "ott:pills") {
        Column {
            RowTitle("Your services")
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ott.services, key = { it.id }) { s -> ServicePill(s) { vm.openService(s.id) } }
            }
        }
    }
    if (ott.newItems.isNotEmpty()) {
        item(key = "ott:new") {
            Column {
                RowTitle("New on your services")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(ott.newItems.take(30), key = { "${it.serviceId}:${it.title.id}" }) { item ->
                        WideOttCard(item, ott.service(item.serviceId), sinceLabel(item.since), 260.dp) { vm.openOtt(item) }
                    }
                }
            }
        }
    }
    for ((id, list) in ott.top) {
        val s = ott.service(id) ?: continue
        item(key = "ott:top:$id") {
            Column {
                BrandRowTitle("Top 10 on", s)
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(list.take(10), key = { _, it -> it.title.id }) { i, item -> RankedPoster(item, i + 1, s) { vm.openOtt(item) } }
                }
            }
        }
    }
}

/** "Top 10 on [NETFLIX]" with the logo in its brand chip. */
@Composable
fun BrandRowTitle(text: String, s: OttService) {
    Row(Modifier.padding(start = 16.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(10.dp))
        ServiceChip(s, onArt = false)
    }
}

// ---------------------------------------------------------------- service page

@Composable
fun ServiceScreen(vm: MainViewModel, id: String) {
    val ott by vm.ott.collectAsState()
    val s = ott.service(id) ?: return
    var kind by rememberSaveable { mutableStateOf<Boolean?>(null) } // null = all, true = series, false = movies
    fun List<OttItem>.pick() = if (kind == null) this else filter { it.title.isSeries == kind }
    val top = ott.top[id].orEmpty().pick()
    val fresh = ott.newOn(id).pick()
    val leaving = ott.leaving[id]?.pick()
    val hero = (top.firstOrNull() ?: fresh.firstOrNull())?.title

    LazyColumn(Modifier.fillMaxSize().background(Ink), contentPadding = PaddingValues(bottom = navInset() + 24.dp)) {
        item(key = "hero") {
            Box(Modifier.fillMaxWidth().height(400.dp)) {
                hero?.let { Art(it.backdrop ?: it.wide, it.title, Modifier.fillMaxSize()) }
                // The brand washes in from the bottom-left so the logo always sits on its own colour.
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(0f to Color(s.color).copy(alpha = 0.95f), 0.55f to Color(s.color2).copy(alpha = 0.55f), 1f to Color.Transparent, start = androidx.compose.ui.geometry.Offset(0f, Float.POSITIVE_INFINITY), end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, 0f))))
                // Fade into the page only below the logo line, so white text stays on the brand colour in light mode too.
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.9f to Color.Transparent, 1f to Ink)))
                IconButton(vm::back, Modifier.statusBarsPadding().padding(4.dp).clip(CircleShape).background(Color(0x66000000))) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    // On its own colour wash the name goes white, big.
                    ServiceName(s, 40.sp, color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        listOfNotNull(
                            ott.newOn(id).size.takeIf { it > 0 }?.let { "$it new this month" },
                            ott.top[id]?.size?.takeIf { it > 0 }?.let { "Top $it today" },
                        ).joinToString(" · ").ifEmpty { "What's on ${s.name}" },
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item(key = "filter") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip(kind == null, { kind = null }, { Text("All") })
                GlassChip(kind == false, { kind = false }, { Text("Movies") })
                GlassChip(kind == true, { kind = true }, { Text("Series") })
            }
        }
        if (top.isNotEmpty()) {
            item(key = "top") {
                Column {
                    RowTitle("Top 10 today")
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(top.take(10), key = { _, it -> it.title.id }) { i, item -> RankedPoster(item, i + 1, s) { vm.openOtt(item) } }
                    }
                }
            }
        }
        if (fresh.isNotEmpty()) {
            item(key = "new") {
                Column {
                    RowTitle("New this month")
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(fresh, key = { it.title.id }) { item -> WideOttCard(item, null, sinceLabel(item.since), 260.dp) { vm.openOtt(item) } }
                    }
                }
            }
        }
        item(key = "leaving") {
            when {
                leaving == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), color = Red, strokeWidth = 2.dp) }
                leaving.isNotEmpty() -> Column {
                    RowTitle("Leaving soon")
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(leaving, key = { it.title.id }) { item ->
                            Column(Modifier.width(112.dp).pressable { vm.openOtt(item) }) {
                                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Card)) { Art(item.title.poster, item.title.title, Modifier.fillMaxSize()) }
                                leavesLabel(item.since)?.let { Text(it, color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp)) }
                            }
                        }
                    }
                }
            }
        }
        if (top.isEmpty() && fresh.isEmpty()) {
            item(key = "empty") { Text("Nothing new on ${s.name} this month.", color = Muted, modifier = Modifier.padding(20.dp)) }
        }
    }
}

// ---------------------------------------------------------------- title page

/** "#3 on Netflix today", "New on Prime · 2 days ago", "Leaves Netflix in 5 days": why this title is in front of you. */
fun whyHere(item: OttItem, service: OttService?, leavingAt: Long?, nowMs: Long = System.currentTimeMillis()): String? {
    val name = service?.name ?: return null
    leavingAt?.let { at ->
        val d = (at - nowMs / 1000) / 86_400
        return if (d <= 0) "Leaves $name today" else "Leaves $name in $d day${if (d == 1L) "" else "s"}"
    }
    item.rank?.let { return "#$it on $name today" }
    item.since?.let { at ->
        val d = (nowMs / 1000 - at) / 86_400
        return "New on $name · " + when { d <= 0 -> "today"; d == 1L -> "yesterday"; else -> "$d days ago" }
    }
    return "Streaming on $name"
}

/** Circular score ring: 0..100 rating drawn as an arc, "7.4" in the middle. */
@Composable
fun RatingRing(rating: Int, size: Dp = 56.dp) {
    val p = LocalPalette.current
    val sweep by androidx.compose.animation.core.animateFloatAsState(rating / 100f * 360f, androidx.compose.animation.core.tween(900), label = "ring")
    val tint = when {
        rating >= 75 -> Color(0xFF46D369)
        rating >= 60 -> Color(0xFFF5C518)
        else -> Color(0xFFFF8A65)
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = size.toPx() / 11, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawArc(p.divider, 0f, 360f, false, style = stroke)
            drawArc(tint, -90f, sweep, false, style = stroke)
        }
        Text("%.1f".format(rating / 10.0), style = MaterialTheme.typography.titleSmall)
    }
}

/** Colourful initials bubble for a person (the guide has names, no photos). */
@Composable
fun PersonBubble(name: String, role: String?) {
    val hues = listOf(0xFF2F2FE4, 0xFFE50914, 0xFF00A8E1, 0xFF8230C6, 0xFFFF5E00, 0xFF0E8A16, 0xFFD6336C, 0xFF1F7A8C)
    val c = Color(hues[(name.hashCode() and 0x7fffffff) % hues.size])
    val initials = name.split(' ').filter { it.isNotBlank() }.let { w -> (w.firstOrNull()?.take(1).orEmpty() + (if (w.size > 1) w.last().take(1) else "")).uppercase() }
    Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(60.dp).clip(CircleShape).background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.55f)))),
            contentAlignment = Alignment.Center,
        ) { Text(initials, color = Color.White, style = MaterialTheme.typography.titleMedium) }
        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        role?.let { Text(it, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
    }
}

/** Directors/creators first (with their role), then cast; someone who is both appears once. */
fun castAndCrew(t: dev.usenetstream.engine.ott.OttTitle): List<Pair<String, String?>> {
    val role = if (t.isSeries) "Creator" else "Director"
    val makers = t.makers.take(3)
    return makers.map { it to role } + t.cast.filter { it !in makers }.take(12).map { it to null }
}

/** Titles from the lanes that share a genre with [t], best-known first. */
fun moreLike(ott: OttState, t: dev.usenetstream.engine.ott.OttTitle, max: Int = 12): List<OttItem> {
    val genres = t.genres.toSet()
    if (genres.isEmpty()) return emptyList()
    val pool = ott.top.values.flatten() + ott.newItems + ott.leaving.values.flatten()
    return pool.filter { it.title.id != t.id }
        .distinctBy { it.title.id }
        .map { it to it.title.genres.count { g -> g in genres } }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<OttItem, Int>> { it.second }.thenByDescending { it.first.title.rating ?: 0 })
        .take(max)
        .map { it.first }
}

@Composable
fun OttTitleScreen(vm: MainViewModel, item: OttItem) {
    val ott by vm.ott.collectAsState()
    val matches by vm.ottMatch.collectAsState()
    val t = item.title
    val s = ott.service(item.serviceId)
    val match = matches[t.id] ?: OttMatch.Checking
    val context = LocalContext.current
    val link = t.linkOn(item.serviceId)
    val leavingAt = ott.leaving[item.serviceId]?.firstOrNull { it.title.id == t.id }?.since
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var storyOpen by rememberSaveable { mutableStateOf(false) }
    val similar = remember(ott, t.id) { moreLike(ott, t) }

    LazyColumn(Modifier.fillMaxSize().background(Ink), state = listState, contentPadding = PaddingValues(bottom = navInset() + 32.dp)) {
        item(key = "hero") {
            Box(Modifier.fillMaxWidth().height(330.dp)) {
                // Backdrop drifts at half speed as the page scrolls.
                val drift by remember { androidx.compose.runtime.derivedStateOf { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset * 0.5f else 0f } }
                Art(t.backdrop ?: t.wide, t.title, Modifier.fillMaxSize().graphicsLayer { translationY = drift })
                s?.let { Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(it.color).copy(alpha = 0.25f), 0.5f to Color.Transparent))) }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x66000000), 0.35f to Color.Transparent, 0.75f to Ink.copy(alpha = 0.6f), 1f to Ink)))
                IconButton(vm::back, Modifier.statusBarsPadding().padding(4.dp).clip(CircleShape).background(Color(0x66000000))) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
            }
        }
        item(key = "head") {
            Row(Modifier.padding(horizontal = 20.dp).offset(y = (-72).dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.width(112.dp).aspectRatio(2f / 3f).shadow(16.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(Card)) {
                    Art(t.poster, t.title, Modifier.fillMaxSize())
                }
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    s?.let { ServiceChip(it, onArt = false) }
                    Text(t.title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    t.originalTitle?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        item(key = "meta") {
            Column(Modifier.padding(horizontal = 20.dp).offset(y = (-56).dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    t.rating?.let { RatingRing(it) }
                    Column {
                        Text(
                            listOfNotNull(
                                t.year?.toString(),
                                if (t.isSeries) listOfNotNull(t.seasonCount?.let { "$it season${if (it == 1) "" else "s"}" }, t.episodeCount?.let { "$it episodes" }).joinToString(" · ").ifEmpty { "Series" }
                                else t.runtimeMin?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") },
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        whyHere(item, s, leavingAt)?.let { Text(it, color = s?.let { brandInk(it, Ink) } ?: Red, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp)) }
                    }
                }
                if (t.genres.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        t.genres.take(5).forEach { GlassTag(it) }
                    }
                }

                // ---- actions
                when (match) {
                    OttMatch.Checking -> Row(Modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Red, strokeWidth = 2.dp)
                        Text("  Looking on Usenet…", color = Muted)
                    }
                    is OttMatch.Movie -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton({ vm.playOtt(item) }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(26.dp))
                            Text(" Play", style = MaterialTheme.typography.titleMedium)
                        }
                        UsenetSummary(vm, match.group) { vm.openTitle(match.group) }
                    }
                    is OttMatch.Show -> GlassButton({ vm.playOtt(item) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(26.dp))
                        Text(" Episodes", style = MaterialTheme.typography.titleMedium)
                    }
                    OttMatch.NotFound -> Text("🕐  Not on Usenet yet. New releases usually show up within a few days.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    is OttMatch.Failed -> Column {
                        Text(match.message, color = Muted)
                        GlassButton({ vm.back(); vm.openOtt(item) }, Modifier.padding(top = 8.dp), primary = false, height = 44.dp) { Text("Try again") }
                    }
                }
                if (s != null && link != null) {
                    BrandPill(
                        s,
                        Modifier.fillMaxWidth().height(52.dp).pressable {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.videoLink ?: link.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                .onFailure { vm.notifyUser("Can't open ${s.name}") }
                        },
                        label = "Open in ${s.name}",
                    )
                }

                // ---- story
                t.overview?.let { story ->
                    Column(Modifier.pressable { storyOpen = !storyOpen }) {
                        Text("The story", style = MaterialTheme.typography.titleMedium)
                        Text(story, style = MaterialTheme.typography.bodyMedium, maxLines = if (storyOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).animateContentSize())
                        if (!storyOpen && story.length > 200) Text("More", color = Red, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    }
                }

                // ---- where to watch
                val options = t.links.filter { it.type != "rent" && it.type != "buy" }.distinctBy { it.serviceId }
                if (options.isNotEmpty()) {
                    Text("Where to watch", style = MaterialTheme.typography.titleMedium)
                    options.forEach { l ->
                        val svc = ott.service(l.serviceId) ?: return@forEach
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BrandPill(svc, Modifier.height(34.dp).widthIn(min = 110.dp), fontSize = 13.sp, glow = false)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                if (l.audios.isNotEmpty()) Text("🔊 " + l.audios.take(6).joinToString(" · ") { dev.usenetstream.engine.ott.Languages.name(it) }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (l.subtitles.isNotEmpty()) Text("💬 " + l.subtitles.take(6).joinToString(" · ") { dev.usenetstream.engine.ott.Languages.name(it) }, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // ---- people
        val people = castAndCrew(t)
        if (people.isNotEmpty()) {
            item(key = "people") {
                Column(Modifier.offset(y = (-40).dp)) {
                    RowTitle("Cast & crew")
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(people, key = { it.first + it.second }) { (name, role) -> PersonBubble(name, role) }
                    }
                }
            }
        }
        if (similar.isNotEmpty()) {
            item(key = "similar") {
                Column(Modifier.offset(y = (-24).dp)) {
                    RowTitle("More like this")
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(similar, key = { it.title.id }) { other ->
                            Column(Modifier.width(112.dp).pressable { vm.openOtt(other) }) {
                                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(Card)) { Art(other.title.poster, other.title.title, Modifier.fillMaxSize()) }
                                Text(other.title.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "7 copies · up to 4K · 1080p plays here" under Play; tap for the release list. */
@Composable
private fun UsenetSummary(vm: MainViewModel, group: dev.usenetstream.engine.newznab.TitleGroup, onOpen: () -> Unit) {
    val infos = remember(group) { group.releases.map { dev.usenetstream.engine.newznab.ReleaseParser.parse(it) } }
    val order = listOf(dev.usenetstream.engine.newznab.Quality.SD, dev.usenetstream.engine.newznab.Quality.HD, dev.usenetstream.engine.newznab.Quality.FHD, dev.usenetstream.engine.newznab.Quality.UHD)
    val best = infos.map { it.quality }.filter { it in order }.maxByOrNull { order.indexOf(it) }
    val bestHere = infos.filter { it.playabilityOn(vm.caps) == dev.usenetstream.engine.newznab.Playability.OK }
        .map { it.quality }.filter { it in order }.maxByOrNull { order.indexOf(it) }
    GlassButton(onOpen, Modifier.fillMaxWidth(), primary = false, height = 46.dp) {
        Text(
            listOfNotNull(
                "${group.releases.size} cop${if (group.releases.size == 1) "y" else "ies"} on Usenet",
                best?.let { "up to ${it.label}" },
                bestHere?.takeIf { it != best }?.let { "${it.label} plays here" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
