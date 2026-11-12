package com.flash.headway

import com.flash.headway.app.SavedRoute
import com.flash.headway.app.delayedDepartures
import com.flash.headway.app.PlanMode
import com.flash.headway.app.PlanTime
import com.flash.headway.app.isDirect
import com.flash.headway.app.planStatus
import com.flash.headway.app.routeStatus
import com.flash.headway.data.Network
import com.flash.headway.engine.IST
import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.Schedule
import com.flash.headway.engine.Transfer
import com.flash.headway.engine.directDestinations
import com.flash.headway.engine.transferDestinations
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTest {
    private fun at(h: Int, mi: Int): Long = LocalDateTime.of(2026, 10, 12, h, mi).atZone(IST).toInstant().toEpochMilli()

    @Test
    fun everyStationIsReachableDirectlyOrWithAChange() {
        for (from in Network.stations.keys) {
            val direct = directDestinations(from).flatMap { g -> g.stations.map { it.first } }.toSet()
            val changes = transferDestinations(from)
            for (to in Network.stations.keys) {
                if (to == from) continue
                assertTrue("$from -> $to", to in direct || to in changes)
                changes[to]?.let { assertFalse(to in direct); assertEquals(it.changes, it.via.size) }
            }
        }
        assertEquals(Transfer(2, listOf("alandur", "vadapalani")), transferDestinations("guindy")["porur-junction"])
    }

    @Test
    fun aRouteWithAChangeListsWholeJourneysWithTheirLegs() {
        val route = SavedRoute("guindy", "koyambedu")
        assertFalse(isDirect(route))
        assertTrue(isDirect(SavedRoute("guindy", "central")))
        val deps = Schedule().delayedDepartures(route, at(8, 10), 3, 0)
        assertEquals(3, deps.size)
        for (d in deps) {
            val legs = d.legs
            assertNotNull(legs)
            legs!!
            assertTrue(legs.size >= 2)
            assertEquals(legs.first().dep, d.dep)
            assertEquals(legs.last().arr, d.arr)
            assertEquals("guindy", legs.first().from)
            assertEquals("koyambedu", legs.last().to)
        }
        // Direct routes keep their plain departures.
        assertNull(Schedule().delayedDepartures(SavedRoute("guindy", "central"), at(8, 10), 1, 0).first().legs)
    }

    @Test
    fun aDelayMovesEveryLegOfATrip() {
        val route = SavedRoute("guindy", "koyambedu")
        val s = Schedule()
        val onTime = s.delayedDepartures(route, at(8, 10) - 4 * MINUTE, 1, 0).first()
        val late = s.delayedDepartures(route, at(8, 10), 1, 4).first()
        assertEquals(onTime.dep + 4 * MINUTE, late.dep)
        assertEquals(onTime.legs!!.map { it.arr + 4 * MINUTE }, late.legs!!.map { it.arr })
    }

    @Test
    fun aTripHasALastJourneyTonight() {
        val st = routeStatus(Schedule(), SavedRoute("guindy", "porur-junction"), at(20, 0), true, 3)
        val last = st.last
        assertNotNull(last)
        last!!
        assertTrue(last.legs!!.size >= 2)
        assertFalse(st.closed)
    }

    @Test
    fun planningForAnotherTimeLeavesAfterOrArrivesBy() {
        val s = Schedule()
        for (route in listOf(SavedRoute("guindy", "central"), SavedRoute("guindy", "koyambedu"))) {
            val by = at(9, 30)
            val arrive = planStatus(s, route, PlanTime(PlanMode.ARRIVE, by), 5)
            assertEquals(5, arrive.departures.size)
            assertTrue(arrive.departures.all { it.arr <= by })
            assertEquals(arrive.departures.size - 1, arrive.best)
            // Nothing later also makes it: the next one after the best arrives too late.
            val after = s.delayedDepartures(route, arrive.departures.last().dep + 1, 1, 0).first()
            assertTrue(after.arr > by)

            val leave = planStatus(s, route, PlanTime(PlanMode.LEAVE, at(8, 0)), 3)
            assertTrue(leave.departures.all { it.dep >= at(8, 0) })
            assertEquals(0, leave.best)
            assertNull(leave.nextChange)
        }
    }
}
