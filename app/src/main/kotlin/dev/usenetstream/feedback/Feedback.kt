package dev.usenetstream.feedback

/** Text that leaves the device (feedback messages) goes through [scrub] first. */
object Feedback {
    /** Drop anything that looks like a key, credential or link. */
    fun scrub(text: String): String = text
        .replace(Regex("""(?i)(apikey|api_key|key|token|pass(word)?|user(name)?)=[^&\s"]+"""), "$1=…")
        .replace(Regex("""motn-key-\S+"""), "…")
        .replace(Regex("""https?://\S+"""), "<link>")
        .take(2000)
}
