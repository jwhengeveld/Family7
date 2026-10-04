package nl.family7.core.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException

class Family7AuthRepository(appContext: Context) {

    private val context: Context = appContext.applicationContext

    private val client = Family7Http.getClient(context)
    private val cookieJar = Family7Http.getCookieJar(context)
    private val authPrefs: SharedPreferences =
        context.getSharedPreferences("family7_auth_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_SAVED_USERNAME = "saved_username_v2"
        private const val KEY_UID = "user_uid_v2"
        private const val BASE_URL = "https://www.family7.nl"
        private const val LOGIN_URL = "$BASE_URL/user/login"
        private const val ACCOUNT_URL = "$BASE_URL/user"
        private val UID_IN_PATH = Regex("/user/(\\d+)")

        /** Waar dezelfde gegevens onversleuteld stonden vóór versie 1.2.0. */
        private val LEGACY_KEYS = mapOf(
            KEY_SAVED_USERNAME to "saved_username",
            KEY_UID to "user_uid"
        )
    }

    suspend fun login(
        usernameOrEmail: String,
        pass: String,
        rememberMe: Boolean = true
    ): Result<UserSession> = withContext(Dispatchers.IO) {
        val account = usernameOrEmail.trim()
        try {
            // Stap 1: het formulier ophalen voor het form_build_id dat Drupal
            // per bezoek meegeeft; zonder dat wijst hij de aanmelding af.
            val formBuildId = client.newCall(Request.Builder().url(LOGIN_URL).build())
                .execute()
                .use { response ->
                    Jsoup.parse(response.body?.string().orEmpty())
                        .selectFirst("input[name=form_build_id]")
                        ?.attr("value")
                        .orEmpty()
                }

            // Stap 2: de aanmelding zelf.
            val form = FormBody.Builder()
                .add("name", account)
                .add("pass", pass)
                .add("form_id", "user_login_form")
                .add("op", "Inloggen")
                .apply {
                    if (formBuildId.isNotEmpty()) add("form_build_id", formBuildId)
                    if (rememberMe) add("persistent_login", "1")
                }
                .build()

            val postHtml = client.newCall(
                Request.Builder().url(LOGIN_URL).post(form).build()
            ).execute().use { it.body?.string().orEmpty() }

            // Drupal meldt een verkeerde combinatie in het formulier zelf. Die
            // melding is het betrouwbaarste signaal dat er iets mis is; het
            // adres waarop we uitkomen is dat niet, want bij een fout blijft de
            // gebruiker gewoon op /user/login staan.
            val formError = Jsoup.parse(postHtml)
                .select(".messages--error, .messages.error, .alert-danger")
                .text()
                .trim()
            if (formError.isNotEmpty()) {
                return@withContext Result.failure(Exception(formError))
            }

            // Stap 3: laat Family7 zelf bevestigen wie we nu zijn. /user leidt een
            // ingelogde bezoeker door naar /user/{uid} en een anonieme naar de
            // aanmeldpagina. Geen giswerk op losse woorden in de HTML.
            val uid = fetchOwnUid()
                ?: return@withContext Result.failure(
                    Exception("Inloggen mislukt. Controleer uw e-mailadres en wachtwoord.")
                )

            authPrefs.edit()
                .putBoolean(KEY_IS_LOGGED_IN, true)
                .putString(KEY_SAVED_USERNAME, SessionCrypto.encrypt(account))
                .putString(KEY_UID, SessionCrypto.encrypt(uid))
                .apply()

            Result.success(
                UserSession(
                    isLoggedIn = true,
                    username = account,
                    email = account,
                    uid = uid,
                    cookies = cookieJar.getAllCookies()
                )
            )
        } catch (e: IOException) {
            Result.failure(
                Exception("Verbindingsfout: ${e.localizedMessage ?: "Kon geen verbinding maken met Family7"}")
            )
        } catch (e: Exception) {
            Result.failure(
                Exception("Inloggen mislukt: ${e.localizedMessage ?: "onbekende fout"}")
            )
        }
    }

    /**
     * De sessie bij het opstarten.
     *
     * Eerst het antwoord dat we lokaal al hebben, zodat de app ook zonder
     * netwerk opent. Staat er een sessiecookie, dan vraagt hij Family7 om
     * bevestiging: pas als die duidelijk zegt dat de sessie voorbij is, wordt
     * er uitgelogd. Een haperend netwerk gooit de gebruiker er dus niet uit.
     */
    suspend fun checkSession(): UserSession = withContext(Dispatchers.IO) {
        val wasLoggedIn = authPrefs.getBoolean(KEY_IS_LOGGED_IN, false)
        if (!wasLoggedIn || !cookieJar.hasSessionCookie()) {
            return@withContext UserSession(isLoggedIn = false)
        }

        val savedUser = readEncrypted(KEY_SAVED_USERNAME)
        val savedUid = readEncrypted(KEY_UID)

        val confirmedUid = try {
            fetchOwnUid()
        } catch (_: IOException) {
            // Onbereikbaar: de opgeslagen sessie blijft staan.
            return@withContext UserSession(
                isLoggedIn = true,
                username = savedUser,
                email = savedUser,
                uid = savedUid,
                cookies = cookieJar.getAllCookies()
            )
        }

        if (confirmedUid == null) {
            logout()
            return@withContext UserSession(isLoggedIn = false)
        }

        UserSession(
            isLoggedIn = true,
            username = savedUser,
            email = savedUser,
            uid = confirmedUid,
            cookies = cookieJar.getAllCookies()
        )
    }

    /**
     * Of er lokaal een sessie bewaard staat, zonder Family7 te vragen. Daarmee
     * kan een app meteen het startscherm tonen en [checkSession] op de
     * achtergrond laten bevestigen.
     */
    fun hasStoredSession(): Boolean =
        authPrefs.getBoolean(KEY_IS_LOGGED_IN, false) && cookieJar.hasSessionCookie()

    /** Het eigen gebruikersnummer volgens Family7, of null als we niet ingelogd zijn. */
    private fun fetchOwnUid(): String? =
        client.newCall(Request.Builder().url(ACCOUNT_URL).build()).execute().use { response ->
            UID_IN_PATH.find(response.request.url.encodedPath)?.groupValues?.get(1)
        }

    /**
     * Leest een versleutelde waarde. Staat er nog een onversleutelde waarde van
     * een oudere versie, dan wordt die overgezet in plaats van weggegooid; een
     * update hoort niemand uit te loggen.
     */
    private fun readEncrypted(key: String): String {
        authPrefs.getString(key, null)?.let { return SessionCrypto.decrypt(it) ?: "" }

        val legacyKey = LEGACY_KEYS[key] ?: return ""
        val legacyValue = authPrefs.getString(legacyKey, null) ?: return ""
        authPrefs.edit()
            .putString(key, SessionCrypto.encrypt(legacyValue))
            .remove(legacyKey)
            .apply()
        return legacyValue
    }

    fun logout() {
        cookieJar.clear()
        authPrefs.edit().clear().apply()
    }

    fun getSavedUsername(): String = readEncrypted(KEY_SAVED_USERNAME)
}
