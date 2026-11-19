package com.flash.headway

import com.flash.headway.app.SavedRoute
import com.flash.headway.app.delayedDepartures
import com.flash.headway.app.PlanMode
import com.flash.headway.app.PlanTime
import com.flash.headway.app.isDirect
import com.flash.headway.app.planStatus
import com.flash.headway.app.routeStatus
import com.flash.headway.data.Network
import com.flash.headway.data.RouteId
import com.flash.headway.engine.IST
import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.Schedule
import com.flash.headway.engine.lineDirKey
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
        // A direct pair is a one-train journey.
        assertEquals(1, Schedule().delayedDepartures(SavedRoute("guindy", "central"), at(8, 10), 1, 0).first().legs?.size)
    }

    @Test
    fun aTripWithChangesRunsOnEachTrainsLineDelayNotTheRoutes() {
        val route = SavedRoute("guindy", "koyambedu")
        val onTime = Schedule().delayedDepartures(route, at(8, 10) - 4 * MINUTE, 1, 0).first()
        assertTrue(onTime.legs!!.size > 1)
        // The route's delay would be one fixed shift for the whole trip: it's left out.
        val routeDelay = Schedule().delayedDepartures(route, at(8, 10) - 4 * MINUTE, 1, 4).first()
        assertEquals(onTime.dep to onTime.arr, routeDelay.dep to routeDelay.arr)
        // Each train's line running 4 min late moves every leg of the trip with it.
        val lines = onTime.legs!!.associate { it.trip.lineDirKey to 4 }
        val late = Schedule(lineDelays = lines).delayedDepartures(route, at(8, 10), 1, 0).first()
        assertEquals(onTime.dep + 4 * MINUTE, late.dep)
        assertEquals(onTime.legs!!.map { it.arr + 4 * MINUTE }, late.legs!!.map { it.arr })
        assertEquals(4, late.delay)
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

    @Test
    fun theFastestTripWinsAndFewerChangesSettleTies() {
        val s = Schedule()
        val start = LocalDateTime.of(2026, 11, 12, 8, 0).atZone(IST).toInstant().toEpochMilli()
        val js = s.journeys("airport", "poonamallee-bypass", start, 8)
        assertEquals(8, js.size)
        // For each arrival the latest start is listed, the quickest way to it.
        for (arr in js.map { it.arr }.toSet()) {
            val quick = js.last { it.arr == arr }
            val later = s.plan("airport", "poonamallee-bypass", quick.dep + MINUTE)
            assertTrue(later == null || later.arr > arr)
        }
        // Arriving 9:40, the 8:42 with two changes (58 min) beats the 8:40 with one (60 min).
        val at940 = js.last { java.time.Instant.ofEpochMilli(it.arr).atZone(IST).let { z -> z.hour == 9 && z.minute == 40 } }
        assertEquals(3, at940.legs.size)
    }

    @Test
    fun aTrainLeavingSoonerThatArrivesAsEarlyIsListedToo() {
        // At Airport at 4:50 PM for Poonamallee: the 4:57 Blue, then Green at Alandur, arrives with
        // the 5:08 Inter-corridor, so a rider on the platform can leave sooner instead of waiting.
        val s = Schedule()
        val start = LocalDateTime.of(2026, 11, 19, 16, 50).atZone(IST).toInstant().toEpochMilli()
        val js = s.journeys("airport", "poonamallee", start, 6)
        for (arr in js.map { it.arr }.toSet()) assertTrue(js.count { it.arr == arr } <= 2)
        for (j in js) {
            val quick = js.last { it.arr == j.arr }
            assertTrue(quick.dep - j.dep <= 15 * MINUTE)
            // Never rides away to catch, further on, a train it could board at the start.
            for (l in j.legs.drop(1)) assertFalse(l.trip.stops.any { it.station == "airport" && it.at >= j.dep })
        }
        // A way that leaves sooner, for an arrival a later train also makes, is offered.
        val sooner = js.filter { j -> js.any { it.arr == j.arr && it.dep > j.dep } }
        assertTrue(sooner.isNotEmpty())
    }
}
