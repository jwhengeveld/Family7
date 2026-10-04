package nl.family7.tv.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.media3.common.Player
import nl.family7.tv.R
import nl.family7.core.data.Family7LiveRepository
import nl.family7.core.data.LiveStreamInfo
import nl.family7.tv.ui.components.TVButton
import nl.family7.tv.ui.player.KeepScreenOn
import nl.family7.tv.ui.player.PauseOnBackground
import nl.family7.tv.ui.player.buildPlayer
import nl.family7.tv.ui.player.buildPlayerView
import nl.family7.tv.ui.player.ensureRemoteControlFocus
import nl.family7.tv.ui.player.handleRemoteKey
import nl.family7.tv.ui.player.rememberMediaSession
import nl.family7.tv.ui.player.setPlayerLabels

@Composable
fun LiveTVScreen(
    liveRepo: Family7LiveRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var liveInfo by remember { mutableStateOf<LiveStreamInfo?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val currentOnBack by rememberUpdatedState(onBack)
    val playerFocus = remember { FocusRequester() }

    val exoPlayer = remember { buildPlayer(context) }
    val playerView = remember {
        buildPlayerView(
            context = context,
            layoutRes = R.layout.family7_live_player_view,
            player = exoPlayer,
            onBack = { currentOnBack() },
            onControllerVisibilityChanged = {}
        )
    }

    rememberMediaSession("family7-live", exoPlayer)
    KeepScreenOn(enabled = isPlaying)
    PauseOnBackground(exoPlayer)

    DisposableEffect(Unit) {
        onDispose {
            playerView.player = null
            exoPlayer.release()
        }
    }

    LaunchedEffect(playerView) {
        runCatching { playerFocus.requestFocus() }
        playerView.setPlayerLabels("Family7 Live TV", "")
        playerView.ensureRemoteControlFocus()
    }

    // De programmagids staat in de bedieningslayout, onder de terugknop.
    LaunchedEffect(liveInfo) {
        liveInfo?.let { info ->
            playerView.setPlayerLabels(
                title = info.currentProgram,
                subtitle = info.description,
                liveTime = info.timeRange
            )
        }
    }

    LaunchedEffect(reloadKey) {
        errorMessage = null
        liveRepo.getLiveInfo()
            .onSuccess { info ->
                liveInfo = info
                if (info.streamUrl.isEmpty()) {
                    errorMessage = "Geen live stream URL gevonden."
                    return@onSuccess
                }
                exoPlayer.setMediaItem(
                    MediaItem.Builder()
                        .setUri(info.streamUrl)
                        .setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder()
                                .setMaxPlaybackSpeed(1.02f)
                                .build()
                        )
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(info.currentProgram)
                                .setArtist("Family7 Live TV")
                                .setDescription(info.description)
                                .setArtworkUri(info.imageUrl.takeIf { it.isNotEmpty() }?.let(Uri::parse))
                                .build()
                        )
                        .build()
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
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                errorMessage = "Afspeelfout: ${error.localizedMessage}"
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
                playerView.handleRemoteKey(event.key.keyCode, allowSeek = false)
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
                        TVButton(text = "OPNIEUW PROBEREN", onClick = { reloadKey++ })
                        TVButton(text = "TERUG", onClick = onBack, isPrimary = false)
                    }
                }
            }
        }
    }
}
