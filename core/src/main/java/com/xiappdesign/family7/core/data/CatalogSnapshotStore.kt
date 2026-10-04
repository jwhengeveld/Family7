package com.xiappdesign.family7.core.data

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

    fun readDetail(name: String): ProgramDetail? =
        read(name)?.let { runCatching { CatalogJson.decodeDetail(it) }.getOrNull() }

    fun writeDetail(name: String, detail: ProgramDetail) =
        write(name, CatalogJson.encodeDetail(detail))

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

    fun encodeDetail(detail: ProgramDetail): String = JSONObject()
        .put("slug", detail.slug)
        .put("title", detail.title)
        .put("posterUrl", detail.posterUrl)
        .put("description", detail.description)
        .put("category", detail.category)
        .put("nodeId", detail.nodeId)
        .put("isInMyList", detail.isInMyList)
        .put("seasons", JSONArray().apply {
            detail.seasons.forEach { season ->
                put(JSONObject()
                    .put("number", season.seasonNumber)
                    .put("title", season.title)
                    .put("episodes", JSONArray().apply {
                        season.episodes.forEach { e ->
                            put(JSONObject()
                                .put("id", e.id).put("number", e.episodeNumber).put("title", e.title)
                                .put("description", e.description).put("duration", e.duration)
                                .put("thumbnailUrl", e.thumbnailUrl).put("videoSlug", e.videoSlug).put("videoUrl", e.videoUrl))
                        }
                    }))
            }
        }).toString()

    fun decodeDetail(json: String): ProgramDetail {
        val o = JSONObject(json)
        val seasons = o.optJSONArray("seasons") ?: JSONArray()
        return ProgramDetail(
            slug = o.getString("slug"),
            title = o.optString("title"),
            posterUrl = o.optString("posterUrl"),
            description = o.optString("description"),
            category = o.optString("category"),
            nodeId = o.optString("nodeId"),
            isInMyList = o.optBoolean("isInMyList"),
            seasons = (0 until seasons.length()).map { i ->
                val s = seasons.getJSONObject(i)
                val episodes = s.optJSONArray("episodes") ?: JSONArray()
                SeasonInfo(
                    seasonNumber = s.optString("number"),
                    title = s.optString("title"),
                    episodes = (0 until episodes.length()).mapNotNull { j ->
                        val e = episodes.optJSONObject(j) ?: return@mapNotNull null
                        val slug = e.optString("videoSlug").ifEmpty { return@mapNotNull null }
                        EpisodeItem(
                            id = e.optString("id").ifEmpty { slug }, episodeNumber = e.optString("number"),
                            title = e.optString("title"), description = e.optString("description"),
                            duration = e.optString("duration"), thumbnailUrl = e.optString("thumbnailUrl"),
                            videoSlug = slug, videoUrl = e.optString("videoUrl")
                        )
                    }
                )
            }
        )
    }

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
