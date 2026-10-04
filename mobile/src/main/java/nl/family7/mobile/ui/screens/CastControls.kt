package nl.family7.mobile.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import nl.family7.brand.R
import nl.family7.mobile.cast.CastButton
import nl.family7.mobile.playback.PlaybackManager
import nl.family7.mobile.playback.PlaybackState
import nl.family7.mobile.ui.components.RemoteImage
import nl.family7.mobile.ui.theme.DarkSurfaceVariant
import nl.family7.mobile.ui.theme.Family7Blue
import nl.family7.mobile.ui.theme.Family7BlueDark
import nl.family7.mobile.ui.theme.Family7Red
import nl.family7.mobile.ui.theme.TextSecondary

/** Positie en duur van wat er speelt, twee keer per seconde bijgewerkt. */
private class Progress(val positionMs: Long, val durationMs: Long) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

@Composable
private fun rememberProgress(player: Player?): Progress {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (player != null) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
            delay(500)
        }
    }
    return Progress(position, duration)
}

private fun timeText(ms: Long): String {
    val total = ms / 1000
    val hours = total / 3600
    val minutes = total / 60 % 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/**
 * De afstandsbediening tijdens het casten, in Family7-stijl: omslag, tijdbalk
 * (of LIVE met "naar live"), afspelen, spoelen, vorige en volgende aflevering,
 * het volume van de tv, en terug naar de telefoon. Speelt er nog niets, dan is
 * dit het "klaar om te casten"-scherm.
 */
@Composable
fun CastController(
    state: PlaybackState,
    player: Player?,
    playback: PlaybackManager,
    onBack: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Family7BlueDark)
    ) {
        // Achtergrond: de omslag, vervaagd, onder een Family7-blauw verloop.
        if (state.artworkUrl.isNotBlank()) {
            RemoteImage(
                state.artworkUrl,
                null,
                Modifier
                    .fillMaxSize()
                    .blur(40.dp)
                    .alpha(0.45f)
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Family7Blue.copy(alpha = 0.55f),
                        0.55f to Family7BlueDark.copy(alpha = 0.9f),
                        1f to Family7BlueDark
                    )
                )
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Sluiten")
                }
                Spacer(Modifier.weight(1f))
                DevicePill(state.castDeviceName)
                Spacer(Modifier.weight(1f))
                CastButton()
            }

            if (state.request == null) {
                ReadyToCast(state.castDeviceName, onChoose = onBack, onStop = playback::stopCasting)
            } else {
                NowPlaying(state, player, playback)
            }
        }
    }
}

@Composable
private fun DevicePill(device: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(Icons.Filled.CastConnected, contentDescription = null, tint = Family7Red, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            device ?: "Chromecast",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Verbonden met de tv, maar er speelt nog niets. */
@Composable
private fun ReadyToCast(device: String?, onChoose: () -> Unit, onStop: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.family7_mark),
            contentDescription = "Family7",
            modifier = Modifier
                .width(140.dp)
                .aspectRatio(774f / 689f)
        )
        Spacer(Modifier.height(28.dp))
        Text("Klaar om te casten", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Verbonden met ${device ?: "uw tv"}. Kies een programma of live tv; het speelt meteen op de tv.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        androidx.compose.material3.Button(
            onClick = onChoose,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Family7Red)
        ) { Text("Programma kiezen") }
        TextButton(onClick = onStop) { Text("Verbinding met de tv verbreken") }
    }
}

@Composable
private fun NowPlaying(state: PlaybackState, player: Player?, playback: PlaybackManager) {
    val progress = rememberProgress(player)
    var dragging by remember { mutableStateOf<Float?>(null) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .shadow(16.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(DarkSurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            RemoteImage(state.artworkUrl, null, Modifier.fillMaxSize())
            if (state.isLoading) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            if (state.isLive) {
                Text(
                    "LIVE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Family7Red)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            state.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (state.subtitle.isNotBlank()) {
            Text(
                state.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(16.dp))
        if (state.isLive) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Family7Red))
                Spacer(Modifier.width(8.dp))
                Text("Live uitzending", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = playback::goLive) { Text("Naar live") }
            }
        } else {
            val shown = dragging ?: progress.fraction
            Slider(
                value = shown,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { playback.seekTo((it * progress.durationMs).toLong()) }
                    dragging = null
                },
                enabled = progress.durationMs > 0,
                colors = SliderDefaults.colors(thumbColor = Family7Red, activeTrackColor = Family7Red),
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .semantics { contentDescription = "Tijdbalk" }
            )
            Row(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Text(timeText((shown * progress.durationMs).toLong()), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(Modifier.weight(1f))
                Text(if (progress.durationMs > 0) timeText(progress.durationMs) else "--:--", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = playback::playPrevious, enabled = state.hasPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Vorige aflevering", modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = { playback.skip(-PlaybackManager.SEEK_BACK_MS) }, enabled = !state.isLive) {
                Icon(Icons.Filled.Replay10, contentDescription = "10 seconden terug", modifier = Modifier.size(32.dp))
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Family7Red)
                    .clickable(onClick = playback::togglePlayPause)
                    .semantics { contentDescription = if (state.isPlaying) "Pauzeren" else "Afspelen" }
            ) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
            IconButton(onClick = { playback.skip(PlaybackManager.SEEK_FORWARD_MS) }, enabled = !state.isLive) {
                Icon(Icons.Filled.Forward30, contentDescription = "30 seconden vooruit", modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = playback::playNext, enabled = state.hasNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Volgende aflevering", modifier = Modifier.size(30.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        VolumeRow(state, playback)

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = playback::stopCasting) {
                Icon(Icons.Filled.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Op telefoon kijken")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun VolumeRow(state: PlaybackState, playback: PlaybackManager) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
    ) {
        IconButton(onClick = playback::toggleCastMute) {
            Icon(
                if (state.castMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (state.castMuted) "Geluid van de tv aan" else "Geluid van de tv uit",
                tint = TextSecondary
            )
        }
        Slider(
            value = dragging ?: state.castVolume,
            onValueChange = { dragging = it; playback.setCastVolume(it) },
            onValueChangeFinished = { dragging = null },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Volume van de tv" }
        )
    }
}

/**
 * Balk onderin tijdens het casten, zodat de bediening binnen handbereik blijft
 * terwijl de kijker verder bladert: voortgang, afspelen/pauzeren en stoppen.
 * Speelt er nog niets, dan meldt hij dat de tv klaarstaat.
 */
@Composable
fun MiniCastBar(
    state: PlaybackState,
    player: Player?,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit
) {
    val progress = rememberProgress(player)
    Column(
        Modifier
            .fillMaxWidth()
            .background(DarkSurfaceVariant)
            .clickable(onClick = onOpen)
    ) {
        if (state.request != null && !state.isLive) {
            LinearProgressIndicator(
                progress = { progress.fraction },
                color = Family7Red,
                trackColor = Color.White.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth().height(2.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (state.request != null) {
                RemoteImage(
                    state.artworkUrl,
                    null,
                    Modifier
                        .size(width = 64.dp, height = 36.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.family7_mark),
                    contentDescription = null,
                    modifier = Modifier.size(width = 40.dp, height = 36.dp)
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    if (state.request != null) state.title else "Klaar om te casten",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Cast, contentDescription = null, tint = Family7Red, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        state.castDeviceName ?: "Chromecast",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1
                    )
                }
            }
            if (state.request != null) {
                IconButton(onClick = onTogglePlay) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pauzeren" else "Afspelen"
                    )
                }
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Filled.Close, contentDescription = "Stoppen met casten", tint = TextSecondary)
            }
        }
    }
}
