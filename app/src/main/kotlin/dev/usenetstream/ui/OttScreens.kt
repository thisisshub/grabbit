package dev.usenetstream.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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

/** The service's white logo (SVG), or its name when the logo can't load. */
@Composable
fun ServiceLogo(service: OttService, height: Dp, modifier: Modifier = Modifier) {
    val name: @Composable () -> Unit = {
        Text(service.name, color = service.onBrand(), style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
    if (service.logoWhite == null) return Box(modifier) { name() }
    SubcomposeAsyncImage(
        model = service.logoWhite,
        contentDescription = service.name,
        contentScale = ContentScale.Fit,
        alignment = Alignment.CenterStart,
        error = { name() },
        modifier = modifier.height(height),
    )
}

@Composable
fun ServicePill(service: OttService, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(service.brush())
            .pressable(pressedScale = 0.95f, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        ServiceLogo(service, 24.dp, Modifier.width(96.dp))
    }
}

/** Small brand badge for a card corner. */
@Composable
fun ServiceChip(service: OttService, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(service.brush()).padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        ServiceLogo(service, 13.dp, Modifier.width(52.dp))
    }
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
        Spacer(Modifier.width(8.dp))
        ServiceChip(s)
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
                    ServiceLogo(s, 40.dp, Modifier.width(180.dp))
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

@Composable
fun OttTitleScreen(vm: MainViewModel, item: OttItem) {
    val ott by vm.ott.collectAsState()
    val matches by vm.ottMatch.collectAsState()
    val t = item.title
    val s = ott.service(item.serviceId)
    val match = matches[t.id] ?: OttMatch.Checking
    val context = LocalContext.current
    val link = t.linkOn(item.serviceId)

    LazyColumn(Modifier.fillMaxSize().background(Ink), contentPadding = PaddingValues(bottom = navInset() + 24.dp)) {
        item(key = "hero") {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                Art(t.backdrop ?: t.wide, t.title, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x80000000), 0.35f to Color.Transparent, 1f to Ink)))
                IconButton(vm::back, Modifier.statusBarsPadding().padding(4.dp).clip(CircleShape).background(Color(0x66000000))) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
            }
        }
        item(key = "body") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                s?.let { ServiceChip(it) }
                Text(t.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    t.year?.let { Text("$it", color = Muted) }
                    t.rating?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Star, null, tint = Color(0xFFF5C518), modifier = Modifier.size(16.dp))
                            Text(" %.1f".format(it / 10.0), color = Muted)
                        }
                    }
                    t.runtimeMin?.let { Text("${it / 60}h ${it % 60}m".removePrefix("0h "), color = Muted) }
                    Text(if (t.isSeries) "Series" else "Movie", color = Muted)
                }
                if (t.genres.isNotEmpty()) Text(t.genres.take(3).joinToString(" · "), color = Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(18.dp))

                when (match) {
                    OttMatch.Checking -> Row(Modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Red, strokeWidth = 2.dp)
                        Text("  Looking on Usenet…", color = Muted)
                    }
                    is OttMatch.Movie -> {
                        GlassButton({ vm.playOtt(item) }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(26.dp))
                            Text(" Play", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(10.dp))
                        GlassButton({ vm.openTitle(match.group) }, Modifier.fillMaxWidth(), primary = false) {
                            Text("${match.group.releases.size} releases", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    is OttMatch.Show -> GlassButton({ vm.playOtt(item) }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(26.dp))
                        Text(" Episodes", style = MaterialTheme.typography.titleMedium)
                    }
                    OttMatch.NotFound -> Text("Not on Usenet yet. New releases usually show up within a few days.", color = Muted)
                    is OttMatch.Failed -> Column {
                        Text(match.message, color = Muted)
                        GlassButton({ vm.back(); vm.openOtt(item) }, Modifier.padding(top = 8.dp), primary = false, height = 44.dp) { Text("Try again") }
                    }
                }

                if (s != null && link != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(s.brush())
                            .pressable {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.videoLink ?: link.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                    .onFailure { vm.notifyUser("Can't open ${s.name}") }
                            },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Open in ", color = s.onBrand(), style = MaterialTheme.typography.titleSmall)
                        ServiceLogo(s, 16.dp, Modifier.width(70.dp))
                    }
                }
                t.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 18.dp)) }
            }
        }
    }
}
