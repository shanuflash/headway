package com.flash.headway.app

import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.RouteDeparture
import com.flash.headway.engine.Schedule

/** Everything a route card or widget needs: which train you can make, and what you'll miss. */
data class RouteStatus(
    val route: SavedRoute,
    val now: Long,
    /** When you reach the platform. */
    val reach: Long,
    val next: RouteDeparture?,
    val more: List<RouteDeparture>,
    val missed: RouteDeparture?,
    val last: RouteDeparture?,
    /** The first catchable train is on the next service day. */
    val closed: Boolean,
    /** Trains still run tonight, just not ones you can reach. */
    val missedLast: Boolean,
    val running: Boolean,
    val late: Boolean,
    val remainingToday: Int,
    val tomorrow: RouteDeparture?,
) {
    /** When this card next changes: the shown train leaves, or stops being catchable. */
    val nextChange: Long?
        get() = next?.let { minOf(it.dep, it.dep - route.arrive * MINUTE).takeIf { t -> t > now } ?: it.dep }
}

fun routeStatus(s: Schedule, route: SavedRoute, now: Long, lastWarn: Boolean): RouteStatus {
    val today = s.today(now).date
    val reach = now + route.arrive * MINUTE
    val deps = s.routeDepartures(route.from, route.to, now, 16)
    val catchable = deps.filter { it.dep >= reach }
    val next = catchable.firstOrNull()
    val last = s.lastRouteDeparture(route.from, route.to, now)
    val closed = next != null && next.trip.serviceDate != today
    return RouteStatus(
        route = route,
        now = now,
        reach = reach,
        next = next,
        more = catchable.drop(1).take(2),
        missed = deps.lastOrNull { it.dep < reach },
        last = last,
        closed = closed,
        missedLast = closed && last != null && last.dep >= now,
        running = last != null && last.dep >= now,
        late = !closed && next != null && last != null && lastWarn && last.dep - now < 60 * MINUTE,
        remainingToday = catchable.count { it.trip.serviceDate == today },
        tomorrow = catchable.firstOrNull { it.trip.serviceDate != today },
    )
}
