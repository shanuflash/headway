package com.flash.headway.app

import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.RouteDeparture
import com.flash.headway.engine.Schedule
import com.flash.headway.engine.asDeparture
import com.flash.headway.engine.routeGroup
import java.util.concurrent.ConcurrentHashMap

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
) {
    val next: RouteDeparture? get() = departures.firstOrNull()

    /** Index of the first departure that belongs to tomorrow, or -1. */
    val tomorrowFrom: Int get() = if (closed) 0 else departures.indexOfFirst { it.trip.serviceDate != departures.first().trip.serviceDate }

    /** When this card next changes: the shown train leaves. */
    val nextChange: Long? get() = next?.dep
}

/**
 * Departures on [route] with the rider's static delay applied: trains that run [delayMin] late
 * leave that much after their timetabled time, so look that far back for the ones still to come.
 */
fun Schedule.delayedDepartures(route: SavedRoute, nowMs: Long, limit: Int, delayMin: Int): List<RouteDeparture> {
    val off = delayMin * MINUTE
    val raw = if (isDirect(route)) {
        routeDepartures(route.from, route.to, nowMs - off, limit)
    } else {
        // No direct train: a trip with changes, planned as whole journeys.
        journeys(route.from, route.to, nowMs - off, limit).map { it.asDeparture() }
    }
    return raw.map { it.shifted(off) }
}

private val directCache = ConcurrentHashMap<String, Boolean>()

/** Whether a train runs straight from the route's start to its end, so it needs no change. */
fun isDirect(route: SavedRoute): Boolean = directCache.getOrPut(route.id) { routeGroup(route.from, route.to) != null }

/** Moved by the route's delay; every leg of a trip with changes moves with it. */
private fun RouteDeparture.shifted(off: Long) =
    if (off == 0L) this else copy(dep = dep + off, arr = arr + off, legs = legs?.map { it.copy(dep = it.dep + off, arr = it.arr + off) })

fun routeStatus(s: Schedule, route: SavedRoute, now: Long, lastWarn: Boolean, count: Int, delayMin: Int = 0): RouteStatus {
    val today = s.today(now).date
    val off = delayMin * MINUTE
    val deps = s.delayedDepartures(route, now, count, delayMin)
    val next = deps.firstOrNull()
    val last = (if (isDirect(route)) s.lastRouteDeparture(route.from, route.to, now - off) else s.lastJourney(route.from, route.to, now - off)?.asDeparture())
        ?.shifted(off)
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
