package com.flash.headway.app

import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.RouteDeparture
import com.flash.headway.engine.Schedule
import com.flash.headway.engine.asDeparture
import com.flash.headway.engine.routeGroup
import com.flash.headway.engine.IST
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import com.flash.headway.data.DayType
import com.flash.headway.engine.serviceDate

/** The next departures on a saved route, plus what's needed for last-train and closed states. */
data class RouteStatus(
    val route: SavedRoute,
    val now: Long,
    /** Upcoming departures, the first one being the next train. May run into tomorrow. */
    val departures: List<RouteDeparture>,
    val last: RouteDeparture?,
    /** The next train is on the next service day. */
    val closed: Boolean,
    /** Trains still run tonight from this station, just none left on this route. */
    val running: Boolean,
    val late: Boolean,
    val remainingToday: Int,
    /** Set when this is planned for another time instead of now (quick look only). */
    val plan: PlanTime? = null,
    /** The option shown first: the next train, or for "arrive by", the latest one that makes it. */
    val best: Int = 0,
    /** A quick look, not a saved route (it may be the same stations as one). */
    val isPreview: Boolean = false,
) {
    val next: RouteDeparture? get() = departures.firstOrNull()

    /** Index of the first departure that belongs to tomorrow, or -1. */
    val tomorrowFrom: Int get() = if (closed) 0 else departures.indexOfFirst { it.trip.serviceDate != departures.first().trip.serviceDate }

    /** When this card next changes: the shown train leaves. */
    val nextChange: Long? get() = if (plan != null) null else next?.dep
}

/**
 * Departures on [route] with the rider's static delay applied: trains that run [delayMin] late
 * leave that much after their timetabled time, so look that far back for the ones still to come.
 */
fun Schedule.delayedDepartures(route: SavedRoute, nowMs: Long, limit: Int, delayMin: Int): List<RouteDeparture> =
    delayedDepartures(route, nowMs, limit, DelayRule(delayMin))

/**
 * Departures with each train's own delay, or the route's: a late train leaves after its
 * timetabled time, so look back as far as a delay can go and keep the ones still to come.
 */
fun Schedule.delayedDepartures(route: SavedRoute, nowMs: Long, limit: Int, rule: DelayRule): List<RouteDeparture> {
    // Every route is a journey: the planner's earliest way there for each departure, a direct
    // train or one with changes, whichever arrives first.
    val raw = journeys(route.from, route.to, nowMs - DELAY_MAX * MINUTE, limit + 6).map { it.asDeparture() }
    return raw.map { it.delayed(rule.forDeparture(it.dep, it.trip.dayType)) }.filter { it.dep >= nowMs }.sortedBy { it.dep }.take(limit)
}

/**
 * How late a route's trains run: [route] minutes for all of them, except trains with their own
 * delay in [byTime], keyed by the timetabled departure from the boarding station:
 * "08:14/2026-11-12" just that day, "08:14/WEEKDAY" (or SATURDAY, SUNDAY) that kind of day, and
 * "08:14" every day. The most specific one that fits wins.
 */
data class DelayRule(val route: Int = 0, val byTime: Map<String, Int> = emptyMap()) {
    /** The train's own delay and its key, if it has one. */
    fun own(timetabled: Long, dayType: DayType?): Pair<String, Int>? {
        for (key in trainKeys(timetabled, dayType)) byTime[key]?.let { return key to it }
        return null
    }

    fun forDeparture(timetabled: Long, dayType: DayType? = null): Int = own(timetabled, dayType)?.second ?: route

    /**
     * The timetabled time of a departure shown at [shown] under this rule. Trains with their own
     * delay are checked first (a shown 8:15 is the 8:13 with its own +2 before it's an 8:15 that
     * follows the route); otherwise it's the route's delay.
     */
    fun timetabledOf(shown: Long, dayType: DayType? = null): Long {
        for (d in byTime.values.toSet()) {
            val t = shown - d * MINUTE
            if (own(t, dayType)?.second == d) return t
        }
        return shown - route * MINUTE
    }
}

/** The keys a train's own delay may be under, most specific first. */
fun trainKeys(timetabled: Long, dayType: DayType?): List<String> {
    val time = timeKey(timetabled)
    return listOfNotNull("$time/${serviceDate(timetabled)}", dayType?.let { "$time/${it.name}" }, time)
}

/** "08:14": a departure as a daily key, in IST. */
fun timeKey(ms: Long): String = Instant.ofEpochMilli(ms).atZone(IST).let { "%02d:%02d".format(it.hour, it.minute) }

private val directCache = ConcurrentHashMap<String, Boolean>()

/** Whether a train runs straight from the route's start to its end, so it needs no change. */
fun isDirect(route: SavedRoute): Boolean = directCache.getOrPut(route.id) { routeGroup(route.from, route.to) != null }

/** Moved by [mins], remembering the timetabled time; every leg of a trip with changes moves with it. */
private fun RouteDeparture.delayed(mins: Int): RouteDeparture {
    val off = mins * MINUTE
    return copy(
        dep = dep + off,
        arr = arr + off,
        legs = legs?.map { it.copy(dep = it.dep + off, arr = it.arr + off) },
        timetabled = dep,
        delay = mins,
    )
}

fun routeStatus(s: Schedule, route: SavedRoute, now: Long, lastWarn: Boolean, count: Int, delayMin: Int = 0): RouteStatus =
    routeStatus(s, route, now, lastWarn, count, DelayRule(delayMin))

fun routeStatus(s: Schedule, route: SavedRoute, now: Long, lastWarn: Boolean, count: Int, rule: DelayRule): RouteStatus {
    val today = s.today(now).date
    val deps = s.delayedDepartures(route, now, count, rule)
    val next = deps.firstOrNull()
    val since = now - DELAY_MAX * MINUTE
    val last = s.lastJourney(route.from, route.to, since)?.asDeparture()?.let { it.delayed(rule.forDeparture(it.dep, it.trip.dayType)) }
    val closed = next != null && next.trip.serviceDate != today
    return RouteStatus(
        route = route,
        now = now,
        departures = deps,
        last = last,
        closed = closed,
        running = s.departures(route.from, now, 1).any { it.trip.serviceDate == today },
        late = !closed && next != null && last != null && lastWarn && last.dep - now < 60 * MINUTE,
        remainingToday = deps.count { it.trip.serviceDate == today },
    )
}

enum class PlanMode { LEAVE, ARRIVE }

/** Plan a quick look for another time: leave at, or arrive by, [at]. */
data class PlanTime(val mode: PlanMode, val at: Long)

/**
 * Trains for a chosen time instead of now. "Leave at": the first ones from then. "Arrive by":
 * the latest ones that still get there in time, found by probing the trip's length and looking
 * back from the deadline. Works for direct trains and trips with changes alike.
 */
fun planStatus(s: Schedule, route: SavedRoute, plan: PlanTime, count: Int, rule: DelayRule = DelayRule()): RouteStatus {
    val deps = when (plan.mode) {
        PlanMode.LEAVE -> s.delayedDepartures(route, plan.at, count, rule)
        PlanMode.ARRIVE -> {
            val probe = s.delayedDepartures(route, plan.at - 180 * MINUTE, 1, rule).firstOrNull()
            if (probe == null) {
                emptyList()
            } else {
                val start = plan.at - (probe.arr - probe.dep) - 45 * MINUTE
                s.delayedDepartures(route, start, 40, rule).filter { it.arr <= plan.at }.takeLast(count)
            }
        }
    }
    return RouteStatus(
        route = route,
        now = plan.at,
        departures = deps,
        last = null,
        closed = false,
        running = true,
        late = false,
        remainingToday = deps.size,
        plan = plan,
        best = if (plan.mode == PlanMode.ARRIVE) (deps.size - 1).coerceAtLeast(0) else 0,
    )
}

