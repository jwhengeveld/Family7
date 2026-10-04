package nl.family7.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import nl.family7.core.data.BACKGROUND_REFRESH_MS
import nl.family7.core.data.CategoryRow
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
