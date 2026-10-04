package nl.family7.core.data

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Leest de pagina's van family7.nl. Los van het netwerk en van Android, zodat
 * het gedrag met echte (en bewust verbouwde) pagina's te testen is.
 *
 * Bestand tegen veranderingen aan de site: elk gegeven heeft een vaste route
 * via de huidige opmaak, en daarachter vangnetten die niet op class-namen
 * leunen. Wordt de site verbouwd, dan valt de app terug op wat altijd blijft:
 * links naar /programmas/ en /video/, afbeeldingen in welke vorm ook, de
 * paginatitel en de node-id die Drupal zelf in drupalSettings zet.
 */
object Family7Parser {

    const val BASE_URL = "https://www.family7.nl"

    /** Kaartelementen zoals ze nu op de site voorkomen. */
    private val CARD_SELECTOR = listOf(
        ".slider-default_element",
        ".more-series-on-demand_element",
        ".view-block_element-wrapper",
        ".view-block_element",
        ".views-row"
    ).joinToString(", ")

    private const val PROGRAM_LINK = "a[href*='/programmas/']"
    private const val VIDEO_LINK = "a[href*='/video/']"

    /** Rijen die de apps zelf al bovenaan tonen. */
    private val SUPPRESSED_ROW_TITLES = setOf("mijn lijst", "mijn lijstje")

    val KIDS_HINTS = listOf("kinder", "kids", "jeugd")

    // ------------------------------------------------------------ sessie

    /**
     * Of dit de inlogpagina is in plaats van de gevraagde pagina. Family7 stuurt
     * een verlopen sessie daarheen; zonder deze controle zou de app de
     * inlogpagina "lezen" als een lege catalogus en die als waarheid bewaren.
     */
    fun isLoginPage(doc: Document, finalPath: String = ""): Boolean =
        finalPath.trimEnd('/').endsWith("/user/login") ||
            doc.selectFirst("input[name=form_id][value=user_login_form], form#user-login-form") != null

    // ----------------------------------------------------------- algemeen

    fun absolute(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$BASE_URL$url"
        else -> "$BASE_URL/$url"
    }

    fun slug(url: String): String = url.substringBefore("?").substringBefore("#").trimEnd('/').substringAfterLast('/')

    fun decodedSlug(url: String): String =
        runCatching { java.net.URLDecoder.decode(slug(url), "UTF-8") }.getOrDefault(slug(url))

    fun titleFromSlug(slug: String): String =
        decodedSlug(slug).replace('-', ' ').split(" ").filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    /**
     * Het beeld bij een element, in welke vorm de site het ook aanlevert: een
     * gewone src, lazy loading (data-src en varianten), srcset (het grootste),
     * een picture-element, of een achtergrondafbeelding in een stijlregel.
     */
    fun imageUrl(scope: Element): String {
        val img = if (scope.tagName() == "img") scope else scope.selectFirst("img")
        if (img != null) {
            for (attr in listOf("data-src", "data-lazy-src", "data-original", "src")) {
                val value = img.attr(attr).trim()
                if (value.isNotEmpty() && !value.startsWith("data:")) return absolute(value)
            }
            for (attr in listOf("data-srcset", "srcset")) {
                largestFromSrcset(img.attr(attr))?.let { return absolute(it) }
            }
        }
        scope.selectFirst("picture source[srcset], source[data-srcset]")?.let { source ->
            largestFromSrcset(source.attr("srcset").ifBlank { source.attr("data-srcset") })?.let { return absolute(it) }
        }
        val styled = (listOf(scope) + scope.select("[style*=url]")).firstNotNullOfOrNull { styleImage(it.attr("style")) }
        return styled?.let(::absolute).orEmpty()
    }

    private fun largestFromSrcset(srcset: String): String? =
        srcset.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .maxByOrNull { part -> part.substringAfterLast(' ').filter(Char::isDigit).toIntOrNull() ?: 0 }
            ?.substringBefore(' ')
            ?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }

    private val STYLE_URL = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""")

    private fun styleImage(style: String): String? = STYLE_URL.find(style)?.groupValues?.get(1)

    /**
     * Het Drupal-node-id van de pagina. Eerst de knop "Mijn lijst", dan wat
     * Drupal zelf in drupalSettings zet (nodeId, of path.currentPath "node/123").
     */
    fun nodeId(doc: Document): String {
        doc.selectFirst(".process-to-my-series-list[data-node-id], .tabs-more[data-node-id], [data-node-id]")
            ?.attr("data-node-id")?.takeIf { it.isNotBlank() }?.let { return it }
        val settings = doc.selectFirst("script[data-drupal-selector=drupal-settings-json]")?.data()
            ?: return ""
        return runCatching {
            val json = JSONObject(settings)
            json.optString("nodeId").takeIf { it.isNotBlank() && it != "null" }
                ?: json.optJSONObject("path")?.optString("currentPath")
                    ?.substringAfter("node/", "")?.takeWhile(Char::isDigit).orEmpty()
        }.getOrDefault("")
    }

    /** De titel van de pagina: og:title, de kop, of de paginatitel zonder " | Family7". */
    fun pageTitle(doc: Document): String =
        doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.title().substringBefore("|").trim()

    // ---------------------------------------------------------- programma's

    /**
     * Alle programmakaarten in een stuk pagina. Eerst de bekende kaarten; levert
     * dat niets op (de opmaak is veranderd), dan elke link naar een programma.
     */
    fun programCards(scope: Element): List<ProgramItem> {
        val viaCards = scope.select(CARD_SELECTOR).mapNotNull(::programFromCard)
        val found = viaCards.ifEmpty {
            scope.select(PROGRAM_LINK).mapNotNull { link -> programFromCard(cardAround(link)) }
        }
        return found.distinctBy { it.slug }
    }

    /**
     * De kaart rond een link: de link zelf, of zijn ouder als daar de titel of
     * het beeld naast staat (zoals nu: beeld in de link, titel ernaast).
     */
    private fun cardAround(link: Element): Element {
        var card: Element = link
        repeat(2) {
            val parent = card.parent() ?: return card
            // Stop voordat de "kaart" meerdere programma's omvat.
            if (parent.select(PROGRAM_LINK).map { slug(it.attr("href")) }.distinct().size > 1) return card
            card = parent
        }
        return card
    }

    private fun programFromCard(card: Element): ProgramItem? {
        val link = if (card.`is`(PROGRAM_LINK)) card else card.selectFirst(PROGRAM_LINK) ?: return null
        val href = link.attr("href")
        if (href.isBlank() || href.startsWith("#")) return null
        val slug = slug(href)
        if (slug.isBlank() || slug == "programmas") return null

        val img = card.selectFirst("img")
        // Op de sliders staat de leesbare naam alleen in het title-attribuut van de afbeelding.
        val title = card.selectFirst(".titleProgramme, .view-block_element-title, .title, h3, h4")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: img?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: link.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: link.text().trim().takeIf { it.isNotEmpty() && it.length < 80 }
            ?: titleFromSlug(slug)

        return ProgramItem(
            id = href,
            slug = slug,
            title = title,
            thumbnailUrl = imageUrl(card),
            badge = card.selectFirst("[class*=ribbon], .badge, .label")?.text()?.trim().orEmpty(),
            url = absolute(href),
            nodeId = card.selectFirst("[data-node-id]")?.attr("data-node-id").orEmpty()
        )
    }

    // --------------------------------------------------------- startpagina

    /** De uitgelichte kop bovenaan de On Demand-startpagina. */
    fun hero(doc: Document): ProgramItem? {
        val header = doc.selectFirst("section.on-demand-header, .on-demand-header, .hero, [class*=hero]") ?: return null
        val href = header.selectFirst(PROGRAM_LINK)?.attr("href").orEmpty()
        if (href.isEmpty()) return null
        val styleImage = header.select("style").joinToString(" ") { it.data() }.let(::styleImage)
        val slug = slug(href)
        return ProgramItem(
            id = href,
            slug = slug,
            title = header.selectFirst("h1, h2, .title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: titleFromSlug(slug),
            thumbnailUrl = absolute(styleImage ?: imageUrl(header)),
            url = absolute(href),
            description = header.selectFirst(".introduction, p")?.text().orEmpty(),
            nodeId = header.selectFirst("[data-node-id]")?.attr("data-node-id").orEmpty()
        )
    }

    /**
     * Elke categorierij op de startpagina, met de bijbehorende "meer"-link.
     * Vangnet: elke sectie met een kop en programmalinks.
     */
    fun homeRows(doc: Document): List<CategoryRow> {
        val primary = doc.select("section.on-demand_home-section, .block-view")
        val blocks = primary.ifEmpty { doc.select("section, .block").filter { it.selectFirst("h2, h3") != null } }
        val rows = mutableListOf<CategoryRow>()
        for (block in blocks) {
            val title = block.selectFirst(".block-view-header_element-title, h3, h2")?.text()?.trim().orEmpty()
            if (title.isEmpty() || title.lowercase() in SUPPRESSED_ROW_TITLES) continue
            val items = programCards(block)
            if (items.isEmpty()) continue
            val id = slugifyTitle(title)
            if (rows.any { it.id == id }) continue
            val more = block.selectFirst(".more-link a[href], a[href*='/plus/special/'], a.more[href]")
                ?.attr("href")?.let(::absolute).orEmpty()
            rows.add(CategoryRow(id = id, title = title, moreUrl = more, items = items))
        }
        return rows
    }

    fun slugifyTitle(title: String): String = title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /** Of er nog een volgende pagina is (Drupal-pager). */
    fun hasNextPage(doc: Document): Boolean =
        doc.select(".pager__item--next a, li.pager-next a, a[rel=next]").isNotEmpty()

    // ------------------------------------------------------- programma-pagina

    data class SeasonOption(val number: String, val title: String, val selected: Boolean)

    /** De seizoenen in de keuzelijst, in de volgorde van de site. */
    fun seasonOptions(doc: Document): List<SeasonOption> {
        val select = doc.selectFirst(".more-videos_season-select, select[class*=season], select[name*=season]")
            ?: return emptyList()
        return select.select("option").mapNotNull { option ->
            val value = option.attr("value").trim()
            if (value.isEmpty()) return@mapNotNull null
            SeasonOption(value, option.text().trim().ifEmpty { "Seizoen $value" }, option.hasAttr("selected"))
        }
    }

    fun programDetailBase(doc: Document, slug: String): ProgramDetail {
        val poster = doc.selectFirst(".video-page-top-content img, .series-page-image img, .main-image img")
            ?.let(::imageUrl)
            ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val myListButton = doc.selectFirst(".process-to-my-series-list, [data-node-id]")
        return ProgramDetail(
            slug = slug,
            title = pageTitle(doc).ifEmpty { titleFromSlug(slug) },
            posterUrl = absolute(poster),
            description = doc.selectFirst(".introduction, .series-page-description, .field--name-body")?.text()?.trim()
                ?: doc.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content").orEmpty(),
            category = doc.selectFirst(".series-info")?.text()?.trim().orEmpty(),
            nodeId = nodeId(doc),
            isInMyList = myListButton?.hasClass("added") == true
        )
    }

    /**
     * De afleveringen in een stuk pagina (de programmapagina, of het antwoord
     * van het seizoen-eindpunt). Vangnet: elke link naar een video.
     */
    fun episodes(scope: Element, fallbackThumb: String): List<EpisodeItem> {
        val viaCards = scope.select(".view-block_element-wrapper, .view-block_element").mapNotNull { episodeFromCard(it, fallbackThumb) }
        val found = viaCards.ifEmpty {
            scope.select(VIDEO_LINK).mapNotNull { link -> episodeFromCard(link.parent() ?: link, fallbackThumb) }
        }
        return found.distinctBy { it.videoSlug }
    }

    private fun episodeFromCard(card: Element, fallbackThumb: String): EpisodeItem? {
        val link = if (card.`is`(VIDEO_LINK)) card else card.selectFirst(VIDEO_LINK) ?: return null
        val href = link.attr("href")
        val episodeSlug = slug(href)
        if (episodeSlug.isEmpty()) return null

        val titleBlock = card.selectFirst(".video-title")
        val title = titleBlock?.selectFirst(".float-left")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: titleBlock?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: card.selectFirst(".view-block_element-title, .title, h3, h4")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: link.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: card.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() && !it.contains('_') }
            ?: titleFromSlug(episodeSlug.replace(Regex("^\\d+-\\d+-"), ""))

        // Het adres van een aflevering heeft de vorm {seizoen}-{nummer}-{slug}.
        val number = card.selectFirst(".video-number")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: episodeSlug.split("-").getOrNull(1)?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: ""

        return EpisodeItem(
            id = episodeSlug,
            episodeNumber = number,
            title = title,
            description = card.selectFirst(".video-description")?.text()?.trim().orEmpty(),
            duration = titleBlock?.selectFirst(".float-right")?.text()?.trim().orEmpty(),
            thumbnailUrl = imageUrl(card).ifEmpty { fallbackThumb },
            videoSlug = episodeSlug,
            videoUrl = absolute(href)
        )
    }

    // -------------------------------------------------------------- spelers

    /**
     * Het adres van de speler op een videopagina. Eerst de bekende containers,
     * dan elke iframe of data-src met "player" of "streampartner", en als
     * laatste elk player.php-adres in de ruwe tekst.
     */
    fun playerUrl(doc: Document, html: String): String {
        val candidates = sequence {
            yield(doc.select(".video-player--loader, .video-player--frame").attr("data-src"))
            yield(doc.select("iframe[src*='player.php']").attr("src"))
            yield(doc.select("[data-src*='player'], [data-src*='streampartner']").attr("data-src"))
            yield(doc.select("iframe[src*='player'], iframe[src*='streampartner']").attr("src"))
            yield(Regex("""https?://[^\s"'<>]*(?:player\.php|streampartner\.nl/)[^\s"'<>]*""").find(html)?.value.orEmpty())
        }
        return candidates.firstOrNull { it.isNotBlank() }?.let(::absolute).orEmpty()
    }

    // ------------------------------------------------------------ mijn lijst

    fun myList(doc: Document): List<ProgramItem> = programCards(doc.body() ?: doc)
}
