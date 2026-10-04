package nl.family7.mobile.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.family7.core.data.EpisodeItem
import nl.family7.core.data.Family7LiveRepository
import nl.family7.core.data.Family7VideoRepository
import nl.family7.core.data.NetworkMonitor
import nl.family7.core.data.ProgramDetail
import nl.family7.core.data.displayLabel
import nl.family7.mobile.MainActivity
import nl.family7.mobile.cast.CastAvailability
import nl.family7.mobile.cast.Family7MediaItemConverter
import nl.family7.mobile.ui.friendlyError

/** Wat er afgespeeld moet worden; het stream-adres wordt pas bij het afspelen opgezocht. */
sealed interface PlayRequest {
    data class Episode(val episode: EpisodeItem, val program: ProgramDetail) : PlayRequest
    data object Live : PlayRequest
}

data class PlaybackState(
    val request: PlayRequest? = null,
    val title: String = "",
    val subtitle: String = "",
    val artworkUrl: String = "",
    val isLive: Boolean = false,
    /** Het stream-adres wordt opgezocht of de speler laadt nog. */
    val isLoading: Boolean = false,
    val isPlaying: Boolean = false,
    val error: String? = null,
    /**
     * Google Cast werkt op dit toestel (Play-services aanwezig). De Cast-knop
     * staat er dan altijd; hij zoekt zelf naar een Chromecast of Google TV.
     */
    val castAvailable: Boolean = false,
    val isCasting: Boolean = false,
    val castDeviceName: String? = null,
    /** Volume van de tv (0..1) en of hij gedempt is, tijdens het casten. */
    val castVolume: Float = 0.5f,
    val castMuted: Boolean = false,
    /** Of er een vorige of volgende aflevering is in dit programma. */
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false
)

/**
 * Speelt af op de telefoon of op een Chromecast, en wisselt daartussen zonder
 * dat de kijker zijn plek kwijtraakt.
 *
 * Leeft zo lang als de app, niet zo lang als een scherm: een cast-sessie loopt
 * door terwijl de gebruiker verder bladert, en draaien of beeld-in-beeld
 * onderbreekt de weergave niet.
 *
 * Robuust laden:
 * - een mislukte weergave haalt een vers stream-adres op (het token van
 *   Streampartner verloopt) en gaat verder waar hij was, met oplopende pauzes;
 * - live tv die achter het live-venster raakt, springt terug naar de live-rand;
 * - een mislukking door een wegvallend netwerk wordt vanzelf hervat zodra de
 *   verbinding terug is;
 * - een Chromecast die de stream weigert, krijgt eerst een vers adres; lukt dat
 *   nog steeds niet, dan zegt de app dat eerlijk en biedt hij aan op de
 *   telefoon verder te kijken.
 */
@OptIn(UnstableApi::class)
class PlaybackManager(
    context: Context,
    private val videoRepo: Family7VideoRepository,
    private val liveRepo: Family7LiveRepository,
    network: NetworkMonitor,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** De speler die nu de weergave doet: lokaal of de Chromecast. */
    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player.asStateFlow()

    private var localPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var castPlayer: CastPlayer? = null
    private var castContext: CastContext? = null

    private var currentItem: MediaItem? = null

    /** Of het spelerscherm in beeld is; bepaalt wat er gebeurt als het casten stopt. */
    var playerScreenVisible = false
    private var loadJob: Job? = null
    private var recoveryAttempts = 0

    init {
        CastAvailability.init(appContext, ::attachCast)

        scope.launch {
            network.online.collect { online ->
                val current = _state.value
                if (online && current.error != null && current.request != null && !current.isLoading) {
                    retry()
                }
            }
        }
    }

    // ------------------------------------------------------------ afspelen

    fun play(request: PlayRequest) {
        recoveryAttempts = 0
        val (previous, next) = neighbours(request)
        _state.update {
            it.copy(
                request = request,
                hasPrevious = previous != null,
                hasNext = next != null,
                title = initialTitle(request),
                subtitle = initialSubtitle(request),
                artworkUrl = initialArtwork(request),
                isLive = request is PlayRequest.Live,
                isLoading = true,
                isPlaying = false,
                error = null
            )
        }
        load(request, forceFresh = false, startPositionMs = C.TIME_UNSET)
    }

    /** Opnieuw proberen na een fout, met een vers stream-adres, vanaf dezelfde plek. */
    fun retry() {
        val request = _state.value.request ?: return
        recoveryAttempts = 0
        _state.update { it.copy(error = null, isLoading = true) }
        load(request, forceFresh = true, startPositionMs = resumePosition())
    }

    /** Stopt de weergave op de telefoon; een cast-sessie loopt gewoon door. */
    fun stopLocal() {
        if (_state.value.isCasting) return
        loadJob?.cancel()
        currentItem = null
        releaseLocal()
        _player.value = null
        _state.update { PlaybackState(castAvailable = it.castAvailable) }
    }

    fun pause() {
        _player.value?.pause()
    }

    fun togglePlayPause() {
        val player = _player.value ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    /** Beëindigt het casten; de weergave gaat (gepauzeerd) verder op de telefoon. */
    fun stopCasting() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    // ----------------------------------------------------- bediening

    fun seekTo(positionMs: Long) {
        _player.value?.seekTo(positionMs.coerceAtLeast(0))
    }

    fun skip(deltaMs: Long) {
        val player = _player.value ?: return
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0)
        val duration = player.duration
        player.seekTo(if (duration > 0) target.coerceAtMost(duration - 1_000) else target)
    }

    /**
     * Terug naar de live-rand. Op de tv via de Cast-opdracht "zoek naar
     * oneindig" (de standaard voor live-streams), op de telefoon via de speler.
     */
    fun goLive() {
        val client = castContext?.sessionManager?.currentCastSession?.remoteMediaClient
        if (_state.value.isCasting && client != null) {
            client.seek(MediaSeekOptions.Builder().setIsSeekToInfinite(true).build())
        } else {
            _player.value?.seekToDefaultPosition()
        }
    }

    /** Volume van de tv, van 0 tot 1. */
    fun setCastVolume(volume: Float) {
        val session = castContext?.sessionManager?.currentCastSession ?: return
        runCatching { session.volume = volume.coerceIn(0f, 1f).toDouble() }
        _state.update { it.copy(castVolume = volume.coerceIn(0f, 1f), castMuted = false) }
    }

    /** Een stapje harder of zachter, voor de volumeknoppen van de telefoon. */
    fun adjustCastVolume(up: Boolean): Boolean {
        if (!_state.value.isCasting) return false
        setCastVolume(_state.value.castVolume + if (up) VOLUME_STEP else -VOLUME_STEP)
        return true
    }

    fun toggleCastMute() {
        val session = castContext?.sessionManager?.currentCastSession ?: return
        val muted = !_state.value.castMuted
        runCatching { session.isMute = muted }
        _state.update { it.copy(castMuted = muted) }
    }

    fun playNext() {
        neighbours(_state.value.request ?: return).second?.let(::play)
    }

    fun playPrevious() {
        neighbours(_state.value.request ?: return).first?.let(::play)
    }

    /**
     * De vorige en volgende aflevering, in kijkvolgorde: binnen het seizoen op
     * nummer, en aan het eind door naar het volgende seizoen.
     */
    private fun neighbours(request: PlayRequest): Pair<PlayRequest?, PlayRequest?> {
        if (request !is PlayRequest.Episode) return null to null
        val ordered = request.program.seasons
            .sortedBy { it.seasonNumber.toIntOrNull() ?: Int.MAX_VALUE }
            .flatMap { it.episodes }
        val index = ordered.indexOfFirst { it.videoSlug == request.episode.videoSlug }
        if (index < 0) return null to null
        fun at(i: Int) = ordered.getOrNull(i)?.let { PlayRequest.Episode(it, request.program) }
        return at(index - 1) to at(index + 1)
    }

    private fun load(request: PlayRequest, forceFresh: Boolean, startPositionMs: Long) {
        loadJob?.cancel()
        loadJob = scope.launch {
            resolve(request, forceFresh)
                .onSuccess { item ->
                    currentItem = item
                    val player = activePlayer()
                    if (startPositionMs == C.TIME_UNSET || isLive(item)) {
                        player.setMediaItem(item, true)
                    } else {
                        player.setMediaItem(item, startPositionMs)
                    }
                    player.prepare()
                    player.play()
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _state.update { it.copy(isLoading = false, error = friendlyError(error)) }
                }
        }
    }

    /**
     * Zoekt het stream-adres op. Een verbindingsfout krijgt één herkansing na
     * een korte pauze; de HTTP-laag zelf probeert het al een paar keer.
     */
    private suspend fun resolve(request: PlayRequest, forceFresh: Boolean): Result<MediaItem> {
        var result = resolveOnce(request, forceFresh)
        if (result.isFailure && result.exceptionOrNull() !is CancellationException) {
            delay(RESOLVE_RETRY_DELAY_MS)
            result = resolveOnce(request, forceFresh = true)
        }
        return result
    }

    private suspend fun resolveOnce(request: PlayRequest, forceFresh: Boolean): Result<MediaItem> =
        when (request) {
            is PlayRequest.Episode -> videoRepo
                .resolveEpisodeStreamUrl(request.episode.videoSlug, forceFresh)
                .mapCatching { url ->
                    if (url.isBlank()) error("Geen afspeelbare videobron gevonden voor deze aflevering.")
                    episodeItem(request, url)
                }

            PlayRequest.Live -> liveRepo.getLiveInfo(force = forceFresh).mapCatching { info ->
                if (info.streamUrl.isBlank()) error("De livestream van Family7 is nu niet te vinden.")
                _state.update {
                    it.copy(
                        title = info.currentProgram.ifBlank { "Family7 Live" },
                        subtitle = listOf(info.timeRange, "Live").filter(String::isNotBlank).joinToString(" · "),
                        artworkUrl = info.imageUrl.ifBlank { it.artworkUrl }
                    )
                }
                MediaItem.Builder()
                    .setMediaId("${Family7MediaItemConverter.LIVE_ID_PREFIX}-${System.currentTimeMillis()}")
                    .setUri(info.streamUrl)
                    .setMimeType(Family7MediaItemConverter.guessMimeType(info.streamUrl))
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder().setMaxPlaybackSpeed(1.02f).build()
                    )
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(info.currentProgram.ifBlank { "Family7 Live" })
                            .setArtist("Family7 Live TV")
                            .setDescription(info.description)
                            .setArtworkUri(info.imageUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
                            .build()
                    )
                    .build()
            }
        }

    private fun episodeItem(request: PlayRequest.Episode, url: String): MediaItem {
        val episode = request.episode
        val program = request.program
        return MediaItem.Builder()
            .setMediaId(episode.videoSlug)
            .setUri(url)
            .setMimeType(Family7MediaItemConverter.guessMimeType(url))
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
            .build()
    }

    // ------------------------------------------------------------ herstel

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Weer aan het spelen: eerdere herkansingen tellen niet meer mee.
            if (isPlaying) recoveryAttempts = 0
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> _state.update { it.copy(isLoading = true) }
                Player.STATE_READY, Player.STATE_ENDED -> _state.update { it.copy(isLoading = false, error = null) }
                else -> _state.update { it.copy(isLoading = false) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            recover(error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, error)
        }
    }

    private fun recover(behindLiveWindow: Boolean, error: Throwable?) {
        val player = _player.value ?: return
        val request = _state.value.request ?: return

        if (behindLiveWindow) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }

        if (recoveryAttempts < MAX_RECOVERY_ATTEMPTS) {
            recoveryAttempts++
            val position = resumePosition()
            _state.update { it.copy(isLoading = true, error = null) }
            loadJob?.cancel()
            loadJob = scope.launch {
                delay(RECOVERY_BACKOFF_MS * recoveryAttempts)
                load(request, forceFresh = true, startPositionMs = position)
            }
            return
        }

        val device = _state.value.castDeviceName
        val message = if (_state.value.isCasting) {
            "Dit programma speelt niet af op ${device ?: "de Chromecast"}. " +
                "U kunt het opnieuw proberen of op deze telefoon verder kijken."
        } else {
            friendlyError(error)
        }
        _state.update { it.copy(isLoading = false, isPlaying = false, error = message) }
    }

    private fun resumePosition(): Long {
        val player = _player.value ?: return C.TIME_UNSET
        if (_state.value.isLive || player.currentMediaItem == null) return C.TIME_UNSET
        return player.currentPosition.takeIf { it > 0 } ?: C.TIME_UNSET
    }

    private fun isLive(item: MediaItem) =
        item.mediaId.startsWith(Family7MediaItemConverter.LIVE_ID_PREFIX)

    // ---------------------------------------------------------- de spelers

    private fun activePlayer(): Player {
        val cast = castPlayer
        val player = if (cast != null && cast.isCastSessionAvailable) cast else ensureLocal()
        if (_player.value !== player) _player.value = player
        return player
    }

    private fun ensureLocal(): ExoPlayer {
        localPlayer?.let { return it }

        // Meer herkansingen per segment dan standaard: op mobiel internet valt
        // een enkel verzoek vaker weg dan op de wifi van een tv.
        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(SEGMENT_RETRIES))

        val player = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // Oortjes eruit: pauzeren in plaats van ineens door de luidspreker.
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener)

        val openApp = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Systeem-mediabediening: de mediabalk, de vergrendelscherm-bediening
        // en de knoppen van een koptelefoon.
        mediaSession = runCatching {
            MediaSession.Builder(appContext, player)
                .setId("family7-mobile")
                .setSessionActivity(openApp)
                .build()
        }.getOrNull()

        localPlayer = player
        return player
    }

    private fun releaseLocal() {
        mediaSession?.release()
        mediaSession = null
        localPlayer?.removeListener(playerListener)
        localPlayer?.release()
        localPlayer = null
    }

    // --------------------------------------------------------------- cast

    private fun attachCast(context: CastContext) {
        castContext = context
        val cast = CastPlayer(context, Family7MediaItemConverter(), SEEK_BACK_MS, SEEK_FORWARD_MS)
        cast.addListener(playerListener)
        cast.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() = switchTo(cast, toCast = true)
            override fun onCastSessionUnavailable() = switchTo(ensureLocal(), toCast = false)
        })
        castPlayer = cast

        // De Cast-knop hoort er altijd te staan zodra Cast op dit toestel werkt:
        // Android zoekt pas naar Chromecasts als er een Cast-knop in beeld is.
        // Wachten tot er al een apparaat gevonden is, betekent dus nooit een knop.
        _state.update { it.copy(castAvailable = true) }
        context.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)

        // Een sessie die al liep (de app werd herstart tijdens het casten).
        context.sessionManager.currentCastSession?.let { session ->
            watchRemote(session)
            if (cast.isCastSessionAvailable) switchTo(cast, toCast = true)
        }
    }

    /**
     * Zet de weergave over naar de andere speler, op dezelfde plek in de
     * aflevering. Naar de tv gaat hij meteen spelen; terug naar de telefoon
     * staat hij op pauze, zodat er niet ineens geluid uit een broekzak komt.
     */
    private fun switchTo(target: Player, toCast: Boolean) {
        val previous = _player.value
        val device = castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName

        _state.update {
            it.copy(
                isCasting = toCast,
                castDeviceName = if (toCast) device else null,
                error = null
            )
        }
        if (previous === target) return

        val item = currentItem
        var position = C.TIME_UNSET
        if (previous != null) {
            if (previous.playbackState != Player.STATE_ENDED) position = previous.currentPosition
            previous.stop()
            previous.clearMediaItems()
        }
        // Tijdens het casten is de lokale speler niet nodig; dat scheelt
        // geheugen en accu.
        if (toCast) releaseLocal()

        recoveryAttempts = 0

        // Het casten stopt terwijl de kijker ergens anders in de app is: niet
        // stilletjes een speler op de achtergrond klaarzetten.
        if (!toCast && (item == null || !playerScreenVisible)) {
            currentItem = null
            releaseLocal()
            _player.value = null
            _state.update { PlaybackState(castAvailable = it.castAvailable) }
            return
        }

        _player.value = target
        if (item == null) return

        if (isLive(item) || position == C.TIME_UNSET) target.setMediaItem(item, true)
        else target.setMediaItem(item, position)
        target.playWhenReady = toCast
        target.prepare()
    }

    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val status = castContext?.sessionManager?.currentCastSession?.remoteMediaClient?.mediaStatus ?: return
            if (status.playerState == MediaStatus.PLAYER_STATE_IDLE &&
                status.idleReason == MediaStatus.IDLE_REASON_ERROR &&
                _state.value.isCasting
            ) {
                recover(behindLiveWindow = false, error = null)
            }
        }
    }

    private fun watchRemote(session: CastSession) {
        session.remoteMediaClient?.unregisterCallback(remoteCallback)
        session.remoteMediaClient?.registerCallback(remoteCallback)
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = onConnected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onConnected(session)
        override fun onSessionEnded(session: CastSession, error: Int) {
            session.remoteMediaClient?.unregisterCallback(remoteCallback)
        }
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            _state.update { it.copy(error = if (it.request != null) it.error else null) }
        }
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit

        private fun onConnected(session: CastSession) {
            watchRemote(session)
            session.removeCastListener(volumeListener)
            session.addCastListener(volumeListener)
            readVolume(session)
            _state.update { it.copy(castDeviceName = session.castDevice?.friendlyName) }
        }
    }

    /** Volume van de tv bijhouden, ook als het op de tv zelf verandert. */
    private val volumeListener = object : Cast.Listener() {
        override fun onVolumeChanged() {
            castContext?.sessionManager?.currentCastSession?.let(::readVolume)
        }
    }

    private fun readVolume(session: CastSession) {
        runCatching {
            _state.update { it.copy(castVolume = session.volume.toFloat(), castMuted = session.isMute) }
        }
    }

    // ------------------------------------------------------------ teksten

    private fun initialTitle(request: PlayRequest) = when (request) {
        is PlayRequest.Episode -> request.program.title
        PlayRequest.Live -> "Family7 Live"
    }

    private fun initialSubtitle(request: PlayRequest) = when (request) {
        is PlayRequest.Episode -> request.episode.displayLabel()
        PlayRequest.Live -> "Live"
    }

    private fun initialArtwork(request: PlayRequest) = when (request) {
        is PlayRequest.Episode -> request.episode.thumbnailUrl.ifEmpty { request.program.posterUrl }
        PlayRequest.Live -> ""
    }

    companion object {
        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L

        private const val MAX_RECOVERY_ATTEMPTS = 2
        private const val RECOVERY_BACKOFF_MS = 1_000L
        private const val RESOLVE_RETRY_DELAY_MS = 1_500L
        private const val SEGMENT_RETRIES = 6
        private const val VOLUME_STEP = 0.05f
    }
}
