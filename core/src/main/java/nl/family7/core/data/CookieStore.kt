package nl.family7.core.data

import okhttp3.Cookie
import okhttp3.HttpUrl

/**
 * Houdt cookies bij volgens de regels die de server meegeeft.
 *
 * Anders dan een simpele "hoort de hostnaam bij elkaar"-vergelijking laat deze
 * opslag OkHttp zelf bepalen of een cookie bij een adres hoort: domein, pad,
 * de secure-vlag en de vervaldatum tellen allemaal mee. Een sessiecookie van
 * family7.nl gaat daarmee nooit naar een ander domein en nooit over een
 * onversleutelde verbinding.
 *
 * Deze klasse kent geen Android; de regels zijn los te testen.
 */
internal class CookieStore(private val now: () -> Long = System::currentTimeMillis) {

    private val cookies = mutableListOf<Cookie>()

    /** Neemt cookies over uit een antwoord; een cookie met een vervaldatum in het verleden wist zichzelf. */
    @Synchronized
    fun save(newCookies: List<Cookie>) {
        for (cookie in newCookies) {
            cookies.removeAll { it.isSameAs(cookie) }
            if (cookie.expiresAt > now()) {
                cookies.add(cookie)
            }
        }
    }

    /** De cookies die bij dit adres horen, verlopen exemplaren eerst opgeruimd. */
    @Synchronized
    fun forUrl(url: HttpUrl): List<Cookie> {
        purgeExpired()
        return cookies.filter { it.matches(url) }
    }

    /** Alles wat nog geldig is, om op schijf te bewaren. */
    @Synchronized
    fun valid(): List<Cookie> {
        purgeExpired()
        return cookies.toList()
    }

    @Synchronized
    fun replaceAll(restored: List<Cookie>) {
        cookies.clear()
        cookies.addAll(restored.filter { it.expiresAt > now() })
    }

    @Synchronized
    fun clear() = cookies.clear()

    /** Naam-waardeparen, zoals de speler ze nodig heeft. */
    @Synchronized
    fun asNameValueMap(): Map<String, String> {
        purgeExpired()
        return cookies.associate { it.name to it.value }
    }

    /** Of er een Drupal-aanmeldcookie tussen staat. */
    @Synchronized
    fun hasSessionCookie(): Boolean {
        purgeExpired()
        return cookies.any { it.name.startsWith("SSESS") || it.name.startsWith("SESS") }
    }

    private fun purgeExpired() {
        val moment = now()
        cookies.removeAll { it.expiresAt <= moment }
    }

    /** Twee cookies zijn dezelfde als naam, domein en pad overeenkomen. */
    private fun Cookie.isSameAs(other: Cookie): Boolean =
        name == other.name && domain == other.domain && path == other.path
}

/**
 * Zet cookies om naar tekst en terug. Het domein gaat apart mee, omdat een
 * cookie zonder domain-attribuut alleen voor precies die host geldt en die
 * eigenschap anders verloren zou gaan bij het terugleggen.
 */
internal object CookieCodec {

    private const val SEPARATOR = " "

    fun encode(cookie: Cookie): String = cookie.domain + SEPARATOR + cookie.toString()

    fun decode(line: String): Cookie? {
        val split = line.indexOf(SEPARATOR)
        if (split <= 0) return null
        val domain = line.substring(0, split)
        val setCookie = line.substring(split + SEPARATOR.length)
        val url = HttpUrl.Builder().scheme("https").host(domain).build()
        return Cookie.parse(url, setCookie)
    }
}
