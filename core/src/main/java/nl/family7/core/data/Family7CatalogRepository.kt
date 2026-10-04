package nl.family7.core.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Leest de On Demand catalogus rechtstreeks van family7.nl. Er staat geen
 * programmalijst in de app: alle rijen, titels en afbeeldingen komen van de
 * site, dus nieuwe programma's verschijnen vanzelf.
 *
 * Ingelogd stuurt /plus door naar /ondemandkijken; die pagina bevat de
 * uitgelichte kop en alle categorierijen.
 */
class Family7CatalogRepository(appContext: Context) {
    private val context: Context = appContext.applicationContext

    private val client = Family7Http.getClient(context)

    /** Onthoudt gevonden specialpagina's, als terugval wanneer de pagina niet leesbaar is. */
    private val cache = context.getSharedPreferences("family7_catalog_cache", Context.MODE_PRIVATE)

    // Cache in het geheugen: laat een scherm meteen de vorige inhoud tonen en
    // ververst stil op de achtergrond.
    val homeCache = TimedCache<List<CategoryRow>>(CATALOG_TTL_MS)
    val azCache = TimedCache<List<ProgramItem>>(CATALOG_TTL_MS)
    val kidsCache = TimedCache<List<ProgramItem>>(CATALOG_TTL_MS)

    /** De catalogus van de vorige sessie, voor een koude start zonder laadscherm. */
    private val snapshots = CatalogSnapshotStore(context)

    /**
     * Wist de cache in het geheugen en op schijf. Bedoeld voor het uitloggen:
     * de catalogus van een account blijft niet achter voor de volgende.
     */
    fun clearMemoryCache() {
        homeCache.clear()
        azCache.clear()
        kidsCache.clear()
        snapshots.clear()
    }

    /**
     * Laadt de catalogus van de vorige sessie van schijf in de caches, zonder
     * dat die als vers telt. Een scherm kan daarmee meteen iets tonen terwijl
     * het eerste ophalen nog loopt. Veilig om vaker aan te roepen.
     */
    suspend fun restoreSnapshots() = withContext(Dispatchers.IO) {
        if (homeCache.snapshot() == null) snapshots.readRows(SNAPSHOT_HOME)?.let(homeCache::seed)
        if (azCache.snapshot() == null) snapshots.readItems(SNAPSHOT_AZ)?.let(azCache::seed)
        if (kidsCache.snapshot() == null) snapshots.readItems(SNAPSHOT_KIDS)?.let(kidsCache::seed)
    }

    companion object {
        private const val BASE_URL = "https://www.family7.nl"
        private const val PLUS_HOME_URL = "$BASE_URL/plus"
        private const val PLUS_NIEUW_URL = "$BASE_URL/plus/nieuw"
        private const val PLUS_AZ_URL = "$BASE_URL/plus/a-z?title=All"

        private const val KEY_KIDS_URL = "kids_url"

        private const val SNAPSHOT_HOME = "home"
        private const val SNAPSHOT_AZ = "az"
        private const val SNAPSHOT_KIDS = "kids"

        /** Rijen die de app zelf al bovenaan toont, om dubbelingen te voorkomen. */
        private val SUPPRESSED_ROW_TITLES = setOf("mijn lijst", "mijn lijstje")

        /** Veiligheidsgrens bij het doorbladeren van gepagineerde overzichten. */
        private const val MAX_PAGES = 20

        /** Waar de app een kidssectie aan herkent, ongeacht de exacte naam. */
        private val KIDS_HINTS = listOf("kinder", "kids", "jeugd")
    }

    // ---------------------------------------------------------------- home

    /**
     * De volledige On Demand startpagina: de uitgelichte kop, "Nieuw toegevoegd"
     * en elke categorierij die Family7 op dat moment toont.
     */
    suspend fun getOnDemandHome(forceRefresh: Boolean = false): Result<List<CategoryRow>> = withContext(Dispatchers.IO) {
        if (!forceRefresh) homeCache.fresh()?.let { return@withContext Result.success(it) }
        try {
            // De startpagina en "Nieuw toegevoegd" (een eigen pagina) tegelijk
            // ophalen: dat scheelt een volle netwerkronde voor het eerste beeld.
            val (doc, newItems) = coroutineScope {
                val newest = async { runCatching { parseCardsFromDoc(fetchDocument(PLUS_NIEUW_URL)) }.getOrNull() }
                val home = async { fetchDocument(PLUS_HOME_URL) }
                home.await() to newest.await()
            }
            val rows = mutableListOf<CategoryRow>()

            parseHero(doc)?.let { hero ->
                rows.add(CategoryRow(id = "uitgelicht", title = "Uitgelicht", items = listOf(hero)))
            }

            newItems
                ?.takeIf { it.isNotEmpty() }
                ?.let { items ->
                    rows.add(
                        CategoryRow(
                            id = "nieuw_toegevoegd",
                            title = "Nieuw toegevoegd",
                            moreUrl = PLUS_NIEUW_URL,
                            items = items.distinctBy { it.slug }
                        )
                    )
                }

            rows.addAll(parseHomeRows(doc).filterNot { row -> rows.any { it.id == row.id } })

            // Terugval als de opmaak van de site verandert: toon dan tenminste alles.
            if (rows.none { it.items.isNotEmpty() && it.id != "uitgelicht" }) {
                val all = fetchAllPages(PLUS_AZ_URL)
                if (all.isNotEmpty()) {
                    rows.add(CategoryRow(id = "alle", title = "Alle programma's", items = all))
                }
            }

            homeCache.put(rows)
            // Een lege uitkomst (bijvoorbeeld een sessie die net verliep) mag de
            // laatst goede catalogus op schijf niet overschrijven.
            if (rows.any { it.items.isNotEmpty() }) snapshots.writeRows(SNAPSHOT_HOME, rows)
            Result.success(rows)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * De uitgelichte kop bovenaan: elke rij op de pagina zit in een eigen
     * `section.on-demand_home-section`, de kop in `section.on-demand-header`.
     */
    private fun parseHero(doc: Document): ProgramItem? {
        val header = doc.selectFirst("section.on-demand-header") ?: return null
        val playHref = header.selectFirst("a[href*='/plus/programmas/']")?.attr("href").orEmpty()
        if (playHref.isEmpty()) return null

        // De achtergrondafbeelding staat in een inline <style>-regel.
        val backgroundUrl = Regex("url\\('([^']+)'\\)")
            .find(header.select("style").html())
            ?.groupValues?.get(1)
            .orEmpty()

        return ProgramItem(
            id = playHref,
            slug = extractSlug(playHref),
            title = titleFromSlug(extractSlug(playHref)),
            thumbnailUrl = absolute(backgroundUrl),
            url = absolute(playHref),
            description = header.selectFirst(".introduction")?.text().orEmpty(),
            nodeId = header.selectFirst("[data-node-id]")?.attr("data-node-id").orEmpty()
        )
    }

    /** Elke categorierij op de startpagina, met de bijbehorende "meer"-link. */
    private fun parseHomeRows(doc: Document): List<CategoryRow> {
        val rows = mutableListOf<CategoryRow>()

        for (block in doc.select("section.on-demand_home-section, .block-view")) {
            val title = block.selectFirst(".block-view-header_element-title, h3, h2")
                ?.text()?.trim().orEmpty()
            if (title.isEmpty() || title.lowercase() in SUPPRESSED_ROW_TITLES) continue

            val items = block.select(CARD_SELECTOR)
                .mapNotNull { parseProgramCard(it) }
                .distinctBy { it.slug }
            if (items.isEmpty()) continue

            val id = slugifyTitle(title)
            if (rows.any { it.id == id }) continue

            val moreUrl = block.selectFirst(".more-link a[href], a[href*='/plus/special/']")
                ?.attr("href")?.let { absolute(it) }.orEmpty()

            if (moreUrl.contains("/plus/special/") &&
                KIDS_HINTS.any { hint -> (title + moreUrl).lowercase().contains(hint) }
            ) {
                cache.edit().putString(KEY_KIDS_URL, moreUrl).apply()
            }

            rows.add(CategoryRow(id = id, title = title, moreUrl = moreUrl, items = items))
        }
        return rows
    }

    // ------------------------------------------------------------ specials

    /** Alle specialpagina's die Family7+ op dit moment aanbiedt. */
    suspend fun getSpecials(): Result<List<CategoryRow>> = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDocument(PLUS_HOME_URL)
            val specials = doc.select("a[href*='/plus/special/']")
                .mapNotNull { a ->
                    val href = a.attr("href").trim().ifEmpty { return@mapNotNull null }
                    val url = absolute(href)
                    val label = a.text().trim()
                        .takeIf { it.isNotEmpty() && !it.equals("meer", ignoreCase = true) }
                        ?: decodeSlug(href)
                    CategoryRow(id = extractSlug(href), title = label, moreUrl = url)
                }
                .distinctBy { it.moreUrl }
            Result.success(specials)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * De kinderprogramma's. Het adres van de specialpagina komt uit de
     * "meer"-link naast de kidsrij op de startpagina, dus een hernoeming of
     * verhuizing aan de kant van Family7 gaat vanzelf mee.
     */
    suspend fun getKidsPrograms(forceRefresh: Boolean = false): Result<List<ProgramItem>> = withContext(Dispatchers.IO) {
        if (!forceRefresh) kidsCache.fresh()?.let { return@withContext Result.success(it) }
        try {
            val url = resolveKidsUrl()
                ?: return@withContext Result.failure(
                    Exception(
                        "Geen kidssectie gevonden. Controleer of u bent ingelogd met een " +
                            "Family7 Plus account."
                    )
                )

            val items = fetchAllPages(url)
            if (items.isEmpty()) {
                Result.failure(Exception("Er zijn nu geen kinderprogramma's beschikbaar."))
            } else {
                kidsCache.put(items)
                snapshots.writeItems(SNAPSHOT_KIDS, items)
                Result.success(items)
            }
        } catch (e: UnauthorizedException) {
            Result.failure(Exception("De kinderprogramma's zijn alleen zichtbaar als u bent ingelogd."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun resolveKidsUrl(): String? {
        runCatching {
            val doc = fetchDocument(PLUS_HOME_URL)
            doc.select("a[href*='/plus/special/']")
                .map { it.attr("href") }
                .firstOrNull { href ->
                    val haystack = decodeSlug(href).lowercase()
                    KIDS_HINTS.any { haystack.contains(it) }
                }
                ?.let { absolute(it) }
        }.getOrNull()?.let { found ->
            cache.edit().putString(KEY_KIDS_URL, found).apply()
            return found
        }
        return cache.getString(KEY_KIDS_URL, null)
    }

    // ----------------------------------------------------------------- a-z

    suspend fun getAllAZPrograms(forceRefresh: Boolean = false): Result<List<ProgramItem>> = withContext(Dispatchers.IO) {
        if (!forceRefresh) azCache.fresh()?.let { return@withContext Result.success(it) }
        try {
            val items = fetchAllPages(PLUS_AZ_URL)
            if (items.isNotEmpty()) {
                azCache.put(items)
                snapshots.writeItems(SNAPSHOT_AZ, items)
            }
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Haalt alle programma's van een willekeurige overzichtspagina. */
    suspend fun getProgramsFrom(url: String): Result<List<ProgramItem>> = withContext(Dispatchers.IO) {
        try {
            Result.success(fetchAllPages(url))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchPrograms(query: String): Result<List<ProgramItem>> = withContext(Dispatchers.IO) {
        try {
            // Zoeken filtert de A-Z-lijst; die hoeft niet bij elke letter opnieuw
            // over het netwerk.
            val all = azCache.fresh() ?: fetchAllPages(PLUS_AZ_URL).also { if (it.isNotEmpty()) azCache.put(it) }
            if (query.isBlank()) return@withContext Result.success(all)
            val q = query.trim().lowercase()
            Result.success(all.filter { it.title.lowercase().contains(q) || it.slug.contains(q) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------- parsing

    /**
     * Kaartelementen zoals ze op de site voorkomen: de sliders op de
     * startpagina en de rasters op overzichts- en specialpagina's.
     */
    private val CARD_SELECTOR = listOf(
        ".slider-default_element",
        ".more-series-on-demand_element",
        ".view-block_element-wrapper",
        ".view-block_element",
        ".views-row"
    ).joinToString(", ")

    private fun parseCardsFromDoc(doc: Document): List<ProgramItem> =
        doc.select(CARD_SELECTOR).mapNotNull { parseProgramCard(it) }.distinctBy { it.slug }

    private fun parseProgramCard(card: Element): ProgramItem? {
        val a = card.selectFirst("a[href*='/plus/programmas/'], a[href*='/programmas/']")
            ?: return null

        val href = a.attr("href")
        if (href.isEmpty() || href.startsWith("#")) return null

        val img = card.selectFirst("img")
        // Op de sliders staat de leesbare naam alleen in het title-attribuut
        // van de afbeelding; elders in een tekstelement.
        val title = card.selectFirst(".titleProgramme, .view-block_element-title, .title, h4")
            ?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: img?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: titleFromSlug(extractSlug(href))

        return ProgramItem(
            id = href,
            slug = extractSlug(href),
            title = title,
            thumbnailUrl = absolute(img?.attr("src").orEmpty()),
            badge = card.selectFirst("[class*=ribbon], .badge, .label")?.text()?.trim().orEmpty(),
            url = absolute(href),
            nodeId = card.selectFirst("[data-node-id]")?.attr("data-node-id").orEmpty()
        )
    }

    // ------------------------------------------------------------ ophalen

    /**
     * Leest een overzichtspagina en volgt de pagineringslinks, zodat ook
     * programma's op vervolgpagina's meekomen.
     */
    private fun fetchAllPages(startUrl: String): List<ProgramItem> {
        val collected = LinkedHashMap<String, ProgramItem>()
        var page = 0

        while (page < MAX_PAGES) {
            val url = if (page == 0) startUrl else appendPageParam(startUrl, page)
            val doc = runCatching { fetchDocument(url) }.getOrNull() ?: break

            val before = collected.size
            parseCardsFromDoc(doc).forEach { collected.putIfAbsent(it.slug, it) }

            val hasMore = doc.select(".pager__item--next a, li.pager-next a, a[rel=next]").isNotEmpty()
            if (collected.size == before || !hasMore) break
            page++
        }
        return collected.values.toList()
    }

    private fun appendPageParam(url: String, page: Int): String =
        if (url.contains("?")) "$url&page=$page" else "$url?page=$page"

    private fun fetchDocument(url: String): Document {
        val req = Request.Builder()
            .url(url)
            .header("Referer", PLUS_HOME_URL)
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 401 || resp.code == 403) throw UnauthorizedException()
            return Jsoup.parse(resp.body?.string() ?: "", url)
        }
    }

    // ------------------------------------------------------------ helpers

    private fun absolute(url: String): String = when {
        url.isEmpty() -> ""
        url.startsWith("http") -> url
        url.startsWith("/") -> "$BASE_URL$url"
        else -> "$BASE_URL/$url"
    }

    private fun extractSlug(url: String): String =
        url.substringBefore("?").trimEnd('/').substringAfterLast('/')

    private fun decodeSlug(url: String): String =
        runCatching { java.net.URLDecoder.decode(extractSlug(url), "UTF-8") }
            .getOrDefault(extractSlug(url))

    private fun titleFromSlug(slug: String): String =
        decodeSlug(slug)
            .replace('-', ' ')
            .split(" ")
            .filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun slugifyTitle(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private class UnauthorizedException : Exception("Niet ingelogd")
}
