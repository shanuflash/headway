package com.flash.headway

import com.flash.headway.app.DelayRule
import com.flash.headway.app.SavedRoute
import com.flash.headway.app.decodeRule
import com.flash.headway.app.decodeTrainDelays
import com.flash.headway.app.encodeRule
import com.flash.headway.app.encodeTrainDelays
import com.flash.headway.app.timeKey
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
        val delays = mapOf("guindy>central" to 3, "airport>vadapalani" to -10)
        assertEquals(delays, decodeDelays(encodeDelays(delays)))
        assertEquals(mapOf("a>b" to 10, "c>d" to -10), decodeDelays("a>b=14;c>d=-12;e>f=0;junk;g>h=x"))
        assertEquals(emptyMap<String, Int>(), decodeDelays(null))
    }

    @Test
    fun aTrainsOwnDelayMovesOnlyThatTrain() {
        val s = Schedule()
        val now = at(8, 0)
        val plain = s.delayedDepartures(route, now, 5, 0)
        val second = plain[1].dep
        val rule = DelayRule(route = 0, byTime = mapOf(timeKey(second) to 2))
        val moved = s.delayedDepartures(route, now, 5, rule)
        val it2 = moved.first { it.timetabled == second }
        assertEquals(second + 2 * MINUTE, it2.dep)
        assertEquals(2, it2.delay)
        assertTrue(moved.filter { it.timetabled != second }.all { it.dep == it.timetabled && it.delay == 0 })
        // A train's own delay beats the route's; the rest follow the route.
        val both = s.delayedDepartures(route, now, 5, DelayRule(route = 4, byTime = mapOf(timeKey(second) to 0)))
        assertTrue(both.filter { it.timetabled != second }.all { it.delay == 4 })
        assertEquals(0, both.first { it.timetabled == second }.delay)
        // The shown time leads back to the timetabled one.
        assertEquals(second, rule.timetabledOf(second + 2 * MINUTE))
    }

    @Test
    fun trainDelaysRoundTrip() {
        val all = mapOf("guindy>central" to mapOf("08:13" to 3, "08:21" to -1), "a>b" to mapOf("18:05" to 10))
        assertEquals(all, decodeTrainDelays(encodeTrainDelays(all)))
        assertEquals(mapOf("x>y" to mapOf("08:00" to 10)), decodeTrainDelays("x>y|08:00=25;x>y|8:00=1;junk"))
        val rule = DelayRule(2, mapOf("08:13" to 3))
        assertEquals(rule, decodeRule(encodeRule(rule)))
        assertEquals(DelayRule(), decodeRule(null))
    }
}
