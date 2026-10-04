package nl.family7.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * De speler van Streampartner levert het stream-adres ingepakt aan. Deze tests
 * pakken een adres eerst zelf in en controleren dat de app er weer hetzelfde
 * uit haalt, zodat een verbouwing aan de uitpakker meteen opvalt.
 */
class StreampartnerPlayerTest {

    private val streamUrl = "https://highvolume08.streampartner.nl/family7/index.m3u8?token=abc123"

    // -- inpakhulpjes, de omgekeerde weg van de app ---------------------------

    /** Elk teken als twee posities in grondtal 36. */
    private fun packBase36(plain: String): String = buildString {
        for (character in plain) {
            append(character.code.toString(36).padStart(2, '0'))
        }
    }

    /**
     * De variant met een sleutel: de eerste vijf posities van elk argument
     * vormen samen de sleutel, de rest de ingepakte tekst. Per teken bepaalt
     * de pariteit van het sleutelteken of er een bij of af gaat.
     */
    private fun packInterleaved(plain: String, key: String): Triple<String, String, String> {
        require(key.length == 15) { "de sleutel bestaat uit vijf posities in drie argumenten" }

        val payload = buildString {
            var keyIndex = 0
            for (character in plain) {
                val shift = if (key[keyIndex].code % 2 != 0) 1 else -1
                append((character.code + shift).toString(36).padStart(2, '0'))
                keyIndex = (keyIndex + 1) % key.length
            }
        }

        // De uitpakker leest per positie eerst w, dan i, dan s; de sleutel komt
        // dus om en om uit de drie argumenten.
        val w = StringBuilder()
        val i = StringBuilder()
        val s = StringBuilder()
        for (position in 0 until 5) {
            w.append(key[position * 3])
            i.append(key[position * 3 + 1])
            s.append(key[position * 3 + 2])
        }
        // Alles voorbij positie 5 zit alleen in het eerste argument, zodat de
        // volgorde van de ingepakte tekst gelijk blijft.
        w.append(payload)

        return Triple(w.toString(), i.toString(), s.toString())
    }

    private fun evalCall(vararg arguments: String): String =
        "eval(function(w,i,s,e){var x=1;}(" +
            arguments.joinToString(",") { "'$it'" } +
            "))"

    // -- tests ----------------------------------------------------------------

    @Test
    fun `paren in grondtal 36 worden weer tekst`() {
        assertEquals("//\r\n", StreampartnerPlayer.unpackBase36("1b1b0d0a"))
    }

    @Test
    fun `een ingepakt adres komt er heel weer uit`() {
        val packed = packBase36(streamUrl)

        assertEquals(streamUrl, StreampartnerPlayer.unpackBase36(packed))
    }

    @Test
    fun `een onleesbaar paar gooit de rest niet weg`() {
        val packed = packBase36("abc") + "!!"

        assertEquals("abc", StreampartnerPlayer.unpackBase36(packed))
    }

    @Test
    fun `de variant met sleutel komt er ook heel weer uit`() {
        val (w, i, s) = packInterleaved(streamUrl, key = "K3y-v00r-Test!1")

        assertEquals(streamUrl, StreampartnerPlayer.unpackInterleaved(w, i, s))
    }

    @Test
    fun `zonder sleutel geen uitkomst in plaats van een crash`() {
        assertEquals("", StreampartnerPlayer.unpackInterleaved("", "", ""))
    }

    @Test
    fun `adres dat gewoon in de tekst staat wordt gevonden`() {
        val html = """<video><source src="$streamUrl" type="application/x-mpegURL"></video>"""

        assertEquals(streamUrl, StreampartnerPlayer.firstM3u8(html))
    }

    @Test
    fun `geen adres in de tekst geeft leeg`() {
        assertEquals("", StreampartnerPlayer.firstM3u8("<html><body>niets</body></html>"))
    }

    @Test
    fun `adres uit een eval met alleen het eerste argument`() {
        val html = "<script>" + evalCall(packBase36(streamUrl), "", "", "") + "</script>"

        assertEquals(listOf(streamUrl), StreampartnerPlayer.decodeStreamUrls(html))
    }

    @Test
    fun `adres uit een eval met sleutel`() {
        val (w, i, s) = packInterleaved(streamUrl, key = "abcde12345fghij")
        val html = "<script>" + evalCall(w, i, s, "") + "</script>"

        assertEquals(listOf(streamUrl), StreampartnerPlayer.decodeStreamUrls(html))
    }

    @Test
    fun `een eval binnen een eval wordt ook uitgepakt`() {
        val inner = "<script>" + evalCall(packBase36(streamUrl), "", "", "") + "</script>"
        val outer = "<script>" + evalCall(packBase36(inner), "", "", "") + "</script>"

        assertEquals(listOf(streamUrl), StreampartnerPlayer.decodeStreamUrls(outer))
    }

    @Test
    fun `eindeloos ingepakte tekst stopt netjes`() {
        var html = "<script>" + evalCall(packBase36("leeg"), "", "", "") + "</script>"
        repeat(8) {
            html = "<script>" + evalCall(packBase36(html), "", "", "") + "</script>"
        }

        // Dieper dan de ingestelde grens kijkt hij niet; belangrijk is dat het
        // eindigt in plaats van door te blijven uitpakken.
        assertTrue(StreampartnerPlayer.decodeStreamUrls(html).isEmpty())
    }

    @Test
    fun `argumenten worden uit de aanroep gehaald`() {
        assertEquals(
            listOf("een", "", "twee", ""),
            StreampartnerPlayer.parseJsStringArgs("'een','','twee',''")
        )
    }

    @Test
    fun `hetzelfde adres komt niet dubbel terug`() {
        val html = """<a href="$streamUrl">een</a><a href="$streamUrl">twee</a>"""

        assertEquals(listOf(streamUrl), StreampartnerPlayer.decodeStreamUrls(html))
    }
}
