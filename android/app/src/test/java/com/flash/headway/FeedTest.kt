package com.flash.headway

import com.flash.headway.data.DayType
import com.flash.headway.data.Line
import com.flash.headway.data.RouteId
import com.flash.headway.engine.Adjustment
import com.flash.headway.engine.Feed
import com.flash.headway.engine.IST
import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.Notice
import com.flash.headway.engine.Schedule
import com.flash.headway.engine.activeNotices
import com.flash.headway.engine.dayTypeFor
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The advisory feed as the engine applies it, matching the web engine (js/engine.js). */
class FeedTest {
    private val monday = LocalDate.of(2026, 10, 12)
    private fun at(h: Int, mi: Int): Long = LocalDateTime.of(2026, 10, 12, h, mi).atZone(IST).toInstant().toEpochMilli()

    @Test
    fun extendedHoursAddTrainsAfterTheLastOne() {
        val plain = Schedule().day(monday)
        val feed = Feed(adjustments = listOf(Adjustment(date = monday, extendUntil = 0, headway = 10, lines = setOf(Line.BLUE))))
        val extended = Schedule(feed = feed).day(monday)
        assertTrue(extended.size > plain.size)
        val lastBlue = { trips: List<com.flash.headway.engine.Trip> -> trips.filter { it.line == Line.BLUE }.maxOf { it.dep } }
        assertTrue(lastBlue(extended) > lastBlue(plain))
        // Other lines keep their last train.
        assertEquals(plain.filter { it.line == Line.GREEN }.size, extended.filter { it.line == Line.GREEN }.size)
    }

    @Test
    fun aFeedDayTypeAndHolidayBeatTheCalendar() {
        val feed = Feed(adjustments = listOf(Adjustment(date = monday, dayType = DayType.SUNDAY, reason = "Diwali")))
        assertEquals(DayType.SUNDAY, dayTypeFor(monday, null, feed).type)
        assertEquals("Diwali", dayTypeFor(monday, null, feed).reason)
        val tuesday = monday.plusDays(1)
        assertEquals(DayType.SUNDAY, dayTypeFor(tuesday, null, Feed(holidays = mapOf(tuesday to "Holiday"))).type)
        assertEquals(DayType.WEEKDAY, dayTypeFor(tuesday, null, null).type)
    }

    @Test
    fun delaysShiftTrainsInTheirWindowOnTheirLine() {
        // 2 minutes, so a delayed train can't land on its neighbour's slot by chance.
        val feed = Feed(adjustments = listOf(Adjustment(from = at(8, 0), to = at(9, 0), delay = 2, lines = setOf(Line.GREEN))))
        val plain = Schedule().day(monday)
        val delayed = Schedule(feed = feed).day(monday)
        val plainGreen = plain.filter { it.route == RouteId.GREEN && it.dep in at(8, 10)..at(8, 40) }.map { it.dep }
        val delayedGreen = delayed.filter { it.route == RouteId.GREEN }.map { it.dep }.toSet()
        assertTrue(plainGreen.isNotEmpty())
        assertTrue(plainGreen.all { it + 2 * MINUTE in delayedGreen && it !in delayedGreen })
        // Other lines run as timetabled.
        val blue = { trips: List<com.flash.headway.engine.Trip> -> trips.filter { it.line == Line.BLUE }.map { it.dep }.sorted() }
        assertEquals(blue(plain), blue(delayed))
    }

    @Test
    fun noticesShowOnlyWhileTheyRun() {
        val feed = Feed(notices = listOf(Notice("a", "warn", "Now", from = at(8, 0), to = at(10, 0)), Notice("b", "info", "Always")))
        assertEquals(listOf("a", "b"), activeNotices(feed, at(9, 0)).map { it.id })
        assertEquals(listOf("b"), activeNotices(feed, at(11, 0)).map { it.id })
    }
}
