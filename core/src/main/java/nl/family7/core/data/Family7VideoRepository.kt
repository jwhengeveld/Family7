package nl.family7.core.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

class Family7VideoRepository(appContext: Context) {
    private val context: Context = appContext.applicationContext

    private val pages = Family7Http.getPageFetcher(context)
    private val snapshots = CatalogSnapshotStore(context)

    // Programmapagina's per slug, zodat terugkeren naar een eerder geopend
    // programma meteen de afleveringen toont in plaats van een laadscherm.
    private val detailCache = HashMap<String, ProgramDetail>()

    /**
     * Kort onthouden stream-adressen per aflevering, zo lang als het token in
     * het adres geldig is (zie [StreamUrlLifetime]). Daarmee kan een scherm
     * het adres al ophalen voordat iemand op afspelen drukt, en hoeft casten
     * het niet nog eens op te zoeken.
     */
    private val streamCache = HashMap<String, TimedCache<String>>()

    companion object {
        private const val BASE_URL = Family7Parser.BASE_URL
        /** Zoveel seizoenen tegelijk ophalen; series als "Bijbelse karakters" hebben er dertien. */
        private const val SEASON_CONCURRENCY = 4
    }

    /** Het laatst bekende programma: uit het geheugen, of van schijf uit een vorige sessie. */
    fun cachedDetail(slug: String): ProgramDetail? =
        synchronized(detailCache) { detailCache[slug] }
            ?: snapshots.readDetail(detailKey(slug))?.also { synchronized(detailCache) { detailCache.putIfAbsent(slug, it) } }

    /**
     * De programmapagina met alle seizoenen. De pagina zelf bevat alleen het
     * gekozen seizoen; de andere haalt de site (en dus ook de app) op via
     * /get-videos-by-season/{node}/{seizoen}, hier tegelijk en begrensd.
     */
    suspend fun getProgramDetail(slug: String): Result<ProgramDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val url = if (slug.startsWith("http")) slug else "$BASE_URL/plus/programmas/$slug"
            val doc = pages.document(url)
            val base = Family7Parser.programDetailBase(doc, slug)
            val pageEpisodes = Family7Parser.episodes(doc, base.posterUrl)
            val options = Family7Parser.seasonOptions(doc)

            val seasons = when {
                options.size <= 1 || base.nodeId.isBlank() -> {
                    val only = options.firstOrNull()
                    if (pageEpisodes.isEmpty()) emptyList()
                    else listOf(SeasonInfo(only?.number ?: "1", only?.title ?: "Afleveringen", pageEpisodes))
                }
                else -> {
                    val shown = options.firstOrNull { it.selected } ?: options.first()
                    val gate = Semaphore(SEASON_CONCURRENCY)
                    coroutineScope {
                        options.map { option ->
                            async {
                                val episodes = if (option.number == shown.number && pageEpisodes.isNotEmpty()) {
                                    pageEpisodes
                                } else {
                                    // Een mislukt seizoen kost alleen dat seizoen, niet de hele pagina.
                                    gate.withPermit { runCatching { seasonEpisodes(base.nodeId, option.number, base.posterUrl) }.getOrNull() }
                                        ?: cachedSeason(slug, option.number)
                                        ?: emptyList()
                                }
                                SeasonInfo(option.number, option.title, episodes)
                            }
                        }.awaitAll()
                    }.filter { it.episodes.isNotEmpty() }
                }
            }

            val detail = base.copy(seasons = seasons)
            // Een verbouwde pagina zonder afleveringen vervangt geen goede versie.
            val previous = cachedDetail(slug)
            val previousCount = previous?.seasons?.sumOf { it.episodes.size }
            val freshCount = seasons.sumOf { it.episodes.size }
            if (previous != null && !Plausibility.acceptable(previousCount, freshCount)) {
                return@runCatching previous
            }
            synchronized(detailCache) { detailCache[slug] = detail }
            if (freshCount > 0) snapshots.writeDetail(detailKey(slug), detail)
            detail
        }
    }

    private fun cachedSeason(slug: String, number: String): List<EpisodeItem>? =
        cachedDetail(slug)?.seasons?.firstOrNull { it.seasonNumber == number }?.episodes

    /** Eén seizoen via het eindpunt dat de site zelf gebruikt; het antwoord is JSON met kant-en-klare HTML. */
    private suspend fun seasonEpisodes(nodeId: String, season: String, fallbackThumb: String): List<EpisodeItem> {
        val page = pages.page("$BASE_URL/get-videos-by-season/$nodeId/$season")
        val html = JSONObject(page.html).optString("renderedItems")
        return Family7Parser.episodes(Jsoup.parseBodyFragment(html, BASE_URL), fallbackThumb)
    }

    private fun detailKey(slug: String) = "detail_" + slug.hashCode().toUInt().toString(16)

    // ------------------------------------------------------------- streams

    /**
     * Zoekt het stream-adres van een aflevering op de achtergrond alvast op.
     * Een mislukking is hier geen fout: afspelen probeert het gewoon opnieuw.
     */
    suspend fun prefetchStreamUrl(videoSlugOrUrl: String) {
        resolveEpisodeStreamUrl(videoSlugOrUrl)
    }

    /**
     * Het stream-adres van een aflevering. [forceFresh] slaat het onthouden
     * adres over, voor als de speler meldt dat het oude adres niet meer werkt.
     */
    suspend fun resolveEpisodeStreamUrl(
        videoSlugOrUrl: String,
        forceFresh: Boolean = false
    ): Result<String> {
        if (!forceFresh) synchronized(streamCache) { streamCache[videoSlugOrUrl]?.fresh() }?.let { return Result.success(it) }
        return fetchEpisodeStreamUrl(videoSlugOrUrl).onSuccess { url ->
            val lifetime = StreamUrlLifetime.validForMs(url)
            synchronized(streamCache) {
                if (lifetime > 0) streamCache[videoSlugOrUrl] = TimedCache<String>(lifetime).apply { put(url) }
                else streamCache.remove(videoSlugOrUrl)
            }
        }
    }

    private suspend fun fetchEpisodeStreamUrl(videoSlugOrUrl: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = if (videoSlugOrUrl.startsWith("http")) videoSlugOrUrl else "$BASE_URL/video/$videoSlugOrUrl"
            // Een videopagina is per bezoek anders (het token); niet uit de korte cache.
            val page = pages.page(url, maxAgeMs = 0)
            val doc = Jsoup.parse(page.html, page.finalUrl)

            val player = Family7Parser.playerUrl(doc, page.html)
            if (player.isNotEmpty()) {
                val playerPage = pages.page(player, referer = BASE_URL, maxAgeMs = 0)
                StreampartnerPlayer.streamUrlFromPlayerHtml(playerPage.html).takeIf { it.isNotEmpty() }
                    ?.let { return@runCatching it }
            }

            // Terugval: het adres staat soms gewoon op de pagina zelf.
            StreampartnerPlayer.firstM3u8(page.html).takeIf { it.isNotEmpty() }?.let { return@runCatching it }

            pages.checkSession(doc)
            if (PageFetcher.isAnonymous(doc)) {
                throw Exception("Deze aflevering is alleen te bekijken als u bent ingelogd met Family7 Plus.")
            }
            throw Exception("Kon geen afspeelbare videobron vinden voor deze aflevering.")
        }
    }
}
