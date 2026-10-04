package nl.family7.core.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * "Mijn lijst" van het ingelogde Family7-account.
 *
 * Dit is dezelfde lijst als op de site: /plus/mijnlijst toont hem, en de knop
 * op een programmapagina roept /plus/mijnlijst/{node-id}/add of /remove aan.
 * Wat u hier bewaart staat dus ook op de website en op andere apparaten.
 */
class Family7MyListRepository(appContext: Context) {
    private val context: Context = appContext.applicationContext
    private val client = Family7Http.getClient(context)

    private val _items = MutableStateFlow<List<ProgramItem>>(emptyList())
    val items: StateFlow<List<ProgramItem>> = _items.asStateFlow()

    companion object {
        private const val BASE_URL = "https://www.family7.nl"
        private const val MY_LIST_URL = "$BASE_URL/plus/mijnlijst"
    }

    /** Haalt de lijst opnieuw op bij Family7. */
    suspend fun refresh(): Result<List<ProgramItem>> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(MY_LIST_URL)
                .header("Referer", "$BASE_URL/plus")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    _items.value = emptyList()
                    return@withContext Result.failure(
                        Exception("Log in om uw lijst te zien.")
                    )
                }
                val doc = Jsoup.parse(resp.body?.string() ?: "", MY_LIST_URL)
                val items = doc.select(".view-block_element-wrapper, .view-block_element, .slider-default_element")
                    .mapNotNull { card ->
                        val href = card.selectFirst("a[href*='/plus/programmas/']")?.attr("href")
                            ?: return@mapNotNull null
                        val img = card.selectFirst("img")
                        val slug = href.substringBefore("?").trimEnd('/').substringAfterLast('/')
                        ProgramItem(
                            id = href,
                            slug = slug,
                            title = img?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
                                ?: slug.replace('-', ' ')
                                    .split(" ")
                                    .filter { it.isNotEmpty() }
                                    .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
                            thumbnailUrl = img?.attr("src").orEmpty()
                                .let { if (it.startsWith("/")) "$BASE_URL$it" else it },
                            url = if (href.startsWith("http")) href else "$BASE_URL$href",
                            nodeId = card.selectFirst("[data-node-id]")?.attr("data-node-id").orEmpty()
                        )
                    }
                    .distinctBy { it.slug }

                _items.value = items
                Result.success(items)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Zet een programma in de lijst of haalt het eruit, via hetzelfde eindpunt
     * dat de website gebruikt. Geeft terug of het programma er nu in staat.
     */
    suspend fun setInList(nodeId: String, add: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        if (nodeId.isBlank()) {
            return@withContext Result.failure(Exception("Dit programma heeft geen node-id."))
        }
        try {
            val action = if (add) "add" else "remove"
            val req = Request.Builder()
                .url("$MY_LIST_URL/$nodeId/$action")
                .header("Referer", "$BASE_URL/plus")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("Kon de lijst niet bijwerken (${resp.code}).")
                    )
                }
                // De site antwoordt met {"status":"added"} of {"status":"removed"}.
                val status = runCatching { JSONObject(body).optString("status") }.getOrDefault("")
                val nowInList = when (status) {
                    "added" -> true
                    "removed" -> false
                    else -> add
                }
                refresh()
                Result.success(nowInList)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun clear() {
        _items.value = emptyList()
    }
}
