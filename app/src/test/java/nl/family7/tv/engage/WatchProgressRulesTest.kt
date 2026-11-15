package nl.family7.tv.engage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressRulesTest {

    private fun p(video: String, program: String = "prog-$video", at: Long = 0, pos: Long = 600_000, dur: Long = 1_800_000) =
        WatchProgress(video, program, "Programma", "Aflevering", positionMs = pos, durationMs = dur, updatedAtMs = at)

    @Test
    fun `aftiteling telt als afgekeken`() {
        assertFalse(WatchProgressRules.isFinished(1_000_000, 1_800_000))
        assertTrue(WatchProgressRules.isFinished(1_720_000, 1_800_000))
        // Korte video: de laatste 30 seconden.
        assertTrue(WatchProgressRules.isFinished(95_000, 120_000))
        assertFalse(WatchProgressRules.isFinished(10_000, 0))
    }

    @Test
    fun `hervatten alleen midden in een aflevering`() {
        assertEquals(600_000L, WatchProgressRules.resumePosition(p("a")))
        assertEquals(0L, WatchProgressRules.resumePosition(p("a", pos = 10_000)))
        assertEquals(0L, WatchProgressRules.resumePosition(p("a", pos = 1_790_000)))
        assertEquals(0L, WatchProgressRules.resumePosition(p("a").copy(isNext = true)))
        assertEquals(0L, WatchProgressRules.resumePosition(null))
    }

    @Test
    fun `per programma een, nieuwste eerst, hooguit tien`() {
        var items = (1..12).map { p("v$it", at = it.toLong()) }
        items = WatchProgressRules.normalize(items)
        assertEquals(10, items.size)
        assertEquals("v12", items.first().videoSlug)

        // Volgende aflevering van hetzelfde programma vervangt de vorige.
        val updated = WatchProgressRules.upsert(items, p("nieuw", program = "prog-v5", at = 99))
        assertEquals("nieuw", updated.first().videoSlug)
        assertFalse(updated.any { it.videoSlug == "v5" })
    }
}
