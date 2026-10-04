package nl.family7.tv.ui.player

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import nl.family7.tv.R

// Toetscodes zoals Compose ze aanlevert (KeyEvent.KEYCODE_* verschoven met 32 bits).
private const val KEY_CENTER = 23L shl 32
private const val KEY_ENTER = 66L shl 32
private const val KEY_NUMPAD_ENTER = 160L shl 32
private const val KEY_SPACE = 62L shl 32
private const val KEY_LEFT = 21L shl 32
private const val KEY_RIGHT = 22L shl 32
private const val KEY_UP = 19L shl 32
private const val KEY_DOWN = 20L shl 32
private const val KEY_MEDIA_PLAY_PAUSE = 85L shl 32
private const val KEY_MEDIA_PLAY = 126L shl 32
private const val KEY_MEDIA_PAUSE = 127L shl 32
private const val KEY_MEDIA_REWIND = 89L shl 32
private const val KEY_MEDIA_FAST_FORWARD = 90L shl 32

const val SEEK_BACK_MS = 10_000L
const val SEEK_FORWARD_MS = 30_000L

/**
 * ExoPlayer met de spoelstappen die de mediabediening en de afstandsbediening
 * gebruiken (de knoppen tonen deze waarden ook).
 */
fun buildPlayer(context: Context): ExoPlayer =
    ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(SEEK_BACK_MS)
        .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
        .build()
        .apply { playWhenReady = true }

/**
 * De officiele Media3 [PlayerView], met een eigen bedieningslayout op
 * 10-voet-formaat (zie res/layout/family7_*_controls.xml). PlayerControlView
 * blijft alle logica afhandelen: verborgen bediening komt in beeld bij de
 * eerste druk op OK of een richtingstoets, OK speelt en pauzeert, de bediening
 * verdwijnt na een paar seconden, en de mediatoetsen werken zoals gebruikelijk.
 *
 * @param layoutRes [R.layout.family7_player_view] of [R.layout.family7_live_player_view]
 */
@OptIn(UnstableApi::class)
fun buildPlayerView(
    context: Context,
    @LayoutRes layoutRes: Int,
    player: Player,
    onBack: () -> Unit,
    onControllerVisibilityChanged: (Boolean) -> Unit
): PlayerView {
    val view = LayoutInflater.from(context).inflate(layoutRes, null, false) as PlayerView
    view.layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    view.setPlayer(player)
    view.findViewById<ImageButton>(R.id.family7_back)?.setOnClickListener { onBack() }

    val playPause = view.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
    view.setControllerVisibilityListener(
        PlayerView.ControllerVisibilityListener { visibility ->
            val visible = visibility == View.VISIBLE
            // Bij het verschijnen meteen focus op afspelen/pauzeren, zodat de
            // D-pad direct bruikbaar is zonder eerst te moeten zoeken.
            if (visible) playPause?.requestFocus()
            onControllerVisibilityChanged(visible)
        }
    )
    return view
}

/** Vult de titelvelden in de bedieningslayout. */
fun PlayerView.setPlayerLabels(title: String, subtitle: String, liveTime: String? = null) {
    findViewById<TextView>(R.id.family7_title)?.text = title
    findViewById<TextView>(R.id.family7_subtitle)?.apply {
        text = subtitle
        visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
    }
    findViewById<TextView>(R.id.family7_live_time)?.apply {
        text = liveTime.orEmpty()
        visibility = if (liveTime.isNullOrBlank()) View.GONE else View.VISIBLE
    }
}

/**
 * Koppelt een [MediaSession] aan de speler, zodat de systeem-mediabediening
 * ("Now playing", de mediabalk en de mediatoetsen van de afstandsbediening)
 * de weergave bestuurt en de juiste titel en omslag toont.
 */
@Composable
fun rememberMediaSession(sessionId: String, player: Player) {
    val view = LocalView.current
    DisposableEffect(sessionId, player) {
        val session = MediaSession.Builder(view.context.applicationContext, player)
            .setId(sessionId)
            .build()
        onDispose { session.release() }
    }
}

/** Houdt het scherm aan zolang er afgespeeld wordt. */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

/** Pauzeert bij het verlaten van de voorgrond en hervat bij terugkeer. */
@Composable
fun PauseOnBackground(player: Player) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        var resumeOnStart = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    resumeOnStart = player.playWhenReady
                    player.pause()
                }
                Lifecycle.Event.ON_START -> if (resumeOnStart) player.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Vertaalt een druk op de afstandsbediening naar de standaardbediening van een
 * TV-speler, met de bediening van Media3 als beeld.
 *
 * De speler zit als Android-view in een Compose-scherm. Compose houdt de focus
 * op het interop-knooppunt vast, waardoor een toets niet doorkomt bij de knop
 * in de bedieningsbalk; daarom wordt de toets hier zelf afgehandeld.
 *
 * Gedrag zoals gebruikelijk op Android TV: is de bediening verborgen, dan haalt
 * de eerste druk hem alleen in beeld. Staat hij in beeld, dan speelt of pauzeert
 * OK, en spoelen links en rechts. De mediatoetsen werken altijd meteen.
 *
 * @return true als de toets is afgehandeld.
 */
@OptIn(UnstableApi::class)
fun PlayerView.handleRemoteKey(key: Long, allowSeek: Boolean): Boolean {
    val player = this.player ?: return false
    val wasVisible = isControllerFullyVisible
    showController()

    fun togglePlayPause() {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0L)
            player.play()
        } else if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    return when (key) {
        KEY_MEDIA_PLAY -> { player.play(); true }
        KEY_MEDIA_PAUSE -> { player.pause(); true }
        KEY_MEDIA_PLAY_PAUSE -> { togglePlayPause(); true }
        KEY_MEDIA_REWIND -> { if (allowSeek) player.seekBack(); true }
        KEY_MEDIA_FAST_FORWARD -> { if (allowSeek) player.seekForward(); true }

        KEY_CENTER, KEY_ENTER, KEY_NUMPAD_ENTER, KEY_SPACE ->
            { if (wasVisible) togglePlayPause(); true }
        KEY_LEFT -> { if (wasVisible && allowSeek) player.seekBack(); true }
        KEY_RIGHT -> { if (wasVisible && allowSeek) player.seekForward(); true }
        KEY_UP, KEY_DOWN -> true

        else -> false
    }
}

/**
 * Zorgt dat de speler de toetsen van de afstandsbediening ontvangt.
 *
 * In een Compose-scherm houdt Compose zelf de focus vast; een [PlayerView] die
 * via AndroidView is ingevoegd krijgt die niet vanzelf, waardoor een druk op OK
 * de bediening niet tevoorschijn haalt. Focus aanvragen kan pas als de view aan
 * het venster hangt, dus dat gebeurt hier vanuit de update-stap.
 */
@OptIn(UnstableApi::class)
fun PlayerView.ensureRemoteControlFocus() {
    if (isAttachedToWindow) {
        if (!hasFocus()) requestFocus()
    } else {
        post { if (!hasFocus()) requestFocus() }
    }
}
