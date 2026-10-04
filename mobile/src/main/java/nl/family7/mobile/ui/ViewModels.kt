package nl.family7.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.family7.core.data.BACKGROUND_REFRESH_MS
import nl.family7.core.data.CategoryRow
import nl.family7.core.data.GuideItem
import nl.family7.core.data.LiveStreamInfo
import nl.family7.core.data.ProgramDetail
import nl.family7.core.data.ProgramItem
import nl.family7.core.data.SessionEvents
import nl.family7.mobile.Family7MobileApp

// ---------------------------------------------------------------- aanmelden

sealed interface AuthState {
    data object Checking : AuthState
    data object LoggedOut : AuthState
    data object LoggedIn : AuthState
}

class AppViewModel(private val app: Family7MobileApp) : ViewModel() {

    private val _auth = MutableStateFlow<AuthState>(AuthState.Checking)
    val auth: StateFlow<AuthState> = _auth.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    private val _loggingIn = MutableStateFlow(false)
    val loggingIn: StateFlow<Boolean> = _loggingIn.asStateFlow()

    init {
        // Gaf Family7 ergens een anonieme pagina of de inlogpagina terug, dan
        // laten we Family7 de sessie bevestigen; alleen als die echt voorbij
        // is, gaat de gebruiker naar het aanmeldscherm.
        viewModelScope.launch {
            SessionEvents.expired.collect {
                if (_auth.value == AuthState.LoggedIn && !app.auth.checkSession().isLoggedIn) signedOut()
            }
        }
        viewModelScope.launch {
            // De catalogus van de vorige keer klaarzetten, zodat het startscherm
            // meteen gevuld is.
            launch { app.catalog.restoreSnapshots() }

            if (app.auth.hasStoredSession()) {
                // Meteen naar binnen; Family7 bevestigt de sessie op de achtergrond.
                // Alleen als Family7 duidelijk zegt dat hij verlopen is, gaat de
                // gebruiker terug naar het aanmeldscherm.
                _auth.value = AuthState.LoggedIn
                launch { app.myList.refresh() }
                if (!app.auth.checkSession().isLoggedIn) signedOut()
            } else {
                _auth.value = AuthState.LoggedOut
            }
        }
    }

    fun login(email: String, password: String) {
        if (_loggingIn.value) return
        _loggingIn.value = true
        _loginError.value = null
        viewModelScope.launch {
            app.auth.login(email, password)
                .onSuccess {
                    _auth.value = AuthState.LoggedIn
                    launch { app.myList.refresh() }
                }
                .onFailure { _loginError.value = it.message ?: "Inloggen mislukt." }
            _loggingIn.value = false
        }
    }

    fun logout() {
        app.playback.stopCasting()
        app.playback.stopLocal()
        app.auth.logout()
        signedOut()
    }

    private fun signedOut() {
        app.myList.clear()
        app.catalog.clearMemoryCache()
        _auth.value = AuthState.LoggedOut
    }
}

// --------------------------------------------------------------- startscherm

class HomeViewModel(private val app: Family7MobileApp) : ViewModel() {

    val rows = Loader(
        scope = viewModelScope,
        network = app.network,
        initial = app.catalog.homeCache.snapshot()
    ) { force -> app.catalog.getOnDemandHome(forceRefresh = force) }

    private val _live = MutableStateFlow<LiveStreamInfo?>(null)
    val live: StateFlow<LiveStreamInfo?> = _live.asStateFlow()

    init {
        viewModelScope.launch {
            // Kwam de snapshot van schijf net na het openen binnen, toon die dan alsnog.
            app.catalog.restoreSnapshots()
            app.catalog.homeCache.snapshot()?.let(rows::offer)
            rows.load()
        }
        refreshLive()
        // De programmagids alvast klaarzetten, zodat live meteen de gids toont.
        viewModelScope.launch { runCatching { app.live.prefetchGuide() } }
    }

    /** Stil verversen zolang het startscherm zichtbaar is, net als op de tv. */
    suspend fun refreshWhileVisible() {
        // Bij terugkeer naar de voorgrond: verversen als de cache muf is. Loopt
        // de eerste lading nog, dan haakt dit daarop in in plaats van hem af te breken.
        if (app.catalog.homeCache.fresh() == null) rows.load()
        while (true) {
            kotlinx.coroutines.delay(BACKGROUND_REFRESH_MS)
            rows.load(force = true)
            refreshLive()
        }
    }

    fun refresh() {
        rows.refresh()
        refreshLive()
    }

    /** "Nu op Family7" is een extraatje: mislukt het, dan blijft de kaart algemeen. */
    private fun refreshLive() {
        viewModelScope.launch {
            app.live.getLiveInfo().onSuccess { _live.value = it }
        }
    }
}

/** De rubrieken van de site, om door te bladeren (zoals "On Demand" op tv). */
class BrowseViewModel(private val app: Family7MobileApp) : ViewModel() {

    val rows = Loader(
        scope = viewModelScope,
        network = app.network,
        initial = app.catalog.homeCache.snapshot()
    ) { force -> app.catalog.getOnDemandHome(forceRefresh = force) }

    init {
        rows.load()
    }
}

// ------------------------------------------------------------- zoeken

/**
 * Zoeken met suggesties: de complete A-Z-lijst van de site, gefilterd op het
 * toestel zelf, en de laatste zoekopdrachten (alleen lokaal bewaard).
 */
class SearchViewModel(private val app: Family7MobileApp) : ViewModel() {

    val programs = Loader(
        scope = viewModelScope,
        network = app.network,
        initial = app.catalog.azCache.snapshot()
    ) { force -> app.catalog.getAllAZPrograms(forceRefresh = force) }

    private val prefs = app.getSharedPreferences("family7_search", android.content.Context.MODE_PRIVATE)
    private val _recent = MutableStateFlow(readRecent())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    init {
        programs.load()
    }

    /** Onthoudt een zoekopdracht bovenaan de lijst, zonder dubbele. */
    fun remember(query: String) {
        val clean = query.trim()
        if (clean.length < 2) return
        val updated = (listOf(clean) + _recent.value.filterNot { it.equals(clean, ignoreCase = true) }).take(MAX_RECENT)
        _recent.value = updated
        prefs.edit().putString(KEY_RECENT, updated.joinToString("\n")).apply()
    }

    fun clearRecent() {
        _recent.value = emptyList()
        prefs.edit().remove(KEY_RECENT).apply()
    }

    private fun readRecent(): List<String> =
        prefs.getString(KEY_RECENT, null)?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

    private companion object {
        const val KEY_RECENT = "recent"
        const val MAX_RECENT = 8
    }
}

/**
 * Suggesties voor een zoekopdracht: titels die ermee beginnen eerst, dan
 * titels met een woord dat ermee begint, dan de rest die het bevat.
 */
fun List<ProgramItem>.suggestionsFor(query: String, limit: Int = 30): List<ProgramItem> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()
    val slugNeedle = needle.replace(' ', '-')
    return mapNotNull { program ->
        val title = program.title.lowercase()
        val rank = when {
            title.startsWith(needle) -> 0
            title.split(' ', '-', '\'').any { it.startsWith(needle) } -> 1
            title.contains(needle) || program.slug.contains(slugNeedle) -> 2
            else -> return@mapNotNull null
        }
        rank to program
    }.sortedWith(compareBy({ it.first }, { it.second.title.lowercase() })).take(limit).map { it.second }
}

// ------------------------------------------------------------- live en gids

/** Een dag in de programmagids: de datum voor de site en het woord voor de kijker. */
data class GuideDay(val date: String, val label: String)

/**
 * Het live-scherm: welke dag de gids toont, de gids van die dag (van de site,
 * met lokale cache) en de klok in Nederlandse tijd voor "nu".
 */
class GuideViewModel(private val app: Family7MobileApp) : ViewModel() {

    /** Gisteren tot en met over zes dagen; de site heeft er niet meer. */
    private val _days = MutableStateFlow(guideDays())
    val days: StateFlow<List<GuideDay>> = _days.asStateFlow()

    private val _selected = MutableStateFlow(TODAY_INDEX)
    val selected: StateFlow<Int> = _selected.asStateFlow()

    private val _guide = MutableStateFlow(LoadState<List<GuideItem>>())
    val guide: StateFlow<LoadState<List<GuideItem>>> = _guide.asStateFlow()

    /** Minuten na middernacht in Nederland, elke halve minuut bijgewerkt. */
    private val _nowMinutes = MutableStateFlow(amsterdamMinutes())
    val nowMinutes: StateFlow<Int> = _nowMinutes.asStateFlow()

    private var loader: Loader<List<GuideItem>>? = null
    private var follow: Job? = null

    init {
        select(TODAY_INDEX)
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                val minutes = amsterdamMinutes()
                // Na middernacht is "vandaag" een andere dag: dan opnieuw beginnen.
                if (minutes < _nowMinutes.value) {
                    _days.value = guideDays()
                    select(_selected.value)
                }
                _nowMinutes.value = minutes
            }
        }
    }

    fun select(index: Int) {
        val day = _days.value.getOrNull(index) ?: return
        _selected.value = index
        follow?.cancel()
        val created = Loader(viewModelScope, app.network, app.live.cachedGuide(day.date)) { force ->
            app.live.getGuide(day.date, force)
        }
        loader = created
        follow = viewModelScope.launch { created.state.collect { _guide.value = it } }
        created.load()
        // De dagen eromheen alvast ophalen: wisselen van dag is dan direct.
        viewModelScope.launch {
            listOf(index - 1, index + 1).mapNotNull { _days.value.getOrNull(it) }.forEach { runCatching { app.live.getGuide(it.date) } }
        }
    }

    fun refresh() {
        loader?.refresh()
    }

    private companion object {
        const val TODAY_INDEX = 1
        val zone: java.util.TimeZone = java.util.TimeZone.getTimeZone("Europe/Amsterdam")

        fun amsterdamMinutes(): Int = java.util.Calendar.getInstance(zone).let {
            it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)
        }

        fun guideDays(): List<GuideDay> {
            val dutch = java.util.Locale("nl", "NL")
            val iso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply { timeZone = zone }
            val label = java.text.SimpleDateFormat("EEE d MMM", dutch).apply { timeZone = zone }
            return (-1..6).map { offset ->
                val day = java.util.Calendar.getInstance(zone).apply { add(java.util.Calendar.DAY_OF_YEAR, offset) }.time
                val name = when (offset) {
                    -1 -> "Gisteren"
                    0 -> "Vandaag"
                    1 -> "Morgen"
                    else -> label.format(day).replace(".", "").replaceFirstChar { it.uppercase(dutch) }
                }
                GuideDay(iso.format(day), name)
            }
        }
    }
}

/** De uitgelichte kop en de gewone rijen, apart. */
fun List<CategoryRow>.splitFeatured(): Pair<ProgramItem?, List<CategoryRow>> {
    val featured = firstOrNull { it.id == "uitgelicht" }?.items?.firstOrNull()
        ?: firstOrNull()?.items?.firstOrNull()
    return featured to filterNot { it.id == "uitgelicht" || it.items.isEmpty() }
}

// ------------------------------------------------------------- programma

class ProgramViewModel(private val app: Family7MobileApp, private val slug: String) : ViewModel() {

    val detail = Loader<ProgramDetail>(
        scope = viewModelScope,
        network = app.network,
        initial = app.video.cachedDetail(slug)
    ) { app.video.getProgramDetail(slug) }

    init {
        viewModelScope.launch {
            // Het stream-adres van de eerste aflevering alvast opzoeken: dan
            // start "Afspelen" zonder wachttijd.
            detail.state
                .mapNotNull { it.data?.seasons?.firstOrNull()?.episodes?.firstOrNull()?.videoSlug }
                .distinctUntilChanged()
                .collect { app.video.prefetchStreamUrl(it) }
        }
        detail.load()
    }

    fun setInMyList(nodeId: String, add: Boolean) {
        viewModelScope.launch { app.myList.setInList(nodeId, add) }
    }
}

// ------------------------------------------------------------- overzichten

/** Welke lijst met programma's een overzichtsscherm toont. */
sealed interface GridSource {
    data object Kids : GridSource
    data object AZ : GridSource
    data class Page(val url: String) : GridSource
}

class GridViewModel(private val app: Family7MobileApp, source: GridSource) : ViewModel() {

    val programs = Loader(
        scope = viewModelScope,
        network = app.network,
        initial = when (source) {
            GridSource.Kids -> app.catalog.kidsCache.snapshot()
            GridSource.AZ -> app.catalog.azCache.snapshot()
            is GridSource.Page -> null
        }
    ) { force ->
        when (source) {
            GridSource.Kids -> app.catalog.getKidsPrograms(forceRefresh = force)
            GridSource.AZ -> app.catalog.getAllAZPrograms(forceRefresh = force)
            is GridSource.Page -> app.catalog.getProgramsFrom(source.url)
        }
    }

    init {
        programs.load()
    }
}

class MyListViewModel(private val app: Family7MobileApp) : ViewModel() {

    val items = app.myList.items

    private val _state = MutableStateFlow(LoadState<Unit>(isLoading = true))
    val state: StateFlow<LoadState<Unit>> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            app.myList.refresh()
                .onSuccess { _state.value = LoadState(data = Unit) }
                .onFailure { _state.value = LoadState(data = Unit.takeIf { items.value.isNotEmpty() }, error = friendlyError(it)) }
        }
    }
}
