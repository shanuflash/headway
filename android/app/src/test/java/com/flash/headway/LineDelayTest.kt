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
import com.flash.headway.engine.lineKey
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineDelayTest {
    private fun at(h: Int, mi: Int): Long = LocalDateTime.of(2026, 10, 12, h, mi).atZone(IST).toInstant().toEpochMilli()

    private val blue = SavedRoute("guindy", "central")

    @Test
    fun aLineDelayMovesEveryTrainOnThatLineOnly() {
        val monday = LocalDate.of(2026, 10, 12)
        val plain = Schedule().day(monday)
        val late = Schedule(lineDelays = mapOf(RouteId.BLUE to 3)).day(monday)
        assertEquals(plain.size, late.size)
        for ((a, b) in plain.zip(late)) {
            val shift = if (lineKey(a.route) == RouteId.BLUE) 3 * MINUTE else 0L
            assertEquals(a.dep + shift, b.dep)
            assertEquals(if (shift != 0L) 3 else 0, b.lineDelay)
        }
    }

    @Test
    fun aLineDelayKeepsTheTimetabledTimeAndCountsInTheShownDelay() {
        val now = at(8, 38)
        val plain = Schedule().delayedDepartures(blue, now - 3 * MINUTE, 4, 0)
        val late = Schedule(lineDelays = mapOf(RouteId.BLUE to 3)).delayedDepartures(blue, now, 4, 0)
        assertEquals(plain.map { it.dep + 3 * MINUTE }, late.map { it.dep })
        // The timetabled time is still the timetable's, so a train's own delay keeps its key.
        assertEquals(plain.map { it.dep }, late.map { it.timetabled })
        assertTrue(late.all { it.delay == 3 })
    }

    @Test
    fun aLineDelayAddsToTheRouteDelayAndATrainsOwn() {
        val now = at(8, 38)
        val s = Schedule(lineDelays = mapOf(RouteId.BLUE to 2))
        val plain = Schedule().delayedDepartures(blue, now - 10 * MINUTE, 6, 0)
        val second = plain.first { it.dep >= now }.dep
        val rule = DelayRule(route = 1, byTime = mapOf(timeKey(second) to 4))
        val moved = s.delayedDepartures(blue, now, 5, rule)
        val own = moved.first { it.timetabled == second }
        assertEquals(second + 6 * MINUTE, own.dep)
        assertEquals(6, own.delay)
        assertTrue(moved.filter { it.timetabled != second }.all { it.dep == it.timetabled!! + 3 * MINUTE && it.delay == 3 })
    }

    @Test
    fun aTripWithChangesIsPlannedOnEachLinesDelay() {
        val route = SavedRoute("guindy", "porur-junction")
        val now = at(8, 10)
        val plain = Schedule().delayedDepartures(route, now, 1, 0).first()
        val legs = plain.legs!!
        assertTrue(legs.size > 1)
        val second = lineKey(legs.last().trip.route)
        val late = Schedule(lineDelays = mapOf(second to 5)).delayedDepartures(route, now, 1, 0).first()
        // The later train is 5 min late, so the trip can't arrive any sooner than before.
        assertTrue(late.arr >= plain.arr)
        assertTrue(late.legs!!.filter { lineKey(it.trip.route) == second }.all { it.trip.lineDelay == 5 })
    }

    @Test
    fun lineDelaysRoundTripAsText() {
        val delays = mapOf(RouteId.BLUE to 2, RouteId.YELLOW to -1)
        assertEquals(delays, decodeLineDelays(encodeLineDelays(delays)))
        assertEquals(emptyMap<RouteId, Int>(), decodeLineDelays(null))
        // Zero is dropped, short loops count as the Blue Line, out of range is clamped, junk ignored.
        assertEquals(mapOf(RouteId.BLUE to 10), decodeLineDelays("GREEN=0;LOOP=40;PURPLE=3;YELLOW=x"))
    }
}
