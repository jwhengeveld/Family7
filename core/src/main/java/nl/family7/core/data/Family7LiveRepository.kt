package nl.family7.core.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.regex.Pattern

class Family7LiveRepository(appContext: Context) {
    private val context: Context = appContext.applicationContext

    private val client = Family7Http.getClient(context)
    private val pages = Family7Http.getPageFetcher(context)

    /**
     * Onthoudt de laatst werkende speler- en stream-URL. Streampartner wisselt
     * regelmatig van host, dus een vaste URL in de code veroudert; een geleerde
     * waarde uit een eerdere sessie is een betere noodgreep.
     */
    private val cache = context.getSharedPreferences("family7_live_cache", Context.MODE_PRIVATE)

    companion object {
        private const val LIVE_PAGE_URL = "https://www.family7.nl/plus/live"
        private const val GUIDE_PAGE_URL = "https://www.family7.nl/tvgids"
        private const val KEY_LAST_PLAYER_URL = "last_player_url"
        private const val KEY_LAST_STREAM_URL = "last_stream_url"
        /** De gids verandert zelden; vaker ophalen dan dit is zinloos. */
        private const val GUIDE_TTL_MS = 15 * 60_000L
        /** Kort genoeg dat het stream-adres nog geldig is en "nu op tv" klopt. */
        private const val LIVE_INFO_TTL_MS = 2 * 60_000L
        private const val GUIDE_DAYS_KEPT = 12

        private val amsterdam: java.util.TimeZone = java.util.TimeZone.getTimeZone("Europe/Amsterdam")

        /** De datum ("yyyy-MM-dd") in Nederland, [offset] dagen vanaf vandaag. */
        fun guideDate(offset: Int): String {
            val day = java.util.Calendar.getInstance(amsterdam).apply { add(java.util.Calendar.DAY_OF_YEAR, offset) }.time
            return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).apply { timeZone = amsterdam }.format(day)
        }
    }

    // ------------------------------------------------------------- tv-gids

    /** De gids per dag ("yyyy-MM-dd") in het geheugen, met het moment van ophalen. */
    private val guideMemory = HashMap<String, Pair<Long, List<GuideItem>>>()
    /** De gids van recente dagen op schijf, zodat hij ook zonder netwerk te zien is. */
    private val guideDisk = context.getSharedPreferences("family7_guide", Context.MODE_PRIVATE)

    /**
     * De programmagids van één dag, zoals de site hem toont. De site is de
     * bron: binnen [GUIDE_TTL_MS] uit het geheugen, anders opnieuw van de site;
     * lukt dat niet, dan de laatst bekende versie van schijf.
     */
    suspend fun getGuide(date: String, force: Boolean = false): Result<List<GuideItem>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force) synchronized(guideMemory) { guideMemory[date] }?.let { (at, items) ->
            if (now - at < GUIDE_TTL_MS) return@withContext Result.success(items)
        }
        val previous = synchronized(guideMemory) { guideMemory[date]?.second } ?: readGuide(date)
        runCatching { fetchGuide(date) }.fold(
            onSuccess = { fresh ->
                // Eén keer een verdacht lege of halve dag houdt de vorige versie vast.
                val items = if (previous != null && !Plausibility.accept("guide:$date", previous.size, fresh.size)) previous else fresh
                synchronized(guideMemory) { guideMemory[date] = now to items }
                writeGuide(date, items)
                Result.success(items)
            },
            onFailure = { error -> previous?.let { Result.success(it) } ?: Result.failure(error) }
        )
    }

    /**
     * Haalt de gids van gisteren tot overmorgen alvast op de achtergrond op
     * (bij het starten van de app en bij het openen van live), zodat de gids
     * meteen klaarstaat. Wat al vers is, wordt niet opnieuw opgehaald.
     */
    suspend fun prefetchGuide() {
        (-1..2).map { guideDate(it) }.forEach { date -> getGuide(date) }
    }

    /**
     * De gids van de site. Eerst het adres dat de tv-gidspagina zelf gebruikt
     * (per dag, als JSON met HTML erin); geeft dat niets bruikbaars, dan de
     * gewone tv-gidspagina, die de uitzendingen van vandaag toont.
     */
    private suspend fun fetchGuide(date: String): List<GuideItem> {
        val fromEndpoint = runCatching {
            val page = pages.page("${Family7Parser.BASE_URL}/tv-guide-get-items/${date}T00:00:00/23:59:59/not_today_search", fresh = true)
            // Normaal JSON met "renderedItems"; is het ooit gewone HTML, dan die.
            val html = runCatching { org.json.JSONObject(page.html).optString("renderedItems") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: page.html
            Family7Parser.guideItems(Jsoup.parseBodyFragment(html, Family7Parser.BASE_URL))
        }
        fromEndpoint.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (date == guideDate(0)) {
            val items = Family7Parser.guideItems(pages.document(GUIDE_PAGE_URL, fresh = true))
            if (items.isNotEmpty()) return items
        }
        throw fromEndpoint.exceptionOrNull() ?: IllegalStateException("De tv-gids is niet te lezen.")
    }

    /** Wat er van een dag al bekend is, zonder netwerk: om meteen iets te tonen. */
    fun cachedGuide(date: String): List<GuideItem>? =
        synchronized(guideMemory) { guideMemory[date]?.second } ?: readGuide(date)

    private fun readGuide(date: String): List<GuideItem>? = runCatching {
        val raw = guideDisk.getString(date, null) ?: return null
        val array = org.json.JSONArray(raw)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            GuideItem(
                start = o.optString("start"), title = o.optString("title"), episode = o.optString("episode"),
                description = o.optString("description"), imageUrl = o.optString("imageUrl"),
                programSlug = o.optString("programSlug"), videoSlug = o.optString("videoSlug")
            )
        }
    }.getOrNull()

    private fun writeGuide(date: String, items: List<GuideItem>) {
        val array = org.json.JSONArray()
        items.forEach { item ->
            array.put(
                org.json.JSONObject()
                    .put("start", item.start).put("title", item.title).put("episode", item.episode)
                    .put("description", item.description).put("imageUrl", item.imageUrl)
                    .put("programSlug", item.programSlug).put("videoSlug", item.videoSlug)
            )
        }
        val editor = guideDisk.edit().putString(date, array.toString())
        // Alleen recente dagen bewaren; datums sorteren als tekst goed.
        guideDisk.all.keys.sorted().dropLast(GUIDE_DAYS_KEPT).forEach(editor::remove)
        editor.apply()
    }

    /** De laatst opgezochte livegegevens, met het moment van ophalen. */
    @Volatile private var lastLive: Pair<Long, LiveStreamInfo>? = null
    /** Eén zoektocht tegelijk: wie tegelijk vraagt, wacht en krijgt dezelfde uitkomst. */
    private val liveLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Het programma van nu en het stream-adres. Het startscherm vraagt dit al
     * op; binnen [LIVE_INFO_TTL_MS] krijgt live het onthouden antwoord, zodat
     * de uitzending zonder wachten start. [force] zoekt opnieuw (bij een fout
     * in de speler).
     */
    suspend fun getLiveInfo(force: Boolean = false): Result<LiveStreamInfo> = liveLock.withLock {
        val hit = lastLive
        if (!force && hit != null && hit.second.streamUrl.isNotBlank() &&
            System.currentTimeMillis() - hit.first < LIVE_INFO_TTL_MS
        ) return@withLock Result.success(hit.second)
        fetchLiveInfo().onSuccess { info ->
            if (info.streamUrl.isNotBlank()) lastLive = System.currentTimeMillis() to info
        }
    }

    private suspend fun fetchLiveInfo(): Result<LiveStreamInfo> = withContext(Dispatchers.IO) {
        try {
            // Via de gedeelde ophaler: een verlopen sessie wordt herkend en
            // gemeld in plaats van dat de livepagina leeg lijkt.
            val doc = pages.document(LIVE_PAGE_URL, maxAgeMs = 15_000)
            val html = doc.outerHtml()

            // Extract TV Guide "NU OP TV" metadata
            val currentProgTitle = doc.select(".tv-guide-teaser_title, .tv-guide-teaser h2, .tv-guide-teaser h3").text().ifEmpty {
                "Family7 Live Uitzending"
            }
            val timeRange = doc.select(".tv-guide-teaser_time, .tv-guide-teaser--info-first p:nth-child(2)").text()
            val description = doc.select(".tv-guide-teaser_description, .tv-guide-teaser--info-second p").text()
            
            var imageUrl = doc.select(".tv-guide-teaser--image img").attr("src")
            if (imageUrl.startsWith("/")) {
                imageUrl = "https://www.family7.nl$imageUrl"
            }

            // De speler-URL wordt van de pagina zelf gehaald, zodat een wijziging
            // bij Family7 of Streampartner meteen wordt overgenomen.
            val playerUrl = discoverPlayerUrl(doc, html)
            if (playerUrl.isNotEmpty()) {
                cache.edit().putString(KEY_LAST_PLAYER_URL, playerUrl).apply()
            }

            val streamUrl = resolveLiveStreamUrl(playerUrl)
                .ifEmpty { resolveLiveStreamUrl(cache.getString(KEY_LAST_PLAYER_URL, "").orEmpty()) }
                .ifEmpty { StreampartnerPlayer.firstM3u8(html) }
                .ifEmpty { cache.getString(KEY_LAST_STREAM_URL, "").orEmpty() }

            if (streamUrl.isNotEmpty()) {
                cache.edit().putString(KEY_LAST_STREAM_URL, streamUrl).apply()
            }

            Result.success(
                LiveStreamInfo(
                    title = "Family7 Live TV",
                    currentProgram = currentProgTitle,
                    timeRange = timeRange,
                    imageUrl = imageUrl,
                    description = description,
                    streamUrl = streamUrl
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Zoekt de speler-URL op de livepagina. Probeert achtereenvolgens de bekende
     * Drupal-containers, elke iframe, en als laatste elke Streampartner-verwijzing
     * in de ruwe HTML, zodat een gewijzigde opmaak niet meteen alles breekt.
     */
    private fun discoverPlayerUrl(doc: org.jsoup.nodes.Document, html: String): String {
        val candidates = buildList {
            add(doc.select(".video-player--loader, .video-player--frame").attr("data-src"))
            add(doc.select("[data-src*='player']").attr("data-src"))
            add(doc.select("iframe[src*='player']").attr("src"))
            add(doc.select("iframe[src*='streampartner']").attr("src"))
            add(doc.select("iframe[src]").attr("src"))
            val raw = Pattern.compile("https?://[^\\s\"'<>]*streampartner\\.nl/[^\\s\"'<>]+")
                .matcher(html)
            if (raw.find()) add(raw.group(0) ?: "")
        }

        return candidates
            .firstOrNull { it.isNotBlank() }
            ?.let { if (it.startsWith("/")) "https://www.family7.nl$it" else it }
            .orEmpty()
    }

    private fun resolveLiveStreamUrl(playerUrl: String): String {
        if (playerUrl.isBlank()) return ""
        return try {
            client.newCall(
                Request.Builder()
                    .url(playerUrl)
                    .header("Referer", "https://www.family7.nl/")
                    .build()
            ).execute().use { response ->
                StreampartnerPlayer.decodeStreamUrls(response.body?.string().orEmpty())
                    .firstOrNull()
                    .orEmpty()
            }
        } catch (_: Exception) {
            // De speler is een noodgreep bovenop de pagina zelf; lukt het hier
            // niet, dan valt getLiveInfo terug op het onthouden adres.
            ""
        }
    }
}
