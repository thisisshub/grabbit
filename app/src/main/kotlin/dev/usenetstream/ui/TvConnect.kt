package dev.usenetstream.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.usenetstream.MainViewModel
import dev.usenetstream.link.FoundTv
import kotlinx.coroutines.launch

/** Settings section: connect this phone to a TV running the app (same Wi-Fi), or forget it. */
@Composable
fun TvConnectSection(vm: MainViewModel) {
    val link = vm.phoneLink
    val tv by link.tv.collectAsState()
    val found by link.found.collectAsState()
    var picking by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<FoundTv?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (picking) DisposableEffect(Unit) {
        link.startDiscovery()
        onDispose { link.stopDiscovery() }
    }

    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            tv != null && !picking -> {
                Text("Connected to ${tv!!.name}", style = MaterialTheme.typography.bodyLarge)
                Text("Use 🎤 in Search to speak to it, or TV on any title to play it there. The app must be open on the TV.", color = Muted, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton({ vm.sendSearchToTv("Big Buck Bunny") }, primary = false, height = 44.dp) { Text("Send a test search") }
                    TextButton(link::forget) { Text("Forget TV", color = Red) }
                }
            }
            !picking -> {
                Text("Search and play on your Android TV from this phone, by voice or typing.", color = Muted, style = MaterialTheme.typography.bodySmall)
                GlassButton({ picking = true; error = null }, primary = false, height = 44.dp) { Text("Connect a TV") }
            }
            chosen == null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Red)
                    Text("  Looking for TVs on this Wi‑Fi… open Grabbit on the TV.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                found.forEach { t ->
                    GlassButton({
                        chosen = t
                        error = null
                        scope.launch { link.requestCode(t).onFailure { error = "Couldn't reach ${t.name}: ${it.message}" } }
                    }, Modifier.fillMaxWidth(), primary = false, height = 48.dp) { Text(t.name) }
                }
                TextButton({ picking = false }) { Text("Cancel", color = Muted) }
            }
            else -> {
                Text("Enter the code now showing on ${chosen!!.name}", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    code, { code = it.filter(Char::isDigit).take(6) },
                    Modifier.fillMaxWidth(),
                    label = { Text("6-digit code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                error?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassButton({
                        busy = true
                        scope.launch {
                            link.pair(chosen!!, code)
                                .onSuccess { picking = false; chosen = null; code = ""; vm.notifyUser("Connected to ${it.name}") }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    }, height = 44.dp) { Text(if (busy) "Connecting…" else "Connect") }
                    TextButton({ chosen = null; code = "" }) { Text("Back", color = Muted) }
                }
            }
        }
    }
}

/** "🎤 to TV": speech -> text on the phone, sent to the paired TV (and searched here too). */
@Composable
fun rememberSpeakToTv(vm: MainViewModel, onText: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text ->
            onText(text)
            vm.sendSearchToTv(text)
        }
    }
    return {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a movie or show for your TV")
        runCatching { launcher.launch(i) }.onFailure { vm.notifyUser("Voice input isn't available on this phone") }
    }
}
