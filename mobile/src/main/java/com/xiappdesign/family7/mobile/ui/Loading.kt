package com.xiappdesign.family7.mobile.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.xiappdesign.family7.core.data.NetworkMonitor
import com.xiappdesign.family7.core.data.SessionExpiredException
import com.xiappdesign.family7.core.data.UnauthorizedException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Wat een scherm over zijn inhoud weet.
 *
 * Het principe: wat er al is (uit het geheugen of van schijf) blijft staan,
 * ook als verversen mislukt. Een foutscherm alleen als er echt niets te tonen
 * is; anders een rustige melding boven de bestaande inhoud.
 */
data class LoadState<T>(
    val data: T? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isOffline: Boolean = false,
    /** De gebruiker veegde zelf om te verversen; alleen dan een zichtbare indicator. */
    val isUserRefresh: Boolean = false
) {
    val showSkeleton: Boolean get() = data == null && isLoading
    val showFullError: Boolean get() = data == null && !isLoading && error != null
    /**
     * Toont de veeg-indicator. Stil verversen op de achtergrond laat niets
     * zien: de nieuwe inhoud verschijnt gewoon.
     */
    val isRefreshing: Boolean get() = data != null && isLoading && isUserRefresh
}

/**
 * Laadt inhoud volgens "toon wat je hebt, ververs stil":
 * - begint met [initial] (cache of snapshot van de vorige sessie);
 * - een tweede verzoek terwijl het eerste nog loopt, start niets nieuws;
 * - na een mislukking door een wegvallend netwerk wordt het vanzelf opnieuw
 *   geprobeerd zodra de verbinding terug is.
 */
class Loader<T>(
    private val scope: CoroutineScope,
    network: NetworkMonitor,
    initial: T?,
    private val fetch: suspend (force: Boolean) -> Result<T>
) {
    private val _state = MutableStateFlow(LoadState(data = initial))
    val state: StateFlow<LoadState<T>> = _state.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch {
            network.online.collect { online ->
                val wasOffline = _state.value.isOffline
                _state.update { it.copy(isOffline = !online) }
                if (online && wasOffline && _state.value.error != null) load(force = true)
            }
        }
    }

    /** Door de gebruiker gevraagd (vegen of "opnieuw"): met zichtbare indicator. */
    fun refresh() = load(force = true, byUser = true)

    fun load(force: Boolean = false, byUser: Boolean = false) {
        if (job?.isActive == true) {
            if (!force) return
            job?.cancel()
        }
        job = scope.launch {
            _state.update { it.copy(isLoading = true, error = null, isUserRefresh = byUser) }
            fetch(force)
                .onSuccess { value -> _state.update { it.copy(data = value, isLoading = false, error = null) } }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(isLoading = false, error = friendlyError(e)) }
                }
        }
    }

    /** Zet inhoud die van elders binnenkwam (bijvoorbeeld een snapshot van schijf). */
    fun offer(value: T) {
        if (_state.value.data == null) _state.update { it.copy(data = value) }
    }
}

/** Een foutmelding die een kijker begrijpt, in plaats van een technische uitzondering. */
fun friendlyError(error: Throwable?): String {
    val cause = generateSequence(error) { it.cause }.toList()
    return when {
        cause.any { it is SessionExpiredException } ->
            "Uw sessie bij Family7 is verlopen. Log opnieuw in."
        cause.any { it is UnauthorizedException } ->
            "Log in met uw Family7 Plus-account om dit te zien."
        cause.any { it is UnknownHostException || it is ConnectException } ->
            "Geen verbinding met Family7. De app probeert het vanzelf opnieuw zodra er weer internet is."
        cause.any { it is SocketTimeoutException } ->
            "Family7 reageert traag. Probeer het zo nog eens."
        cause.any { it is SSLException } ->
            "Er kon geen veilige verbinding met Family7 worden gemaakt."
        cause.any { it is IOException } ->
            "De verbinding met Family7 viel weg. Probeer het opnieuw."
        else -> error?.message?.takeIf { it.isNotBlank() } ?: "Er ging iets mis bij het laden."
    }
}
