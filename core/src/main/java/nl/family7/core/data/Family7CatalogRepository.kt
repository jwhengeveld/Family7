package nl.family7.core.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * Leest de On Demand catalogus rechtstreeks van family7.nl. Er staat geen
 * programmalijst in de app: alle rijen, titels en afbeeldingen komen van de
 * site, dus nieuwe programma's verschijnen vanzelf.
 *
 * Bestand tegen veranderingen aan de site: het lezen zelf heeft vangnetten
 * (zie [Family7Parser]), en een uitkomst die verdacht mager is vergeleken met
 * de vorige keer (zie [Plausibility]) vervangt de laatst goede catalogus niet.
 * Die blijft dan staan, ook op schijf, tot de site weer iets zinnigs oplevert.
 */
class Family7CatalogRepository(appContext: Context) {
    private val context: Context = appContext.applicationContext

    private val pages = Family7Http.getPageFetcher(context)

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
        pages.clear()
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
        private const val BASE_URL = Family7Parser.BASE_URL
        private const val PLUS_HOME_URL = "$BASE_URL/plus"
        private const val PLUS_NIEUW_URL = "$BASE_URL/plus/nieuw"
        private const val PLUS_AZ_URL = "$BASE_URL/plus/a-z?title=All"

        private const val KEY_KIDS_URL = "kids_url"

        private const val SNAPSHOT_HOME = "home"
        private const val SNAPSHOT_AZ = "az"
        private const val SNAPSHOT_KIDS = "kids"

        /** Veiligheidsgrens bij het doorbladeren van gepagineerde overzichten. */
        private const val MAX_PAGES = 20
        /** Zoveel vervolgpagina's tegelijk, als het aantal bekend is. */
        private const val PAGE_CONCURRENCY = 4
    }

    // ---------------------------------------------------------------- home

    /**
     * De volledige On Demand startpagina: de uitgelichte kop, "Nieuw toegevoegd"
     * en elke categorierij die Family7 op dat moment toont.
     */
    suspend fun getOnDemandHome(forceRefresh: Boolean = false): Result<List<CategoryRow>> = withContext(Dispatchers.IO) {
        if (!forceRefresh) homeCache.fresh()?.let { return@withContext Result.success(it) }
        runCatching {
            // De startpagina en "Nieuw toegevoegd" (een eigen pagina) tegelijk
            // ophalen: dat scheelt een volle netwerkronde voor het eerste beeld.
            val (doc, newItems) = coroutineScope {
                val newest = async { runCatching { Family7Parser.programCards(pages.document(PLUS_NIEUW_URL)) }.getOrNull() }
                val home = async { pages.document(PLUS_HOME_URL) }
                home.await() to newest.await()
            }

            val rows = mutableListOf<CategoryRow>()
            Family7Parser.hero(doc)?.let { hero ->
                rows.add(CategoryRow(id = "uitgelicht", title = "Uitgelicht", items = listOf(hero)))
            }
            newItems?.takeIf { it.isNotEmpty() }?.let { items ->
                rows.add(CategoryRow(id = "nieuw_toegevoegd", title = "Nieuw toegevoegd", moreUrl = PLUS_NIEUW_URL, items = items))
            }
            val homeRows = Family7Parser.homeRows(doc)
            rows.addAll(homeRows.filterNot { row -> rows.any { it.id == row.id } })
            rememberKidsUrl(homeRows)

            // Terugval als de rijen niet meer te lezen zijn: toon dan tenminste alles.
            if (rows.none { it.items.isNotEmpty() && it.id != "uitgelicht" }) {
                val all = fetchAllPages(PLUS_AZ_URL)
                if (all.isNotEmpty()) rows.add(CategoryRow(id = "alle", title = "Alle programma's", items = all))
            }
            keepBestOf(homeCache, rows, SNAPSHOT_HOME) { list -> list.sumOf { it.items.size } }
        }
    }

    /**
     * Bewaart een nieuwe uitkomst, tenzij hij verdacht mager is vergeleken met
     * de laatst goede: dan blijft die staan en krijgt het scherm die terug.
     */
    private fun <T> keepBestOf(
        memory: TimedCache<List<T>>,
        fresh: List<T>,
        snapshotName: String,
        size: (List<T>) -> Int = { it.size }
    ): List<T> {
        val previous = memory.snapshot()
        if (!Plausibility.acceptable(previous?.let(size), size(fresh)) && previous != null) {
            // Niet als vers markeren: bij de volgende gelegenheid opnieuw proberen.
            return previous
        }
        memory.put(fresh)
        if (size(fresh) > 0) {
            @Suppress("UNCHECKED_CAST")
            when (fresh.firstOrNull()) {
                is CategoryRow -> snapshots.writeRows(snapshotName, fresh as List<CategoryRow>)
                is ProgramItem -> snapshots.writeItems(snapshotName, fresh as List<ProgramItem>)
            }
        }
        return fresh
    }

    private fun rememberKidsUrl(rows: List<CategoryRow>) {
        rows.firstOrNull { row ->
            row.moreUrl.contains("/plus/special/") &&
                Family7Parser.KIDS_HINTS.any { (row.title + row.moreUrl).lowercase().contains(it) }
        }?.let { cache.edit().putString(KEY_KIDS_URL, it.moreUrl).apply() }
    }

    // ------------------------------------------------------------ specials

    /** Alle specialpagina's die Family7+ op dit moment aanbiedt. */
    suspend fun getSpecials(): Result<List<CategoryRow>> = withContext(Dispatchers.IO) {
        runCatching {
            pages.document(PLUS_HOME_URL).select("a[href*='/plus/special/']")
                .mapNotNull { a ->
                    val href = a.attr("href").trim().ifEmpty { return@mapNotNull null }
                    val label = a.text().trim()
                        .takeIf { it.isNotEmpty() && !it.equals("meer", ignoreCase = true) }
                        ?: Family7Parser.decodedSlug(href)
                    CategoryRow(id = Family7Parser.slug(href), title = label, moreUrl = Family7Parser.absolute(href))
                }
                .distinctBy { it.moreUrl }
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
                    Exception("Geen kidssectie gevonden. Controleer of u bent ingelogd met een Family7 Plus account.")
                )
            val items = keepBestOf(kidsCache, fetchAllPages(url), SNAPSHOT_KIDS)
            if (items.isEmpty()) Result.failure(Exception("Er zijn nu geen kinderprogramma's beschikbaar."))
            else Result.success(items)
        } catch (e: UnauthorizedException) {
            Result.failure(Exception("De kinderprogramma's zijn alleen zichtbaar als u bent ingelogd."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun resolveKidsUrl(): String? {
        runCatching {
            pages.document(PLUS_HOME_URL).select("a[href*='/plus/special/']")
                .map { it.attr("href") }
                .firstOrNull { href ->
                    val haystack = Family7Parser.decodedSlug(href).lowercase()
                    Family7Parser.KIDS_HINTS.any { haystack.contains(it) }
                }
                ?.let(Family7Parser::absolute)
        }.getOrNull()?.let { found ->
            cache.edit().putString(KEY_KIDS_URL, found).apply()
            return found
        }
        return cache.getString(KEY_KIDS_URL, null)
    }

    // ----------------------------------------------------------------- a-z

    suspend fun getAllAZPrograms(forceRefresh: Boolean = false): Result<List<ProgramItem>> = withContext(Dispatchers.IO) {
        if (!forceRefresh) azCache.fresh()?.let { return@withContext Result.success(it) }
        runCatching { keepBestOf(azCache, fetchAllPages(PLUS_AZ_URL), SNAPSHOT_AZ) }
    }

    /** Haalt alle programma's van een willekeurige overzichtspagina. */
    suspend fun getProgramsFrom(url: String): Result<List<ProgramItem>> = withContext(Dispatchers.IO) {
        runCatching { fetchAllPages(url) }
    }

    suspend fun searchPrograms(query: String): Result<List<ProgramItem>> = withContext(Dispatchers.IO) {
        runCatching {
            // Zoeken filtert de A-Z-lijst; die hoeft niet bij elke letter opnieuw over het netwerk.
            val all = azCache.fresh() ?: getAllAZPrograms().getOrThrow()
            if (query.isBlank()) return@runCatching all
            val q = query.trim().lowercase()
            all.filter { it.title.lowercase().contains(q) || it.slug.contains(q.replace(' ', '-')) }
        }
    }

    // ------------------------------------------------------------ ophalen

    /**
     * Een overzichtspagina met al zijn vervolgpagina's. Noemt de pager de
     * laatste pagina, dan worden de vervolgpagina's tegelijk opgehaald (met een
     * bovengrens); anders één voor één, tot er niets nieuws meer bij komt.
     */
    private suspend fun fetchAllPages(startUrl: String): List<ProgramItem> {
        val first = pages.document(startUrl)
        val collected = LinkedHashMap<String, ProgramItem>()
        Family7Parser.programCards(first).forEach { collected.putIfAbsent(it.slug, it) }
        if (!Family7Parser.hasNextPage(first)) return collected.values.toList()

        val last = lastPageNumber(first)
        if (last != null) {
            val gate = Semaphore(PAGE_CONCURRENCY)
            val rest = coroutineScope {
                (1..minOf(last, MAX_PAGES - 1)).map { page ->
                    async { gate.withPermit { runCatching { pages.document(pageUrl(startUrl, page)) }.getOrNull() } }
                }.awaitAll()
            }
            rest.filterNotNull().forEach { doc -> Family7Parser.programCards(doc).forEach { collected.putIfAbsent(it.slug, it) } }
            return collected.values.toList()
        }

        var page = 1
        var doc: Document = first
        while (page < MAX_PAGES && Family7Parser.hasNextPage(doc)) {
            doc = runCatching { pages.document(pageUrl(startUrl, page)) }.getOrNull() ?: break
            val before = collected.size
            Family7Parser.programCards(doc).forEach { collected.putIfAbsent(it.slug, it) }
            if (collected.size == before) break
            page++
        }
        return collected.values.toList()
    }

    /** Het nummer van de laatste pagina volgens de pager (Drupal telt vanaf 0). */
    private fun lastPageNumber(doc: Document): Int? =
        doc.select(".pager__item--last a[href], li.pager-last a[href]").attr("href")
            .let { Regex("""[?&]page=(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun pageUrl(url: String, page: Int): String =
        if (url.contains("?")) "$url&page=$page" else "$url?page=$page"
}
