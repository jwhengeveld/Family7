package nl.family7.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * De catalogus wordt gecachet zodat heen en weer navigeren geen laadscherm
 * oplevert. Belangrijk daarbij: een oude waarde mag een scherm wel meteen
 * vullen, maar niet doorgaan voor vers.
 */
class TimedCacheTest {

    @Test
    fun `verse waarde komt terug`() {
        var now = 0L
        val cache = TimedCache<String>(ttlMs = 1_000, clock = { now })

        cache.put("catalogus")
        now = 500

        assertEquals("catalogus", cache.fresh())
        assertEquals("catalogus", cache.snapshot())
    }

    @Test
    fun `na de houdbaarheid is de waarde niet meer vers maar nog wel bruikbaar`() {
        var now = 0L
        val cache = TimedCache<String>(ttlMs = 1_000, clock = { now })

        cache.put("catalogus")
        now = 1_001

        assertNull(cache.fresh())
        assertEquals("catalogus", cache.snapshot())
    }

    @Test
    fun `opnieuw vullen zet de houdbaarheid terug`() {
        var now = 0L
        val cache = TimedCache<String>(ttlMs = 1_000, clock = { now })

        cache.put("oud")
        now = 900
        cache.put("nieuw")
        now = 1_500

        assertEquals("nieuw", cache.fresh())
    }

    @Test
    fun `leeggooien laat niets achter`() {
        val cache = TimedCache<String>(ttlMs = 1_000, clock = { 0L })

        cache.put("catalogus")
        cache.clear()

        assertNull(cache.fresh())
        assertNull(cache.snapshot())
    }

    @Test
    fun `een lege cache geeft niets terug`() {
        val cache = TimedCache<String>(ttlMs = 1_000, clock = { 0L })

        assertNull(cache.fresh())
        assertNull(cache.snapshot())
    }

    @Test
    fun `een waarde van schijf vult het scherm maar telt niet als vers`() {
        val cache = TimedCache<String>(ttlMs = 1_000, clock = { 0L })

        cache.seed("van schijf")

        assertEquals("van schijf", cache.snapshot())
        assertNull(cache.fresh())
    }

    @Test
    fun `een waarde van schijf overschrijft geen nieuwere waarde`() {
        val cache = TimedCache<String>(ttlMs = 1_000, clock = { 0L })

        cache.put("van het netwerk")
        cache.seed("van schijf")

        assertEquals("van het netwerk", cache.fresh())
    }

    @Test
    fun `na verversen telt de waarde weer als vers`() {
        val cache = TimedCache<String>(ttlMs = 1_000, clock = { 0L })

        cache.seed("van schijf")
        cache.put("van het netwerk")

        assertEquals("van het netwerk", cache.fresh())
    }
}
