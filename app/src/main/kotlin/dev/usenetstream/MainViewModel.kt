package dev.usenetstream

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.usenetstream.engine.OpenedMedia
import dev.usenetstream.engine.UsenetStreamer
import dev.usenetstream.engine.newznab.IndexerConfig
import dev.usenetstream.engine.newznab.Playability
import dev.usenetstream.engine.newznab.ReleaseParser
import dev.usenetstream.engine.newznab.playabilityOn
import dev.usenetstream.engine.newznab.IndexerException
import dev.usenetstream.engine.newznab.NewznabClient
import dev.usenetstream.engine.newznab.NzbResult
import dev.usenetstream.engine.newznab.PlayPrefs
import dev.usenetstream.engine.newznab.Ranker
import dev.usenetstream.engine.newznab.ReleaseFilter
import dev.usenetstream.engine.newznab.SearchCategory
import dev.usenetstream.engine.newznab.TitleFilter
import dev.usenetstream.engine.newznab.TitleGroup
import dev.usenetstream.engine.newznab.Titles
import dev.usenetstream.engine.newznab.key
import dev.usenetstream.engine.nntp.NntpConfig
import dev.usenetstream.engine.nzb.Nzb
import dev.usenetstream.engine.ott.DiskCachedGet
import dev.usenetstream.engine.ott.MotnClient
import dev.usenetstream.engine.ott.OttItem
import dev.usenetstream.engine.ott.OttService
import dev.usenetstream.engine.ott.UrlHeaderGet
import dev.usenetstream.engine.nzb.NzbParser
import dev.usenetstream.engine.rar.NotStreamableException
import dev.usenetstream.engine.tv.EpisodeProgress
import dev.usenetstream.engine.tv.GuideEpisode
import dev.usenetstream.engine.tv.NextUp
import dev.usenetstream.engine.tv.SeasonReleases
import dev.usenetstream.engine.tv.ShowGuide
import dev.usenetstream.engine.tv.TvMazeClient
import dev.usenetstream.engine.tv.episodeKey
import dev.usenetstream.engine.tv.nextUp
import dev.usenetstream.engine.tv.splitSeason
import dev.usenetstream.engine.yenc.YencException
import dev.usenetstream.history.FailedRelease
import dev.usenetstream.history.ListEntry
import dev.usenetstream.history.ResumePolicy
import dev.usenetstream.history.SourceKind
import dev.usenetstream.history.WatchEntry
import dev.usenetstream.history.WatchHistory
import dev.usenetstream.player.Decoders
import dev.usenetstream.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab { HOME, SEARCH, MY_LIST, SETTINGS }

/** Screens pushed on top of the tabs. The top of the stack is what's shown. */
sealed interface Route {
    data class Movie(val group: TitleGroup) : Route
    data class Show(val key: String, val title: String, val coverUrl: String?, val tvdbId: String?) : Route
    /** A title we only know by key (history / My List); resolved to Movie or Show once loaded. */
    data class Lookup(val key: String, val title: String, val coverUrl: String?) : Route
    data class Opening(val title: String, val coverUrl: String?, val steps: List<Step>) : Route
    data class Player(val media: OpenedMedia, val streamer: UsenetStreamer, val session: PlaySession) : Route
    /** One streaming service's lane page (Netflix, Prime Video...). */
    data class Service(val id: String) : Route
    /** A title from the streaming guide; matched to Usenet releases on open. */
    data class OttTitle(val item: OttItem) : Route
}

/** The "what's on" lanes from the streaming guide. */
data class OttState(
    val services: List<OttService> = emptyList(),
    val newItems: List<OttItem> = emptyList(),
    val top: Map<String, List<OttItem>> = emptyMap(),
    val leaving: Map<String, List<OttItem>> = emptyMap(),
    val loading: Boolean = false,
    val loadedAt: Long = 0,
    val error: String? = null,
) {
    fun service(id: String): OttService? = services.firstOrNull { it.id == id }
    fun newOn(id: String): List<OttItem> = newItems.filter { it.serviceId == id }
}

/** Is a streaming-guide title on Usenet? */
sealed interface OttMatch {
    data object Checking : OttMatch
    data class Movie(val group: TitleGroup) : OttMatch
    data class Show(val key: String, val tvdbId: String) : OttMatch
    data object NotFound : OttMatch
    data class Failed(val message: String) : OttMatch
}

data class Step(val label: String, val state: StepState)
enum class StepState { DONE, ACTIVE, FAILED, PENDING }

data class SearchState(
    val query: String = "",
    val category: SearchCategory = SearchCategory.ALL,
    val groups: List<TitleGroup> = emptyList(),
    val filter: TitleFilter = TitleFilter(),
    val searching: Boolean = false,
    val message: String? = null,
) {
    val shown: List<TitleGroup> get() = filter.apply(groups)
}

/** Indexer-driven home rows. Each row loads on its own; a failed row is just left out. */
data class HomeFeeds(
    val trending: List<TitleGroup> = emptyList(),
    val newMovies: List<TitleGroup> = emptyList(),
    val newTv: List<TitleGroup> = emptyList(),
    val topRated: List<TitleGroup> = emptyList(),
    val moreLike: Pair<String, List<TitleGroup>>? = null,
    val loading: Boolean = false,
    val loadedAt: Long = 0,
    val error: String? = null,
)

/** Everything the show page needs. Seasons load lazily as the user swipes to them. */
data class ShowState(
    val key: String,
    val title: String,
    val coverUrl: String?,
    val tvdbId: String?,
    val guide: ShowGuide? = null,
    val guideError: String? = null,
    val seasons: Map<Int, SeasonReleases> = emptyMap(),
    val loadingSeasons: Set<Int> = emptySet(),
    val progress: Map<String, EpisodeProgress> = emptyMap(),
) {
    val nextUp: NextUp? get() = guide?.let { nextUp(it.episodes, progress) }
    val watchedCount: Int get() = progress.values.count { it.finished }
}

/** What is playing, and where its progress gets saved. */
data class PlaySession(
    val id: String,
    val title: String,
    val release: String,
    val releaseKey: String,
    val coverUrl: String?,
    val kind: SourceKind,
    val source: String,
    val startMs: Long,
    val genre: String? = null,
    val showKey: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeName: String? = null,
) {
    val subtitle: String? get() = if (season != null && episode != null) "S%02dE%02d · %s".format(season, episode, episodeName.orEmpty()) else null
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val db = WatchHistory(app)
    private val tvmaze = TvMazeClient()
    val caps = Decoders.caps

    var config: NntpConfig? = store.load() ?: friendsConfig()?.also { (n, i) -> store.save(n); store.saveIndexer(i) }?.first
        private set
    var indexer: IndexerConfig? = store.loadIndexer()
        private set

    /** Private friends builds come with the owner's logins; each install uses a small share of the connections. */
    private fun friendsConfig(): Pair<NntpConfig, IndexerConfig?>? {
        val user = BuildConfig.FRIENDS_NNTP_USER.ifEmpty { return null }
        val nntp = NntpConfig(host = BuildConfig.FRIENDS_NNTP_HOST, username = user, password = BuildConfig.FRIENDS_NNTP_PASS, connections = FRIENDS_CONNECTIONS)
        val idx = BuildConfig.FRIENDS_INDEXER_KEY.ifEmpty { null }?.let { IndexerConfig(BuildConfig.FRIENDS_INDEXER_URL, it) }
        return nntp to idx
    }

    private val _tab = MutableStateFlow(if (config == null) Tab.SETTINGS else Tab.HOME)
    val tab: StateFlow<Tab> = _tab

    private val _stack = MutableStateFlow<List<Route>>(emptyList())
    val stack: StateFlow<List<Route>> = _stack

    /** One-line notices (snackbar). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search

    private val _recentSearches = MutableStateFlow(store.recentSearches)
    val recentSearches: StateFlow<List<String>> = _recentSearches

    private val _history = MutableStateFlow<List<WatchEntry>>(emptyList())
    val history: StateFlow<List<WatchEntry>> = _history

    private val _myList = MutableStateFlow<List<ListEntry>>(emptyList())
    val myList: StateFlow<List<ListEntry>> = _myList

    private val _failed = MutableStateFlow<Set<String>>(emptySet())
    val failed: StateFlow<Set<String>> = _failed

    private val _feeds = MutableStateFlow(HomeFeeds())
    val feeds: StateFlow<HomeFeeds> = _feeds

    private val _show = MutableStateFlow<ShowState?>(null)
    val show: StateFlow<ShowState?> = _show

    private val _releaseFilter = MutableStateFlow(store.releaseFilter)
    val releaseFilter: StateFlow<ReleaseFilter> = _releaseFilter

    private val _playPrefs = MutableStateFlow(store.playPrefs)
    val playPrefs: StateFlow<PlayPrefs> = _playPrefs

    /** Set by MainActivity on a TV (the link server lives with the activity). */
    var tvLink: dev.usenetstream.link.TvLink? = null

    /** Phone side of the link (phones only). */
    val phoneLink by lazy { dev.usenetstream.link.PhoneLink(app) }

    fun sendSearchToTv(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            phoneLink.search(query.trim())
                .onSuccess { notify("Sent to ${phoneLink.tv.value?.name}") }
                .onFailure { notify("Can't reach the TV: ${it.message}. Is the app open on the TV?") }
        }
    }

    fun playOnTv(key: String, title: String, coverUrl: String?) {
        viewModelScope.launch {
            phoneLink.play(key, title, coverUrl)
                .onSuccess { notify("Playing on ${phoneLink.tv.value?.name}") }
                .onFailure { notify("Can't reach the TV: ${it.message}. Is the app open on the TV?") }
        }
    }

    private val _themeMode = MutableStateFlow(store.themeMode)
    val themeMode: StateFlow<dev.usenetstream.ui.ThemeMode> = _themeMode

    fun setThemeMode(m: dev.usenetstream.ui.ThemeMode) {
        store.themeMode = m
        _themeMode.value = m
    }

    private val _showStats = MutableStateFlow(store.showStats)
    val showStats: StateFlow<Boolean> = _showStats

    // ---------------------------------------------------------------- streaming guide

    /** The tester's own key, else the one built into beta APKs. */
    var guideKey: String? = store.loadGuideKey() ?: BuildConfig.GUIDE_KEY.ifEmpty { null }
        private set

    /** Only what the user typed (the Settings field never shows the built-in key). */
    val ownGuideKey: String? get() = store.loadGuideKey()
    val hasBuiltInGuideKey: Boolean get() = BuildConfig.GUIDE_KEY.isNotEmpty()

    val guideCountry: String
        get() = store.guideCountry ?: deviceCountry()

    private val _ott = MutableStateFlow(OttState())
    val ott: StateFlow<OttState> = _ott

    /** Title id -> its Usenet match, for the guide title page. */
    private val _ottMatch = MutableStateFlow<Map<String, OttMatch>>(emptyMap())
    val ottMatch: StateFlow<Map<String, OttMatch>> = _ottMatch

    private val guideHttp by lazy { DiskCachedGet(java.io.File(getApplication<Application>().cacheDir, "guide"), GUIDE_TTL_MS, UrlHeaderGet()) }

    private fun deviceCountry(): String {
        val tm = getApplication<Application>().getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        return listOf(tm?.networkCountryIso, tm?.simCountryIso, java.util.Locale.getDefault().country)
            .firstOrNull { !it.isNullOrBlank() && it.length == 2 }?.lowercase() ?: "us"
    }

    fun saveGuide(key: String?, country: String?) {
        store.saveGuideKey(key)
        store.guideCountry = country?.takeIf { it.length == 2 }
        guideKey = key?.trim()?.takeIf { it.isNotEmpty() } ?: BuildConfig.GUIDE_KEY.ifEmpty { null }
        _ott.value = OttState()
        loadOtt(force = true)
    }

    fun loadOtt(force: Boolean = false) {
        val key = guideKey ?: return
        val cur = _ott.value
        if (cur.loading || (!force && System.currentTimeMillis() - cur.loadedAt < FEED_TTL_MS)) return
        _ott.update { it.copy(loading = true, error = null) }
        val country = guideCountry
        viewModelScope.launch(Dispatchers.IO) {
            val client = MotnClient(key, guideHttp)
            try {
                val services = client.services(country)
                val ids = services.map { it.id }
                val fresh = async { runCatching { client.newOn(country, ids) } }
                val tops = ids.filter { it in TOP_SERVICES }.map { id -> id to async { runCatching { client.top(country, id) }.getOrDefault(emptyList()) } }
                val newItems = fresh.await()
                _ott.value = OttState(
                    services = services,
                    newItems = newItems.getOrDefault(emptyList()),
                    top = tops.associate { (id, d) -> id to d.await() }.filterValues { it.isNotEmpty() },
                    leaving = _ott.value.leaving,
                    loadedAt = System.currentTimeMillis(),
                    error = newItems.exceptionOrNull()?.let { "Can't load what's new: ${it.message}" },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ott.update { it.copy(loading = false, error = "Streaming guide: ${e.message}") }
            }
        }
    }

    fun openService(id: String) {
        push(Route.Service(id))
        val key = guideKey ?: return
        if (_ott.value.leaving.containsKey(id)) return
        val country = guideCountry
        viewModelScope.launch(Dispatchers.IO) {
            val items = runCatching { MotnClient(key, guideHttp).leaving(country, listOf(id)) }.getOrDefault(emptyList())
            _ott.update { it.copy(leaving = it.leaving + (id to items)) }
        }
    }

    fun openOtt(item: OttItem) {
        push(Route.OttTitle(item))
        val t = item.title
        val known = _ottMatch.value[t.id]
        if (known != null && known !is OttMatch.Failed) return
        _ottMatch.update { it + (t.id to OttMatch.Checking) }
        viewModelScope.launch(Dispatchers.IO) {
            val match = try {
                val imdb = t.imdbId
                when {
                    imdb == null -> OttMatch.NotFound
                    t.isSeries -> tvmaze.tvdbByImdb(imdb)?.let { OttMatch.Show("tvdb:$it", it) } ?: OttMatch.NotFound
                    else -> {
                        val idx = indexer ?: throw IllegalStateException("Add an indexer in Settings first")
                        Titles.group(NewznabClient(idx).movieByImdb(imdb)).firstOrNull()
                            ?.let { g -> OttMatch.Movie(g.copy(coverUrl = g.coverUrl ?: t.poster)) } ?: OttMatch.NotFound
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                OttMatch.Failed(e.message ?: "Can't check Usenet")
            }
            _ottMatch.update { it + (t.id to match) }
        }
    }

    /** Main button on a guide title: play the movie, or open the show's episodes. */
    fun playOtt(item: OttItem) {
        when (val m = _ottMatch.value[item.title.id]) {
            is OttMatch.Movie -> playMovie(m.group)
            is OttMatch.Show -> openShow(m.key, item.title.title, item.title.poster, m.tvdbId)
            else -> Unit
        }
    }

    private val _disclaimerOk = MutableStateFlow(store.disclaimerAccepted >= dev.usenetstream.ui.Disclaimer.VERSION)
    val disclaimerOk: StateFlow<Boolean> = _disclaimerOk

    fun acceptDisclaimer() {
        store.disclaimerAccepted = dev.usenetstream.ui.Disclaimer.VERSION
        _disclaimerOk.value = true
    }

    /** Anonymous feedback + automatic playback-error reports (see feedback/Relay.kt). */
    val relay = dev.usenetstream.feedback.Relay(
        app, caps, app.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK),
    ).also { it.flush() }

    private val _autoReports = MutableStateFlow(relay.autoReports)
    val autoReports: StateFlow<Boolean> = _autoReports

    fun setAutoReports(v: Boolean) {
        relay.autoReports = v
        _autoReports.value = v
    }

    /** Called by the player when a stream dies mid-playback (after its own damage-skipping gave up). */
    fun reportPlayerError(codeName: String) = relay.reportPlayback("player", codeName)

    private var openJob: Job? = null
    private var searchJob: Job? = null
    private val showJobs = HashMap<String, Job>()

    init {
        refreshDb()
        loadFeeds()
        loadOtt()
    }

    // ---------------------------------------------------------------- navigation

    fun selectTab(t: Tab) {
        if (t == _tab.value && _stack.value.isEmpty()) return
        stopPlayback()
        _stack.value = emptyList()
        _tab.value = t
        if (t == Tab.HOME) {
            loadFeeds()
            loadOtt()
        }
    }

    private fun push(r: Route) {
        _stack.update { it + r }
    }

    private fun replaceTop(r: Route) {
        _stack.update { if (it.isEmpty()) listOf(r) else it.dropLast(1) + r }
    }

    /** @return false when there is nothing left to go back to (the activity should finish). */
    fun back(): Boolean {
        val top = _stack.value.lastOrNull()
        return when {
            top is Route.Player || top is Route.Opening -> { stopPlayback(); true }
            top != null -> { _stack.update { it.dropLast(1) }; true }
            _tab.value != Tab.HOME && config != null -> { _tab.value = Tab.HOME; true }
            else -> false
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    private fun notify(msg: String) {
        _notice.value = msg
    }

    fun notifyUser(msg: String) = notify(msg)

    // ---------------------------------------------------------------- remote (phone -> TV)

    /** Name of the phone whose text is in the search box, so the TV can say "From …". */
    private val _remoteSearchFrom = MutableStateFlow<String?>(null)
    val remoteSearchFrom: StateFlow<String?> = _remoteSearchFrom

    /** Text spoken or typed on a paired phone: jump to Search and run it. */
    fun remoteSearch(query: String, from: String) {
        stopPlayback()
        _stack.value = emptyList()
        _tab.value = Tab.SEARCH
        _remoteSearchFrom.value = from
        notify("From $from: \"$query\"")
        search(query)
    }

    /** "Play on TV" from a paired phone: open the title and start the best release (or the next-up episode). */
    fun playKey(key: String, title: String, coverUrl: String?, from: String) {
        notify("Playing \"$title\" from $from")
        stopPlayback()
        _stack.value = emptyList()
        if (key.startsWith("tvdb:")) return openShow(key, title, coverUrl, key.removePrefix("tvdb:"), autoPlay = true)
        val idx = indexer ?: return selectTab(Tab.SETTINGS)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val client = NewznabClient(idx)
                    val results = if (key.startsWith("imdb:")) client.movieByImdb(key.removePrefix("imdb:")) else client.search(title)
                    Titles.group(results).let { gs -> gs.firstOrNull { it.key == key } ?: gs.firstOrNull() }
                }
            }.onSuccess { g ->
                if (g == null) notify("Can't find \"$title\" on the indexer") else { push(Route.Movie(g)); playMovie(g) }
            }.onFailure { notify("Can't open \"$title\": ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- settings

    fun saveSettings(newConfig: NntpConfig, newIndexer: IndexerConfig?) {
        store.save(newConfig)
        store.saveIndexer(newIndexer)
        config = newConfig
        indexer = newIndexer
        notify("Saved")
        _tab.value = Tab.HOME
        loadFeeds(force = true)
    }

    fun setPlayPrefs(p: PlayPrefs) {
        store.playPrefs = p
        _playPrefs.value = p
    }

    fun setReleaseFilter(f: ReleaseFilter) {
        store.releaseFilter = f
        _releaseFilter.value = f
    }

    fun setShowStats(v: Boolean) {
        store.showStats = v
        _showStats.value = v
    }

    fun clearFailures() {
        viewModelScope.launch(Dispatchers.IO) {
            db.failures().forEach { db.writableDatabase.delete("failures", "release_key = ?", arrayOf(it.key)) }
            _failed.value = db.failedKeys()
        }
    }

    // ---------------------------------------------------------------- search + feeds

    fun search(query: String, category: SearchCategory = _search.value.category) {
        val idx = indexer ?: return selectTab(Tab.SETTINGS)
        val q = query.trim()
        if (q.isBlank()) return
        _recentSearches.value = (listOf(q) + _recentSearches.value.filter { !it.equals(q, ignoreCase = true) }).take(10)
        store.recentSearches = _recentSearches.value
        searchJob?.cancel()
        _search.update { it.copy(query = q, category = category, searching = true, message = null) }
        searchJob = viewModelScope.launch {
            _search.value = try {
                val groups = withContext(Dispatchers.IO) {
                    // Indexer name search is fuzzy for TV; TVmaze finds the show itself, joined by tvdbid.
                    val shows = async {
                        if (category == SearchCategory.MOVIES) emptyList()
                        else runCatching { tvmaze.searchShows(q) }.getOrDefault(emptyList())
                    }
                    val found = Titles.group(NewznabClient(idx).search(q, category))
                    val known = found.map { it.key }.toSet()
                    // TVmaze search is fuzzy: keep shows whose name really matches, and none that merely share a
                    // movie's name (Big Buck Bunny the film vs. a same-named TVmaze entry).
                    val qs = Titles.squash(q)
                    val movieNames = found.filter { !it.isShow }.map { Titles.squash(it.title) }.toSet()
                    val showCards = shows.await().filter { h ->
                        val n = Titles.squash(h.name)
                        h.tvdbId != null && "tvdb:${h.tvdbId}" !in known && (n.contains(qs) || qs.contains(n)) && n !in movieNames
                    }.map { h ->
                        TitleGroup(
                            key = "tvdb:${h.tvdbId}", title = h.name, year = h.year, coverUrl = h.posterUrl, score = h.rating,
                            genre = h.genres.joinToString(", ").ifEmpty { null }, tagline = null, runtime = null,
                            releases = emptyList(), tvdbId = h.tvdbId, isShow = true,
                        )
                    }
                    showCards + found
                }
                _search.value.copy(groups = groups, searching = false, message = if (groups.isEmpty()) "No results for \"$q\"" else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _search.value.copy(searching = false, message = "Can't search: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun setTitleFilter(f: TitleFilter) = _search.update { it.copy(filter = f) }

    fun setCategory(c: SearchCategory) = _search.update { it.copy(category = c) }

    fun clearRecentSearches() {
        _recentSearches.value = emptyList()
        store.recentSearches = emptyList()
    }

    fun loadFeeds(force: Boolean = false) {
        val idx = indexer ?: return
        val now = System.currentTimeMillis()
        val current = _feeds.value
        if (current.loading || (!force && now - current.loadedAt < FEED_TTL_MS)) return
        _feeds.update { it.copy(loading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val client = NewznabClient(idx)
            // The most recent title with a genre drives "Because you watched ...".
            val seed = db.recent().firstOrNull { it.genre != null && !it.isEpisode }
            val movies = async { runCatching { Titles.group(client.latestMovies()) } }
            val tv = async { runCatching { Titles.group(client.latestTv()) } }
            val similar = seed?.let { s ->
                val genre = s.genre!!.substringBefore(',').trim()
                async { runCatching { s.title to Titles.group(client.latestMovies(genre = genre)).filter { it.key != s.id } }.getOrNull() }
            }
            val m = movies.await()
            val t = tv.await()
            val ms = m.getOrDefault(emptyList())
            _feeds.value = HomeFeeds(
                trending = ms.sortedByDescending { g -> g.releases.sumOf { it.grabs ?: 0 } }.take(ROW_SIZE),
                newMovies = ms.preferCovers().take(ROW_SIZE),
                newTv = t.getOrDefault(emptyList()).preferCovers().take(ROW_SIZE),
                topRated = ms.filter { (it.score ?: 0.0) >= 7.0 }.sortedByDescending { it.score }.take(ROW_SIZE),
                moreLike = similar?.await()?.takeIf { it.second.isNotEmpty() }?.let { (s, gs) -> s to gs.preferCovers().take(ROW_SIZE) },
                loading = false,
                loadedAt = System.currentTimeMillis(),
                error = (m.exceptionOrNull() ?: t.exceptionOrNull())?.let { "Can't load rows: ${it.message}" },
            )
        }
    }

    /** Rows are posters; drop cover-less titles unless that leaves the row nearly empty. */
    private fun List<TitleGroup>.preferCovers(): List<TitleGroup> =
        filter { it.coverUrl != null }.takeIf { it.size >= 6 } ?: this

    // ---------------------------------------------------------------- titles

    fun openTitle(group: TitleGroup) {
        if (group.isShow) openShow(group.key, group.title, group.coverUrl, group.tvdbId)
        else push(Route.Movie(group))
    }

    /** From history / My List, where we only have the key. */
    fun openKey(key: String, title: String, coverUrl: String?) {
        when {
            key.startsWith("tvdb:") -> openShow(key, title, coverUrl, key.removePrefix("tvdb:"))
            else -> lookupMovie(key, title, coverUrl)
        }
    }

    private fun lookupMovie(key: String, title: String, coverUrl: String?) {
        val idx = indexer ?: return selectTab(Tab.SETTINGS)
        val route = Route.Lookup(key, title, coverUrl)
        push(route)
        viewModelScope.launch {
            try {
                val group = withContext(Dispatchers.IO) {
                    val client = NewznabClient(idx)
                    val results = if (key.startsWith("imdb:")) client.movieByImdb(key.removePrefix("imdb:")) else client.search(title)
                    Titles.group(results).let { gs -> gs.firstOrNull { it.key == key } ?: gs.firstOrNull() }
                }
                if (_stack.value.lastOrNull() != route) return@launch
                if (group == null) {
                    _stack.update { it.dropLast(1) }
                    notify("Can't find \"$title\" on the indexer any more")
                } else {
                    replaceTop(Route.Movie(group))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_stack.value.lastOrNull() == route) _stack.update { it.dropLast(1) }
                notify("Can't load \"$title\": ${e.message}")
            }
        }
    }

    /** Show key whose next-up episode should start as soon as its season's releases are in. */
    private var autoPlayShow: String? = null

    fun openShow(key: String, title: String, coverUrl: String?, tvdbId: String?, autoPlay: Boolean = false) {
        autoPlayShow = if (autoPlay) key else null
        push(Route.Show(key, title, coverUrl, tvdbId))
        if (_show.value?.key != key) _show.value = ShowState(key, title, coverUrl, tvdbId)
        refreshShowProgress(key)
        if (_show.value?.guide != null) return maybeAutoPlay()
        showJobs[key]?.cancel()
        showJobs[key] = viewModelScope.launch(Dispatchers.IO) {
            try {
                val guide = tvdbId?.let { tvmaze.guideByTvdb(it) } ?: tvmaze.guideByName(title)
                _show.update { s -> if (s?.key == key) s.copy(guide = guide, guideError = if (guide == null) "No episode guide found" else null) else s }
                val seasons = guide?.seasons?.keys.orEmpty()
                if (guide != null && seasons.isEmpty()) {
                    _show.update { s -> if (s?.key == key) s.copy(guideError = "No episodes listed for this show yet") else s }
                } else if (seasons.isNotEmpty()) {
                    // Start where the user is: the season of their next-up episode, else the first.
                    val start = (_show.value?.nextUp as? NextUp.Resume)?.episode?.season
                        ?: (_show.value?.nextUp as? NextUp.Play)?.episode?.season ?: seasons.first()
                    loadSeason(start)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _show.update { s -> if (s?.key == key) s.copy(guideError = "Can't load episodes: ${e.message}") else s }
            }
        }
    }

    fun loadSeason(season: Int) {
        val s = _show.value ?: return
        val idx = indexer ?: return
        if (season in s.seasons || season in s.loadingSeasons) return
        _show.update { it?.copy(loadingSeasons = it.loadingSeasons + season) }
        viewModelScope.launch(Dispatchers.IO) {
            val client = NewznabClient(idx)
            val result = runCatching {
                val releases = s.tvdbId?.let { client.tvSeason(it, season) }
                    ?: client.search("${s.title} S%02d".format(season), SearchCategory.TV)
                splitSeason(releases, season)
            }
            _show.update { cur ->
                if (cur?.key != s.key) cur
                else cur.copy(
                    seasons = cur.seasons + (season to result.getOrDefault(SeasonReleases(emptyMap(), emptyList()))),
                    loadingSeasons = cur.loadingSeasons - season,
                )
            }
            result.exceptionOrNull()?.let { notify("Can't load season $season: ${it.message}") }
            withContext(Dispatchers.Main) { maybeAutoPlay() }
        }
    }

    private fun maybeAutoPlay() {
        val s = _show.value ?: return
        if (autoPlayShow != s.key) return
        val episode = when (val n = s.nextUp) {
            is NextUp.Resume -> n.episode
            is NextUp.Play -> n.episode
            else -> null
        } ?: return run { autoPlayShow = null }
        if (episode.season !in s.seasons) return loadSeason(episode.season)
        autoPlayShow = null
        playEpisode(episode)
    }

    private fun refreshShowProgress(showKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val progress = db.episodes(showKey).associate { e ->
                e.code!! to EpisodeProgress(e.positionMs, e.durationMs, e.finished, e.updatedAt)
            }
            _show.update { s -> if (s?.key == showKey) s.copy(progress = progress) else s }
        }
    }

    fun setEpisodesWatched(episodes: List<GuideEpisode>, watched: Boolean) {
        val s = _show.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            for ((i, e) in episodes.withIndex()) {
                val id = episodeKey(s.key, e.season, e.number)
                if (watched) {
                    val dur = (e.runtimeMin ?: 45) * 60_000L
                    db.save(
                        WatchEntry(id, s.title, "", s.coverUrl, SourceKind.INDEXER, "", dur, dur, now + i,
                            showKey = s.key, season = e.season, episode = e.number, episodeName = e.name),
                    )
                } else {
                    db.delete(id)
                }
            }
            _history.value = db.recent()
            refreshShowProgress(s.key)
        }
    }

    // ---------------------------------------------------------------- My List

    fun inMyList(key: String): Boolean = _myList.value.any { it.key == key }

    fun toggleMyList(key: String, title: String, coverUrl: String?, year: Int?) {
        viewModelScope.launch(Dispatchers.IO) {
            val adding = _myList.value.none { it.key == key }
            if (adding) db.addToList(ListEntry(key, title, coverUrl, year, System.currentTimeMillis())) else db.removeFromList(key)
            _myList.value = db.myList()
            notify(if (adding) "Added to My List" else "Removed from My List")
        }
    }

    fun forget(entry: WatchEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            if (entry.showKey != null) db.deleteShow(entry.showKey) else db.delete(entry.id)
            _history.value = db.recent()
            notify("Removed \"${entry.title}\" from history")
        }
    }

    // ---------------------------------------------------------------- playback

    fun rank(releases: List<NzbResult>): List<NzbResult> = Ranker.rank(releases, caps, _playPrefs.value, _failed.value)

    /** What Play tries on its own: best first, never a copy this device can't show (it would play as garbage). */
    private fun autoPick(releases: List<NzbResult>): List<NzbResult> =
        rank(releases).filter { ReleaseParser.parse(it).playabilityOn(caps) != Playability.NO }.take(MAX_ATTEMPTS)

    fun historyFor(key: String): WatchEntry? = _history.value.firstOrNull { it.id == key }

    /** Play [release], or the group's releases best-first until one streams. */
    fun playMovie(group: TitleGroup, release: NzbResult? = null) {
        val candidates = release?.let(::listOf) ?: autoPick(group.releases)
        if (candidates.isEmpty()) return notify(noPlayableCopy(group.releases))
        playReleases(group.title, group.coverUrl, candidates) { r ->
            PlaySession(group.key, group.title, r.title, r.key, group.coverUrl, SourceKind.INDEXER, r.guid ?: r.nzbUrl, 0, group.genre)
        }
    }

    fun playEpisode(e: GuideEpisode, release: NzbResult? = null) {
        val s = _show.value ?: return
        val season = s.seasons[e.season]
        val candidates = release?.let(::listOf)
            ?: (autoPick(season?.byEpisode?.get(e.number).orEmpty()) + autoPick(season?.packs.orEmpty())).take(MAX_ATTEMPTS)
        if (candidates.isEmpty()) return notify("No release of ${e.code} on the indexer")
        playReleases(s.title, e.stillUrl ?: s.coverUrl, candidates, episode = e.season to e.number) { r ->
            PlaySession(
                episodeKey(s.key, e.season, e.number), s.title, r.title, r.key, s.coverUrl, SourceKind.INDEXER,
                r.guid ?: r.nzbUrl, 0, s.guide?.genres?.firstOrNull(), s.key, e.season, e.number, e.name,
            )
        }
    }

    /** The episode after the one playing, if we know it and have a release for it. */
    fun nextEpisodeAfter(session: PlaySession): GuideEpisode? {
        val s = _show.value ?: return null
        if (session.showKey != s.key || session.season == null || session.episode == null) return null
        val eps = s.guide?.episodes ?: return null
        val i = eps.indexOfFirst { it.season == session.season && it.number == session.episode }
        return eps.getOrNull(i + 1)
    }

    fun playNext(session: PlaySession) {
        val next = nextEpisodeAfter(session) ?: return
        loadSeason(next.season)
        viewModelScope.launch {
            // The next season's releases may still be loading.
            for (i in 0 until 40) {
                if (_show.value?.seasons?.containsKey(next.season) == true) break
                kotlinx.coroutines.delay(250)
            }
            playEpisode(next)
        }
    }

    fun resume(entry: WatchEntry) {
        if (entry.isEpisode) {
            // Go through the show page so the guide (and the next episode) are there, then play what's next up.
            return openShow(entry.showKey!!, entry.title, entry.coverUrl, entry.showKey.removePrefix("tvdb:"), autoPlay = true)
        }
        when (entry.kind) {
            SourceKind.FILE -> openFile(Uri.parse(entry.source), entry.title)
            SourceKind.INDEXER -> {
                val idx = indexer ?: return selectTab(Tab.SETTINGS)
                val client = NewznabClient(idx)
                val url = if (entry.source.startsWith("http")) entry.source else client.nzbUrlFor(entry.source)
                val r = NzbResult(entry.release, url, null, null, null, null, guid = entry.source)
                playReleases(entry.title, entry.coverUrl, listOf(r)) {
                    PlaySession(entry.id, entry.title, entry.release, r.key, entry.coverUrl, entry.kind, entry.source, 0, entry.genre)
                }
            }
        }
    }

    fun openFile(uri: Uri, displayName: String) {
        open(displayName, null, listOf(Attempt(displayName, null) {
            val nzb = getApplication<Application>().contentResolver.openInputStream(uri)?.use(NzbParser::parse)
                ?: error("can't read $displayName")
            nzb to PlaySession("file:$uri", displayName, displayName, "file:$uri", null, SourceKind.FILE, uri.toString(), 0)
        }))
    }

    private fun noPlayableCopy(all: List<NzbResult>): String = when {
        all.isEmpty() -> "Nothing to play"
        all.all { it.key in _failed.value } -> "Every copy failed before"
        else -> "Only copies this screen can't show (4K or unsupported). Pick one in the release list to try anyway."
    }

    private fun playReleases(
        title: String,
        coverUrl: String?,
        releases: List<NzbResult>,
        episode: Pair<Int, Int>? = null,
        session: (NzbResult) -> PlaySession,
    ) {
        val idx = indexer ?: return selectTab(Tab.SETTINGS)
        if (releases.isEmpty()) return notify("Nothing to play")
        val client = NewznabClient(idx)
        open(title, coverUrl, releases.map { r -> Attempt(r.title, r, episode) { client.fetchNzb(r) to session(r) } })
    }

    private class Attempt(
        val label: String,
        val release: NzbResult?,
        val episode: Pair<Int, Int>? = null,
        val load: () -> Pair<Nzb, PlaySession>,
    )

    private fun open(title: String, coverUrl: String?, attempts: List<Attempt>) {
        val cfg = config ?: return selectTab(Tab.SETTINGS)
        stopPlayback()
        val many = attempts.size > 1
        fun steps(i: Int, label: String, state: StepState, detail: String? = null) = buildList {
            if (many) add(Step("Release ${i + 1} of ${attempts.size}", StepState.DONE))
            add(Step(attempts[i].label, StepState.DONE))
            add(Step(detail ?: label, state))
        }
        val opening = Route.Opening(title, coverUrl, steps(0, "Getting NZB", StepState.ACTIVE))
        push(opening)
        openJob = viewModelScope.launch {
            var streamer: UsenetStreamer? = null
            fun show(steps: List<Step>) = _stack.update { st ->
                if (st.lastOrNull() is Route.Opening) st.dropLast(1) + Route.Opening(title, coverUrl, steps) else st
            }
            try {
                val playing = withContext(Dispatchers.IO) {
                    streamer = UsenetStreamer.connect(cfg)
                    val reasons = ArrayList<String>()
                    for ((i, attempt) in attempts.withIndex()) {
                        try {
                            show(steps(i, "Getting NZB", StepState.ACTIVE))
                            val (nzb, session) = attempt.load()
                            show(steps(i, "Connecting to server", StepState.ACTIVE))
                            val media = streamer!!.open(nzb, attempt.episode) { done, total ->
                                if (total > 1) show(steps(i, "", StepState.ACTIVE, "Checking files $done of $total"))
                            }
                            show(steps(i, "Starting playback", StepState.ACTIVE))
                            val start = ResumePolicy.startAt(db.get(session.id))
                            return@withContext Route.Player(media, streamer!!, session.copy(startMs = start))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Broken / compressed / limited releases: remember, then try the next one. Anything else is fatal.
                            if (e !is NotStreamableException && e !is IndexerException && e !is YencException) throw e
                            val reason = e.message ?: e.javaClass.simpleName
                            reasons += reason
                            attempt.release?.let { r ->
                                if (e !is IndexerException) db.markFailed(FailedRelease(r.key, r.title, reason, System.currentTimeMillis()))
                            }
                            _failed.value = db.failedKeys()
                            show(steps(i, "", StepState.FAILED, reason.lineSequence().first().take(120)))
                        }
                    }
                    throw NotStreamableException(reasons.joinToString("\n"))
                }
                _stack.update { st -> if (st.lastOrNull() is Route.Opening) st.dropLast(1) + playing else st.also { playing.streamer.close() } }
            } catch (e: CancellationException) {
                streamer?.close() // user backed out while we were still opening
                throw e
            } catch (e: Exception) {
                streamer?.close()
                _stack.update { st -> if (st.lastOrNull() is Route.Opening) st.dropLast(1) else st }
                val reason = e.message ?: e.javaClass.simpleName
                relay.reportPlayback(dev.usenetstream.feedback.Relay.categoryOf(reason), e.javaClass.simpleName)
                notify("Can't play: ${reason.lineSequence().firstOrNull()}")
            }
        }
    }

    /** Called by the player every few seconds and when it closes. */
    fun saveProgress(session: PlaySession, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            db.save(
                WatchEntry(
                    id = session.id,
                    title = session.title,
                    release = session.release,
                    coverUrl = session.coverUrl,
                    kind = session.kind,
                    source = session.source,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = System.currentTimeMillis(),
                    genre = session.genre,
                    showKey = session.showKey,
                    season = session.season,
                    episode = session.episode,
                    episodeName = session.episodeName,
                ),
            )
            _history.value = db.recent()
            session.showKey?.let(::refreshShowProgress)
        }
    }

    private fun refreshDb() {
        viewModelScope.launch(Dispatchers.IO) {
            _history.value = db.recent()
            _myList.value = db.myList()
            _failed.value = db.failedKeys()
        }
    }

    fun stopPlayback() {
        openJob?.cancel()
        val st = _stack.value
        (st.lastOrNull() as? Route.Player)?.streamer?.close()
        if (st.lastOrNull() is Route.Player || st.lastOrNull() is Route.Opening) _stack.value = st.dropLast(1)
    }

    override fun onCleared() {
        (_stack.value.lastOrNull() as? Route.Player)?.streamer?.close()
        db.close()
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val ROW_SIZE = 20
        const val FEED_TTL_MS = 30 * 60 * 1000L
        const val FRIENDS_CONNECTIONS = 4
        /** Guide responses are kept on disk this long: the free key allows a limited number of calls a day. */
        const val GUIDE_TTL_MS = 6 * 60 * 60 * 1000L
        /** Services the guide keeps a daily top list for. */
        val TOP_SERVICES = setOf("netflix", "prime", "disney", "apple", "hbo", "hulu", "paramount", "crunchyroll", "crave", "starz")
    }
}
