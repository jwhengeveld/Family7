package nl.family7.tv.engage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Alleen in de debugbuild: zet een aflevering in "Verder kijken" zonder in te
 * loggen, om de Engage-koppeling met de verificatieapp te testen.
 *   adb shell am broadcast -n <pakket>/nl.family7.tv.engage.DebugContinueWatchingReceiver \
 *     --es programma bijbelse-karakters --es video 13-1-bijbelse-karakters ...
 */
class DebugContinueWatchingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = ContinueWatching.get(context)
        if (intent.getBooleanExtra("wissen", false)) {
            store.signOut()
            return
        }
        store.setAccount("debug-test")
        store.record(
            WatchProgress(
                videoSlug = intent.getStringExtra("video") ?: return,
                programSlug = intent.getStringExtra("programma") ?: return,
                programTitle = intent.getStringExtra("programmatitel").orEmpty(),
                episodeTitle = intent.getStringExtra("titel").orEmpty(),
                episodeNumber = intent.getStringExtra("aflevering").orEmpty(),
                seasonNumber = intent.getStringExtra("seizoen").orEmpty(),
                imageUrl = intent.getStringExtra("beeld").orEmpty(),
                positionMs = intent.getLongExtra("positie", 600_000L),
                durationMs = intent.getLongExtra("duur", 1_800_000L),
                updatedAtMs = System.currentTimeMillis()
            ),
            next = null
        )
    }
}
