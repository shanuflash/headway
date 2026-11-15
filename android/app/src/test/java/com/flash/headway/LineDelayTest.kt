package com.flash.headway

import com.flash.headway.app.DelayRule
import com.flash.headway.app.SavedRoute
import com.flash.headway.app.decodeLineDelays
import com.flash.headway.app.delayedDepartures
import com.flash.headway.app.encodeLineDelays
import com.flash.headway.app.timeKey
import com.flash.headway.data.RouteId
import com.flash.headway.engine.IST
import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.Schedule
import com.flash.headway.engine.Dir
import com.flash.headway.engine.lineDirKey
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineDelayTest {
    private fun at(h: Int, mi: Int): Long = LocalDateTime.of(2026, 10, 12, h, mi).atZone(IST).toInstant().toEpochMilli()

    private val blue = SavedRoute("guindy", "central")

    /** The line and direction guindy -> central rides. */
    private val blueKey: String get() = Schedule().delayedDepartures(blue, at(8, 38), 1, 0).first().trip.lineDirKey

    @Test
    fun aLineDelayMovesEveryTrainOnThatLineInThatDirectionOnly() {
        val monday = LocalDate.of(2026, 10, 12)
        val key = lineDirKey(RouteId.BLUE, Dir.FWD)
        val plain = Schedule().day(monday)
        val late = Schedule(lineDelays = mapOf(key to 3)).day(monday)
        assertEquals(plain.size, late.size)
        var moved = 0
        var kept = 0
        for ((a, b) in plain.zip(late)) {
            // Short loops run on the Blue Line, so they move with it.
            val on = (a.route == RouteId.BLUE || a.route == RouteId.LOOP) && a.dir == Dir.FWD
            assertEquals(a.dep + if (on) 3 * MINUTE else 0L, b.dep)
            assertEquals(if (on) 3 else 0, b.lineDelay)
            if (on) moved++ else if (a.route == RouteId.BLUE) kept++
        }
        assertTrue(moved > 0 && kept > 0)
    }

    @Test
    fun aLineDelayKeepsTheTimetabledTimeAndCountsInTheShownDelay() {
        val now = at(8, 38)
        val plain = Schedule().delayedDepartures(blue, now - 3 * MINUTE, 4, 0)
        val late = Schedule(lineDelays = mapOf(blueKey to 3)).delayedDepartures(blue, now, 4, 0)
        assertEquals(plain.map { it.dep + 3 * MINUTE }, late.map { it.dep })
        // The timetabled time is still the timetable's, so a train's own delay keeps its key.
        assertEquals(plain.map { it.dep }, late.map { it.timetabled })
        assertTrue(late.all { it.delay == 3 })
    }

    @Test
    fun aLineDelayAddsToTheRouteDelayAndATrainsOwn() {
        val now = at(8, 38)
        val s = Schedule(lineDelays = mapOf(blueKey to 2))
        val plain = Schedule().delayedDepartures(blue, now - 10 * MINUTE, 6, 0)
        val second = plain.first { it.dep >= now }.dep
        val rule = DelayRule(route = 1, byTime = mapOf(timeKey(second) to 4))
        val moved = s.delayedDepartures(blue, now, 5, rule)
        val own = moved.first { it.timetabled == second }
        assertEquals(second + 6 * MINUTE, own.dep)
        assertEquals(6, own.delay)
        assertTrue(moved.filter { it.timetabled != second }.all { it.dep == it.timetabled!! + 3 * MINUTE && it.delay == 3 })
    }

    private val change = SavedRoute("guindy", "porur-junction")

    @Test
    fun aTripWithChangesIsPlannedOnEachTrainsLineDelay() {
        val now = at(8, 10)
        val plain = Schedule().delayedDepartures(change, now, 1, 0).first()
        val legs = plain.legs!!
        assertTrue(legs.size > 1)
        val second = legs.last().trip.lineDirKey
        val late = Schedule(lineDelays = mapOf(second to 5)).delayedDepartures(change, now, 1, 0).first()
        // The later train is 5 min late, so the trip can't arrive any sooner than before.
        assertTrue(late.arr >= plain.arr)
        assertTrue(late.legs!!.filter { it.trip.lineDirKey == second }.all { it.trip.lineDelay == 5 })
    }

    @Test
    fun aTripWithChangesTakesNoFixedDelay() {
        val now = at(8, 10)
        val s = Schedule()
        val plain = s.delayedDepartures(change, now, 3, 0).filter { (it.legs?.size ?: 1) > 1 }
        assertTrue(plain.isNotEmpty())
        // The route's delay and a train's own are one shift for the whole trip: not for these.
        val rule = DelayRule(route = 4, byTime = plain.associate { timeKey(it.dep) to 7 })
        val withRule = s.delayedDepartures(change, now, 3, rule).filter { (it.legs?.size ?: 1) > 1 }
        assertEquals(plain.map { it.dep to it.arr }, withRule.map { it.dep to it.arr })
        assertTrue(withRule.all { it.delay == 0 })
    }

    @Test
    fun lineDelaysRoundTripAsText() {
        val delays = mapOf("BLUE:FWD" to 2, "YELLOW:REV" to -1)
        assertEquals(delays, decodeLineDelays(encodeLineDelays(delays)))
        assertEquals(emptyMap<String, Int>(), decodeLineDelays(null))
        // Zero is dropped, short loops count as the Blue Line, out of range is clamped, junk ignored.
        assertEquals(mapOf("BLUE:REV" to 10), decodeLineDelays("GREEN:FWD=0;LOOP:REV=40;PURPLE:FWD=3;YELLOW:FWD=x;BLUE:UP=2"))
        // One delay for the whole line, as 1.0.58 saved it, counts both ways.
        assertEquals(mapOf("GREEN:FWD" to 3, "GREEN:REV" to 3), decodeLineDelays("GREEN=3"))
    }
}
