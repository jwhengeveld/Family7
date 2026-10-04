package nl.family7.core.data

import android.os.SystemClock

/**
 * Kleine cache in het geheugen met een houdbaarheidsduur.
 *
 * [snapshot] geeft de laatst geladen waarde terug, ongeacht ouderdom — genoeg om
 * een scherm meteen te vullen in plaats van eerst een laadscherm te tonen.
 * [fresh] geeft die waarde alleen terug als hij nog vers is, zodat snel heen en
 * weer navigeren geen nieuwe netwerkoproep kost.
 */
class TimedCache<T>(
    private val ttlMs: Long,
    /** Losse klok, zodat het verlopen van de houdbaarheid te testen is. */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    @Volatile private var value: T? = null
    @Volatile private var storedAt = 0L
    @Volatile private var seeded = false

    fun snapshot(): T? = value

    fun fresh(): T? {
        val v = value ?: return null
        if (seeded) return null
        return if (clock() - storedAt < ttlMs) v else null
    }

    fun put(v: T) {
        value = v
        storedAt = clock()
        seeded = false
    }

    /**
     * Vult de cache met een waarde uit een vorige sessie (van schijf). Die
     * waarde vult een scherm meteen, maar telt nooit als vers: het eerste
     * ophalen gaat dus altijd nog naar het netwerk. Een waarde die er al is
     * wint, want die is nieuwer.
     */
    fun seed(v: T) {
        if (value != null) return
        value = v
        seeded = true
    }

    fun clear() {
        value = null
        storedAt = 0L
        seeded = false
    }
}

/** Standaard houdbaarheid voor catalogusinhoud: vers genoeg, maar niet muf. */
const val CATALOG_TTL_MS = 5 * 60_000L

/** Hoe vaak de startpagina zichzelf stil ververst zolang hij op de voorgrond staat. */
const val BACKGROUND_REFRESH_MS = 10 * 60_000L
