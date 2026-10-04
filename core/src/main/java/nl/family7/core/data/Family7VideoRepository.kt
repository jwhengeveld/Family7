package nl.family7.core.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.regex.Pattern

class Family7VideoRepository(appContext: Context) {
    private val context: Context = appContext.applicationContext

    private val client = Family7Http.getClient(context)

    // Programmapagina's per slug, zodat terugkeren naar een eerder geopend
    // programma meteen de afleveringen toont in plaats van een laadscherm.
    private val detailCache = HashMap<String, ProgramDetail>()

    fun cachedDetail(slug: String): ProgramDetail? = synchronized(detailCache) { detailCache[slug] }

    /**
     * Kort onthouden stream-adressen per aflevering. Daarmee kan een scherm het
     * adres al ophalen voordat iemand op afspelen drukt (de knop start dan
     * meteen), en hoeft casten het niet nog eens op te zoeken. Kort, omdat het
     * token in het adres van Streampartner na een tijd verloopt.
     */
    private val streamCache = HashMap<String, TimedCache<String>>()

    companion object {
        private const val BASE_URL = "https://www.family7.nl"
        private const val STREAM_URL_TTL_MS = 2 * 60_000L
    }

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
        val cache = synchronized(streamCache) {
            streamCache.getOrPut(videoSlugOrUrl) { TimedCache(STREAM_URL_TTL_MS) }
        }
        if (!forceFresh) cache.fresh()?.let { return Result.success(it) }
        return fetchEpisodeStreamUrl(videoSlugOrUrl).onSuccess { if (it.isNotEmpty()) cache.put(it) }
    }

    suspend fun getProgramDetail(slug: String): Result<ProgramDetail> = withContext(Dispatchers.IO) {
        try {
            val url = if (slug.startsWith("http")) slug else "$BASE_URL/plus/programmas/$slug"
            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            val html = resp.body?.string() ?: ""
            resp.close()

            val doc = Jsoup.parse(html, url)

            // De programmapagina heeft geen kop met de naam erin; die staat in de
            // paginatitel en anders af te leiden uit het adres.
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: doc.title().substringBefore("|").trim().takeIf { it.isNotEmpty() }
                ?: slug.replace('-', ' ').replaceFirstChar { it.uppercase() }

            var posterUrl = doc.selectFirst(
                ".video-page-top-content img, .series-page-image img, .main-image img"
            )?.attr("src").orEmpty()
            if (posterUrl.startsWith("/")) posterUrl = "$BASE_URL$posterUrl"

            val description = doc.selectFirst(".introduction, .series-page-description, .field--name-body")
                ?.text()?.trim().orEmpty()
            val category = doc.selectFirst(".series-info")?.text()?.trim().orEmpty()

            // De "Mijn lijst"-knop van de site draagt het node-id en, via de
            // klasse "added", of het programma al in de lijst van dit account staat.
            val myListButton = doc.selectFirst(".process-to-my-series-list, [data-node-id]")
            val nodeId = myListButton?.attr("data-node-id").orEmpty()
            val isInMyList = myListButton?.hasClass("added") == true

            val episodes = doc.select(".view-block_element-wrapper, .view-block_element")
                .mapNotNull { card -> parseEpisode(card, posterUrl) }
                .distinctBy { it.videoSlug }

            // De seizoenkiezer van de site geeft aan welk seizoen op deze pagina staat.
            val seasonSelect = doc.selectFirst(".more-videos_season-select")
            val seasonNumber = seasonSelect?.selectFirst("option[selected]")?.attr("value")
                ?.takeIf { it.isNotEmpty() }
                ?: seasonSelect?.selectFirst("option")?.attr("value")
                ?: "1"
            val seasonLabel = seasonSelect?.selectFirst("option[selected]")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: seasonSelect?.selectFirst("option")?.text()?.trim()
                ?: "Afleveringen"

            val seasons = if (episodes.isNotEmpty()) {
                listOf(SeasonInfo(seasonNumber = seasonNumber, title = seasonLabel, episodes = episodes))
            } else {
                emptyList()
            }

            val detail = ProgramDetail(
                slug = slug,
                title = title,
                posterUrl = posterUrl,
                description = description,
                category = category,
                seasons = seasons,
                nodeId = nodeId,
                isInMyList = isInMyList
            )
            synchronized(detailCache) { detailCache[slug] = detail }
            Result.success(detail)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Een afleveringskaart zoals de site die opbouwt: het nummer staat in
     * `.video-number`, de titel links en de speelduur rechts in `.video-title`.
     * De tweede afbeelding in de kaart is het kijkwijzer-icoon, niet de
     * aflevering, dus daar mag de titel niet vandaan komen.
     */
    private fun parseEpisode(card: org.jsoup.nodes.Element, fallbackThumb: String): EpisodeItem? {
        val a = card.selectFirst("a[href*='/video/']") ?: return null
        val href = a.attr("href")
        val epSlug = href.substringBefore("?").trimEnd('/').substringAfterLast('/')
        if (epSlug.isEmpty()) return null

        val titleBlock = card.selectFirst(".video-title")
        val epTitle = titleBlock?.selectFirst(".float-left")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: titleBlock?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: card.selectFirst(".view-block_element-title")?.text()?.trim().orEmpty()
                .ifEmpty { "Aflevering" }

        val duration = titleBlock?.selectFirst(".float-right")?.text()?.trim().orEmpty()
        val number = card.selectFirst(".video-number")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            // Het adres van een aflevering heeft de vorm {seizoen}-{nummer}-{slug}.
            ?: epSlug.split("-").getOrNull(1)?.takeIf { it.all(Char::isDigit) }
            ?: ""

        var thumb = card.selectFirst(".view-block_element-thumbnail > img")?.attr("src").orEmpty()
        if (thumb.startsWith("/")) thumb = "$BASE_URL$thumb"

        return EpisodeItem(
            id = epSlug,
            episodeNumber = number,
            title = epTitle,
            description = card.selectFirst(".video-description")?.text()?.trim().orEmpty(),
            duration = duration,
            thumbnailUrl = thumb.ifEmpty { fallbackThumb },
            videoSlug = epSlug,
            videoUrl = if (href.startsWith("http")) href else "$BASE_URL$href"
        )
    }

    private suspend fun fetchEpisodeStreamUrl(videoSlugOrUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = if (videoSlugOrUrl.startsWith("http")) videoSlugOrUrl else "$BASE_URL/video/$videoSlugOrUrl"
            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            val html = resp.body?.string() ?: ""
            resp.close()

            val doc = Jsoup.parse(html)
            var playerUrl = doc.select(".video-player--loader, .video-player--frame").attr("data-src")
            if (playerUrl.isEmpty()) {
                // Check iframes or video tags
                playerUrl = doc.select("iframe[src*='player.php']").attr("src")
            }

            if (playerUrl.isNotEmpty()) {
                if (playerUrl.startsWith("/")) playerUrl = "$BASE_URL$playerUrl"

                val playerReq = Request.Builder()
                    .url(playerUrl)
                    .header("Referer", BASE_URL)
                    .build()

                val playerResp = client.newCall(playerReq).execute()
                val playerHtml = playerResp.body?.string() ?: ""
                playerResp.close()

                // Extract stream m3u8 or mp4 from player page
                val streamUrl = extractStreamFromPlayerHtml(playerHtml)
                if (streamUrl.isNotEmpty()) {
                    return@withContext Result.success(streamUrl)
                }
            }

            // Terugval: het adres staat soms gewoon op de pagina zelf.
            val direct = StreampartnerPlayer.firstM3u8(html)
            if (direct.isNotEmpty()) {
                return@withContext Result.success(direct)
            }

            Result.failure(Exception("Kon geen afspeelbare videobron vinden voor deze aflevering."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Het stream-adres uit de spelerpagina.
     *
     * Eerst het adres dat er letterlijk in staat, en anders dezelfde uitpakker
     * die de live-stream gebruikt: Streampartner zet de on-demand speler soms
     * ook ingepakt op de pagina, en dan hoort de aflevering het gewoon te doen
     * in plaats van met "geen videobron" te stoppen.
     */
    private fun extractStreamFromPlayerHtml(html: String): String {
        // De speler zet het adres bij voorkeur in "src:".
        val labelled = Pattern.compile("src:\\s*[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']")
            .matcher(html)
        if (labelled.find()) return labelled.group(1).orEmpty()

        StreampartnerPlayer.firstM3u8(html).takeIf { it.isNotEmpty() }?.let { return it }

        StreampartnerPlayer.decodeStreamUrls(html).firstOrNull()?.let { return it }

        // Losse mp4-bestanden komen voor bij oudere afleveringen.
        val mp4 = Pattern.compile("https?://[^\\s\"'<>]+\\.mp4[^\\s\"'<>]*").matcher(html)
        return if (mp4.find()) mp4.group(0).orEmpty() else ""
    }
}
