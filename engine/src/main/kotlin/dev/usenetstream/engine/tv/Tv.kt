package dev.usenetstream.engine.tv

import dev.usenetstream.engine.newznab.HttpGet
import dev.usenetstream.engine.newznab.NzbResult
import dev.usenetstream.engine.newznab.ReleaseParser
import dev.usenetstream.engine.newznab.UrlConnectionGet

data class GuideEpisode(
    val season: Int,
    val number: Int,
    val name: String,
    val runtimeMin: Int?,
    val airdate: String?,
    val stillUrl: String?,
    val summary: String?,
) {
    val code: String get() = episodeCode(season, number)
}

data class ShowGuide(
    val tvmazeId: Int,
    val name: String,
    val posterUrl: String?,
    val summary: String?,
    val genres: List<String>,
    val premiered: String?,
    /** Regular episodes by season, seasons ascending. Specials (season 0) left out. */
    val seasons: Map<Int, List<GuideEpisode>>,
) {
    val episodes: List<GuideEpisode> get() = seasons.values.flatten()
}

fun episodeCode(season: Int, episode: Int) = "S%02dE%02d".format(season, episode)

/** History / progress key for one episode of one show. */
fun episodeKey(showKey: String, season: Int, episode: Int) = "$showKey:${episodeCode(season, episode)}"

/** A show found by name on TVmaze. */
data class ShowHit(val name: String, val tvdbId: String?, val posterUrl: String?, val year: Int?, val genres: List<String>, val rating: Double?)

/** Free TVmaze API (no key): show search, full episode list, names, stills. */
class TvMazeClient(private val http: HttpGet = UrlConnectionGet()) {
    fun searchShows(query: String, limit: Int = 4): List<ShowHit> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return Json.parse(String(http.get("https://api.tvmaze.com/search/shows?q=$q"))).list().mapNotNull { hit ->
            val show = hit.obj()?.get("show").obj() ?: return@mapNotNull null
            ShowHit(
                name = show.str("name") ?: return@mapNotNull null,
                tvdbId = show["externals"].obj()?.int("thetvdb")?.toString(),
                posterUrl = show["image"].obj()?.let { it.str("original") ?: it.str("medium") },
                year = show.str("premiered")?.take(4)?.toIntOrNull(),
                genres = show["genres"].list().filterIsInstance<String>(),
                rating = show["rating"].obj()?.get("average") as? Double,
            )
        }.take(limit)
    }

    fun guideByTvdb(tvdbId: String): ShowGuide? {
        val show = fetch("https://api.tvmaze.com/lookup/shows?thetvdb=$tvdbId") ?: return null
        return guide(show)
    }

    /** TheTVDB id of a show known by IMDb id (streaming guides give IMDb; the indexer wants TVDB). */
    fun tvdbByImdb(imdbId: String): String? =
        fetch("https://api.tvmaze.com/lookup/shows?imdb=$imdbId")?.get("externals").obj()?.int("thetvdb")?.toString()

    fun guideByName(name: String): ShowGuide? {
        val q = java.net.URLEncoder.encode(name, "UTF-8")
        val show = fetch("https://api.tvmaze.com/singlesearch/shows?q=$q") ?: return null
        return guide(show)
    }

    private fun guide(show: Map<String, Any?>): ShowGuide? {
        val id = show.int("id") ?: return null
        val eps = Json.parse(String(http.get("https://api.tvmaze.com/shows/$id/episodes"))).list().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val season = o.int("season") ?: return@mapNotNull null
            val number = o.int("number") ?: return@mapNotNull null
            if (season == 0 || o.str("type") == "significant_special") return@mapNotNull null
            GuideEpisode(
                season, number,
                name = o.str("name").orEmpty(),
                runtimeMin = o.int("runtime"),
                airdate = o.str("airdate"),
                stillUrl = o["image"].obj()?.let { it.str("original") ?: it.str("medium") },
                summary = o.str("summary")?.stripTags(),
            )
        }
        return ShowGuide(
            tvmazeId = id,
            name = show.str("name").orEmpty(),
            posterUrl = show["image"].obj()?.let { it.str("original") ?: it.str("medium") },
            summary = show.str("summary")?.stripTags(),
            genres = show["genres"].list().filterIsInstance<String>(),
            premiered = show.str("premiered"),
            seasons = eps.groupBy { it.season }.toSortedMap().mapValues { (_, v) -> v.sortedBy { it.number } },
        )
    }

    private fun fetch(url: String): Map<String, Any?>? = try {
        Json.parse(String(http.get(url))).obj()
    } catch (e: java.io.IOException) {
        if (e.message?.contains("404") == true) null else throw e
    }

    private fun String.stripTags() = replace(Regex("<[^>]+>"), "").trim()
}

/** Releases of one season, split into single episodes and season packs. */
data class SeasonReleases(val byEpisode: Map<Int, List<NzbResult>>, val packs: List<NzbResult>)

fun splitSeason(releases: List<NzbResult>, season: Int): SeasonReleases {
    val parsed = releases.map { it to ReleaseParser.parse(it) }.filter { (_, info) -> info.season == season }
    return SeasonReleases(
        byEpisode = parsed.filter { (_, i) -> i.episode != null }.groupBy({ it.second.episode!! }, { it.first }),
        packs = parsed.filter { (_, i) -> i.seasonPack }.map { it.first },
    )
}

/** Watch state of one episode, from history. */
data class EpisodeProgress(val positionMs: Long, val durationMs: Long, val finished: Boolean, val updatedAt: Long)

sealed interface NextUp {
    data class Resume(val episode: GuideEpisode, val progress: EpisodeProgress) : NextUp
    data class Play(val episode: GuideEpisode) : NextUp
    data object AllWatched : NextUp
}

/**
 * Netflix-style "what's next": the most recently touched episode decides.
 * Half-watched -> resume it; finished -> the next episode in order (crossing seasons); nothing yet -> first episode.
 */
fun nextUp(episodes: List<GuideEpisode>, progress: Map<String, EpisodeProgress>): NextUp {
    if (episodes.isEmpty()) return NextUp.AllWatched
    val ordered = episodes.sortedWith(compareBy({ it.season }, { it.number }))
    val last = ordered.mapNotNull { e -> progress[e.code]?.let { e to it } }.maxByOrNull { it.second.updatedAt }
        ?: return NextUp.Play(ordered.first())
    val (episode, p) = last
    if (!p.finished) return NextUp.Resume(episode, p)
    val after = ordered.drop(ordered.indexOf(episode) + 1).firstOrNull { progress[it.code]?.finished != true }
    return after?.let { NextUp.Play(it) } ?: NextUp.AllWatched
}
