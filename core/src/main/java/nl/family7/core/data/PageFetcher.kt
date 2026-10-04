package nl.family7.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

/** De sessie bleek verlopen: Family7 gaf een anonieme pagina of de inlogpagina terug. */
class SessionExpiredException : IOException("Uw sessie bij Family7 is verlopen. Log opnieuw in.")

/**
 * Meldingen over de sessie, voor de apps: bij [expired] laten ze Family7 de
 * sessie bevestigen en sturen ze de gebruiker zo nodig naar het aanmeldscherm,
 * in plaats van lege lijsten te tonen.
 */
object SessionEvents {
    private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val expired: SharedFlow<Unit> = _expired.asSharedFlow()

    internal fun reportExpired() {
        _expired.tryEmit(Unit)
    }
}

/**
 * Haalt pagina's van family7.nl op, slim en voorzichtig:
 *
 * - Gelijktijdige verzoeken voor hetzelfde adres delen één download (de
 *   startpagina, de kids-sectie en de specials vragen /plus vaak tegelijk).
 * - Een pagina blijft een halve minuut in het geheugen, zodat snel heen en
 *   weer navigeren geen nieuwe verzoeken kost.
 * - Een verlopen sessie wordt herkend (inlogpagina, of een anonieme pagina
 *   terwijl er een sessiecookie meeging) en gemeld, in plaats van dat de
 *   anonieme pagina als lege catalogus wordt gelezen.
 */
class PageFetcher(
    private val client: OkHttpClient,
    private val hasSessionCookie: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis
) {
    data class Page(val html: String, val finalUrl: String, val code: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val inFlight = HashMap<String, Deferred<Page>>()
    private val recent = HashMap<String, Pair<Long, Page>>()

    /** De ruwe pagina, gedeeld met gelijktijdige verzoeken en kort onthouden. */
    suspend fun page(url: String, referer: String = DEFAULT_REFERER, maxAgeMs: Long = MEMO_MS): Page {
        val pending = synchronized(lock) {
            recent[url]?.let { (at, page) -> if (now() - at < maxAgeMs) return page }
            inFlight[url] ?: scope.async { download(url, referer) }.also { inFlight[url] = it }
        }
        try {
            val page = pending.await()
            synchronized(lock) { recent[url] = now() to page }
            return page
        } finally {
            synchronized(lock) { if (inFlight[url] === pending) inFlight.remove(url) }
        }
    }

    /**
     * De pagina als document. Gooit [SessionExpiredException] als Family7 de
     * inlogpagina of een anonieme pagina teruggeeft terwijl we ingelogd zouden
     * moeten zijn; gooit [UnauthorizedException] bij 401/403.
     */
    suspend fun document(url: String, referer: String = DEFAULT_REFERER, maxAgeMs: Long = MEMO_MS): Document {
        val page = page(url, referer, maxAgeMs)
        if (page.code == 401 || page.code == 403) {
            forget(url)
            if (hasSessionCookie()) SessionEvents.reportExpired()
            throw UnauthorizedException()
        }
        val doc = Jsoup.parse(page.html, page.finalUrl)
        val path = page.finalUrl.toHttpPath()
        if (Family7Parser.isLoginPage(doc, path) || (hasSessionCookie() && isAnonymous(doc) && requiresLogin(url))) {
            forget(url)
            SessionEvents.reportExpired()
            throw SessionExpiredException()
        }
        return doc
    }

    /**
     * Voor pagina's die publiek leesbaar zijn maar ingelogd meer tonen: is de
     * pagina anoniem terwijl er een sessiecookie meeging, dan is de sessie verlopen.
     */
    fun checkSession(doc: Document) {
        if (hasSessionCookie() && isAnonymous(doc)) {
            SessionEvents.reportExpired()
            throw SessionExpiredException()
        }
    }

    fun forget(url: String) = synchronized(lock) { recent.remove(url) }

    fun clear() = synchronized(lock) { recent.clear() }

    private fun download(url: String, referer: String): Page {
        val request = Request.Builder().url(url).header("Referer", referer).build()
        client.newCall(request).execute().use { response ->
            return Page(
                html = response.body?.string().orEmpty(),
                finalUrl = response.request.url.toString(),
                code = response.code
            )
        }
    }

    private fun String.toHttpPath(): String = substringAfter("://").substringAfter('/', "").let { "/$it" }.substringBefore('?')

    companion object {
        const val DEFAULT_REFERER = "https://www.family7.nl/plus"
        private const val MEMO_MS = 30_000L

        /** Het menu dat Drupal alleen aan anonieme bezoekers toont. */
        fun isAnonymous(doc: Document): Boolean =
            doc.selectFirst(".menu-anonymous-user, .account-menu_menu-anonymous-user") != null

        /**
         * Pagina's die alleen ingelogd hun inhoud tonen. Programma-, video- en
         * A-Z-pagina's zijn ook anoniem te lezen; daar is een anonieme versie
         * dus geen teken van een verlopen sessie.
         */
        fun requiresLogin(url: String): Boolean {
            val path = url.substringAfter("family7.nl").substringBefore('?').trimEnd('/')
            return path == "/plus" || path.startsWith("/plus/mijnlijst") || path.startsWith("/plus/live") ||
                path.startsWith("/plus/nieuw") || path.startsWith("/ondemandkijken") || path.startsWith("/plus/special")
        }
    }
}

class UnauthorizedException : IOException("Niet ingelogd")

/**
 * Of een nieuwe uitkomst een goede vorige mag vervangen. Een site-verbouwing
 * levert vaak een pagina op die wel laadt maar (bijna) niets oplevert; dan
 * blijft de laatst goede versie staan in plaats van dat de app leegloopt.
 */
object Plausibility {
    /** Minder dan dit deel van de vorige omvang is verdacht. */
    private const val MIN_FRACTION = 0.4
    /** Onder deze vorige omvang is schommelen normaal en controleren zinloos. */
    private const val MIN_PREVIOUS = 10

    fun acceptable(previousCount: Int?, newCount: Int): Boolean {
        if (previousCount == null || previousCount == 0) return true
        if (newCount == 0) return false
        if (previousCount < MIN_PREVIOUS) return true
        return newCount >= previousCount * MIN_FRACTION
    }
}

/**
 * Hoe lang een stream-adres bruikbaar is. Streampartner (Wowza) zet het einde
 * van het token in het adres; zolang dat ruim in de toekomst ligt, hoeft de app
 * het adres niet opnieuw op te zoeken. Zonder herkenbaar token: twee minuten.
 */
object StreamUrlLifetime {
    private const val FALLBACK_MS = 2 * 60_000L
    private const val MAX_MS = 6 * 60 * 60_000L
    /** Ruim voor het einde stoppen: een aflevering moet ook nog af te kijken zijn. */
    private const val MARGIN_MS = 45 * 60_000L
    private val END_TIME = Regex("""(?i)[?&][a-z_]*endtime=(\d{9,13})""")

    fun validForMs(url: String, now: Long = System.currentTimeMillis()): Long {
        val end = END_TIME.find(url)?.groupValues?.get(1)?.toLongOrNull() ?: return FALLBACK_MS
        val endMs = if (end < 100_000_000_000L) end * 1000 else end
        val remaining = endMs - now - MARGIN_MS
        return when {
            remaining <= 0 -> 0L
            else -> minOf(remaining, MAX_MS)
        }
    }
}
