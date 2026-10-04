package nl.family7.core.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * Sitewachter: controleert of de echte family7.nl nog leest zoals de apps
 * verwachten. Draait alleen op verzoek, en wekelijks in CI
 * (.github/workflows/site-watch.yml), zodat een verbouwing aan de site opvalt
 * voordat kijkers lege schermen zien:
 *
 *     ./gradlew :core:testDebugUnitTest --tests '*LiveSiteTest*' -Pfamily7.live=true
 *
 * Alleen publieke pagina's; er zijn geen inloggegevens voor nodig.
 */
class LiveSiteTest {

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    @Before
    fun onlyWhenAsked() {
        assumeTrue("Alleen met -Pfamily7.live=true", System.getProperty("family7.live") == "true")
    }

    private fun get(url: String): String =
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Family7 sitewachter)")
                .header("Referer", "https://www.family7.nl/plus")
                .build()
        ).execute().use { response ->
            assertTrue(response.isSuccessful, "$url gaf ${response.code}")
            response.body!!.string()
        }

    private fun page(url: String): Document = Jsoup.parse(get(url), url)

    @Test
    fun `de A-Z-lijst levert nog programma's met titel en beeld`() {
        val programs = Family7Parser.programCards(page("https://www.family7.nl/plus/a-z?title=All"))
        assertTrue(programs.size >= 50, "maar ${programs.size} programma's op de A-Z-pagina")
        assertTrue(programs.count { it.thumbnailUrl.isNotBlank() } >= programs.size * 0.9, "beelden ontbreken")
        assertTrue(programs.none { it.title.isBlank() }, "programma's zonder titel")
    }

    @Test
    fun `een programmapagina levert nog node-id, seizoenen en afleveringen`() {
        val slug = Family7Parser.programCards(page("https://www.family7.nl/plus/a-z?title=All"))
            .map { it.slug }
            .firstOrNull { it == "bijbelse-karakters" } ?: "bijbelse-karakters"
        val doc = page("https://www.family7.nl/plus/programmas/$slug")
        val detail = Family7Parser.programDetailBase(doc, slug)
        assertTrue(detail.nodeId.isNotBlank(), "geen node-id op de programmapagina")
        assertTrue(Family7Parser.episodes(doc, detail.posterUrl).isNotEmpty(), "geen afleveringen op de programmapagina")

        val options = Family7Parser.seasonOptions(doc)
        assertTrue(options.size > 1, "de seizoenkeuze is verdwenen of veranderd")
        val other = options.last { !it.selected }
        val season = JSONObject(get("https://www.family7.nl/get-videos-by-season/${detail.nodeId}/${other.number}"))
        val episodes = Family7Parser.episodes(Jsoup.parseBodyFragment(season.getString("renderedItems"), "https://www.family7.nl"), "")
        assertTrue(episodes.isNotEmpty(), "het seizoen-eindpunt levert geen afleveringen meer")
    }

    @Test
    fun `de herkenning van een anonieme pagina klopt nog`() {
        // Zonder sessie is elke pagina anoniem; verdwijnt die markering, dan
        // herkent de app een verlopen sessie niet meer.
        assertTrue(PageFetcher.isAnonymous(page("https://www.family7.nl/plus/a-z?title=All")), "anonieme markering verdwenen")
        assertTrue(Family7Parser.isLoginPage(page("https://www.family7.nl/user/login")), "inlogformulier niet meer herkend")
    }
}
