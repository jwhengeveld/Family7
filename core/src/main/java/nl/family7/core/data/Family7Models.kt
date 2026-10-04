package nl.family7.core.data

data class UserSession(
    val isLoggedIn: Boolean = false,
    val username: String = "",
    val email: String = "",
    val uid: String = "",
    val cookies: Map<String, String> = emptyMap(),
    val errorMessage: String? = null
)

data class LiveStreamInfo(
    val title: String = "Family7 Live",
    val currentProgram: String = "Family7 Uitzending",
    val timeRange: String = "",
    val imageUrl: String = "",
    val description: String = "",
    val streamUrl: String = ""
)

data class ProgramItem(
    val id: String,
    val slug: String,
    val title: String,
    val thumbnailUrl: String,
    val badge: String = "",
    val url: String = "",
    val description: String = "",
    /** Drupal node-id, nodig om het programma aan "Mijn lijst" toe te voegen. */
    val nodeId: String = ""
)

data class CategoryRow(
    val id: String,
    val title: String,
    val moreUrl: String = "",
    val items: List<ProgramItem> = emptyList()
)

data class EpisodeItem(
    val id: String,
    val episodeNumber: String,
    val title: String,
    val description: String = "",
    val duration: String = "",
    val thumbnailUrl: String = "",
    val videoSlug: String,
    val videoUrl: String,
    var streamUrl: String = ""
)

/** "Afl. 3: Titel", of alleen de titel als de aflevering geen nummer heeft. */
fun EpisodeItem.displayLabel(): String =
    if (episodeNumber.isBlank()) title else "Afl. $episodeNumber: $title"

data class SeasonInfo(
    val seasonNumber: String,
    val title: String,
    val episodes: List<EpisodeItem>
)

data class ProgramDetail(
    val slug: String,
    val title: String,
    val posterUrl: String,
    val description: String,
    val category: String = "",
    val seasons: List<SeasonInfo> = emptyList(),
    /** Drupal node-id van het programma, gelezen uit de "Mijn lijst"-knop. */
    val nodeId: String = "",
    /** Of Family7 dit programma al in de lijst van dit account heeft staan. */
    val isInMyList: Boolean = false
)

data class PlayableMedia(
    val title: String,
    val subtitle: String = "",
    val streamUrl: String,
    val thumbnailUrl: String = "",
    val isLive: Boolean = false,
    val episodeSlug: String = "",
    val programSlug: String = "",
    val resumePositionMs: Long = 0L
)
