package com.xiappdesign.family7.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobustnessTest {

    // ------------------------------------------------------ plausibiliteit

    @Test
    fun `een lege uitkomst vervangt geen goede catalogus`() {
        assertFalse(Plausibility.acceptable(previousCount = 196, newCount = 0))
    }

    @Test
    fun `een veel kleinere uitkomst is verdacht`() {
        assertFalse(Plausibility.acceptable(previousCount = 196, newCount = 20))
        assertTrue(Plausibility.acceptable(previousCount = 196, newCount = 150))
    }

    @Test
    fun `zonder vorige of bij kleine lijsten mag alles`() {
        assertTrue(Plausibility.acceptable(previousCount = null, newCount = 0))
        assertTrue(Plausibility.acceptable(previousCount = 4, newCount = 1))
        assertTrue(Plausibility.acceptable(previousCount = 0, newCount = 0))
    }

    @Test
    fun `de site is de bron van waarheid - een bevestigde verandering wint`() {
        val key = "test-" + System.nanoTime()
        // Eerste keer verdacht mager: de vorige blijft staan (eenmalige hapering).
        assertFalse(Plausibility.accept(key, previousCount = 196, newCount = 12))
        // De site geeft opnieuw hetzelfde: dat is de werkelijkheid.
        assertTrue(Plausibility.accept(key, previousCount = 196, newCount = 12))
    }

    @Test
    fun `een hapering die herstelt laat geen sporen na`() {
        val key = "test-" + System.nanoTime()
        assertFalse(Plausibility.accept(key, previousCount = 196, newCount = 0))
        assertTrue(Plausibility.accept(key, previousCount = 196, newCount = 197))
        // Een volgende hapering begint weer opnieuw.
        assertFalse(Plausibility.accept(key, previousCount = 197, newCount = 0))
    }

    // ------------------------------------------------------- stream-tokens

    @Test
    fun `een Wowza-token bepaalt hoe lang het adres bruikbaar is`() {
        val now = 1_700_000_000_000L
        val end = now / 1000 + 12 * 3600
        val url = "https://highvolume155.streampartner.nl/x/playlist.m3u8?token_endtime=$end&token_starttime=0&token_hash=abc"

        // Twaalf uur geldig: begrensd op zes uur.
        assertEquals(6 * 3600_000L, StreamUrlLifetime.validForMs(url, now))
    }

    @Test
    fun `een bijna verlopen token wordt niet bewaard`() {
        val now = 1_700_000_000_000L
        val url = "https://x/playlist.m3u8?token_endtime=${now / 1000 + 600}"
        assertEquals(0L, StreamUrlLifetime.validForMs(url, now))
    }

    @Test
    fun `zonder herkenbaar token twee minuten`() {
        assertEquals(2 * 60_000L, StreamUrlLifetime.validForMs("https://x/index.m3u8?token=abc123"))
    }

    // ---------------------------------------------------- spelerpagina

    @Test
    fun `spelerpagina - src-label gaat voor, mp4 als laatste`() {
        val url = "https://highvolume08.streampartner.nl/x/playlist.m3u8?t=1"
        assertEquals(url, StreampartnerPlayer.streamUrlFromPlayerHtml("""var a="https://oud.nl/a.m3u8"; player({src: "$url"})"""))
        assertEquals("https://x.nl/oud.mp4", StreampartnerPlayer.streamUrlFromPlayerHtml("""<video src="https://x.nl/oud.mp4">"""))
    }

    // ---------------------------------------------------- detail-snapshot

    @Test
    fun `een programmapagina komt ongeschonden van schijf terug`() {
        val detail = ProgramDetail(
            slug = "bijbelse-karakters", title = "Bijbelse karakters", posterUrl = "https://x/p.jpg",
            description = "Met \"aanhalingstekens\"", category = "13 seizoenen", nodeId = "84470", isInMyList = true,
            seasons = listOf(
                SeasonInfo("13", "Seizoen 13", listOf(EpisodeItem("a", "1", "Een", "b", "25 m", "https://x/a.jpg", "13-1-a", "https://x/video/13-1-a"))),
                SeasonInfo("12", "Seizoen 12", emptyList())
            )
        )
        assertEquals(detail, CatalogJson.decodeDetail(CatalogJson.encodeDetail(detail)))
    }
}
