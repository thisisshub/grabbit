package dev.usenetstream.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.usenetstream.update.UpdateState
import dev.usenetstream.update.Updater

/** Release notes are Markdown; the card only needs a few plain lines of them. */
fun whatsNew(notes: String, lines: Int = 4): String = notes.lineSequence()
    .map { it.trim().removePrefix("#").removePrefix("#").removePrefix("#").trim().removePrefix("- ").removePrefix("* ") }
    .map { it.replace("**", "").replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1") }
    .filter { it.isNotBlank() && !it.startsWith(">") && !it.startsWith("<") }
    .take(lines)
    .joinToString("\n")

fun versionOf(tag: String) = tag.removePrefix("v")

/** Phone: the "Update ready" card at the top of Home. */
@Composable
fun UpdateCard(updater: Updater, modifier: Modifier = Modifier) {
    val state by updater.state.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val p = LocalPalette.current
    val release = when (val s = state) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.NeedsPermission -> s.release
        is UpdateState.Installing -> s.release
        is UpdateState.Failed -> s.release
        else -> null
    }
    AnimatedVisibility(release != null, modifier, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val r = release ?: return@AnimatedVisibility
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .background(p.accent.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                .border(1.dp, p.accent.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🐰  Grabbit ${versionOf(r.tag)} is here", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state is UpdateState.Available) Text("Later", color = Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.pressable(onClick = updater::dismiss).padding(6.dp))
            }
            whatsNew(r.notes, if (expanded) 12 else 3).takeIf { it.isNotEmpty() }?.let {
                Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = if (expanded) 12 else 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.pressable { expanded = !expanded })
            }
            when (val s = state) {
                is UpdateState.Downloading -> {
                    LinearProgressIndicator(progress = { s.fraction }, Modifier.fillMaxWidth().height(6.dp), color = p.accent, trackColor = p.surface2, drawStopIndicator = {}, gapSize = 0.dp)
                    Text("Downloading… ${(s.fraction * 100).toInt()}%", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                is UpdateState.Installing -> Text("Installing… the app restarts when it's done.", color = Muted, style = MaterialTheme.typography.bodySmall)
                is UpdateState.NeedsPermission -> {
                    Text("One-time step: allow Grabbit to install its own updates.", style = MaterialTheme.typography.bodySmall)
                    GlassButton(updater::openInstallPermission, Modifier.fillMaxWidth(), height = 46.dp) { Text("Allow, then come back") }
                }
                is UpdateState.Failed -> {
                    Text("Didn't work: ${s.message}", color = Red, style = MaterialTheme.typography.bodySmall)
                    GlassButton({ updater.install(r) }, Modifier.fillMaxWidth(), height = 46.dp) { Text("Try again") }
                }
                else -> GlassButton({ updater.install(r) }, Modifier.fillMaxWidth(), height = 46.dp) { Text("Update now") }
            }
        }
    }
}

/** Settings: auto switch + manual check. */
@Composable
fun UpdateSettings(updater: Updater) {
    val state by updater.state.collectAsState()
    var auto by remember { mutableStateOf(updater.auto) }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("You're on Grabbit ${dev.usenetstream.BuildConfig.VERSION_NAME}.", color = Muted, style = MaterialTheme.typography.bodySmall)
        GlassButton({ updater.check() }, Modifier.fillMaxWidth(), primary = false, height = 46.dp) {
            Text(
                when (state) {
                    UpdateState.Checking -> "Checking…"
                    UpdateState.UpToDate -> "You're up to date ✓"
                    is UpdateState.Failed -> "Couldn't check. Try again"
                    else -> "Check for updates"
                },
            )
        }
    }
    ToggleRow("Download updates automatically (Wi‑Fi)", auto) { auto = it; updater.auto = it }
}
