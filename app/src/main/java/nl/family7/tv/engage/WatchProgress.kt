package nl.family7.tv.engage

/**
 * Waar de kijker in een aflevering gebleven is. Hiermee gaat de speler verder
 * waar hij stopte, en hieruit bouwt [ContinueWatching] de rij "Verder kijken"
 * op het Google TV-startscherm.
 */
data class WatchProgress(
    val videoSlug: String,
    val programSlug: String,
    val programTitle: String,
    val episodeTitle: String,
    val episodeNumber: String = "",
    val seasonNumber: String = "",
    val imageUrl: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    /** De volgende aflevering na een afgekeken aflevering (nog niet begonnen). */
    val isNext: Boolean = false
)

internal object WatchProgressRules {
    /** Google TV toont er hooguit tien per app. */
    const val MAX_ITEMS = 10

    /** Korter gekeken telt niet als "begonnen"; zo komt zappen niet in de rij. */
    const val MIN_WATCHED_MS = 30_000L

    /** Aftiteling: de laatste 5 % (en minstens de laatste 30 seconden) telt als afgekeken. */
    fun isFinished(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        val tail = maxOf(durationMs / 20, 30_000L)
        return positionMs >= durationMs - tail
    }

    /** Waar de speler moet beginnen: bij een afgekeken of net begonnen aflevering vooraan. */
    fun resumePosition(progress: WatchProgress?): Long {
        if (progress == null || progress.isNext) return 0L
        if (progress.positionMs < MIN_WATCHED_MS) return 0L
        if (isFinished(progress.positionMs, progress.durationMs)) return 0L
        return progress.positionMs
    }

    /** Nieuwste eerst, per aflevering één, per programma één, hooguit [MAX_ITEMS]. */
    fun upsert(items: List<WatchProgress>, update: WatchProgress): List<WatchProgress> =
        normalize(items.filterNot { it.videoSlug == update.videoSlug || it.programSlug == update.programSlug } + update)

    fun normalize(items: List<WatchProgress>): List<WatchProgress> =
        items.sortedByDescending { it.updatedAtMs }
            .distinctBy { it.videoSlug }
            .distinctBy { it.programSlug }
            .take(MAX_ITEMS)
}
