package nl.family7.core.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Bewaart de laatst geladen catalogus op schijf, zodat een koude start meteen
 * de rijen van de vorige keer toont en pas daarna stil ververst. Zonder netwerk
 * blijft de app daardoor bruikbaar om door het aanbod te bladeren.
 *
 * De bestanden staan in de no-backup-map: ze horen bij dit account op dit
 * toestel, en [clear] gooit ze weg bij het uitloggen.
 */
class CatalogSnapshotStore(context: Context) {

    private val dir = File(context.applicationContext.noBackupFilesDir, "family7_catalog")

    fun readRows(name: String): List<CategoryRow>? =
        read(name)?.let { runCatching { CatalogJson.decodeRows(it) }.getOrNull() }

    fun writeRows(name: String, rows: List<CategoryRow>) =
        write(name, CatalogJson.encodeRows(rows))

    fun readItems(name: String): List<ProgramItem>? =
        read(name)?.let { runCatching { CatalogJson.decodeItems(it) }.getOrNull() }

    fun writeItems(name: String, items: List<ProgramItem>) =
        write(name, CatalogJson.encodeItems(items))

    fun clear() {
        runCatching { dir.deleteRecursively() }
    }

    private fun read(name: String): String? = runCatching {
        File(dir, "$name.json").takeIf { it.isFile }?.readText()
    }.getOrNull()

    /** Eerst naar een tijdelijk bestand, dan hernoemen: nooit een half geschreven snapshot. */
    private fun write(name: String, json: String) {
        runCatching {
            dir.mkdirs()
            val target = File(dir, "$name.json")
            val temp = File(dir, "$name.json.tmp")
            temp.writeText(json)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }
}

/** Zet de catalogusmodellen om naar JSON en terug. Los van Android te testen. */
internal object CatalogJson {

    fun encodeRows(rows: List<CategoryRow>): String = JSONArray().apply {
        rows.forEach { row ->
            put(
                JSONObject()
                    .put("id", row.id)
                    .put("title", row.title)
                    .put("moreUrl", row.moreUrl)
                    .put("items", itemsToJson(row.items))
            )
        }
    }.toString()

    fun decodeRows(json: String): List<CategoryRow> {
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            CategoryRow(
                id = row.getString("id"),
                title = row.optString("title"),
                moreUrl = row.optString("moreUrl"),
                items = itemsFromJson(row.optJSONArray("items") ?: JSONArray())
            )
        }
    }

    fun encodeItems(items: List<ProgramItem>): String = itemsToJson(items).toString()

    fun decodeItems(json: String): List<ProgramItem> = itemsFromJson(JSONArray(json))

    private fun itemsToJson(items: List<ProgramItem>) = JSONArray().apply {
        items.forEach { item ->
            put(
                JSONObject()
                    .put("id", item.id)
                    .put("slug", item.slug)
                    .put("title", item.title)
                    .put("thumbnailUrl", item.thumbnailUrl)
                    .put("badge", item.badge)
                    .put("url", item.url)
                    .put("description", item.description)
                    .put("nodeId", item.nodeId)
            )
        }
    }

    private fun itemsFromJson(array: JSONArray): List<ProgramItem> =
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val slug = item.optString("slug").ifEmpty { return@mapNotNull null }
            ProgramItem(
                id = item.optString("id").ifEmpty { slug },
                slug = slug,
                title = item.optString("title"),
                thumbnailUrl = item.optString("thumbnailUrl"),
                badge = item.optString("badge"),
                url = item.optString("url"),
                description = item.optString("description"),
                nodeId = item.optString("nodeId")
            )
        }
}
