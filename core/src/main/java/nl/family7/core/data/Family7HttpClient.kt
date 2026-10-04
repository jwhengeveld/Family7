package nl.family7.core.data

import android.content.Context
import android.content.SharedPreferences
import nl.family7.core.BuildConfig
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * Bewaart de aanmeldsessie tussen twee keer opstarten, versleuteld met een
 * sleutel uit de AndroidKeyStore. Welke cookie bij welk adres hoort bepaalt
 * [CookieStore]; dit deel gaat alleen over opslaan en terugzetten.
 */
class PersistentCookieJar(context: Context) : CookieJar {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val store = CookieStore()

    init {
        restore()
    }

    private fun restore() {
        val stored = prefs.getString(KEY_COOKIES, null)
            ?: return migrateFromPlainStorage()
        val plain = SessionCrypto.decrypt(stored)
        if (plain == null) {
            // Onleesbaar geworden, bijvoorbeeld na het herstellen van een
            // back-up op een ander toestel. Weggooien en opnieuw inloggen.
            prefs.edit().remove(KEY_COOKIES).apply()
            return
        }
        val restored = runCatching {
            val array = JSONArray(plain)
            (0 until array.length()).mapNotNull { CookieCodec.decode(array.getString(it)) }
        }.getOrDefault(emptyList())
        store.replaceAll(restored)
    }

    /**
     * Neemt de sessie over uit de onversleutelde opslag van een oudere versie,
     * zodat een update niet iedereen uitlogt. Daarna gaat het oude formaat weg.
     */
    private fun migrateFromPlainStorage() {
        val legacy = prefs.all.filterKeys { it != KEY_COOKIES }
        if (legacy.isEmpty()) return

        val restored = legacy.flatMap { (host, value) ->
            val serialized = value as? String ?: return@flatMap emptyList()
            val url = runCatching {
                HttpUrl.Builder().scheme("https").host(host).build()
            }.getOrNull() ?: return@flatMap emptyList()
            serialized.split("||").mapNotNull { Cookie.parse(url, it) }
        }

        prefs.edit().apply {
            legacy.keys.forEach { remove(it) }
        }.apply()

        if (restored.isEmpty()) return
        store.replaceAll(restored)
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        store.valid().forEach { array.put(CookieCodec.encode(it)) }
        val encrypted = SessionCrypto.encrypt(array.toString())
        if (encrypted == null) {
            // Zonder werkende keystore liever niets op schijf dan onbeschermd.
            prefs.edit().remove(KEY_COOKIES).apply()
            return
        }
        prefs.edit().putString(KEY_COOKIES, encrypted).apply()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        store.save(cookies)
        persist()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store.forUrl(url)

    fun clear() {
        store.clear()
        prefs.edit().clear().apply()
    }

    /** Of er een geldige Drupal-sessie in de jar zit. */
    fun hasSessionCookie(): Boolean = store.hasSessionCookie()

    fun getAllCookies(): Map<String, String> = store.asNameValueMap()

    private companion object {
        const val PREFS_NAME = "family7_cookies"
        const val KEY_COOKIES = "cookies_v2"
    }
}

/**
 * Probeert een mislukt GET-verzoek opnieuw. Een tv hangt vaak aan wifi die er
 * na het aanzetten een paar seconden over doet; zonder deze herkansing ziet de
 * gebruiker dan een foutscherm terwijl het netwerk een tel later gewoon werkt.
 */
private class RetryOnNetworkFailure(private val maxAttempts: Int) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var lastFailure: IOException? = null

        repeat(maxAttempts) { attempt ->
            if (chain.call().isCanceled()) throw IOException("Verzoek afgebroken")
            try {
                return chain.proceed(request)
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                if (request.method != "GET") throw e
                lastFailure = e
                val isLastAttempt = attempt == maxAttempts - 1
                if (isLastAttempt) throw e
                try {
                    Thread.sleep(BACKOFF_MS * (attempt + 1))
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw lastFailure ?: IOException("Verzoek mislukt")
    }

    private companion object {
        const val BACKOFF_MS = 300L
    }
}

object Family7Http {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val CACHE_BYTES = 20L * 1024 * 1024
    private const val MAX_ATTEMPTS = 3

    @Volatile private var clientInstance: OkHttpClient? = null
    @Volatile private var cookieJarInstance: PersistentCookieJar? = null

    fun getClient(context: Context): OkHttpClient = synchronized(this) {
        clientInstance ?: buildClient(context).also { clientInstance = it }
    }

    fun getCookieJar(context: Context): PersistentCookieJar = synchronized(this) {
        cookieJarInstance
            ?: PersistentCookieJar(context.applicationContext).also { cookieJarInstance = it }
    }

    private fun buildClient(context: Context): OkHttpClient {
        // Disk-cache van 20 MB, zodat een koude start en het verversen tegen
        // procesdood kunnen en niet elke keer alles opnieuw over het netwerk halen.
        val httpCache = Cache(File(context.applicationContext.cacheDir, "family7_http"), CACHE_BYTES)

        val builder = OkHttpClient.Builder()
            .cache(httpCache)
            .cookieJar(getCookieJar(context))
            .addInterceptor(RetryOnNetworkFailure(MAX_ATTEMPTS))
            // Family7 stuurt op ingelogde pagina's "no-cache"; op dit ene apparaat
            // is een korte bewaartijd prima en scheelt telkens opnieuw ophalen.
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (chain.request().method == "GET") {
                    response.newBuilder()
                        .removeHeader("Pragma")
                        .header("Cache-Control", "public, max-age=60, stale-while-revalidate=600")
                        .build()
                } else {
                    response
                }
            }
            .addInterceptor { chain ->
                val original = chain.request()
                val withHeaders = original.newBuilder().header("User-Agent", USER_AGENT)
                if (original.header("Referer") == null) {
                    withHeaders.header("Referer", "https://www.family7.nl/")
                }
                chain.proceed(withHeaders.build())
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // Bovengrens over alle herkansingen heen, zodat een scherm nooit
            // eindeloos op een laadbalk blijft staan.
            .callTimeout(60, TimeUnit.SECONDS)

        // Alleen tijdens ontwikkelen meelezen: in een release horen de bezochte
        // adressen van een gebruiker niet in logcat thuis.
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
        }

        return builder.build()
    }
}
