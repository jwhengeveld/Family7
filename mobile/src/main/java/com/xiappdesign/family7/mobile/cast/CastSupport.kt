package com.xiappdesign.family7.mobile.cast

import android.content.Context
import android.net.Uri
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import com.xiappdesign.family7.mobile.BuildConfig
import com.xiappdesign.family7.mobile.MainActivity
import com.xiappdesign.family7.mobile.R
import org.json.JSONObject
import com.google.android.gms.cast.MediaMetadata as CastMetadata
import androidx.media3.common.MediaMetadata as Media3Metadata

/**
 * Instellingen voor Google Cast. De Cast-bibliotheek leest deze klasse via de
 * naam in het manifest.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        val notification = NotificationOptions.Builder()
            // Een tik op de melding brengt de gebruiker terug in de app.
            .setTargetActivityClassName(MainActivity::class.java.name)
            .build()
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(notification)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(BuildConfig.CAST_RECEIVER_ID)
            .setCastMediaOptions(media)
            // Na een herstart van de app of een korte wifi-onderbreking pakt de
            // app de lopende cast-sessie weer op in plaats van hem te verliezen.
            .setResumeSavedSession(true)
            .setEnableReconnectionService(true)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Start Google Cast als dat op dit toestel kan. Zonder Google Play-services
 * (bijvoorbeeld een Huawei-telefoon) of met een verouderde versie werkt de app
 * gewoon, alleen zonder Cast-knop; dat mag nooit tot een crash leiden.
 */
object CastAvailability {

    fun init(context: Context, onReady: (CastContext) -> Unit) {
        val playServices = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        }.getOrDefault(ConnectionResult.SERVICE_MISSING)
        if (playServices != ConnectionResult.SUCCESS) return

        runCatching {
            CastContext.getSharedInstance(context, ContextCompat.getMainExecutor(context))
                .addOnSuccessListener { onReady(it) }
        }
    }
}

/**
 * Zet een [MediaItem] om in wat een Cast-ontvanger begrijpt, en terug.
 *
 * Eigen omzetter in plaats van de standaard, om twee redenen: de ontvanger moet
 * weten dat een HLS-stream HLS is (Streampartner zet geen bruikbare extensie in
 * elk adres), en live tv moet als live worden aangemeld, zodat de ontvanger
 * geen tijdbalk met een eindpunt toont en op de live-rand blijft.
 */
class Family7MediaItemConverter : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val uri = mediaItem.localConfiguration?.uri?.toString().orEmpty()
        val metadata = mediaItem.mediaMetadata
        val isLive = mediaItem.liveConfiguration != MediaItem.LiveConfiguration.UNSET ||
            mediaItem.mediaId.startsWith(LIVE_ID_PREFIX)
        val mimeType = mediaItem.localConfiguration?.mimeType ?: guessMimeType(uri)

        val castMetadata = CastMetadata(
            if (isLive) CastMetadata.MEDIA_TYPE_GENERIC else CastMetadata.MEDIA_TYPE_TV_SHOW
        ).apply {
            metadata.title?.let { putString(CastMetadata.KEY_TITLE, it.toString()) }
            metadata.artist?.let { putString(CastMetadata.KEY_SUBTITLE, it.toString()) }
            metadata.artworkUri?.let { addImage(WebImage(it)) }
        }

        val customData = JSONObject()
            .put(KEY_ID, mediaItem.mediaId)
            .put(KEY_URI, uri)
            .put(KEY_MIME, mimeType)
            .put(KEY_TITLE, metadata.title?.toString().orEmpty())
            .put(KEY_SUBTITLE, metadata.artist?.toString().orEmpty())
            .put(KEY_ARTWORK, metadata.artworkUri?.toString().orEmpty())
            .put(KEY_LIVE, isLive)

        val info = MediaInfo.Builder(uri)
            .setContentUrl(uri)
            .setContentType(mimeType)
            .setStreamType(if (isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(castMetadata)
            .setCustomData(customData)
            .build()

        return MediaQueueItem.Builder(info).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val info = mediaQueueItem.media
        val data = info?.customData
        val uri = data?.optString(KEY_URI).orEmpty().ifEmpty { info?.contentUrl ?: info?.contentId.orEmpty() }

        // Een sessie die een andere app startte, kent onze extra gegevens niet;
        // dan wat de ontvanger zelf vertelt.
        val title = data?.optString(KEY_TITLE)?.takeIf { it.isNotEmpty() }
            ?: info?.metadata?.getString(CastMetadata.KEY_TITLE).orEmpty()
        val artwork = data?.optString(KEY_ARTWORK)?.takeIf { it.isNotEmpty() }
            ?: info?.metadata?.images?.firstOrNull()?.url?.toString().orEmpty()

        return MediaItem.Builder()
            .setMediaId(data?.optString(KEY_ID)?.takeIf { it.isNotEmpty() } ?: uri)
            .setUri(uri)
            .setMimeType(data?.optString(KEY_MIME)?.takeIf { it.isNotEmpty() } ?: info?.contentType)
            .setMediaMetadata(
                Media3Metadata.Builder()
                    .setTitle(title)
                    .setArtist(data?.optString(KEY_SUBTITLE).orEmpty())
                    .setArtworkUri(artwork.takeIf { it.isNotEmpty() }?.let(Uri::parse))
                    .build()
            )
            .build()
    }

    companion object {
        /** Het mediaId van live tv begint hiermee, zodat beide kanten live herkennen. */
        const val LIVE_ID_PREFIX = "family7-live"

        private const val KEY_ID = "id"
        private const val KEY_URI = "uri"
        private const val KEY_MIME = "mime"
        private const val KEY_TITLE = "title"
        private const val KEY_SUBTITLE = "subtitle"
        private const val KEY_ARTWORK = "artwork"
        private const val KEY_LIVE = "live"

        fun guessMimeType(uri: String): String {
            val path = uri.substringBefore('?').lowercase()
            return when {
                path.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
                // Alles wat Streampartner levert en geen mp4 is, is HLS.
                else -> MimeTypes.APPLICATION_M3U8
            }
        }
    }
}

/**
 * De standaard Cast-knop. Verschijnt vanzelf zodra er een Chromecast of
 * Google TV in het netwerk is, en opent dan de apparatenkiezer.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            MediaRouteButton(ContextThemeWrapper(context, R.style.Theme_Family7Mobile)).apply {
                runCatching { CastButtonFactory.setUpMediaRouteButton(context.applicationContext, this) }
            }
        },
        modifier = modifier.size(48.dp)
    )
}
