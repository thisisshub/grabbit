@file:OptIn(ExperimentalLayoutApi::class)

package dev.usenetstream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.usenetstream.MainViewModel
import dev.usenetstream.engine.newznab.Quality
import dev.usenetstream.settings.SettingsScreen
import kotlin.math.roundToInt

@Composable
fun SettingsTab(vm: MainViewModel, openFile: () -> Unit) {
    val prefs by vm.playPrefs.collectAsState()
    val rf by vm.releaseFilter.collectAsState()
    val stats by vm.showStats.collectAsState()
    val failed by vm.failed.collectAsState()
    val caps = vm.caps

    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).statusBarsPadding().padding(bottom = navInset() + 16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
        if (vm.config == null) {
            Text(
                "Welcome! Add your Usenet server to start. An indexer (like NZBGeek) lets you browse and search.",
                color = Muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        Section("Appearance")
        val mode by vm.themeMode.collectAsState()
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { m -> GlassChip(mode == m, { vm.setThemeMode(m) }, { Text(m.label) }) }
        }

        Section("TV")
        TvConnectSection(vm)

        Section("Streaming guide")
        GuideSection(vm)

        if (dev.usenetstream.feedback.Feedback.enabled) {
            Section("Beta feedback")
            FeedbackSection(vm)
        }

        Section(dev.usenetstream.feedback.Support.TITLE)
        SupportSection(vm)

        Section("About")
        Text("Grabbit ${dev.usenetstream.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 20.dp))
        DisclaimerText(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

        Section("Playback")
        Column(Modifier.padding(horizontal = 20.dp)) {
            Label("Preferred quality (used when picking a release for you)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Quality.SD, Quality.HD, Quality.FHD, Quality.UHD).forEach { q ->
                    GlassChip(prefs.quality == q, { vm.setPlayPrefs(prefs.copy(quality = q)) }, { Text(q.label) })
                }
            }
            val gb = (prefs.maxBytes ?: 0) / 1e9f
            Label("Largest release to pick automatically  ${if (gb <= 0f) "no limit" else "%.0f GB".format(gb)}")
            Slider(
                value = gb.coerceIn(0f, 30f),
                onValueChange = { v -> vm.setPlayPrefs(prefs.copy(maxBytes = (v.roundToInt() * 1_000_000_000L).takeIf { it > 0 })) },
                valueRange = 0f..30f,
                steps = 29,
                colors = SliderDefaults.colors(thumbColor = Fg, activeTrackColor = Red),
            )
            ToggleRow("Hide releases this phone can't play", rf.hideUnplayable) { vm.setReleaseFilter(rf.copy(hideUnplayable = it)) }
            ToggleRow("Hide password-locked releases", rf.hideLocked) { vm.setReleaseFilter(rf.copy(hideLocked = it)) }
            ToggleRow("Show stream stats in the player", stats, vm::setShowStats)
            Text(
                "This phone decodes: " + listOfNotNull(
                    "H.264",
                    "HEVC".takeIf { caps.hevc },
                    "AV1".takeIf { caps.av1 },
                    "Dolby Vision".takeIf { caps.dolbyVision },
                    "DTS".takeIf { caps.dts },
                    "TrueHD".takeIf { caps.trueHd },
                ).joinToString(", "),
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Section("Releases that failed")
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (failed.isEmpty()) "None yet" else "${failed.size} release${if (failed.size == 1) "" else "s"} won't be picked first",
                color = Muted,
                modifier = Modifier.weight(1f),
            )
            if (failed.isNotEmpty()) TextButton(vm::clearFailures) { Text("Forget", color = Red) }
        }

        Section("Server & indexer")
        SettingsScreen(vm.config, vm.indexer, vm::saveSettings)

        Section("Files")
        TextButton(openFile, Modifier.padding(horizontal = 12.dp)) { Text("Open an NZB file…", color = Fg) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SupportSection(vm: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(dev.usenetstream.feedback.Support.BLURB, color = Muted, style = MaterialTheme.typography.bodySmall)
        GlassButton(
            {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(dev.usenetstream.feedback.Support.URL))) }
                    .onFailure { vm.notifyUser("No browser to open the page") }
            },
            Modifier.fillMaxWidth(),
            height = 48.dp,
        ) { Text("Buy me a chai") }
    }
}

/** Opens a pre-filled GitHub issue; its form applies the labels. */
@Composable
private fun FeedbackSection(vm: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Grabbit ${dev.usenetstream.BuildConfig.VERSION_NAME}. Reports open on GitHub with your device details filled in (never your server or passwords).",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        dev.usenetstream.feedback.Feedback.Kind.entries.forEach { kind ->
            GlassButton(
                {
                    val failure = vm.lastFailure.takeIf { kind == dev.usenetstream.feedback.Feedback.Kind.PLAYBACK }
                    val url = dev.usenetstream.feedback.Feedback.issueUrl(context, kind, vm.caps, isTv = false, failure = failure) ?: return@GlassButton
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                        .onFailure { vm.notifyUser("No browser to open GitHub") }
                },
                Modifier.fillMaxWidth(),
                primary = false,
                height = 48.dp,
            ) { Text(kind.label + if (kind == dev.usenetstream.feedback.Feedback.Kind.PLAYBACK && vm.lastFailure != null) " (last error filled in)" else "") }
        }
    }
}

/** Movie of the Night key + country for the "what's on" lanes. */
@Composable
private fun GuideSection(vm: MainViewModel) {
    var key by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(vm.ownGuideKey.orEmpty()) }
    var country by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(vm.guideCountry) }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "New and top titles on Netflix, Prime Video and more, from movieofthenight.com." +
                if (vm.hasBuiltInGuideKey) " This beta has a key built in; add your own only if the lanes stop loading." else "",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        androidx.compose.material3.OutlinedTextField(
            key, { key = it.trim() }, Modifier.fillMaxWidth(), label = { Text(if (vm.hasBuiltInGuideKey) "Your own API key (optional)" else "API key") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        )
        androidx.compose.material3.OutlinedTextField(
            country, { country = it.trim().lowercase().take(2) }, Modifier.fillMaxWidth(), label = { Text("Country (2 letters, e.g. in, us)") }, singleLine = true,
        )
        GlassButton({ vm.saveGuide(key, country) }, Modifier.fillMaxWidth(), height = 48.dp) { Text("Save") }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(color = Divider, modifier = Modifier.padding(top = 20.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(pressedScale = 0.99f) { onChange(!value) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Red, checkedThumbColor = Color.White))
    }
}
