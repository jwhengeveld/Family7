package nl.family7.tv.ui.player

import androidx.media3.common.PlaybackException

/**
 * Herstel bij afspeelfouten op de tv, zoals de mobiele app (PlaybackManager.recover) het al doet:
 * achter het live-venster → terug naar live; andere fouten → eerst zelf een paar keer opnieuw
 * (met verse stream-URL), pas daarna een melding met foutcode.
 */
internal object PlaybackRecovery {
    const val MAX_AUTO_RETRIES = 2
    const val BACKOFF_MS = 1_500L

    fun isBehindLiveWindow(e: PlaybackException) =
        e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW

    fun describe(e: PlaybackException): String {
        val reason = when (e.errorCode) {
            in 2000..2999 -> "De verbinding met Family7 viel weg."
            in 3000..4999 -> "Deze video kan op deze tv niet worden afgespeeld."
            in 6000..6999 -> "Deze beveiligde video speelt op deze tv niet af."
            else -> "Er ging iets mis bij het afspelen."
        }
        val code = e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
        return "$reason Probeer het opnieuw.\n($code, ${e.errorCode})"
    }
}
