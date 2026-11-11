package com.flash.headway

import com.flash.headway.app.SavedRoute
import com.flash.headway.app.decodeDelays
import com.flash.headway.app.delayedDepartures
import com.flash.headway.app.encodeDelays
import com.flash.headway.app.routeStatus
import com.flash.headway.engine.IST
import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.Schedule
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DelayTest {
    private fun at(h: Int, mi: Int): Long = LocalDateTime.of(2026, 10, 12, h, mi).atZone(IST).toInstant().toEpochMilli()

    private val route = SavedRoute("guindy", "central")

    @Test
    fun aDelayShiftsEveryDepartureAndArrival() {
        val s = Schedule()
        val now = at(8, 38)
        val base = s.routeDepartures(route.from, route.to, now - 3 * MINUTE, 4)
        val late = s.delayedDepartures(route, now, 4, 3)
        assertEquals(base.map { it.dep + 3 * MINUTE }, late.map { it.dep })
        assertEquals(base.map { it.arr + 3 * MINUTE }, late.map { it.arr })
        assertTrue(late.all { it.dep >= now })
    }

    @Test
    fun aLateTrainStillShowsAfterItsTimetabledTimeHasPassed() {
        val s = Schedule()
        val timetabled = s.routeDepartures(route.from, route.to, at(8, 38), 1).first().dep
        // One minute after the timetabled time: gone on time, still to come at +2.
        val now = timetabled + MINUTE
        assertTrue(s.routeDepartures(route.from, route.to, now, 1).first().dep > timetabled)
        assertEquals(timetabled + 2 * MINUTE, s.delayedDepartures(route, now, 1, 2).first().dep)
    }

    @Test
    fun theLastTrainMovesWithTheDelay() {
        val s = Schedule()
        val now = at(8, 38)
        val onTime = routeStatus(s, route, now, true, 3)
        val early = routeStatus(s, route, now, true, 3, -4)
        assertEquals(onTime.last!!.dep - 4 * MINUTE, early.last!!.dep)
    }

    @Test
    fun delaysRoundTripAndStayInRange() {
        val delays = mapOf("guindy>central" to 3, "airport>vadapalani" to -5)
        assertEquals(delays, decodeDelays(encodeDelays(delays)))
        assertEquals(mapOf("a>b" to 5, "c>d" to -5), decodeDelays("a>b=9;c>d=-7;e>f=0;junk;g>h=x"))
        assertEquals(emptyMap<String, Int>(), decodeDelays(null))
    }
}
