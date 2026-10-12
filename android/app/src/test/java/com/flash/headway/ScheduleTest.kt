package com.flash.headway

import com.flash.headway.data.DayType
import com.flash.headway.data.RouteId
import com.flash.headway.engine.DayOverride
import com.flash.headway.engine.Dir
import com.flash.headway.engine.IST
import com.flash.headway.engine.Schedule
import com.flash.headway.engine.dayTypeFor
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(IST).toInstant().toEpochMilli()

    private fun hhmm(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(IST).format(DateTimeFormatter.ofPattern("HH:mm"))

    private val monday = LocalDate.of(2026, 10, 12)

    @Test
    fun dayTypesFollowCalendarHolidaysAndOverrides() {
        assertEquals(DayType.WEEKDAY, dayTypeFor(monday, null).type)
        assertEquals(DayType.SATURDAY, dayTypeFor(LocalDate.of(2026, 10, 17), null).type)
        assertEquals(DayType.SUNDAY, dayTypeFor(LocalDate.of(2026, 10, 18), null).type)
        assertEquals(DayType.SUNDAY, dayTypeFor(LocalDate.of(2026, 10, 20), null).type)
        assertEquals(DayType.SUNDAY, dayTypeFor(monday, DayOverride(monday, DayType.SUNDAY)).type)
    }

    @Test
    fun terminalFirstAndLastTrainsMatchTheOfficialTimetable() {
        val trips = Schedule().day(monday)
        fun check(route: RouteId, dir: Dir, first: String, last: String) {
            val deps = trips.filter { it.route == route && it.dir == dir }.map { hhmm(it.dep) }
            assertEquals("$route $dir first", first, deps.first())
            assertEquals("$route $dir last", last, deps.last())
        }
        check(RouteId.BLUE, Dir.FWD, "04:56", "23:00")
        check(RouteId.BLUE, Dir.REV, "04:51", "23:00")
        check(RouteId.GREEN, Dir.FWD, "05:02", "23:00")
        check(RouteId.INTER, Dir.FWD, "04:55", "23:17")
        check(RouteId.INTER, Dir.REV, "05:02", "23:08")
        check(RouteId.YELLOW, Dir.FWD, "07:00", "21:00")
    }

    @Test
    fun headwaysFollowThePeakBands() {
        val deps = Schedule().day(monday).filter { it.route == RouteId.BLUE && it.dir == Dir.FWD }.map { it.dep }
        fun gapAt(h: Int, m: Int): Long {
            val i = deps.indexOfFirst { it >= at(2026, 10, 12, h, m) }
            return (deps[i + 1] - deps[i]) / 60_000
        }
        assertEquals(6, gapAt(9, 0))
        assertEquals(7, gapAt(13, 0))
        assertEquals(15, gapAt(22, 15))
    }

    @Test
    fun shortLoopsRunOnWeekdayPeaksOnly() {
        val s = Schedule()
        assertTrue(s.day(monday).any { it.route == RouteId.LOOP })
        assertFalse(s.day(LocalDate.of(2026, 10, 17)).any { it.route == RouteId.LOOP })
    }

    @Test
    fun journeysPickSensibleInterchanges() {
        val s = Schedule()
        val j = s.plan("guindy", "porur-junction", at(2026, 10, 12, 20, 2))
        assertNotNull(j)
        assertEquals(
            listOf("BLUE:guindy>alandur", "INTER:alandur>vadapalani", "YELLOW:vadapalani>porur-junction"),
            j!!.legs.map { "${it.trip.route}:${it.from}>${it.to}" },
        )
        val direct = s.journeys("airport", "vadapalani", at(2026, 10, 12, 8, 15), 3)
        assertTrue(direct.any { it.legs.size == 1 && it.legs[0].trip.route == RouteId.INTER })
        for (x in direct) assertFalse(x.legs.size > 1 && x.legs[1].trip.route == RouteId.INTER)
    }

    @Test
    fun lateAtNightTheNextJourneyIsTheFirstTrain() {
        val j = Schedule().plan("guindy", "central", at(2026, 10, 12, 23, 30))
        assertNotNull(j)
        assertEquals(1, j!!.legs.size)
        assertEquals("05:01", hhmm(j.dep))
    }

    @Test
    fun afterALineClosesTripsStartFromItsFirstTrainTomorrow() {
        val js = Schedule().journeys("guindy", "porur-junction", at(2026, 10, 12, 21, 45), 2)
        assertTrue(js.isNotEmpty())
        val legs = js[0].legs
        val yellow = legs.first { it.trip.route == RouteId.YELLOW }
        assertEquals("07:00", hhmm(yellow.dep))
        val wait = (yellow.dep - legs[legs.indexOf(yellow) - 1].arr) / 60_000
        assertTrue("waits $wait min at Vadapalani", wait < 20)
    }

    @Test
    fun lastJourneyIsTheFinalTrainOfTheServiceDay() {
        val last = Schedule().lastJourney("guindy", "central", at(2026, 10, 12, 20, 0))
        assertNotNull(last)
        assertEquals("23:10", hhmm(last!!.dep))
    }
}
