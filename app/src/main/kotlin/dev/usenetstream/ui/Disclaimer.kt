package dev.usenetstream.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

object Disclaimer {
    /** Bump when the wording changes in a way people should see again. */
    const val VERSION = 1

    const val TITLE = "Before you start"

    val POINTS = listOf(
        "Grabbit is a personal, educational project shared for beta testing only.",
        "It is not for sale and must not be commercialized, rebranded or redistributed for profit.",
        "Grabbit doesn't host, index or provide any content. It only plays what your own Usenet provider and indexer give you.",
        "You are responsible for what you access. Use it only for content you have the right to watch, and follow the copyright laws where you live.",
        "Don't use Grabbit for piracy, harassment or any other unlawful or harmful purpose.",
        "It's beta software, provided as is, with no warranty.",
    )

    const val SHORT = "Educational, non-commercial beta. Grabbit hosts no content; you're responsible for what you watch and for following your local laws."
}

/** Phone: full-screen notice on first launch; Back leaves the app instead of skipping it. */
@Composable
fun DisclaimerScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    BackHandler(onBack = onDecline)
    Box(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Wordmark()
            Text(Disclaimer.TITLE, style = MaterialTheme.typography.headlineMedium)
            Disclaimer.POINTS.forEach { p ->
                Text("•  $p", style = MaterialTheme.typography.bodyLarge, color = Fg)
            }
            GlassButton(onAccept, Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("I understand", style = MaterialTheme.typography.titleMedium) }
            GlassButton(onDecline, Modifier.fillMaxWidth(), primary = false, height = 48.dp) { Text("Exit") }
        }
    }
}

/** For Settings → About. */
@Composable
fun DisclaimerText(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Disclaimer.POINTS.forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall, color = Muted) }
    }
}
