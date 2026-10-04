package nl.family7.mobile.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import nl.family7.mobile.cast.CastButton
import nl.family7.mobile.playback.PlaybackManager
import nl.family7.mobile.playback.PlaybackState
import nl.family7.mobile.ui.components.RemoteImage
import nl.family7.mobile.ui.theme.DarkSurfaceVariant
import nl.family7.mobile.ui.theme.Family7Red
import nl.family7.mobile.ui.theme.TextSecondary

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    playback: PlaybackManager,
    isInPip: Boolean,
    onPipEligibleChanged: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val state by playback.state.collectAsStateWithLifecycle()
    val player by playback.player.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    val currentOnBack by rememberUpdatedState(onBack)

    // Niets meer om af te spelen (bijvoorbeeld na het herstarten van de app).
    LaunchedEffect(state.request) {
        if (state.request == null) currentOnBack()
    }

    // Het scherm sluiten stopt de weergave op de telefoon; draaien niet.
    DisposableEffect(playback) {
        playback.playerScreenVisible = true
        onDispose {
            playback.playerScreenVisible = false
            onPipEligibleChanged(false)
            if (activity?.isChangingConfigurations != true) playback.stopLocal()
        }
    }

    val playingLocally = !state.isCasting
    // Lokaal kijken: liggend en schermvullend. Tijdens het casten is dit een
    // afstandsbediening en mag de telefoon gewoon rechtop.
    DisposableEffect(activity, playingLocally) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (playingLocally) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity?.requestedOrientation = previousOrientation
            insets?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Scherm aan zolang er op de telefoon iets speelt.
    DisposableEffect(view, playingLocally && state.isPlaying) {
        view.keepScreenOn = playingLocally && state.isPlaying
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(playingLocally, state.isPlaying) {
        onPipEligibleChanged(playingLocally && state.isPlaying)
    }

    // Naar de achtergrond (of het beeld-in-beeldvenster gesloten): pauzeren.
    // Een cast-sessie speelt gewoon door op de tv.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, playback) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !playback.state.value.isCasting) playback.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (state.isCasting) {
            CastingView(state = state, player = player, onBack = onBack, onStopCasting = playback::stopCasting)
        } else {
            LocalPlayerView(state = state, player = player, isInPip = isInPip, onBack = onBack)
        }

        if (state.error != null && !isInPip) {
            ErrorOverlay(
                state = state,
                onRetry = playback::retry,
                onPlayOnPhone = playback::stopCasting,
                onBack = onBack
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LocalPlayerView(state: PlaybackState, player: Player?, isInPip: Boolean, onBack: () -> Unit) {
    var controlsVisible by remember { mutableStateOf(true) }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                setShowNextButton(false)
                setShowPreviousButton(false)
                controllerShowTimeoutMs = 4_000
                setControllerVisibilityListener(
                    PlayerView.ControllerVisibilityListener { controlsVisible = it == View.VISIBLE }
                )
                keepScreenOn = false
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            view.useController = !isInPip
            // Live tv: geen spoelknoppen; de stream is altijd "nu".
            view.setShowFastForwardButton(!state.isLive)
            view.setShowRewindButton(!state.isLive)
        },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize()
    )

    // Het stream-adres wordt nog opgezocht; zodra de speler laadt, toont
    // PlayerView zelf zijn buffer-indicator.
    if (player == null || (state.isLoading && player.playbackState == Player.STATE_IDLE)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Family7Red)
        }
    }

    AnimatedVisibility(visible = controlsVisible && !isInPip, enter = fadeIn(), exit = fadeOut()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") }
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.subtitle.isNotBlank()) {
                    Text(state.subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (state.castAvailable) CastButton()
        }
    }
}

/** Tijdens het casten: de omslag, waar het speelt, en de bediening op afstand. */
@OptIn(UnstableApi::class)
@Composable
private fun CastingView(state: PlaybackState, player: Player?, onBack: () -> Unit, onStopCasting: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        RemoteImage(state.artworkUrl, null, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .statusBarsPadding()
                .padding(4.dp)
                .fillMaxWidth()
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug") }
            Spacer(Modifier.weight(1f))
            CastButton()
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp)
        ) {
            Icon(Icons.Filled.CastConnected, contentDescription = null, tint = Family7Red, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            Text(state.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (state.subtitle.isNotBlank()) {
                Text(state.subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Speelt af op ${state.castDeviceName ?: "uw tv"}",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            if (state.isLoading) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(color = Family7Red, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onStopCasting) { Text("Op deze telefoon verder kijken") }
        }

        AndroidView(
            factory = { ctx ->
                PlayerControlView(ctx).apply {
                    showTimeoutMs = 0
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    show()
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
                view.setShowFastForwardButton(!state.isLive)
                view.setShowRewindButton(!state.isLive)
                view.show()
            },
            onRelease = { it.player = null },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(160.dp)
        )
    }
}

@Composable
private fun ErrorOverlay(state: PlaybackState, onRetry: () -> Unit, onPlayOnPhone: () -> Unit, onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(24.dp)
        ) {
            Text(state.error.orEmpty(), textAlign = TextAlign.Center, color = Color.White)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Family7Red)) {
                    Text("Opnieuw proberen")
                }
                if (state.isCasting) {
                    OutlinedButton(onClick = onPlayOnPhone) { Text("Op telefoon") }
                } else {
                    OutlinedButton(onClick = onBack) { Text("Terug") }
                }
            }
        }
    }
}

/**
 * Balk onderin tijdens het casten, zodat de bediening binnen handbereik
 * blijft terwijl de kijker verder bladert. Een tik opent de volledige bediening.
 */
@Composable
fun MiniCastBar(state: PlaybackState, onTogglePlay: () -> Unit, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurfaceVariant)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        RemoteImage(
            state.artworkUrl,
            null,
            Modifier
                .size(width = 64.dp, height = 36.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(state.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Cast, contentDescription = null, tint = Family7Red, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    state.castDeviceName ?: "Casten",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1
                )
            }
        }
        IconButton(onClick = onTogglePlay) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "Pauzeren" else "Afspelen"
            )
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
