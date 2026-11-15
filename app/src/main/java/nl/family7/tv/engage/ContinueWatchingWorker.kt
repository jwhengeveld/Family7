package nl.family7.tv.engage

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.engage.common.datamodel.AccountProfile
import com.google.android.engage.common.datamodel.ClusterType
import com.google.android.engage.common.datamodel.ContinuationCluster
import com.google.android.engage.common.datamodel.Image
import com.google.android.engage.common.datamodel.PlatformSpecificUri
import com.google.android.engage.common.datamodel.PlatformType
import com.google.android.engage.service.AppEngagePublishClient
import com.google.android.engage.service.DeleteClustersRequest
import com.google.android.engage.service.DeleteReason
import com.google.android.engage.service.PublishContinuationClusterRequest
import com.google.android.engage.service.ServiceAvailabilityRequest
import com.google.android.engage.video.datamodel.TvEpisodeEntity
import com.google.android.engage.video.datamodel.WatchNextType
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import nl.family7.tv.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import kotlin.coroutines.cancellation.CancellationException
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Zet de rij "Verder kijken" op het Google TV-startscherm (Engage SDK). De
 * SDK vervangt bij elke publicatie de hele rij, dus hier gaat steeds de
 * volledige lijst heen. Zonder Engage-dienst op het toestel (of zolang Google
 * de app nog niet heeft toegelaten) doet dit stil niets.
 */
class ContinueWatchingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val client = AppEngagePublishClient(applicationContext)
        val available = runCatching {
            val perType = client.isServiceAvailable(
                ServiceAvailabilityRequest.Builder()
                    .addIntendedClusterType(ClusterType.TYPE_CONTINUATION)
                    .build()
            ).await()
            Log.d(TAG, "isServiceAvailable: $perType")
            perType[ClusterType.TYPE_CONTINUATION] == true ||
                // De verificatieapp schakelt alleen de algemene beschikbaarheid om.
                (BuildConfig.DEBUG && client.isServiceAvailable().await())
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "isServiceAvailable mislukt", it)
        }.getOrDefault(false)
        if (!available) {
            Log.i(TAG, "Engage-dienst niet beschikbaar; niets gepubliceerd.")
            return Result.success()
        }

        inputData.getString(KEY_SIGN_OUT_ACCOUNT)?.let { account ->
            return runCatching {
                client.deleteClusters(
                    DeleteClustersRequest.Builder()
                        .setAccountProfile(profile(account))
                        .setDeleteReason(DeleteReason.DELETE_REASON_USER_LOG_OUT)
                        .setSyncAcrossDevices(SYNC_ACROSS_DEVICES)
                        .build()
                ).await()
                Log.i(TAG, "Verder kijken verwijderd na uitloggen.")
                Result.success()
            }.getOrElse { retryOrFail(it) }
        }

        val store = ContinueWatching.get(applicationContext)
        val account = store.accountId() ?: return Result.success()
        // Kan een beeld niet worden opgehaald (geen netwerk), dan later opnieuw in
        // plaats van een kortere rij te publiceren: dat zou afleveringen wissen.
        val items = try {
            withContext(Dispatchers.IO) { store.all().mapNotNull { entity(it) } }
        } catch (e: IOException) {
            Log.w(TAG, "Beelden niet bereikbaar; later opnieuw ($e)")
            return Result.retry()
        }

        return runCatching {
            if (items.isEmpty()) {
                client.deleteClusters(
                    DeleteClustersRequest.Builder()
                        .setAccountProfile(profile(account))
                        .addClusterType(ClusterType.TYPE_CONTINUATION)
                        .setDeleteReason(DeleteReason.DELETE_REASON_OTHER)
                        .setSyncAcrossDevices(SYNC_ACROSS_DEVICES)
                        .build()
                ).await()
            } else {
                val cluster = ContinuationCluster.Builder()
                    .setAccountProfile(profile(account))
                    .setSyncAcrossDevices(SYNC_ACROSS_DEVICES)
                items.forEach { cluster.addEntity(it) }
                client.publishContinuationCluster(
                    PublishContinuationClusterRequest.Builder()
                        .setContinuationCluster(cluster.build())
                        .build()
                ).await()
            }
            Log.i(TAG, "publishContinuationCluster: ${items.size} afleveringen.")
            Result.success()
        }.getOrElse { retryOrFail(it) }
    }

    private fun retryOrFail(error: Throwable): Result {
        Log.w(TAG, "Engage-publicatie mislukt", error)
        return if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    private fun profile(account: String) = AccountProfile.Builder()
        .setAccountId(account)
        .setLocale(Locale.forLanguageTag("nl-NL").toLanguageTag())
        .build()

    private fun entity(p: WatchProgress): TvEpisodeEntity? {
        val image = poster(p.imageUrl) ?: return null
        val uri = ContinueWatching.playUri(p)
        val builder = TvEpisodeEntity.Builder()
            .setEntityId(p.videoSlug)
            .setWatchNextType(if (p.isNext) WatchNextType.TYPE_NEXT else WatchNextType.TYPE_CONTINUE)
            .setName(p.episodeTitle.ifBlank { p.programTitle })
            .setShowTitle(p.programTitle)
            .setPlayBackUri(uri)
            .addPlatformSpecificPlaybackUri(
                PlatformSpecificUri.Builder()
                    .setPlatformType(PlatformType.TYPE_ANDROID_TV)
                    .setActionUri(uri)
                    .build()
            )
            .addPosterImage(image)
            .setLastEngagementTimeMillis(p.updatedAtMs)
            .setCallToActionText(if (p.isNext) "Volgende aflevering" else "Verder kijken")
            // Family7 noemt geen seizoen bij elk programma; één reeks telt als seizoen 1.
            .setSeasonNumber(p.seasonNumber.filter(Char::isDigit).ifEmpty { "1" })
        p.episodeNumber.filter(Char::isDigit).toIntOrNull()?.let { builder.setEpisodeNumber(it) }
        if (p.durationMs > 0) builder.setDurationMillis(p.durationMs)
        if (!p.isNext) builder.setLastPlayBackPositionTimeMillis(p.positionMs)
        return runCatching { builder.build() }
            .onFailure { Log.w(TAG, "Aflevering ${p.videoSlug} overgeslagen", it) }
            .getOrNull()
    }

    /** Engage wil de echte afmetingen; die leest hij uit de kop van het plaatje. */
    private fun poster(url: String): Image? {
        if (url.isBlank()) return null
        val size = imageSizes[url] ?: imageSize(url)?.also { imageSizes[url] = it } ?: run {
            Log.w(TAG, "Beeld onleesbaar: $url")
            return null
        }
        val (width, height) = size
        // Kleiner dan 200 pixels hoog toont Google TV niet.
        if (height < 200) {
            Log.w(TAG, "Beeld te klein (${width}x$height): $url")
            return null
        }
        return Image.Builder()
            .setImageUri(Uri.parse(url))
            .setImageWidthInPixel(width)
            .setImageHeightInPixel(height)
            .build()
    }

    /** Gooit IOException bij een netwerkfout; null als het geen leesbaar beeld is. */
    private fun imageSize(url: String): Pair<Int, Int>? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        return try {
            connection.inputStream.use { stream ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, options)
                if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val WORK_NAME = "verder-kijken-publiceren"
        private const val TAG = "Family7Engage"
        private const val KEY_SIGN_OUT_ACCOUNT = "uitloggen"

        /** Afmetingen per beeld, zodat niet elke publicatie alles opnieuw ophaalt. */
        private val imageSizes = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()

        /**
         * Alleen op dit toestel. Synchroniseren tussen toestellen vraagt om een
         * eigen instelling met toestemming van de kijker; die heeft de app nog niet.
         */
        private const val SYNC_ACROSS_DEVICES = false

        fun enqueueSignOut(context: Context, account: String) {
            val request = OneTimeWorkRequestBuilder<ContinueWatchingWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_SIGN_OUT_ACCOUNT to account))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
