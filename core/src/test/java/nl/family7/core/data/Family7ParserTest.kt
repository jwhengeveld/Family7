package nl.family7.core.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * De parser op echte pagina's van family7.nl (vastgelegd in
 * src/test/resources/fixtures), en op bewust verbouwde versies daarvan. Die
 * laatste bewijzen dat de app blijft werken als Family7 class-namen hernoemt,
 * afbeeldingen lazy gaat laden of de kaarten anders opbouwt.
 */
class Family7ParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    private fun doc(html: String, url: String = "https://www.family7.nl/plus"): Document = Jsoup.parse(html, url)

    // ---------------------------------------------------------------- A-Z

    @Test
    fun `de A-Z-pagina levert alle programma's met titel en beeld`() {
        val programs = Family7Parser.programCards(doc(fixture("az.html")))

        assertEquals(196, programs.size)
        assertTrue(programs.all { it.title.isNotBlank() })
        assertTrue(programs.all { it.thumbnailUrl.startsWith("https://www.family7.nl/") })
        assertTrue(programs.any { it.slug == "bijbelse-karakters" && it.title == "Bijbelse karakters" })
        assertEquals("'t Weten Waard", programs.first { it.slug == "t-weten-waard" }.title)
    }

    @Test
    fun `hernoemde kaartklassen - de programma's worden toch gevonden`() {
        val renamed = fixture("az.html")
            .replace("view-block_element", "tegel")
            .replace("views-row", "rij")
            .replace("titleProgramme", "naam")
        val programs = Family7Parser.programCards(doc(renamed))

        assertEquals(196, programs.size)
        assertTrue("titels uit de tekst naast de link", programs.count { it.title.isNotBlank() } == 196)
    }

    @Test
    fun `lazy loading - de afbeeldingen komen uit data-src`() {
        val lazy = fixture("az.html").replace(Regex("""<img\s+src="""), """<img src="data:image/gif;base64,R0lGOD" data-src=""")
        val programs = Family7Parser.programCards(doc(lazy))

        assertTrue(programs.all { it.thumbnailUrl.startsWith("https://www.family7.nl/sites/") })
    }

    @Test
    fun `srcset, picture en achtergrondafbeelding worden ook gelezen`() {
        val html = """
            <div><a href="/plus/programmas/a"><img srcset="/a-320.jpg 320w, /a-1280.jpg 1280w, /a-640.jpg 640w"></a></div>
            <div><a href="/plus/programmas/b"><picture><source srcset="/b.webp 1x"><img></picture></a></div>
            <div><a href="/plus/programmas/c"><div style="background-image: url('/c.jpg')"></div></a></div>
        """.trimIndent()
        val images = Family7Parser.programCards(doc(html)).associate { it.slug to it.thumbnailUrl }

        assertEquals("https://www.family7.nl/a-1280.jpg", images["a"])
        assertEquals("https://www.family7.nl/b.webp", images["b"])
        assertEquals("https://www.family7.nl/c.jpg", images["c"])
    }

    @Test
    fun `een label als Nieuwe afleveringen is nooit de titel`() {
        val html = """
            <div class="slider-default_element"><a href="/plus/programmas/fearless"><img src="/f.jpg" title="Fearless"></a>
              <h3 class="ribbon-ribbon">Nieuwe afleveringen</h3></div>
            <div class="slider-default_element"><a href="/plus/programmas/x"><img src="/x.jpg"></a>
              <div class="ribbon"><h3>Nieuw</h3></div><h4>Echte titel</h4></div>
        """.trimIndent()
        val programs = Family7Parser.programCards(doc(html)).associate { it.slug to it }

        assertEquals("Fearless", programs["fearless"]!!.title)
        assertEquals("Nieuwe afleveringen", programs["fearless"]!!.badge)
        assertEquals("Echte titel", programs["x"]!!.title)
    }

    // -------------------------------------------------------- startpagina

    @Test
    fun `startpagina - kop, rijen met meer-link, en Mijn lijst weggelaten`() {
        val home = doc(fixture("startpagina_nagebouwd.html"))

        val hero = Family7Parser.hero(home)!!
        assertEquals("onvolmaakt-vertrouwen", hero.slug)
        assertEquals("https://www.family7.nl/sites/default/files/2026-09/OnvolmaaktVertrouwen_header.jpg", hero.thumbnailUrl)

        val rows = Family7Parser.homeRows(home)
        assertEquals(listOf("aanbevolen", "kinderprogramma_s"), rows.map { it.id })
        assertEquals(listOf("De Schatkamer", "Eindtijd in Zicht", "Bijbelse karakters"), rows[0].items.map { it.title })
        assertEquals("https://www.family7.nl/plus/special/aanbevolen", rows[0].moreUrl)
        assertEquals("Nieuwe afleveringen", rows[0].items[0].badge)
    }

    @Test
    fun `startpagina zonder de bekende secties - rijen via koppen en links`() {
        val rebuilt = fixture("startpagina_nagebouwd.html")
            .replace("on-demand_home-section", "rij")
            .replace("slider-default_element", "kaart")
            .replace("block-view-header_element-title", "kop")
        val rows = Family7Parser.homeRows(doc(rebuilt))

        assertTrue(rows.any { it.title == "Aanbevolen" && it.items.size == 3 })
        assertTrue(rows.none { it.title.equals("Mijn lijst", ignoreCase = true) })
    }

    // ---------------------------------------------------- programmapagina

    @Test
    fun `programmapagina - titel, node-id, beeld en afleveringen`() {
        val page = doc(fixture("programma_1_seizoen.html"), "https://www.family7.nl/plus/programmas/onvolmaakt-vertrouwen")
        val detail = Family7Parser.programDetailBase(page, "onvolmaakt-vertrouwen")
        val episodes = Family7Parser.episodes(page, detail.posterUrl)

        assertEquals("Onvolmaakt vertrouwen", detail.title)
        assertEquals("201012", detail.nodeId)
        assertTrue(detail.posterUrl.startsWith("https://www.family7.nl/"))
        assertTrue(episodes.size >= 4)
        assertEquals("1", episodes[0].episodeNumber)
        assertEquals("Doe niets", episodes[0].title)
        assertEquals("1-1-onvolmaakt-vertrouwen", episodes[0].videoSlug)
    }

    @Test
    fun `node-id komt uit drupalSettings als de knop verdwijnt`() {
        val withoutButton = fixture("programma_1_seizoen.html").replace("data-node-id", "data-weg")
        assertEquals("201012", Family7Parser.nodeId(doc(withoutButton)))
    }

    @Test
    fun `seizoenen uit de keuzelijst, in de volgorde van de site`() {
        val options = Family7Parser.seasonOptions(doc(fixture("programma_13_seizoenen.html")))

        assertEquals(13, options.size)
        assertEquals("13", options.first().number)
        assertEquals("Seizoen 13", options.first().title)
        assertEquals("84470", Family7Parser.nodeId(doc(fixture("programma_13_seizoenen.html"))))
    }

    @Test
    fun `een seizoen uit het seizoen-eindpunt`() {
        val json = org.json.JSONObject(fixture("seizoen_2.json"))
        val episodes = Family7Parser.episodes(Jsoup.parseBodyFragment(json.getString("renderedItems"), "https://www.family7.nl"), "")

        assertEquals(13, episodes.size)
        assertEquals("2-1-bijbelse-karakters", episodes[0].videoSlug)
        assertTrue(episodes.all { it.thumbnailUrl.isNotEmpty() })
    }

    @Test
    fun `afleveringen zonder de bekende kaarten - via de videolinks`() {
        val rebuilt = fixture("programma_1_seizoen.html")
            .replace("view-block_element", "tegel")
            .replace("video-title", "kop")
            .replace("video-number", "nr")
        val episodes = Family7Parser.episodes(doc(rebuilt), "")

        assertTrue(episodes.size >= 4)
        assertEquals("1", episodes.first { it.videoSlug == "1-1-onvolmaakt-vertrouwen" }.episodeNumber)
    }

    // ------------------------------------------------------------- spelers

    @Test
    fun `speleradres - bekende container, iframe, of ruwe tekst`() {
        assertEquals("https://family7.tv/player.php?itemtoken=x",
            Family7Parser.playerUrl(doc("""<div class="video-player--loader" data-src="https://family7.tv/player.php?itemtoken=x"></div>"""), ""))
        assertEquals("https://family7.tv/player.php?a=1",
            Family7Parser.playerUrl(doc("""<iframe src="https://family7.tv/player.php?a=1"></iframe>"""), ""))
        val raw = """<script>var p = "https://family7.tv/player.php?itemtoken=y";</script>"""
        assertEquals("https://family7.tv/player.php?itemtoken=y", Family7Parser.playerUrl(doc(raw), raw))
    }

    // -------------------------------------------------------------- sessie

    @Test
    fun `inlogpagina en anonieme pagina worden herkend`() {
        assertTrue(Family7Parser.isLoginPage(doc(fixture("inlogpagina.html"))))
        assertTrue(Family7Parser.isLoginPage(doc("<p>x</p>"), "/user/login"))
        assertFalse(Family7Parser.isLoginPage(doc(fixture("az.html"))))

        assertTrue(PageFetcher.isAnonymous(doc(fixture("video_publiek.html"))))
        assertFalse(PageFetcher.isAnonymous(doc(fixture("startpagina_nagebouwd.html"))))
    }

    @Test
    fun `alleen pagina's die inlog vragen tellen voor een verlopen sessie`() {
        assertTrue(PageFetcher.requiresLogin("https://www.family7.nl/plus"))
        assertTrue(PageFetcher.requiresLogin("https://www.family7.nl/plus/mijnlijst"))
        assertTrue(PageFetcher.requiresLogin("https://www.family7.nl/plus/live"))
        assertFalse(PageFetcher.requiresLogin("https://www.family7.nl/plus/a-z?title=All"))
        assertFalse(PageFetcher.requiresLogin("https://www.family7.nl/plus/programmas/bijbelse-karakters"))
        assertFalse(PageFetcher.requiresLogin("https://www.family7.nl/video/1-1-x"))
    }

    @Test
    fun seasonCountLabelCountsInsteadOfTrustingAYear() {
        assertEquals("1 seizoen", Family7Parser.seasonCountLabel("2026 seizoenen", 1))
        assertEquals("3 seizoenen", Family7Parser.seasonCountLabel("2026 seizoenen", 3))
        assertEquals("2026 seizoenen", Family7Parser.seasonCountLabel("2026 seizoenen", 0))
        assertEquals("Documentaire", Family7Parser.seasonCountLabel("Documentaire", 3))
    }

    @Test
    fun guideItemsReadsADayFromTheSite() {
        val html = org.json.JSONObject(fixture("tvgids_dag.json")).getString("renderedItems")
        val items = Family7Parser.guideItems(Jsoup.parseBodyFragment(html, Family7Parser.BASE_URL))

        assertEquals(42, items.size)
        assertEquals("00:30", items.first().start)
        assertEquals("EuroSpirit", items.first().title)
        // In volgorde van de dag, en het Windows-beletselteken is een echt "…".
        assertEquals(items.map { it.startMinutes }.sorted(), items.map { it.startMinutes })
        assertTrue(items.any { it.title == "Uitzien… en bouwen!" })
        // Ook in beschrijvingen geen stuurtekens meer (Windows-aanhalingstekens).
        assertTrue(items.none { item -> (item.title + item.description + item.episode).any { it.code in 0x80..0x9F } })

        val joyce = items.first { it.title == "Joyce Meyer" }
        assertEquals("01:00", joyce.start)
        assertEquals("Aflevering 194 - Wat doe je als het leven pijn doet?", joyce.episode)
        assertEquals("joyce-meyer", joyce.programSlug)
        assertEquals("2026-194-joyce-meyer", joyce.videoSlug)
        assertTrue(joyce.imageUrl.startsWith("https://www.family7.nl/sites/default/files/"))
        // Het standaardlogo telt niet als programmabeeld.
        assertTrue(items.none { it.imageUrl.contains("fam7logo") })
    }

    /** De gids met neutrale klassenamen: zo zou een verbouwing van de site eruitzien. */
    private fun rebuiltGuide(html: String): String = html
        .replace("tv-guide-item-time", "s-t")
        .replace("tv-guide-item-title", "s-h")
        .replace("tv-guide-item-data", "s-d")
        .replace("tv-guide-item-description", "s-x")
        .replace("tv-guide-item-image", "s-i")
        .replace("tv-guide-item", "s-row")

    @Test
    fun guideItemsSurviveRenamedClasses() {
        val html = rebuiltGuide(org.json.JSONObject(fixture("tvgids_dag.json")).getString("renderedItems"))
        assertTrue("de oude klassenamen zijn echt weg", !html.contains("tv-guide-item"))
        val items = Family7Parser.guideItems(Jsoup.parseBodyFragment(html, Family7Parser.BASE_URL))

        assertEquals(42, items.size)
        assertEquals("00:30", items.first().start)
        assertEquals("EuroSpirit", items.first().title)
        val joyce = items.first { it.title == "Joyce Meyer" }
        assertEquals("01:00", joyce.start)
        assertEquals("joyce-meyer", joyce.programSlug)
        assertTrue(joyce.imageUrl.startsWith("https://www.family7.nl/sites/default/files/"))
        // Kijkwijzer-icoontjes zijn geen programmabeeld.
        assertTrue(items.none { it.imageUrl.contains("Kijkwijzer", ignoreCase = true) })
    }

    @Test
    fun guideItemsAlsoReadTheTvGuidePage() {
        // De uitwijkroute als het gids-adres niet werkt: de gewone tv-gidspagina.
        val items = Family7Parser.guideItems(Jsoup.parse(fixture("tvgids_pagina.html"), "https://www.family7.nl/tvgids"))
        assertTrue(items.size >= 3)
        assertTrue(items.all { it.start.matches(Regex("""\d{2}:\d{2}""")) && it.title.isNotBlank() })
    }
}
