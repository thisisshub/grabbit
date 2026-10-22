@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.usenetstream.ui.tv

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.usenetstream.MainViewModel
import dev.usenetstream.OttMatch
import dev.usenetstream.OttState
import dev.usenetstream.engine.ott.OttItem
import dev.usenetstream.engine.ott.OttService
import dev.usenetstream.ui.DarkPalette
import dev.usenetstream.ui.ServiceLogo
import dev.usenetstream.ui.brush
import dev.usenetstream.ui.leavesLabel
import dev.usenetstream.ui.onBrand
import dev.usenetstream.ui.sinceLabel
import kotlinx.coroutines.delay

private val P = DarkPalette

/** Artwork without subcomposition or fades (cheap on TV chips); 16-bit decode. */
@Composable
private fun TvArt(url: String?, title: String, modifier: Modifier) {
    if (url == null) return Box(modifier.background(P.surface))
    val context = LocalContext.current
    AsyncImage(
        model = remember(url) { coil.request.ImageRequest.Builder(context).data(url).allowRgb565(true).crossfade(false).build() },
        contentDescription = title,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------- home rows

fun LazyListScope.tvOttRows(vm: MainViewModel, ott: OttState) {
    if (ott.services.isEmpty()) return
    item(key = "ott:pills") {
        TvRowTitle("Your services")
        LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = TvSafeH, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(ott.services, key = { it.id }) { s -> TvServicePill(s) { vm.openService(s.id) } }
        }
    }
    if (ott.newItems.isNotEmpty()) item(key = "ott:new") {
        TvRowTitle("New on your services")
        LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = TvSafeH, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            items(ott.newItems.take(30), key = { "${it.serviceId}:${it.title.id}" }) { item ->
                TvWideCard(item, ott.service(item.serviceId), sinceLabel(item.since)) { vm.openOtt(item) }
            }
        }
    }
    for ((id, list) in ott.top) {
        val s = ott.service(id) ?: continue
        item(key = "ott:top:$id") {
            TvBrandTitle("Top 10 on", s)
            TvTopRow(list.take(10), s) { vm.openOtt(it) }
        }
    }
}

@Composable
private fun TvBrandTitle(text: String, s: OttService) {
    Row(Modifier.padding(start = TvSafeH, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(s.brush()).padding(horizontal = 8.dp, vertical = 4.dp)) { ServiceLogo(s, 14.dp, Modifier.width(60.dp)) }
    }
}

@Composable
private fun TvServicePill(s: OttService, onClick: () -> Unit) {
    TvFocusable(
        onClick = onClick,
        modifier = Modifier.height(56.dp).width(170.dp),
        shape = RoundedCornerShape(28.dp),
        container = Color(s.color),
        focusedContainer = Color(s.color),
        content = s.onBrand(),
        focusedContent = s.onBrand(),
        focusedScale = 1.1f,
    ) {
        Box(Modifier.fillMaxSize().background(s.brush()), contentAlignment = Alignment.Center) { ServiceLogo(s, 30.dp, Modifier.width(130.dp)) }
    }
}

@Composable
private fun TvWideCard(item: OttItem, s: OttService?, caption: String?, width: Dp = 320.dp, onFocus: (() -> Unit)? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus?.invoke() }
    Column(Modifier.width(width)) {
        TvFocusable(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), shape = RoundedCornerShape(10.dp), focusedScale = 1.08f, interaction = interaction) {
            val art = item.title.wide ?: item.title.backdrop
            if (art != null) TvArt(art, item.title.title, Modifier.fillMaxSize()) else dev.usenetstream.ui.NoArt(item.title.title, s, 26.sp)
            s?.let { Box(Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(it.brush()).padding(horizontal = 7.dp, vertical = 4.dp)) { ServiceLogo(it, 16.dp, Modifier.width(64.dp)) } }
        }
        Column(Modifier.height(48.dp).padding(top = 10.dp)) {
            Text(item.title.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (focused) P.fg else P.muted)
            caption?.let { Text(it, fontSize = 12.sp, color = P.accent, maxLines = 1) }
        }
    }
}

@Composable
private fun TvTopRow(items: List<OttItem>, s: OttService, onFocus: ((OttItem) -> Unit)? = null, onClick: (OttItem) -> Unit) {
    LazyRow(Modifier.focusRestorer(), contentPadding = PaddingValues(horizontal = TvSafeH, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(items, key = { _, it -> it.title.id }) { i, item ->
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            LaunchedEffect(focused) { if (focused) onFocus?.invoke(item) }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${i + 1}",
                    color = Color(s.color).takeIf { dev.usenetstream.ui.contrast(it, P.bg) >= 2.0 } ?: P.fg,
                    style = TextStyle(fontFamily = dev.usenetstream.ui.Sans, fontSize = 110.sp, drawStyle = Stroke(width = 5f), letterSpacing = (-6).sp),
                )
                TvFocusable(onClick = { onClick(item) }, modifier = Modifier.width(140.dp).aspectRatio(2f / 3f), shape = RoundedCornerShape(8.dp), focusedScale = 1.1f, interaction = interaction) {
                    TvArt(item.title.poster, item.title.title, Modifier.fillMaxSize())
                }
            }
        }
    }
}

// ---------------------------------------------------------------- service page

@Composable
fun TvService(vm: MainViewModel, id: String) {
    val ott by vm.ott.collectAsState()
    val s = ott.service(id) ?: return
    val top = ott.top[id].orEmpty()
    val fresh = ott.newOn(id)
    val leaving = ott.leaving[id]
    // The hero follows focus: whatever card is focused shows behind the logo.
    var shown by remember(id) { mutableStateOf((top.firstOrNull() ?: fresh.firstOrNull())?.title) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(P.bg)) {
        Box(Modifier.fillMaxWidth().height(420.dp)) {
            shown?.let { TvArt(it.backdrop ?: it.wide, it.title, Modifier.fillMaxSize()) }
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(0f to Color(s.color).copy(alpha = 0.95f), 0.5f to Color(s.color2).copy(alpha = 0.6f), 1f to Color.Transparent, start = Offset(0f, Float.POSITIVE_INFINITY), end = Offset(Float.POSITIVE_INFINITY, 0f))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to P.bg)))
        }
        Column(Modifier.fillMaxSize()) {
        // Fixed band for the logo and the focused title; the rows scroll underneath it, never over it.
        Column(Modifier.fillMaxWidth().height(200.dp).padding(start = TvSafeH, top = TvSafeV + 8.dp)) {
            ServiceLogo(s, 40.dp, Modifier.width(200.dp))
            shown?.let {
                Text(it.title, fontSize = 28.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp).width(640.dp))
                it.overview?.let { o -> Text(o, fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp).width(640.dp)) }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = TvSafeV)) {
            if (top.isNotEmpty()) item {
                TvRowTitle("Top 10 today")
                Box(Modifier.focusRequester(first)) { TvTopRow(top.take(10), s, onFocus = { shown = it.title }) { vm.openOtt(it) } }
            }
            if (fresh.isNotEmpty()) item {
                TvRowTitle("New this month")
                LazyRow(
                    (if (top.isEmpty()) Modifier.focusRequester(first) else Modifier).focusRestorer(),
                    contentPadding = PaddingValues(horizontal = TvSafeH, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(fresh, key = { it.title.id }) { item -> TvWideCard(item, null, sinceLabel(item.since), onFocus = { shown = item.title }) { vm.openOtt(item) } }
                }
            }
            item {
                when {
                    leaving == null -> CircularProgressIndicator(Modifier.padding(start = TvSafeH, top = 20.dp).size(28.dp), color = P.accent)
                    leaving.isNotEmpty() -> {
                        TvRowTitle("Leaving soon")
                        TvPosterRow(leaving.map { item -> TvTile(item.title.id, item.title.title, item.title.poster, null, leavesLabel(item.since)) { vm.openOtt(item) } })
                    }
                }
            }
        }
        }
    }
}

// ---------------------------------------------------------------- title page

@Composable
fun TvOttTitle(vm: MainViewModel, item: OttItem) {
    val ott by vm.ott.collectAsState()
    val matches by vm.ottMatch.collectAsState()
    val t = item.title
    val s = ott.service(item.serviceId)
    val match = matches[t.id] ?: OttMatch.Checking
    val context = LocalContext.current
    val link = t.linkOn(item.serviceId)
    val main = remember { FocusRequester() }
    LaunchedEffect(match) { delay(150); runCatching { main.requestFocus() } }

    Box(Modifier.fillMaxSize().background(P.bg)) {
        TvArt(t.backdrop ?: t.wide, t.title, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().tvScrim())
        Column(Modifier.align(Alignment.CenterStart).padding(start = TvSafeH).width(640.dp)) {
            s?.let { Box(Modifier.clip(RoundedCornerShape(6.dp)).background(it.brush()).padding(horizontal = 9.dp, vertical = 5.dp)) { ServiceLogo(it, 16.dp, Modifier.width(70.dp)) } }
            Text(t.title, fontSize = 44.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, letterSpacing = (-0.8).sp, modifier = Modifier.padding(top = 12.dp))
            Text(
                listOfNotNull(t.year?.toString(), t.rating?.let { "★ %.1f".format(it / 10.0) }, t.runtimeMin?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") }, if (t.isSeries) "Series" else "Movie", t.genres.take(3).joinToString(" • ").ifEmpty { null })
                    .joinToString("   "),
                color = P.muted, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp),
            )
            t.overview?.let { Text(it, color = P.fg.copy(alpha = 0.85f), fontSize = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp)) }
            Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                when (match) {
                    OttMatch.Checking -> {
                        CircularProgressIndicator(Modifier.size(28.dp), color = P.accent)
                        Text("Looking on Usenet…", color = P.muted, fontSize = 16.sp)
                    }
                    is OttMatch.Movie -> {
                        TvButton("Play", { vm.playOtt(item) }, Modifier.focusRequester(main), primary = true, leading = { Icon(Icons.Filled.PlayArrow, null, tint = LocalContentColor.current) })
                        TvButton("${match.group.releases.size} releases", { vm.openTitle(match.group) })
                    }
                    is OttMatch.Show -> TvButton("Episodes", { vm.playOtt(item) }, Modifier.focusRequester(main), primary = true, leading = { Icon(Icons.Filled.PlayArrow, null, tint = LocalContentColor.current) })
                    OttMatch.NotFound -> Text("Not on Usenet yet", color = P.muted, fontSize = 16.sp)
                    is OttMatch.Failed -> TvButton("Try again", { vm.back(); vm.openOtt(item) }, Modifier.focusRequester(main))
                }
                if (s != null && link != null) {
                    TvFocusable(
                        onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.videoLink ?: link.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                .onFailure { vm.notifyUser("${s.name} isn't installed on this TV") }
                        },
                        modifier = Modifier.height(52.dp).let { if (match == OttMatch.NotFound || match == OttMatch.Checking) it.focusRequester(main) else it },
                        shape = RoundedCornerShape(26.dp),
                        container = Color(s.color),
                        focusedContainer = Color(s.color),
                        content = s.onBrand(),
                        focusedContent = s.onBrand(),
                        focusedScale = 1.08f,
                    ) {
                        Row(Modifier.align(Alignment.Center).background(s.brush()).fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Open in ", fontSize = 17.sp, color = LocalContentColor.current)
                            ServiceLogo(s, 18.dp, Modifier.width(80.dp))
                        }
                    }
                }
            }
        }
    }
}
