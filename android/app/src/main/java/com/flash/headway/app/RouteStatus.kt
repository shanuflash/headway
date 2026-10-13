package com.flash.headway.app

import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.RouteDeparture
import com.flash.headway.engine.Schedule

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

fun routeStatus(s: Schedule, route: SavedRoute, now: Long, lastWarn: Boolean, count: Int): RouteStatus {
    val today = s.today(now).date
    val deps = s.routeDepartures(route.from, route.to, now, count)
    val next = deps.firstOrNull()
    val last = s.lastRouteDeparture(route.from, route.to, now)
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
