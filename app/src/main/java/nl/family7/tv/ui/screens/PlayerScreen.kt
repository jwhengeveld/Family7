package nl.family7.tv.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import nl.family7.tv.R
import nl.family7.tv.engage.ContinueWatching
import nl.family7.tv.engage.WatchProgress
import nl.family7.core.data.EpisodeItem
import nl.family7.core.data.displayLabel
import nl.family7.core.data.Family7VideoRepository
import nl.family7.core.data.ProgramDetail
import nl.family7.tv.ui.components.TVButton
import nl.family7.tv.ui.player.KeepScreenOn
import nl.family7.tv.ui.player.PlaybackRecovery
import nl.family7.tv.ui.player.PauseOnBackground
import nl.family7.tv.ui.player.buildPlayer
import nl.family7.tv.ui.player.buildPlayerView
import nl.family7.tv.ui.player.ensureRemoteControlFocus
import nl.family7.tv.ui.player.handleRemoteKey
import nl.family7.tv.ui.player.rememberMediaSession
import nl.family7.tv.ui.player.setPlayerLabels

@Composable
fun PlayerScreen(
    episode: EpisodeItem,
    program: ProgramDetail,
    videoRepo: Family7VideoRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var autoRetries by remember { mutableIntStateOf(0) }
    val continueWatching = remember { ContinueWatching.get(context) }
    // Verder waar de kijker de vorige keer stopte (ook vanuit "Verder kijken" op Google TV).
    var resumeAtMs by remember(episode.videoSlug) {
        mutableLongStateOf(continueWatching.resumePositionMs(episode.videoSlug))
    }
    val currentOnBack by rememberUpdatedState(onBack)
    val playerFocus = remember { FocusRequester() }

    val exoPlayer = remember { buildPlayer(context) }
    val playerView = remember {
        buildPlayerView(
            context = context,
            layoutRes = R.layout.family7_player_view,
            player = exoPlayer,
            onBack = { currentOnBack() },
            onControllerVisibilityChanged = {}
        )
    }

    // Systeem-mediabediening: "Now playing", de mediabalk en de mediatoetsen.
    rememberMediaSession("family7-ondemand", exoPlayer)
    KeepScreenOn(enabled = isPlaying)
    PauseOnBackground(exoPlayer)

    // De stand vastleggen: elke 15 seconden tijdens het kijken, bij pauze en bij weggaan.
    fun saveProgress() {
        val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = exoPlayer.currentPosition.coerceIn(0L, duration)
        val season = program.seasons.firstOrNull { s -> s.episodes.any { it.videoSlug == episode.videoSlug } }
        val all = program.seasons.flatMap { s -> s.episodes.map { s to it } }
        val index = all.indexOfFirst { it.second.videoSlug == episode.videoSlug }
        val next = all.getOrNull(index + 1)?.takeIf { index >= 0 }
        val now = System.currentTimeMillis()
        continueWatching.record(
            WatchProgress(
                videoSlug = episode.videoSlug,
                programSlug = program.slug,
                programTitle = program.title,
                episodeTitle = episode.title,
                episodeNumber = episode.episodeNumber,
                seasonNumber = season?.seasonNumber.orEmpty(),
                imageUrl = episode.thumbnailUrl.ifEmpty { program.posterUrl },
                positionMs = position,
                durationMs = duration,
                updatedAtMs = now
            ),
            next = next?.let { (nextSeason, nextEpisode) ->
                WatchProgress(
                    videoSlug = nextEpisode.videoSlug,
                    programSlug = program.slug,
                    programTitle = program.title,
                    episodeTitle = nextEpisode.title,
                    episodeNumber = nextEpisode.episodeNumber,
                    seasonNumber = nextSeason.seasonNumber,
                    imageUrl = nextEpisode.thumbnailUrl.ifEmpty { program.posterUrl },
                    updatedAtMs = now
                )
            }
        )
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            delay(15_000)
            saveProgress()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            saveProgress()
            playerView.player = null
            exoPlayer.release()
        }
    }

    LaunchedEffect(playerView, program.title, episode.title) {
        playerView.setPlayerLabels(
            title = program.title,
            subtitle = episode.displayLabel()
        )
        playerView.ensureRemoteControlFocus()
    }

    LaunchedEffect(episode.videoSlug, reloadKey) {
        errorMessage = null
        if (autoRetries > 0) delay(PlaybackRecovery.BACKOFF_MS * autoRetries)
        videoRepo.resolveEpisodeStreamUrl(episode.videoSlug)
            .onSuccess { streamUrl ->
                if (streamUrl.isEmpty()) {
                    errorMessage = "Geen stream URL gevonden."
                    return@onSuccess
                }
                exoPlayer.setMediaItem(
                    MediaItem.Builder()
                        .setUri(streamUrl)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(episode.displayLabel())
                                .setArtist(program.title)
                                .setDescription(episode.description.ifEmpty { program.description })
                                .setArtworkUri(
                                    episode.thumbnailUrl.ifEmpty { program.posterUrl }
                                        .takeIf { it.isNotEmpty() }?.let(Uri::parse)
                                )
                                .build()
                        )
                        .build(),
                    resumeAtMs
                )
                exoPlayer.prepare()
                exoPlayer.play()
            }
            .onFailure { errorMessage = it.message }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) saveProgress()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) autoRetries = 0
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Eerst zelf opnieuw (verse stream-URL, zelfde plek), pas daarna een melding.
                resumeAtMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                if (autoRetries < PlaybackRecovery.MAX_AUTO_RETRIES) {
                    autoRetries++
                    reloadKey++
                    return
                }
                errorMessage = PlaybackRecovery.describe(error)
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(playerFocus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                playerView.handleRemoteKey(event.key.keyCode, allowSeek = true)
            }
    ) {
        AndroidView(
            factory = { playerView },
            update = { it.ensureRemoteControlFocus() },
            modifier = Modifier.fillMaxSize()
        )

        if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = errorMessage!!, color = Color.White, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TVButton(text = "OPNIEUW PROBEREN", onClick = { autoRetries = 0; reloadKey++ })
                        TVButton(text = "TERUG", onClick = onBack, isPrimary = false)
                    }
                }
            }
        }
    }
}
