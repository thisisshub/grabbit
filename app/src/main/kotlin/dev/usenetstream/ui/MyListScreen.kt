package dev.usenetstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.usenetstream.MainViewModel
import dev.usenetstream.Tab

@Composable
fun MyListScreen(vm: MainViewModel) {
    val list by vm.myList.collectAsState()
    val history by vm.history.collectAsState()
    if (list.isEmpty()) {
        Column(
            Modifier.fillMaxSize().background(Ink).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Your list is empty", style = MaterialTheme.typography.headlineSmall)
            Text("Tap + My List on any title to save it for later.", color = Muted, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(24.dp))
            PlayButton("Find something", icon = null) { vm.selectTab(Tab.SEARCH) }
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = navInset() + 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize().background(Ink).statusBarsPadding(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("My List", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(4.dp))
        }
        items(list, key = { it.key }) { e ->
            // Progress for shows/movies already started.
            val watch = history.filter { it.id == e.key || it.showKey == e.key }.maxByOrNull { it.updatedAt }
            Column(
                Modifier.animateItem().testTag("mylist:${e.key}").pressable(onLongClick = { vm.toggleMyList(e.key, e.title, e.coverUrl, e.year) }) {
                    vm.openKey(e.key, e.title, e.coverUrl)
                },
            ) {
                PosterCard(e.coverUrl, e.title, watch?.takeIf { !it.finished }?.progress, width = 112.dp, modifier = Modifier.fillMaxWidth(), sharedKey = e.key)
                Text(e.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text(
                    watch?.code?.let { "Last: $it" } ?: e.year?.toString() ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Long-press a poster to remove it.", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
        }
    }
}
