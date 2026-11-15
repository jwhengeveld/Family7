package nl.family7.tv.engage

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import nl.family7.tv.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Onthoudt per aflevering waar de kijker gebleven is (alleen op dit toestel) en
 * laat [ContinueWatchingWorker] daar de rij "Verder kijken" op het Google
 * TV-startscherm van maken via de Engage SDK.
 */
class ContinueWatching private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<WatchProgress> = synchronized(this) { read() }

    fun get(videoSlug: String): WatchProgress? = all().firstOrNull { it.videoSlug == videoSlug }

    /** Startpositie voor de speler: verder waar de kijker stopte, anders vooraan. */
    fun resumePositionMs(videoSlug: String): Long = WatchProgressRules.resumePosition(get(videoSlug))

    /**
     * Legt de stand vast. Is de aflevering (bijna) uit, dan gaat hij uit de rij
     * en komt [next] (de volgende aflevering) ervoor in de plaats.
     */
    fun record(progress: WatchProgress, next: WatchProgress?) {
        val finished = WatchProgressRules.isFinished(progress.positionMs, progress.durationMs)
        if (!finished && progress.positionMs < WatchProgressRules.MIN_WATCHED_MS) return
        synchronized(this) {
            val items = read()
            val updated = when {
                !finished -> WatchProgressRules.upsert(items, progress)
                next != null -> WatchProgressRules.upsert(
                    items.filterNot { it.videoSlug == progress.videoSlug },
                    next.copy(isNext = true, positionMs = 0L, updatedAtMs = progress.updatedAtMs)
                )
                else -> items.filterNot { it.videoSlug == progress.videoSlug || it.programSlug == progress.programSlug }
            }
            if (updated == items) return
            write(updated)
        }
        schedulePublish()
    }

    /** Na inloggen of bij het opstarten: het account waaronder de rij hoort. */
    fun setAccount(uid: String) {
        if (uid.isBlank()) return
        val id = hash(uid)
        if (prefs.getString(KEY_ACCOUNT, null) == id) {
            schedulePublish()
            return
        }
        prefs.edit().putString(KEY_ACCOUNT, id).remove(KEY_ITEMS).apply()
        schedulePublish()
    }

    /** Bij uitloggen: de rij van het startscherm halen en de standen vergeten. */
    fun signOut() {
        val account = prefs.getString(KEY_ACCOUNT, null)
        prefs.edit().remove(KEY_ITEMS).remove(KEY_ACCOUNT).apply()
        if (account != null && BuildConfig.ENGAGE_ENABLED) ContinueWatchingWorker.enqueueSignOut(appContext, account)
    }

    internal fun accountId(): String? = prefs.getString(KEY_ACCOUNT, null)

    internal fun replaceAll(items: List<WatchProgress>) = synchronized(this) { write(items) }

    fun schedulePublish() {
        if (!BuildConfig.ENGAGE_ENABLED || accountId() == null) return
        val request = OneTimeWorkRequestBuilder<ContinueWatchingWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(ContinueWatchingWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun read(): List<WatchProgress> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun write(items: List<WatchProgress>) {
        val array = JSONArray()
        WatchProgressRules.normalize(items).forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "verder_kijken"
        private const val KEY_ITEMS = "items"
        private const val KEY_ACCOUNT = "account"

        /** Het adres waarmee Google TV de app een aflevering laat hervatten. */
        const val SCHEME = "family7tv"
        const val HOST_PLAY = "verder-kijken"

        @Volatile private var instance: ContinueWatching? = null

        fun get(context: Context): ContinueWatching =
            instance ?: synchronized(this) {
                instance ?: ContinueWatching(context).also { instance = it }
            }

        fun playUri(progress: WatchProgress): Uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST_PLAY)
            .appendQueryParameter("programma", progress.programSlug)
            .appendQueryParameter("video", progress.videoSlug)
            .build()

        /** Het Family7-gebruikersnummer gaat nooit zelf naar Google, alleen een afgeleide sleutel. */
        private fun hash(uid: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("family7-tv:$uid".toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(32)

        private fun toJson(p: WatchProgress) = JSONObject()
            .put("video", p.videoSlug)
            .put("program", p.programSlug)
            .put("programTitle", p.programTitle)
            .put("title", p.episodeTitle)
            .put("episode", p.episodeNumber)
            .put("season", p.seasonNumber)
            .put("image", p.imageUrl)
            .put("position", p.positionMs)
            .put("duration", p.durationMs)
            .put("updated", p.updatedAtMs)
            .put("next", p.isNext)

        private fun fromJson(o: JSONObject) = WatchProgress(
            videoSlug = o.getString("video"),
            programSlug = o.getString("program"),
            programTitle = o.optString("programTitle"),
            episodeTitle = o.optString("title"),
            episodeNumber = o.optString("episode"),
            seasonNumber = o.optString("season"),
            imageUrl = o.optString("image"),
            positionMs = o.optLong("position"),
            durationMs = o.optLong("duration"),
            updatedAtMs = o.optLong("updated"),
            isNext = o.optBoolean("next")
        )
    }
}
