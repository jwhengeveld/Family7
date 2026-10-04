package nl.family7.core.data

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * De aanmeldsessie van Family7 zit in een cookie. Deze tests leggen vast waar
 * die cookie wel en niet naartoe mag, want dat is precies het soort fout dat
 * je pas merkt als het te laat is.
 */
class CookieStoreTest {

    private val family7 = "https://www.family7.nl/plus".toHttpUrl()

    private fun sessionCookie(setCookie: String, from: String = "https://www.family7.nl/"): Cookie =
        requireNotNull(Cookie.parse(from.toHttpUrl(), setCookie)) { "onparsebare cookie: $setCookie" }

    @Test
    fun `cookie gaat mee naar het eigen domein`() {
        val store = CookieStore()
        store.save(listOf(sessionCookie("SSESSabc=geheim; path=/")))

        val sent = store.forUrl(family7)

        assertEquals(1, sent.size)
        assertEquals("SSESSabc", sent.first().name)
    }

    @Test
    fun `cookie gaat niet naar een domein dat er alleen op lijkt`() {
        val store = CookieStore()
        store.save(listOf(sessionCookie("SSESSabc=geheim; domain=family7.nl; path=/")))

        // Een aanvaller die notfamily7.nl registreert, hoort niets te krijgen:
        // op de hostnaam eindigen is niet hetzelfde als tot het domein behoren.
        assertTrue(store.forUrl("https://notfamily7.nl/".toHttpUrl()).isEmpty())
        assertTrue(store.forUrl("https://family7.nl.example.com/".toHttpUrl()).isEmpty())
        assertTrue(store.forUrl("https://streampartner.nl/".toHttpUrl()).isEmpty())
    }

    @Test
    fun `subdomein van hetzelfde domein krijgt de cookie wel`() {
        val store = CookieStore()
        store.save(listOf(sessionCookie("SSESSabc=geheim; domain=family7.nl; path=/")))

        assertEquals(1, store.forUrl("https://media.family7.nl/beeld.jpg".toHttpUrl()).size)
    }

    @Test
    fun `cookie zonder domain-attribuut blijft bij precies die host`() {
        val store = CookieStore()
        store.save(listOf(sessionCookie("SSESSabc=geheim; path=/")))

        assertEquals(1, store.forUrl(family7).size)
        assertTrue(store.forUrl("https://media.family7.nl/".toHttpUrl()).isEmpty())
    }

    @Test
    fun `secure cookie gaat niet over een onversleutelde verbinding`() {
        val store = CookieStore()
        store.save(listOf(sessionCookie("SSESSabc=geheim; path=/; secure")))

        assertTrue(store.forUrl("http://www.family7.nl/plus".toHttpUrl()).isEmpty())
        assertEquals(1, store.forUrl(family7).size)
    }

    @Test
    fun `pad wordt gerespecteerd`() {
        val store = CookieStore()
        store.save(listOf(sessionCookie("lijst=1; path=/plus")))

        assertEquals(1, store.forUrl("https://www.family7.nl/plus/mijnlijst".toHttpUrl()).size)
        assertTrue(store.forUrl("https://www.family7.nl/user/login".toHttpUrl()).isEmpty())
    }

    @Test
    fun `verlopen cookie verdwijnt`() {
        // OkHttp rekent max-age om naar een echt tijdstip, dus de klok van deze
        // test moet vanaf nu lopen en niet vanaf een verzonnen nul.
        var now = System.currentTimeMillis()
        val store = CookieStore { now }
        store.save(listOf(sessionCookie("SSESSabc=geheim; path=/; max-age=60")))
        assertEquals(1, store.forUrl(family7).size)

        now += 61_000L
        assertTrue(store.forUrl(family7).isEmpty())
        assertFalse(store.hasSessionCookie())
    }

    @Test
    fun `een cookie die de server intrekt wordt niet bewaard`() {
        val store = CookieStore()
        store.save(listOf(sessionCookie("SSESSabc=geheim; path=/")))
        store.save(listOf(sessionCookie("SSESSabc=; path=/; max-age=0")))

        assertTrue(store.valid().isEmpty())
    }

    @Test
    fun `nieuwe waarde vervangt de oude in plaats van ernaast te komen`() {
        val store = CookieStore()
        store.save(listOf(sessionCookie("SSESSabc=oud; path=/")))
        store.save(listOf(sessionCookie("SSESSabc=nieuw; path=/")))

        val sent = store.forUrl(family7)
        assertEquals(1, sent.size)
        assertEquals("nieuw", sent.first().value)
    }

    @Test
    fun `sessiecookie wordt herkend`() {
        val store = CookieStore()
        assertFalse(store.hasSessionCookie())

        store.save(listOf(sessionCookie("SSESSabc123=geheim; path=/")))

        assertTrue(store.hasSessionCookie())
    }

    @Test
    fun `opslaan en terugzetten houdt de eigenschappen intact`() {
        val original = sessionCookie("SSESSabc=geheim; domain=family7.nl; path=/plus; secure; httponly")

        val restored = CookieCodec.decode(CookieCodec.encode(original))

        assertNotNull(restored)
        requireNotNull(restored)
        assertEquals(original.name, restored.name)
        assertEquals(original.value, restored.value)
        assertEquals(original.domain, restored.domain)
        assertEquals(original.path, restored.path)
        assertEquals(original.secure, restored.secure)
        assertEquals(original.httpOnly, restored.httpOnly)
        assertEquals(original.hostOnly, restored.hostOnly)
    }

    @Test
    fun `terugzetten houdt ook vast dat een cookie hostgebonden is`() {
        val original = sessionCookie("SSESSabc=geheim; path=/")
        assertTrue(original.hostOnly)

        val restored = requireNotNull(CookieCodec.decode(CookieCodec.encode(original)))

        assertTrue(restored.hostOnly)
        assertEquals("www.family7.nl", restored.domain)
    }

    @Test
    fun `onzin op schijf levert geen cookie op in plaats van een crash`() {
        assertEquals(null, CookieCodec.decode("zonder scheiding"))
        assertEquals(null, CookieCodec.decode(""))
    }
}
