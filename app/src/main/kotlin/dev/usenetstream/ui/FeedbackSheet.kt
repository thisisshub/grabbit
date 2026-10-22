@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.usenetstream.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** The feedback form's words, shared by phone and TV. */
object FeedbackCopy {
    val MOODS = listOf("love" to "😍", "good" to "🙂", "meh" to "😐", "sad" to "😕", "angry" to "😤")
    val TOPICS = listOf("bug" to "🐞 Something broke", "playback" to "🎬 Playback", "idea" to "💡 Idea", "looks" to "🎨 Looks", "other" to "💬 Other")
    fun prompt(topic: String) = when (topic) {
        "bug" -> "What broke? What did you tap right before it happened?"
        "playback" -> "What were you trying to watch, and what did it do instead?"
        "idea" -> "Dream big. What should Grabbit do next?"
        "looks" -> "What looks off, or what would look better?"
        else -> "Anything at all. The rabbit is listening. 🐇"
    }
    const val MAX = 2000
    const val THANKS = "Got it! The rabbit hopped it straight to the dev. 🐇"
    const val PRIVACY = "Sent anonymously with your app version and device model. Never your server, logins or what you watch."
}

/** Phone: a bottom sheet with mood, topic and a message. */
@Composable
fun FeedbackSheet(send: suspend (topic: String, mood: String?, message: String) -> String?, onDismiss: () -> Unit) {
    var mood by rememberSaveable { mutableStateOf<String?>(null) }
    var topic by rememberSaveable { mutableStateOf("bug") }
    var message by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Card) {
        AnimatedContent(sent, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith fadeOut() }, label = "feedback") { done ->
            if (done) {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("🐰", fontSize = 64.sp)
                    Text(FeedbackCopy.THANKS, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    GlassButton(onDismiss, Modifier.fillMaxWidth(), height = 48.dp) { Text("Back to watching") }
                }
            } else {
                Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Tell me everything", style = MaterialTheme.typography.headlineSmall)
                    Text("How's Grabbit treating you?", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        FeedbackCopy.MOODS.forEach { (id, face) ->
                            val on = mood == id
                            val s by animateFloatAsState(if (on) 1.25f else 1f, label = "mood")
                            Box(
                                Modifier.size(52.dp).scale(s).clip(CircleShape)
                                    .background(if (on) LocalPalette.current.accent.copy(alpha = 0.25f) else LocalPalette.current.glass)
                                    .pressable { mood = if (on) null else id },
                                contentAlignment = Alignment.Center,
                            ) { Text(face, fontSize = 26.sp) }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FeedbackCopy.TOPICS.forEach { (id, label) -> GlassChip(topic == id, { topic = id }, { Text(label) }) }
                    }
                    OutlinedTextField(
                        message,
                        { if (it.length <= FeedbackCopy.MAX) message = it },
                        Modifier.fillMaxWidth().heightIn(min = 140.dp),
                        placeholder = { Text(FeedbackCopy.prompt(topic), color = Muted) },
                        supportingText = { Text("${message.length} / ${FeedbackCopy.MAX}", color = Muted) },
                    )
                    error?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
                    GlassButton(
                        {
                            if (sending || message.trim().length < 3) return@GlassButton
                            sending = true
                            error = null
                            scope.launch {
                                error = send(topic, mood, message)
                                sending = false
                                if (error == null) sent = true
                            }
                        },
                        Modifier.fillMaxWidth(),
                        height = 52.dp,
                    ) {
                        if (sending) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text(if (message.trim().length < 3) "Write a little first" else "Send it 🐰")
                    }
                    Text(FeedbackCopy.PRIVACY, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
