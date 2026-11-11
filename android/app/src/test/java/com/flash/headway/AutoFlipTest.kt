package com.flash.headway

import com.flash.headway.app.AutoFlip
import com.flash.headway.app.Prefs
import com.flash.headway.app.Reminder
import com.flash.headway.app.SavedRoute
import com.flash.headway.app.applyFlips
import com.flash.headway.app.decodeFlips
import com.flash.headway.app.encodeFlips
import com.flash.headway.app.flipPeriod
import com.flash.headway.app.pairKey
import com.flash.headway.app.reverseId
import com.flash.headway.engine.IST
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFlipTest {
    private fun at(d: Int, h: Int, mi: Int): Long = LocalDateTime.of(2026, 10, d, h, mi).atZone(IST).toInstant().toEpochMilli()

    private val out = SavedRoute("guindy", "central")
    private val home = SavedRoute("central", "guindy")
    private val flip = AutoFlip(at = 17 * 60, home = "guindy")
    private fun prefs(route: SavedRoute, f: AutoFlip = flip) = Prefs(routes = listOf(route), flips = mapOf(pairKey(route) to f))

    @Test
    fun theServiceDayRunsPastMidnight() {
        assertEquals(false to "2026-10-12:am", flipPeriod(at(12, 8, 0), 17 * 60))
        assertEquals(true to "2026-10-12:pm", flipPeriod(at(12, 17, 0), 17 * 60))
        // 1 AM still belongs to the evening before.
        assertEquals(true to "2026-10-12:pm", flipPeriod(at(13, 1, 0), 17 * 60))
    }

    @Test
    fun aRouteTurnsAtItsSwitchTimeAndBackInTheMorning() {
        val evening = applyFlips(prefs(out), at(12, 17, 1))
        assertEquals(listOf(home), evening.routes)
        val morning = applyFlips(evening, at(13, 7, 0))
        assertEquals(listOf(out), morning.routes)
    }

    @Test
    fun aSwapByHandLastsUntilTheNextChangeOver() {
        val evening = applyFlips(prefs(out), at(12, 17, 1))
        // The rider swaps back by hand later that evening: nothing flips it again tonight.
        val swapped = evening.copy(routes = listOf(out))
        assertSame(swapped, applyFlips(swapped, at(12, 20, 0)))
        // Next morning is a new change-over.
        assertEquals(listOf(out), applyFlips(swapped, at(13, 8, 0)).routes)
    }

    @Test
    fun turningItOnDoesNotFlipTheCardThere() {
        val (_, key) = flipPeriod(at(12, 18, 0), 17 * 60)
        val p = prefs(out, AutoFlip(17 * 60, home = "central", applied = key))
        assertSame(p, applyFlips(p, at(12, 18, 30)))
    }

    @Test
    fun aFlipClearsACountdownOnThatRoute() {
        val p = prefs(out).copy(reminder = Reminder(out.id, at(12, 17, 10)))
        assertNull(applyFlips(p, at(12, 17, 1)).reminder)
    }

    @Test
    fun bothDirectionsSavedNeverFlip() {
        val p = Prefs(routes = listOf(out, home), flips = mapOf(pairKey(out) to flip))
        val next = applyFlips(p, at(12, 17, 1))
        assertEquals(listOf(out, home), next.routes)
    }

    @Test
    fun flipsRoundTrip() {
        val flips = mapOf("central|guindy" to AutoFlip(1020, "guindy", "2026-10-12:pm"), "a|b" to AutoFlip(600, "a"))
        assertEquals(flips, decodeFlips(encodeFlips(flips)))
        assertTrue(decodeFlips("junk;x=1,2").isEmpty())
        assertEquals("central>guindy", reverseId("guindy>central"))
        assertFalse(pairKey(out) != pairKey(home))
    }
}
