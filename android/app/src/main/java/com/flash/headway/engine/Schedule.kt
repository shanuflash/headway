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

/**
 * The advisory feed (advisories.json; the shape is documented in js/live.js), as far as the
 * timetable cares: holidays, and adjustments that change a day's timetable, extend service past
 * the last train, or delay trains in a window.
 */
data class Feed(
    val updated: String? = null,
    val holidays: Map<LocalDate, String> = emptyMap(),
    val notices: List<Notice> = emptyList(),
    val adjustments: List<Adjustment> = emptyList(),
)

data class Notice(
    val id: String,
    val level: String,
    val title: String,
    val body: String? = null,
    val link: String? = null,
    val from: Long? = null,
    val to: Long? = null,
)

/** One of: [dayType] for [date]; [extendUntil] (minutes past midnight) on [date]; or [delay] minutes from [from] to [to]. */
data class Adjustment(
    val date: LocalDate? = null,
    val dayType: DayType? = null,
    val extendUntil: Int? = null,
    val headway: Int? = null,
    val from: Long? = null,
    val to: Long? = null,
    val delay: Int? = null,
    val lines: Set<Line>? = null,
    val reason: String? = null,
)

/** Notices to show at [nowMs]. */
fun activeNotices(feed: Feed?, nowMs: Long): List<Notice> =
    feed?.notices.orEmpty().filter { (it.from == null || nowMs >= it.from) && (it.to == null || nowMs <= it.to) }

fun dayTypeFor(date: LocalDate, override: DayOverride?, feed: Feed? = null): DayInfo {
    if (override != null && override.date == date) return DayInfo(date, override.type, "Set by you")
    feed?.adjustments?.firstOrNull { it.date == date && it.dayType != null }?.let { return DayInfo(date, it.dayType!!, it.reason ?: "CMRL advisory") }
    (feed?.holidays?.get(date) ?: Timetable.holidays[date])?.let { return DayInfo(date, DayType.SUNDAY, it) }
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
    /** Which timetable the day runs (weekday, Saturday, Sunday/holiday). */
    val dayType: DayType = DayType.WEEKDAY,
    /** Minutes the rider's delay for this train's line and direction moved it (see [Schedule]'s lineDelays). */
    val lineDelay: Int = 0,
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

/** A way from one station to another: a direct train, or with [legs] when it takes a change. */
data class RouteDeparture(
    val trip: Trip,
    val dep: Long,
    val arr: Long,
    val legs: List<Leg>? = null,
    /** The timetabled departure, before the rider's delay moved it (null when it hasn't been). */
    val timetabled: Long? = null,
    /** Minutes this departure was moved by: its own delay, or the route's. */
    val delay: Int = 0,
)

/** A planned journey as a route departure: its first train, overall dep and arr, and its legs. */
fun Journey.asDeparture(): RouteDeparture = RouteDeparture(legs.first().trip, dep, arr, legs)

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

/** How a station that needs a change is reached: the number of changes and where. */
data class Transfer(val changes: Int, val via: List<String>)

/**
 * Stations that need a change from [from], with the fewest changes (up to 2) and, among those,
 * the least riding. Direct destinations are left out. A quick structural guess for the picker;
 * the timetable planner picks the real interchange.
 */
fun transferDestinations(from: String): Map<String, Transfer> {
    class Best(val changes: Int, val minutes: Int, val via: List<String>)
    class Front(val at: String, val minutes: Int, val via: List<String>)
    val isInterchange = { id: String -> Network.stations.getValue(id).lines.size > 1 }
    val best = HashMap<String, Best>()
    for (g in directDestinations(from)) for ((id, m) in g.stations) best[id] = Best(0, m, emptyList())
    var frontier = best.filter { isInterchange(it.key) }.map { Front(it.key, it.value.minutes, listOf(it.key)) }
    var changes = 1
    while (changes <= 2 && frontier.isNotEmpty()) {
        val next = ArrayList<Front>()
        for (f in frontier) {
            for (g in directDestinations(f.at)) {
                for ((id, m) in g.stations) {
                    if (id == from) continue
                    val minutes = f.minutes + m
                    val cur = best[id]
                    if (cur != null && (cur.changes < changes || (cur.changes == changes && cur.minutes <= minutes))) continue
                    best[id] = Best(changes, minutes, f.via)
                    if (isInterchange(id)) next += Front(id, minutes, f.via + id)
                }
            }
        }
        frontier = next
        changes++
    }
    return best.filter { it.value.changes > 0 && it.key != from }.mapValues { Transfer(it.value.changes, it.value.via) }
}

/** The most a trip listed for leaving sooner may leave before the quickest way, in minutes. */
private const val SOONER_MAX = 15

private class Conn(val trip: Trip, val from: String, val to: String, val dep: Long, val arr: Long)

private class Window(val trips: List<Trip>, val conns: Array<Conn>)

/**
 * Builds every train of a service day from the official first/last trains and headway bands,
 * then answers routing questions with the Connection Scan Algorithm.
 * Not thread-safe: confine each instance to one thread.
 */
/** The line a rider's line delay is set on: short loops run on the Blue Line. */
fun lineKey(route: RouteId): RouteId = if (route == RouteId.LOOP) RouteId.BLUE else route

/** A line delay's key, "BLUE:FWD": the line (short loops count as Blue) and the direction. */
fun lineDirKey(route: RouteId, dir: Dir): String = "${lineKey(route).name}:${dir.name}"

val Trip.lineDirKey: String get() = lineDirKey(route, dir)

/** The end of [route]'s line its trains head to in [dir] (a short loop's is the Blue Line's). */
fun lineTerminal(route: RouteId, dir: Dir): String {
    val r = Network.routes.getValue(lineKey(route))
    return if (dir == Dir.FWD) r.fwd.last().first else r.fwd.first().first
}

/**
 * [lineDelays]: minutes the rider says each line runs off the timetable in each direction
 * (negative early), by [lineDirKey]. Applied to every such train, so trips with changes are
 * planned on them.
 */
class Schedule(
    private val override: DayOverride? = null,
    private val feed: Feed? = null,
    private val lineDelays: Map<String, Int> = emptyMap(),
) {
    private val days = HashMap<LocalDate, List<Trip>>()
    private val windows = HashMap<LocalDate, Window>()
    private val lastCache = HashMap<String, Journey?>()

    fun today(nowMs: Long): DayInfo = dayTypeFor(serviceDate(nowMs), override, feed)

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
        val type = dayTypeFor(date, override, feed).type
        val spec = Timetable.day(type)
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
                for (dep in originTimes(lineSpec, dir) + extensionTimes(date, route.line, lineSpec, dir)) {
                    val stops = stopsFor(route, dir, dep.toDouble()).map { (s, t) -> Stop(s, abs(t)) }
                    trips += Trip(routeId, route.line, dir, stops, date, bandAt(lineSpec.bands, dep), type)
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
                loops += Trip(RouteId.LOOP, Line.BLUE, trip.dir, stops, date, trip.band, type)
            }
            trips += loops
        }
        return applyDelays(trips)
    }

    /** Feed "extendUntil" adjustments add trains past the published last train (event nights). */
    private fun extensionTimes(date: LocalDate, line: Line, spec: LineSpec, dir: Dir): List<Int> {
        val ext = feed?.adjustments?.firstOrNull { it.date == date && it.extendUntil != null && (it.lines == null || line in it.lines) }
            ?: return emptyList()
        var until = ext.extendUntil!!
        if (until < ROLLOVER_HOUR * 60) until += 24 * 60
        val step = ext.headway ?: 15
        val last = if (dir == Dir.FWD) spec.lastFwd else spec.lastRev
        return generateSequence(last + step) { it + step }.takeWhile { it <= until }.toList()
    }

    /** Feed "delay" adjustments shift trains running inside a window, per line; then the rider's line delays. */
    private fun applyDelays(trips: List<Trip>): List<Trip> {
        val delays = feed?.adjustments?.filter { it.delay != null && it.from != null && it.to != null }.orEmpty()
        if (delays.isEmpty() && lineDelays.values.all { it == 0 }) return trips
        return trips.map { trip ->
            var shift = 0L
            for (d in delays) {
                if (d.lines != null && trip.line !in d.lines) continue
                if (trip.stops.last().at < d.from!! || trip.stops.first().at > d.to!!) continue
                shift += d.delay!! * MINUTE
            }
            val line = lineDelays[trip.lineDirKey] ?: 0
            shift += line * MINUTE
            if (shift == 0L) {
                trip
            } else {
                Trip(trip.route, trip.line, trip.dir, trip.stops.map { Stop(it.station, it.at + shift) }, trip.serviceDate, trip.band, trip.dayType, line)
            }
        }
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

    private fun scan(from: String, to: String, startMs: Long, boardFirst: Trip? = null): Journey? {
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
            // With [boardFirst], the trip has to start on that train: no other boards at the start.
            val startsElsewhere = boardFirst != null && c.from == from && c.trip !== boardFirst
            if (e != null && arrivedBy !== c.trip && !startsElsewhere) {
                val ready = if (c.from == from) e else e + Network.transfer(c.from) * MINUTE
                val legs = legCount.getValue(c.from)
                val cur = enter[c.trip]
                // Nobody waits at an interchange overnight: a change stays within one service day.
                val sameDay = c.from == from || arrivedBy?.serviceDate == c.trip.serviceDate
                if (ready <= c.dep && sameDay && (cur == null || legs <= cur.second)) enter[c.trip] = c to legs
            }
            val en = enter[c.trip] ?: continue
            // Earliest arrival first; arriving at the same time, the way with fewer changes.
            val cur = earliest[c.to]
            if (cur == null || c.arr < cur || (c.arr == cur && en.second + 1 < legCount.getValue(c.to))) {
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

    /**
     * The next [count] journeys, by departure. Each is the earliest arrival from its start, and
     * the latest start for that arrival, so the quickest. As well, when a train leaving up to
     * [SOONER_MAX] min earlier still arrives as early (often with a change), the soonest such
     * trip is listed too, so a rider already at the platform can take it instead of waiting.
     */
    fun journeys(from: String, to: String, startMs: Long, count: Int = 4): List<Journey> {
        // By arrival: the quickest way (latest start) and the soonest start.
        class Group(var quick: Journey, var soon: Journey)
        val groups = LinkedHashMap<Long, Group>()
        fun add(j: Journey) {
            val g = groups[j.arr]
            if (g == null) {
                groups[j.arr] = Group(j, j)
            } else {
                if (j.dep > g.quick.dep) g.quick = j
                if (j.dep < g.soon.dep) g.soon = j
            }
        }
        var t = startMs
        var n = 0
        while (n++ < count * 20) {
            val j = plan(from, to, t) ?: break
            // Settled: enough arrivals listed, and this one arrives after them all.
            if (groups.size >= count && j.arr > groups.keys.max()) break
            leavesSooner(from, to, t, j).forEach(::add)
            add(j)
            t = j.dep + 1
        }
        // Leaving sooner is only worth it a little sooner, not an hour early to wait at a change.
        return groups.values.flatMap { g ->
            if (g.soon !== g.quick && g.quick.dep - g.soon.dep <= SOONER_MAX * MINUTE) listOf(g.quick, g.soon) else listOf(g.quick)
        }
            .sortedBy { it.dep }
            .take(count)
    }

    /** Trips that leave [from] in [t, j.dep) and still arrive by [j]'s arrival, each on its own first train. */
    private fun leavesSooner(from: String, to: String, t: Long, j: Journey): List<Journey> {
        val out = ArrayList<Journey>()
        val seen = HashSet<Trip>()
        for (d in departures(from, maxOf(t, j.dep - SOONER_MAX * MINUTE), 40)) {
            if (d.at >= j.dep) break
            if (!seen.add(d.trip)) continue
            val alt = scan(from, to, d.at, d.trip) ?: continue
            // Never change onto a train that could be boarded at the start instead.
            val pointless = alt.legs.drop(1).any { l -> l.trip.stops.any { it.station == from && it.at >= d.at } }
            if (!pointless && alt.dep < j.dep && alt.arr <= j.arr) out += alt
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
