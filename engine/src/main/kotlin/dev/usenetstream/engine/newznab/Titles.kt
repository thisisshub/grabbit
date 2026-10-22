package dev.usenetstream.engine.newznab

/** One movie or show, with every release the indexer returned for it. */
data class TitleGroup(
    val key: String,
    val title: String,
    val year: Int?,
    val coverUrl: String?,
    val score: Double?,
    val genre: String?,
    val tagline: String?,
    val runtime: String?,
    val releases: List<NzbResult>,
    val tvdbId: String? = null,
    val isShow: Boolean = false,
) {
    val genres: List<String> get() = genre?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** Release to play when the user just taps Play: unlocked, most grabbed, then smaller. */
    val best: NzbResult
        get() = releases.filterNot { it.passworded }.ifEmpty { releases }
            .sortedBy { it.innerArchive }
            .sortedWith(compareByDescending<NzbResult> { it.grabs ?: 0 }.thenBy { it.sizeBytes ?: Long.MAX_VALUE })
            .first()
}

object Titles {
    private val CUT = Regex("""(?i)[ ._\-(\[]+((19|20)\d{2}|s\d{1,2}e\d{1,3}|s\d{1,2}\b|\d{3,4}p|\d{3,4}x\d{3,4}|web|bluray|hdtv)\b.*""")
    private val NON_ALNUM = Regex("""[^a-z0-9]""")
    private val SEPARATORS = Regex("""[._]+""")

    /**
     * Groups releases by IMDb id, else by the name before the year / episode / quality tags. An untagged release
     * whose name matches a tagged one's IMDb title joins that group. Keeps search order.
     */
    fun group(results: List<NzbResult>): List<TitleGroup> {
        val imdbByName = results.filter { it.imdbId != null && it.imdbTitle != null }
            .associate { squash(it.imdbTitle!!) to "imdb:${it.imdbId}" }
        // TV: one card per show (tvdbid), never per episode. Movies: IMDb id, else the cleaned name.
        fun keyOf(r: NzbResult): String =
            r.tvdbId?.let { "tvdb:$it" } ?: r.imdbId?.let { "imdb:$it" }
                ?: squash(cleanName(r.title)).let { imdbByName[it] ?: "name:$it" }

        return results.groupBy(::keyOf).map { (key, releases) ->
            val meta = releases.firstOrNull { it.imdbTitle != null } ?: releases.first()
            TitleGroup(
                key = key,
                title = releases.firstNotNullOfOrNull { it.showTitle } ?: meta.imdbTitle ?: cleanName(meta.title),
                year = releases.firstNotNullOfOrNull { it.year },
                coverUrl = releases.firstNotNullOfOrNull { it.coverUrl },
                score = releases.firstNotNullOfOrNull { it.score },
                genre = releases.firstNotNullOfOrNull { it.genre },
                tagline = releases.firstNotNullOfOrNull { it.tagline },
                runtime = releases.firstNotNullOfOrNull { it.runtime },
                releases = releases,
                tvdbId = releases.firstNotNullOfOrNull { it.tvdbId },
                // One stray "TV > Documentary" release must not turn a movie into a series.
                isShow = key.startsWith("tvdb:") || (!key.startsWith("imdb:") && releases.all { it.isTv }),
            )
        }
    }

    /** "Big.Buck Bunny" and "BigBuckBunny" compare equal. */
    fun squash(name: String) = NON_ALNUM.replace(name.lowercase(), "")

    fun cleanName(release: String): String =
        CUT.replace(release, "").replace(SEPARATORS, " ").trim().ifEmpty { release }
}
