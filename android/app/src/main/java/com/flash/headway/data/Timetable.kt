// Generated from js/timetable.js: the official CMRL timetable. Keep both in sync.
package com.flash.headway.data

import java.time.LocalDate

enum class DayType(val label: String) {
    WEEKDAY("Weekday"),
    SATURDAY("Saturday"),
    SUNDAY("Sunday or holiday"),
}

enum class BandKind { PEAK, OFFPEAK, LATE }

/** Departures from [from] (inclusive) to [to] (exclusive) minutes after midnight run every [headway] minutes. */
data class Band(val from: Int, val to: Int, val headway: Int, val kind: BandKind)

data class LineSpec(
    val firstFwd: Int,
    val firstRev: Int,
    val lastFwd: Int,
    val lastRev: Int,
    val bands: List<Band>,
)

data class LoopSpec(val windows: List<IntRange>, val offset: Int)

data class DaySpec(
    val blue: LineSpec,
    val green: LineSpec,
    val inter: LineSpec,
    val yellow: LineSpec,
    val loop: LoopSpec?,
)

object Timetable {
    const val SOURCE_URL = "https://chennaimetrorail.org/wp-content/uploads/2026/10/Time-Table.pdf"
    const val PUBLISHED = "2026-10-09"

    private val weekday = DaySpec(
        blue = LineSpec(
            firstFwd = 296,
            firstRev = 291,
            lastFwd = 1380,
            lastRev = 1380,
            bands = listOf(
                Band(300, 480, 7, BandKind.OFFPEAK),
                Band(480, 660, 6, BandKind.PEAK),
                Band(660, 1020, 7, BandKind.OFFPEAK),
                Band(1020, 1200, 6, BandKind.PEAK),
                Band(1200, 1320, 7, BandKind.OFFPEAK),
                Band(1320, 1440, 15, BandKind.LATE),
            ),
        ),
        green = LineSpec(
            firstFwd = 302,
            firstRev = 301,
            lastFwd = 1380,
            lastRev = 1380,
            bands = listOf(
                Band(300, 480, 14, BandKind.OFFPEAK),
                Band(480, 660, 12, BandKind.PEAK),
                Band(660, 1020, 14, BandKind.OFFPEAK),
                Band(1020, 1200, 12, BandKind.PEAK),
                Band(1200, 1320, 14, BandKind.OFFPEAK),
                Band(1320, 1440, 30, BandKind.LATE),
            ),
        ),
        inter = LineSpec(
            firstFwd = 295,
            firstRev = 302,
            lastFwd = 1397,
            lastRev = 1388,
            bands = listOf(
                Band(300, 480, 14, BandKind.OFFPEAK),
                Band(480, 660, 12, BandKind.PEAK),
                Band(660, 1020, 14, BandKind.OFFPEAK),
                Band(1020, 1200, 12, BandKind.PEAK),
                Band(1200, 1320, 14, BandKind.OFFPEAK),
                Band(1320, 1440, 30, BandKind.LATE),
            ),
        ),
        yellow = LineSpec(
            firstFwd = 420,
            firstRev = 420,
            lastFwd = 1260,
            lastRev = 1260,
            bands = listOf(
                Band(420, 480, 15, BandKind.OFFPEAK),
                Band(480, 660, 10, BandKind.PEAK),
                Band(660, 1020, 15, BandKind.OFFPEAK),
                Band(1020, 1200, 10, BandKind.PEAK),
                Band(1200, 1440, 15, BandKind.OFFPEAK),
            ),
        ),
        loop = LoopSpec(listOf(480 until 660, 1020 until 1200), 3),
    )

    private val saturday = DaySpec(
        blue = LineSpec(
            firstFwd = 296,
            firstRev = 291,
            lastFwd = 1380,
            lastRev = 1380,
            bands = listOf(
                Band(300, 480, 7, BandKind.OFFPEAK),
                Band(480, 660, 6, BandKind.PEAK),
                Band(660, 1020, 7, BandKind.OFFPEAK),
                Band(1020, 1200, 6, BandKind.PEAK),
                Band(1200, 1320, 7, BandKind.OFFPEAK),
                Band(1320, 1440, 15, BandKind.LATE),
            ),
        ),
        green = LineSpec(
            firstFwd = 302,
            firstRev = 301,
            lastFwd = 1380,
            lastRev = 1380,
            bands = listOf(
                Band(300, 480, 14, BandKind.OFFPEAK),
                Band(480, 660, 12, BandKind.PEAK),
                Band(660, 1020, 14, BandKind.OFFPEAK),
                Band(1020, 1200, 12, BandKind.PEAK),
                Band(1200, 1320, 14, BandKind.OFFPEAK),
                Band(1320, 1440, 30, BandKind.LATE),
            ),
        ),
        inter = LineSpec(
            firstFwd = 295,
            firstRev = 302,
            lastFwd = 1397,
            lastRev = 1388,
            bands = listOf(
                Band(300, 480, 14, BandKind.OFFPEAK),
                Band(480, 660, 12, BandKind.PEAK),
                Band(660, 1020, 14, BandKind.OFFPEAK),
                Band(1020, 1200, 12, BandKind.PEAK),
                Band(1200, 1320, 14, BandKind.OFFPEAK),
                Band(1320, 1440, 30, BandKind.LATE),
            ),
        ),
        yellow = LineSpec(
            firstFwd = 420,
            firstRev = 420,
            lastFwd = 1260,
            lastRev = 1260,
            bands = listOf(
                Band(420, 480, 15, BandKind.OFFPEAK),
                Band(480, 660, 10, BandKind.PEAK),
                Band(660, 1020, 15, BandKind.OFFPEAK),
                Band(1020, 1200, 10, BandKind.PEAK),
                Band(1200, 1440, 15, BandKind.OFFPEAK),
            ),
        ),
        loop = null,
    )

    private val sunday = DaySpec(
        blue = LineSpec(
            firstFwd = 303,
            firstRev = 302,
            lastFwd = 1380,
            lastRev = 1380,
            bands = listOf(
                Band(300, 720, 10, BandKind.OFFPEAK),
                Band(720, 1200, 7, BandKind.PEAK),
                Band(1200, 1320, 10, BandKind.OFFPEAK),
                Band(1320, 1440, 15, BandKind.LATE),
            ),
        ),
        green = LineSpec(
            firstFwd = 291,
            firstRev = 291,
            lastFwd = 1397,
            lastRev = 1387,
            bands = listOf(
                Band(300, 720, 20, BandKind.OFFPEAK),
                Band(720, 1200, 14, BandKind.PEAK),
                Band(1200, 1320, 20, BandKind.OFFPEAK),
                Band(1320, 1440, 30, BandKind.LATE),
            ),
        ),
        inter = LineSpec(
            firstFwd = 301,
            firstRev = 295,
            lastFwd = 1387,
            lastRev = 1388,
            bands = listOf(
                Band(300, 720, 20, BandKind.OFFPEAK),
                Band(720, 1200, 14, BandKind.PEAK),
                Band(1200, 1320, 20, BandKind.OFFPEAK),
                Band(1320, 1440, 30, BandKind.LATE),
            ),
        ),
        yellow = LineSpec(
            firstFwd = 420,
            firstRev = 420,
            lastFwd = 1260,
            lastRev = 1260,
            bands = listOf(
                Band(420, 1440, 15, BandKind.OFFPEAK),
            ),
        ),
        loop = null,
    )

    fun day(type: DayType): DaySpec = when (type) {
        DayType.WEEKDAY -> weekday
        DayType.SATURDAY -> saturday
        DayType.SUNDAY -> sunday
    }

    /** Public holidays that run the Sunday timetable. Check festival dates against the Tamil Nadu G.O. each year. */
    val holidays: Map<LocalDate, String> = mapOf(
        LocalDate.parse("2026-01-01") to "New Year",
        LocalDate.parse("2026-01-15") to "Pongal",
        LocalDate.parse("2026-01-16") to "Thiruvalluvar Day",
        LocalDate.parse("2026-01-26") to "Republic Day",
        LocalDate.parse("2026-05-01") to "May Day",
        LocalDate.parse("2026-08-15") to "Independence Day",
        LocalDate.parse("2026-10-02") to "Gandhi Jayanti",
        LocalDate.parse("2026-10-19") to "Ayudha Pooja",
        LocalDate.parse("2026-10-20") to "Vijayadasami",
        LocalDate.parse("2026-12-25") to "Christmas",
        LocalDate.parse("2027-01-01") to "New Year",
        LocalDate.parse("2027-01-26") to "Republic Day",
    )
}
