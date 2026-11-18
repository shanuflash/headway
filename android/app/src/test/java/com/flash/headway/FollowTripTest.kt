package com.flash.headway

import com.flash.headway.app.ActiveTrip
import com.flash.headway.app.SavedRoute
import com.flash.headway.app.decodeTrip
import com.flash.headway.app.delayedDepartures
import com.flash.headway.app.encodeTrip
import com.flash.headway.app.followTrip
import com.flash.headway.app.followOf
import com.flash.headway.app.tripsOnTheWay
import com.flash.headway.app.nextLeg
import com.flash.headway.engine.IST
import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.Schedule
import com.flash.headway.engine.lineDirKey
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowTripTest {
    private fun at(h: Int, mi: Int): Long = LocalDateTime.of(2026, 10, 12, h, mi).atZone(IST).toInstant().toEpochMilli()

    private val route = SavedRoute("guindy", "koyambedu")

    private fun firstTrip() = Schedule().delayedDepartures(route, at(8, 10), 3, 0).first { (it.legs?.size ?: 1) > 1 }

    @Test
    fun aFollowedTripIsTheSameTripAfterItsFirstTrainLeaves() {
        val d = firstTrip()
        val trip = ActiveTrip(route.id, d.legs!!.first().trip.lineDirKey, d.timetabled!!)
        val followed = Schedule().followTrip(route, trip, emptyMap())!!
        assertEquals(d.legs!!.map { it.dep to it.arr }, followed.legs!!.map { it.dep to it.arr })
    }

    @Test
    fun aFollowedTripKeepsItsFirstTrainWhenThatLineRunsLate() {
        val d = firstTrip()
        val key = d.legs!!.first().trip.lineDirKey
        val trip = ActiveTrip(route.id, key, d.timetabled!!)
        val lines = mapOf(key to 3)
        val followed = Schedule(lineDelays = lines).followTrip(route, trip, lines)!!
        assertEquals(d.dep + 3 * MINUTE, followed.dep)
        assertTrue(followed.arr >= d.arr)
    }

    @Test
    fun nextLegMovesOnAsEachTrainLeaves() {
        val d = firstTrip()
        val legs = d.legs!!
        assertEquals(0, d.nextLeg(legs[0].dep - MINUTE))
        assertEquals(1, d.nextLeg(legs[0].dep + 1))
        assertEquals(-1, d.nextLeg(legs.last().dep + 1))
    }

    @Test
    fun aTripAlreadyUnderWayCanBeFollowedFromTheMiddle() {
        val d = firstTrip()
        // Just after its first train has left: it's on the way, and following it finds the same trip.
        val now = d.legs!!.first().dep + MINUTE
        val onTheWay = Schedule().tripsOnTheWay(route, now)
        assertTrue(onTheWay.isNotEmpty())
        assertTrue(onTheWay.all { it.dep <= now && it.arr > now && it.legs!!.size > 1 })
        assertEquals(onTheWay.sortedByDescending { it.dep }, onTheWay)
        val mine = onTheWay.first { it.dep == d.dep }
        val followed = Schedule().followTrip(route, followOf(route, mine)!!, emptyMap())!!
        assertEquals(d.legs!!.map { it.dep to it.arr }, followed.legs!!.map { it.dep to it.arr })
        assertEquals(1, followed.nextLeg(now))
    }

    @Test
    fun aTripRoundTripsAsText() {
        val t = ActiveTrip("guindy>koyambedu", "BLUE:REV", 1_731_900_000_000L)
        assertEquals(t, decodeTrip(encodeTrip(t)))
        assertNull(decodeTrip(null))
        assertNull(decodeTrip("a>b|BLUE:FWD"))
        assertNull(decodeTrip("a>b|BLUE:FWD|soon"))
    }
}
