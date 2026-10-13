package com.flash.headway.engine

import com.flash.headway.data.Band
import com.flash.headway.data.DayType
import com.flash.headway.data.Line
import com.flash.headway.data.LineSpec
import com.flash.headway.data.Network
import com.flash.headway.data.Route
import com.flash.headway.data.RouteId
import com.flash.headway.data.Timetable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

/** Chennai runs on IST whatever zone the phone is set to. */
val IST: ZoneId = ZoneId.of("Asia/Kolkata")

const val MINUTE = 60_000L

// Trains after midnight still belong to the previous service day.
private const val ROLLOVER_HOUR = 3

fun serviceDate(nowMs: Long): LocalDate {
    val z = Instant.ofEpochMilli(nowMs).atZone(IST)
    return if (z.hour < ROLLOVER_HOUR) z.toLocalDate().minusDays(1) else z.toLocalDate()
}

fun startOfDay(date: LocalDate): Long = date.atStartOfDay(IST).toInstant().toEpochMilli()

data class DayOverride(val date: LocalDate, val type: DayType)

data class DayInfo(val date: LocalDate, val type: DayType, val reason: String)

fun dayTypeFor(date: LocalDate, override: DayOverride?): DayInfo {
    if (override != null && override.date == date) return DayInfo(date, override.type, "Set by you")
    Timetable.holidays[date]?.let { return DayInfo(date, DayType.SUNDAY, it) }
    return when (date.dayOfWeek) {
        DayOfWeek.SUNDAY -> DayInfo(date, DayType.SUNDAY, "Sunday")
        DayOfWeek.SATURDAY -> DayInfo(date, DayType.SATURDAY, "Saturday")
        else -> DayInfo(date, DayType.WEEKDAY, "Weekday")
    }
}

fun bandAt(bands: List<Band>, minute: Int): Band =
    bands.firstOrNull { minute >= it.from && minute < it.to }
        ?: if (minute < bands.first().from) bands.first() else bands.last()

enum class Dir { FWD, REV }

data class Stop(val station: String, val at: Long)

/** One train run. Identity matters: the router keys its maps on the object. */
class Trip(
    val route: RouteId,
    val line: Line,
    val dir: Dir,
    val stops: List<Stop>,
    val serviceDate: LocalDate,
    val band: Band,
) {
    val origin: String get() = stops.first().station
    val dest: String get() = stops.last().station
    val dep: Long get() = stops.first().at

    fun indexOf(station: String): Int = stops.indexOfFirst { it.station == station }
}

data class Leg(val trip: Trip, val from: String, val to: String, val dep: Long, val arr: Long, val stops: List<Stop>)

data class Journey(val from: String, val to: String, val legs: List<Leg>) {
    val dep: Long get() = legs.first().dep
    val arr: Long get() = legs.last().arr
    val changes: Int get() = legs.size - 1
}

data class Departure(val trip: Trip, val at: Long)

data class RouteDeparture(val trip: Trip, val dep: Long, val arr: Long)

data class DestinationGroup(val next: String, val line: Line, val terminals: List<String>, val stations: List<Pair<String, Int>>)

/**
 * Stations reachable from [from] without changing trains, grouped by direction of travel
 * (the next stop). Stations come in travel order with typical ride minutes.
 */
fun directDestinations(from: String): List<DestinationGroup> {
    class Acc(val line: Line) {
        val terminals = LinkedHashSet<String>()
        val stations = LinkedHashMap<String, Int>()
    }
    val groups = LinkedHashMap<String, Acc>()
    for (id in listOf(RouteId.BLUE, RouteId.GREEN, RouteId.INTER, RouteId.YELLOW)) {
        val route = Network.routes.getValue(id)
        for (seq in listOf(route.fwd, route.rev)) {
            val i = seq.indexOfFirst { it.first == from }
            if (i < 0 || i == seq.lastIndex) continue
            val acc = groups.getOrPut(seq[i + 1].first) { Acc(route.line) }
            acc.terminals += seq.last().first
            for (k in i + 1 until seq.size) {
                val minutes = Math.round(seq[k].second - seq[i].second).toInt()
                val (s, _) = seq[k]
                if ((acc.stations[s] ?: Int.MAX_VALUE) > minutes) acc.stations[s] = minutes
            }
        }
    }
    return groups.map { (next, acc) ->
        DestinationGroup(next, acc.line, acc.terminals.toList(), acc.stations.entries.sortedBy { it.value }.map { it.key to it.value })
    }
}

/** The group a saved route belongs to, which names its line and direction. */
fun routeGroup(from: String, to: String): DestinationGroup? =
    directDestinations(from).firstOrNull { g -> g.stations.any { it.first == to } }

private class Conn(val trip: Trip, val from: String, val to: String, val dep: Long, val arr: Long)

private class Window(val trips: List<Trip>, val conns: Array<Conn>)

/**
 * Builds every train of a service day from the official first/last trains and headway bands,
 * then answers routing questions with the Connection Scan Algorithm.
 * Not thread-safe: confine each instance to one thread.
 */
class Schedule(private val override: DayOverride? = null) {
    private val days = HashMap<LocalDate, List<Trip>>()
    private val windows = HashMap<LocalDate, Window>()
    private val lastCache = HashMap<String, Journey?>()

    fun today(nowMs: Long): DayInfo = dayTypeFor(serviceDate(nowMs), override)

    fun day(date: LocalDate): List<Trip> = days.getOrPut(date) { buildDay(date) }

    // ---------- timetable generation ----------

    private fun originTimes(spec: LineSpec, dir: Dir): List<Int> {
        val first = if (dir == Dir.FWD) spec.firstFwd else spec.firstRev
        val last = if (dir == Dir.FWD) spec.lastFwd else spec.lastRev
        val out = ArrayList<Int>()
        var t = first
        while (t < last) {
            out += t
            t += bandAt(spec.bands, t).headway
        }
        // Land exactly on the published last train; drop a generated train that would crowd it.
        val prev = out.lastOrNull()
        if (prev != null && last - prev < bandAt(spec.bands, prev).headway / 2.0) out.removeAt(out.lastIndex)
        out += last
        return out
    }

    private fun stopsFor(route: Route, dir: Dir, dep: Double, from: String? = null, to: String? = null): List<Pair<String, Double>> {
        var seq = if (dir == Dir.FWD) route.fwd else route.rev
        if (from != null && to != null) {
            val a = seq.indexOfFirst { it.first == from }
            val b = seq.indexOfFirst { it.first == to }
            seq = seq.subList(a, b + 1)
        }
        val base = seq.first().second
        return seq.map { (s, cum) -> s to dep + cum - base }
    }

    private fun buildDay(date: LocalDate): List<Trip> {
        val spec = Timetable.day(dayTypeFor(date, override).type)
        val base = startOfDay(date)
        fun abs(minutes: Double) = base + (minutes * MINUTE).roundToLong()
        val trips = ArrayList<Trip>()
        val patterns = listOf(
            RouteId.BLUE to spec.blue,
            RouteId.GREEN to spec.green,
            RouteId.INTER to spec.inter,
            RouteId.YELLOW to spec.yellow,
        )
        for ((routeId, lineSpec) in patterns) {
            val route = Network.routes.getValue(routeId)
            for (dir in Dir.entries) {
                for (dep in originTimes(lineSpec, dir)) {
                    val stops = stopsFor(route, dir, dep.toDouble()).map { (s, t) -> Stop(s, abs(t)) }
                    trips += Trip(routeId, route.line, dir, stops, date, bandAt(lineSpec.bands, dep))
                }
            }
        }

        // Short loops shadow the Blue mainline by `offset` minutes inside the peak windows.
        spec.loop?.let { loop ->
            val blue = Network.routes.getValue(RouteId.BLUE)
            val loops = ArrayList<Trip>()
            for (trip in trips) {
                if (trip.route != RouteId.BLUE) continue
                val (start, end) = if (trip.dir == Dir.FWD) Network.LOOP_FROM to Network.LOOP_TO else Network.LOOP_TO to Network.LOOP_FROM
                val at = trip.stops.firstOrNull { it.station == start } ?: continue
                val dep = (at.at - base).toDouble() / MINUTE + loop.offset
                if (loop.windows.none { dep >= it.first && dep < it.last + 1 }) continue
                val stops = stopsFor(blue, trip.dir, dep, start, end).map { (s, t) -> Stop(s, abs(t)) }
                loops += Trip(RouteId.LOOP, Line.BLUE, trip.dir, stops, date, trip.band)
            }
            trips += loops
        }
        return trips
    }

    private fun window(nowMs: Long): Window {
        val date = serviceDate(nowMs)
        return windows.getOrPut(date) {
            val trips = day(date) + day(date.plusDays(1))
            val conns = ArrayList<Conn>(trips.size * 16)
            for (trip in trips) {
                for (i in 0 until trip.stops.size - 1) {
                    val a = trip.stops[i]
                    val b = trip.stops[i + 1]
                    conns += Conn(trip, a.station, b.station, a.at, b.at)
                }
            }
            conns.sortWith(compareBy<Conn> { it.dep }.thenBy { it.arr })
            Window(trips, conns.toTypedArray())
        }
    }

    // ---------- queries ----------

    /** Upcoming departures from a station, all directions. */
    fun departures(station: String, nowMs: Long, limit: Int = 80): List<Departure> {
        val out = ArrayList<Departure>()
        for (trip in window(nowMs).trips) {
            val i = trip.indexOf(station)
            if (i < 0 || i == trip.stops.lastIndex) continue
            val at = trip.stops[i].at
            if (at >= nowMs) out += Departure(trip, at)
        }
        out.sortBy { it.at }
        return out.take(limit)
    }

    /** Direct trains from [from] that later stop at [to], departing at or after [nowMs]. */
    fun routeDepartures(from: String, to: String, nowMs: Long, limit: Int = 16): List<RouteDeparture> {
        val out = ArrayList<RouteDeparture>()
        for (trip in window(nowMs).trips) {
            routeDeparture(trip, from, to)?.takeIf { it.dep >= nowMs }?.let { out += it }
        }
        out.sortBy { it.dep }
        return out.take(limit)
    }

    /** The last direct train of the current service day from [from] to [to]. */
    fun lastRouteDeparture(from: String, to: String, nowMs: Long): RouteDeparture? =
        day(serviceDate(nowMs)).mapNotNull { routeDeparture(it, from, to) }.maxByOrNull { it.dep }

    private fun routeDeparture(trip: Trip, from: String, to: String): RouteDeparture? {
        val i = trip.indexOf(from)
        if (i < 0) return null
        for (k in i + 1 until trip.stops.size) {
            if (trip.stops[k].station == to) return RouteDeparture(trip, trip.stops[i].at, trip.stops[k].at)
        }
        return null
    }

    /**
     * Earliest arrival leaving no earlier than [startMs]. When nothing reaches [to] tonight
     * (a line has closed), plan again from the start of the next service day.
     */
    fun plan(from: String, to: String, startMs: Long): Journey? {
        if (from == to) return null
        scan(from, to, startMs)?.let { return it }
        val tomorrow = startOfDay(serviceDate(startMs).plusDays(1)) + ROLLOVER_HOUR * 60 * MINUTE
        return if (tomorrow > startMs) scan(from, to, tomorrow) else null
    }

    private fun scan(from: String, to: String, startMs: Long): Journey? {
        val conns = window(startMs).conns
        val earliest = HashMap<String, Long>().apply { put(from, startMs) }
        val legCount = HashMap<String, Int>().apply { put(from, 0) }
        val enter = HashMap<Trip, Pair<Conn, Int>>()
        val ptr = HashMap<String, Pair<Conn, Conn>>()

        var i = lowerBound(conns, startMs)
        while (i < conns.size) {
            val c = conns[i++]
            val best = earliest[to]
            if (best != null && best <= c.dep) break
            // Board each trip where it costs the fewest legs; on a tie take the later stop, so a
            // route never rides away from an interchange to catch the same train further back.
            val e = earliest[c.from]
            val arrivedBy = ptr[c.from]?.second?.trip
            if (e != null && arrivedBy !== c.trip) {
                val ready = if (c.from == from) e else e + Network.transfer(c.from) * MINUTE
                val legs = legCount.getValue(c.from)
                val cur = enter[c.trip]
                // Nobody waits at an interchange overnight: a change stays within one service day.
                val sameDay = c.from == from || arrivedBy?.serviceDate == c.trip.serviceDate
                if (ready <= c.dep && sameDay && (cur == null || legs <= cur.second)) enter[c.trip] = c to legs
            }
            val en = enter[c.trip] ?: continue
            val cur = earliest[c.to]
            if (cur == null || c.arr < cur) {
                earliest[c.to] = c.arr
                legCount[c.to] = en.second + 1
                ptr[c.to] = en.first to c
            }
        }
        if (!ptr.containsKey(to)) return null

        val legs = ArrayList<Leg>()
        var s = to
        var guard = 0
        while (s != from && guard++ < 8) {
            val (en, ex) = ptr.getValue(s)
            val trip = en.trip
            val a = trip.stops.indexOfFirst { it.station == en.from && it.at == en.dep }
            val b = trip.stops.indexOfFirst { it.station == ex.to && it.at == ex.arr }
            legs.add(0, Leg(trip, en.from, ex.to, en.dep, ex.arr, trip.stops.subList(a, b + 1)))
            s = en.from
        }
        return if (s == from) Journey(from, to, legs) else null
    }

    /** The next [count] journeys; a later train that arrives just as early replaces an earlier one. */
    fun journeys(from: String, to: String, startMs: Long, count: Int = 4): List<Journey> {
        val out = ArrayList<Journey>()
        var t = startMs
        var n = 0
        while (n++ < count * 20 && out.size < count) {
            val j = plan(from, to, t) ?: break
            val same = out.indexOfFirst { it.arr == j.arr }
            if (same >= 0) out[same] = j else out += j
            t = j.dep + 1
        }
        return out
    }

    /** Last departure of the current service day that still reaches [to]: binary search on start time. */
    fun lastJourney(from: String, to: String, nowMs: Long): Journey? {
        val date = serviceDate(nowMs)
        val key = "$from|$to|$date"
        if (lastCache.containsKey(key)) return lastCache[key]
        var best = plan(from, to, nowMs)?.takeIf { it.legs.first().trip.serviceDate == date }
        if (best != null) {
            var lo = best.dep
            var hi = nowMs + 30 * 60 * MINUTE
            while (hi - lo > MINUTE) {
                val mid = (lo + hi) / 2
                val j = plan(from, to, mid)
                if (j != null && j.legs.first().trip.serviceDate == date) {
                    best = j
                    lo = maxOf(mid, j.dep)
                } else {
                    hi = mid
                }
            }
        }
        lastCache[key] = best
        return best
    }

    private fun lowerBound(conns: Array<Conn>, t: Long): Int {
        var lo = 0
        var hi = conns.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (conns[mid].dep < t) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
