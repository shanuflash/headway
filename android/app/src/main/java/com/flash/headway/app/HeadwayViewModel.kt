package com.flash.headway.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flash.headway.data.DayType
import com.flash.headway.data.Network
import com.flash.headway.data.RouteId
import com.flash.headway.engine.lineDirKey
import com.flash.headway.engine.lineKey
import com.flash.headway.engine.DayInfo
import com.flash.headway.engine.DayOverride
import com.flash.headway.engine.Feed
import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.Schedule
import com.flash.headway.engine.dayTypeFor
import com.flash.headway.engine.serviceDate
import com.flash.headway.ui.clock
import com.flash.headway.widget.Widgets
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HeadwayViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PrefsStore(app)
    private val engineThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "headway-engine").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    val prefs: StateFlow<Prefs?> = store.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** A route opened from search to look at without saving it. */
    private val _preview = MutableStateFlow<SavedRoute?>(null)
    val preview: StateFlow<SavedRoute?> = _preview.asStateFlow()

    /** The quick look planned for another time, or null for now. */
    private val _plan = MutableStateFlow<PlanTime?>(null)
    val plan: StateFlow<PlanTime?> = _plan.asStateFlow()

    private val _statuses = MutableStateFlow<List<RouteStatus>?>(null)
    val statuses: StateFlow<List<RouteStatus>?> = _statuses.asStateFlow()

    private val _previewStatus = MutableStateFlow<RouteStatus?>(null)
    val previewStatus: StateFlow<RouteStatus?> = _previewStatus.asStateFlow()

    /** The advisory feed: the cached copy straight away, then refreshed while the app is open. */
    private val _feed = MutableStateFlow(Advisories.cached(app))
    val feed: StateFlow<Feed?> = _feed.asStateFlow()

    /** Whether the app is on screen. The loops below only run then (the widget and FeedWorker cover the rest). */
    private val foreground = MutableStateFlow(true)

    fun setForeground(on: Boolean) {
        foreground.value = on
    }

    // Only touched on the engine thread.
    private var planMemo: Pair<Any, RouteStatus>? = null
    private var schedule = Schedule()
    private var scheduleOverride: DayOverride? = null
    private var scheduleFeed: Feed? = null
    private var scheduleLines: Map<String, Int> = emptyMap()

    private fun engine(p: Prefs): Schedule {
        val f = _feed.value
        if (p.override != scheduleOverride || f !== scheduleFeed || p.lineDelays != scheduleLines) {
            scheduleOverride = p.override
            scheduleFeed = f
            scheduleLines = p.lineDelays
            schedule = Schedule(p.override, f, p.lineDelays)
        }
        return schedule
    }

    init {
        viewModelScope.launch {
            combine(prefs.filterNotNull(), _preview, _plan, _feed, foreground) { p, pv, plan, _, fg -> if (fg) Triple(p, pv, plan) else null }.collectLatest { state ->
                // In the background nothing's shown, so nothing's worked out.
                val (p, pv, plan) = state ?: return@collectLatest
                Clock.offset = p.mockOffset
                var flipping = false
                while (true) {
                    val now = Clock.now()
                    // A countdown whose train has left is over: clear it so nothing brings it back.
                    if (p.reminder != null && p.reminder.dep < now - 60_000L) update { q -> if (q.reminder?.dep == p.reminder.dep) q.copy(reminder = null) else q }
                    // A route that switches direction by time is due to flip: show it flipped
                    // straight away, and save it once (the saved prefs then restart this loop).
                    val shown = applyFlips(p, now)
                    if (shown !== p && !flipping) {
                        flipping = true
                        flipNow()
                    }
                    val (list, previewNow) = withContext(engineThread) {
                        val s = engine(p)
                        shown.routes.map { r ->
                            val st = routeStatus(s, r, now, p.lastWarn, p.count, p.delayRule(r.id))
                            // The trip being followed on this route, planned again on the delays now.
                            val t = p.trip?.takeIf { it.routeId == r.id }
                            if (t != null) st.copy(trip = s.followTrip(r, t, p.lineDelays)) else st
                        } to
                            pv?.let { route ->
                                if (plan != null) {
                                    // A plan doesn't change as the clock runs: work it out once.
                                    val key = listOf(route, plan, p.count, p.delayRule(route.id), s)
                                    planMemo?.takeIf { it.first == key }?.second
                                        ?: planStatus(s, route, plan, p.count, p.delayRule(route.id)).also { planMemo = key to it }
                                } else {
                                    routeStatus(s, route, now, p.lastWarn, p.count)
                                }
                            }
                    }
                    _statuses.value = list
                    // A followed trip is over once it's arrived (or can't be found, or its route went).
                    p.trip?.let { t ->
                        val trip = list.firstOrNull { it.route.id == t.routeId }?.trip
                        if (trip == null || now > trip.arr + 2 * MINUTE) update { q -> if (q.trip == t) q.copy(trip = null) else q }
                    }
                    _previewStatus.value = previewNow?.copy(isPreview = true)
                    // Recompute the moment a shown train leaves, else every 15 s.
                    val boundary = (list + listOfNotNull(previewNow)).mapNotNull { it.nextChange }.minOrNull()
                    val wait = boundary?.let { (it - Clock.now() + 300).coerceIn(300L, 15_000L) } ?: 15_000L
                    delay(wait)
                }
            }
        }
        viewModelScope.launch {
            // CMRL advisories: check on open, then every half hour while on screen. A failed check
            // keeps the last copy; in the background, FeedWorker checks instead.
            foreground.collectLatest { fg ->
                while (fg) {
                    Advisories.refresh(getApplication())?.let { fresh ->
                        if (fresh != _feed.value) {
                            _feed.value = fresh
                            Widgets.updateAllAsync(getApplication())
                        }
                    }
                    delay(30 * 60_000L)
                }
            }
        }
        viewModelScope.launch {
            // Keep the widgets' copy of the routes current and redraw them when anything changes.
            // The redraw waits a moment, so dragging a slider redraws once, not on every step.
            prefs.filterNotNull().collectLatest { p ->
                Widgets.mirror(getApplication(), p)
                delay(400)
                Widgets.updateAllAsync(getApplication())
            }
        }
    }

    /** Next trains each way from a station, for the map's station sheet. On the engine thread. */
    suspend fun stationBoard(id: String): List<com.flash.headway.ui.StationBoard> = withContext(engineThread) {
        val p = prefs.value ?: return@withContext emptyList()
        val s = engine(p)
        val now = Clock.now()
        com.flash.headway.engine.directDestinations(id).map { g ->
            val trains = g.terminals
                .flatMap { t -> s.routeDepartures(id, t, now, 4).map { it to t } }
                .sortedBy { it.first.dep }
                .distinctBy { it.first.dep }
                .take(4)
            com.flash.headway.ui.StationBoard(g, com.flash.headway.ui.platformAt(id, g), trains)
        }
    }

    /**
     * The next trains on one line at [station], toward each end, for the Lines tab: times as the
     * rider sees them (their line delays applied), so they can be checked against the platform
     * board. Short loops count as the Blue Line. On the engine thread.
     */
    suspend fun lineBoard(route: RouteId, station: String, per: Int = 3): Map<com.flash.headway.engine.Dir, List<Long>> = withContext(engineThread) {
        val p = prefs.value ?: return@withContext emptyMap()
        engine(p).departures(station, Clock.now(), 400)
            .filter { lineKey(it.trip.route) == route }
            .groupBy { it.trip.dir }
            .mapValues { (_, deps) -> deps.map { it.at }.distinct().take(per) }
    }

    fun todayInfo(): DayInfo = dayTypeFor(serviceDate(Clock.now()), prefs.value?.override, _feed.value)

    // ---------- actions ----------

    private fun update(transform: (Prefs) -> Prefs) {
        viewModelScope.launch { store.update(transform) }
    }

    fun addRoute(from: String, to: String) = update { p ->
        val route = SavedRoute(from, to)
        p.copy(routes = if (p.routes.any { it.id == route.id }) p.routes else listOf(route) + p.routes)
    }

    /** Flips a route to the other direction in place, keeping its spot in the list. */
    fun swapRoute(id: String) {
        val p0 = prefs.value ?: return
        val r0 = p0.routes.firstOrNull { it.id == id } ?: return
        val reverseSaved = p0.routes.any { it.id == reverseId(id) }
        // Flipping in place changes the route, so its countdown ends; trading places doesn't.
        if (!reverseSaved && p0.reminder?.routeId == id) Alerts.stopCountdown(getApplication())
        update { p ->
            val i = p.routes.indexOfFirst { it.id == id }
            if (i < 0) return@update p
            val j = p.routes.indexOfFirst { it.id == reverseId(id) }
            if (j >= 0) {
                // Both directions are saved: trade places, so this spot shows the return trip and
                // neither card is lost.
                val routes = p.routes.toMutableList()
                routes[i] = p.routes[j]
                routes[j] = p.routes[i]
                return@update p.copy(routes = routes)
            }
            p.copy(
                routes = p.routes.map { if (it.id == id) SavedRoute(r0.to, r0.from) else it },
                reminder = p.reminder?.takeIf { it.routeId != id },
                trip = p.trip?.takeIf { it.routeId != id },
            )
        }
    }

    /** Flips the quick look to the other direction. */
    fun swapPreview() {
        val pv = _preview.value ?: return
        _preview.value = SavedRoute(pv.to, pv.from)
    }

    fun removeRoute(id: String) {
        if (prefs.value?.reminder?.routeId == id) Alerts.stopCountdown(getApplication())
        update { p ->
            val routes = p.routes.filterNot { it.id == id }
            val pair = p.routes.firstOrNull { it.id == id }?.let(::pairKey)
            // Its settings go with it, so adding it again starts fresh.
            p.copy(
                routes = routes,
                reminder = p.reminder?.takeIf { it.routeId != id },
                trip = p.trip?.takeIf { it.routeId != id },
                coaches = p.coaches.filterKeys { it != id && !it.startsWith("$id@") },
                delays = p.delays - id,
                trainDelays = p.trainDelays - id,
                flips = if (pair != null && routes.none { pairKey(it) == pair }) p.flips - pair else p.flips,
            )
        }
    }

    fun moveToTop(id: String) = update { p ->
        val r = p.routes.firstOrNull { it.id == id } ?: return@update p
        p.copy(routes = listOf(r) + p.routes.filterNot { it.id == id })
    }

    /** Best coach for a route's first train, or with [station], for the train boarded there after a change. */
    fun setCoach(routeId: String, station: String?, coach: Coach?) = update { p ->
        val key = if (station == null) routeId else coachKey(routeId, station)
        p.copy(coaches = if (coach == null) p.coaches - key else p.coaches + (key to coach))
    }

    /**
     * Sets how early or late a line runs in one direction ([key] from [lineDirKey]), for every
     * route that rides it. A countdown whose train is on it moves with it, so it keeps pointing
     * at the same train.
     */
    fun setLineDelay(key: String, minutes: Int) {
        val d = minutes.coerceIn(-DELAY_MAX, DELAY_MAX)
        val p = prefs.value ?: return
        val delta = d - p.lineDelayOf(key)
        if (delta == 0) return
        val rem = p.reminder
        val onLine = rem != null && _statuses.value.orEmpty().any { s ->
            s.route.id == rem.routeId && s.departures.any { it.dep == rem.dep && it.trip.lineDirKey == key }
        }
        val moved = if (onLine && rem != null) Reminder(rem.routeId, rem.dep + delta * MINUTE) else null
        val next = p.copy(lineDelays = if (d == 0) p.lineDelays - key else p.lineDelays + (key to d))
        val route = moved?.let { m -> p.routes.firstOrNull { it.id == m.routeId } }
        update { q ->
            val c = q.copy(lineDelays = next.lineDelays)
            if (moved != null && q.reminder == rem) c.copy(reminder = moved) else c
        }
        // A followed trip is planned again on the new delay (the countdown reads the widgets' mirror).
        val trip = p.trip
        val tripRoute = trip?.let { t -> p.routes.firstOrNull { it.id == t.routeId } }
        if (trip != null && tripRoute != null) {
            Widgets.mirror(getApplication(), next)
            Alerts.startTrip(getApplication(), tripRoute, trip)
            return
        }
        // The countdown's engine reads the widgets' mirror; write it first so it plans on the new times.
        if (moved != null && route != null) {
            Widgets.mirror(getApplication(), next.copy(reminder = moved))
            Alerts.startCountdown(getApplication(), route, moved.dep, p.delayRule(route.id))
        }
    }

    /**
     * The next train on [route] if its delay were [delayMin] (trains with their own keep theirs):
     * timetabled and shown departure, for the delay card. On the engine thread.
     */
    suspend fun delayPreview(route: SavedRoute, delayMin: Int): com.flash.headway.ui.DelayPreview? = withContext(engineThread) {
        val p = prefs.value ?: return@withContext null
        val rule = DelayRule(delayMin, p.trainDelays[route.id].orEmpty())
        val next = engine(p).delayedDepartures(route, Clock.now(), 1, rule).firstOrNull() ?: return@withContext null
        com.flash.headway.ui.DelayPreview(timetabled = next.timetabled ?: next.dep, shown = next.dep)
    }

    /** After notifications are allowed: post the countdown that was set while they weren't. */
    fun repostReminder() {
        val p = prefs.value ?: return
        val rem = p.reminder ?: return
        val route = p.routes.firstOrNull { it.id == rem.routeId } ?: return
        if (rem.dep > Clock.now()) Alerts.startCountdown(getApplication(), route, rem.dep, p.delayRule(route.id))
    }

    /** Notifications were refused: no countdown can show, so don't pretend one is running. */
    fun clearReminder() {
        Alerts.stopCountdown(getApplication())
        update { it.copy(reminder = null) }
    }

    /**
     * Sets a route's static delay. A countdown running on that route follows its train to the
     * new time, so it keeps pointing at the same train.
     */
    fun setDelay(routeId: String, minutes: Int) {
        val d = minutes.coerceIn(-DELAY_MAX, DELAY_MAX)
        if (prefs.value?.delayOf(routeId) == d) return
        keepingCountdown(routeId) { q -> q.copy(delays = if (d == 0) q.delays - routeId else q.delays + (routeId to d)) }
    }

    /**
     * Sets one train's own delay (by its timetabled departure): for every day of its kind
     * ([dayType]), or with [today], just this service day. Null lets it follow the route again.
     */
    fun setTrainDelay(routeId: String, timetabled: Long, dayType: DayType, minutes: Int?, today: Boolean) = keepingCountdown(routeId) { q ->
        val mine = q.trainDelays[routeId].orEmpty().toMutableMap()
        // Whatever this train had (any scope) gives way to the new setting.
        trainKeys(timetabled, dayType).forEach { mine.remove(it) }
        if (minutes != null) {
            val key = if (today) "${timeKey(timetabled)}/${serviceDate(timetabled)}" else "${timeKey(timetabled)}/${dayType.name}"
            mine[key] = minutes.coerceIn(-DELAY_MAX, DELAY_MAX)
        }
        q.copy(trainDelays = if (mine.isEmpty()) q.trainDelays - routeId else q.trainDelays + (routeId to mine))
    }

    /**
     * Applies a delay change while keeping a running countdown on the same train: it moves by
     * that train's own change, found through its timetabled time.
     */
    private fun keepingCountdown(routeId: String, change: (Prefs) -> Prefs) {
        val p = prefs.value ?: return
        val next = change(p)
        val rem = p.reminder?.takeIf { it.routeId == routeId }
        val route = p.routes.firstOrNull { it.id == routeId }
        val moved = rem?.let {
            val type = dayTypeFor(serviceDate(it.dep), p.override, _feed.value).type
            val base = p.delayRule(routeId).timetabledOf(it.dep, type)
            Reminder(routeId, base + next.delayRule(routeId).forDeparture(base, type) * MINUTE)
        }
        if (moved != null && route != null) Alerts.startCountdown(getApplication(), route, moved.dep, next.delayRule(routeId))
        update { q ->
            val c = change(q)
            if (moved != null && q.reminder?.routeId == routeId) c.copy(reminder = moved) else c
        }
    }

    /** Applies due change-overs, stopping a countdown on a route that flipped. */
    private fun flipNow() {
        viewModelScope.launch {
            var stopped = false
            store.update { q ->
                val next = applyFlips(q, Clock.now())
                stopped = q.reminder != null && next.reminder == null
                next
            }
            if (stopped) Alerts.stopCountdown(getApplication())
        }
    }

    /** Turns switching by time on or off. Turning it on keeps the card as it is right now. */
    fun setFlip(routeId: String, on: Boolean) = update { p ->
        val r = p.routes.firstOrNull { it.id == routeId } ?: return@update p
        if (!on) return@update p.copy(flips = p.flips - pairKey(r))
        val (pm, key) = flipPeriod(Clock.now(), FLIP_DEFAULT)
        p.copy(flips = p.flips + (pairKey(r) to AutoFlip(FLIP_DEFAULT, if (pm) r.to else r.from, key)))
    }

    /** Moves the switch time by [stepMin]; the card turns right away if that changes its half of the day. */
    fun moveFlipTime(routeId: String, stepMin: Int) = changeFlip(routeId) { f, _ ->
        f.copy(at = (f.at + stepMin).coerceIn(FLIP_MIN, FLIP_MAX), applied = null)
    }

    /** Swaps which direction is the morning trip, and turns the card to match. */
    fun swapFlipHome(routeId: String) = changeFlip(routeId) { f, r ->
        f.copy(home = if (r.from == f.home) r.to else r.from, applied = null)
    }

    private fun changeFlip(routeId: String, change: (AutoFlip, SavedRoute) -> AutoFlip) {
        viewModelScope.launch {
            var stopped = false
            store.update { p ->
                val r = p.routes.firstOrNull { it.id == routeId } ?: return@update p
                val f = p.flips[pairKey(r)] ?: return@update p
                val next = applyFlips(p.copy(flips = p.flips + (pairKey(r) to change(f, r))), Clock.now())
                stopped = p.reminder != null && next.reminder == null
                next
            }
            if (stopped) Alerts.stopCountdown(getApplication())
        }
    }

    fun setCount(n: Int) = update { it.copy(count = n) }

    fun showPreview(from: String, to: String) {
        _plan.value = null
        _preview.value = SavedRoute(from, to)
    }

    fun closePreview() {
        _preview.value = null
        _plan.value = null
    }

    /** Plans the quick look for another time, or back to now with null. */
    fun setPlan(plan: PlanTime?) {
        _plan.value = plan
    }

    fun savePreview() {
        val pv = _preview.value ?: return
        _preview.value = null
        _plan.value = null
        addRoute(pv.from, pv.to)
    }

    fun toggleReminder(route: SavedRoute, dep: Long) {
        val p = prefs.value ?: return
        val ctx = getApplication<Application>()
        val current = p.reminder
        if (current != null && current.routeId == route.id && current.dep == dep) {
            Alerts.stopCountdown(ctx)
            update { it.copy(reminder = null, trip = it.trip?.takeIf { t -> t.routeId != route.id }) }
            return
        }
        // A trip with changes is followed train by train: the card and the countdown move on to
        // each change, then arrival, instead of stopping when the first train leaves.
        val d = _statuses.value?.firstOrNull { it.route.id == route.id }?.departures?.firstOrNull { it.dep == dep }
        val legs = d?.legs
        val trip = d?.takeIf { (legs?.size ?: 1) > 1 }?.let { followOf(route, it) }
        if (trip != null) {
            Alerts.startTrip(ctx, route, trip)
            update { it.copy(reminder = Reminder(route.id, dep), trip = trip) }
            return
        }
        Alerts.startCountdown(ctx, route, dep, p.delayRule(route.id))
        // One countdown at a time: a train on another route replaces a trip being followed.
        update { it.copy(reminder = Reminder(route.id, dep), trip = null) }
    }

    /** Trips with changes on [route] already under way, newest first, to follow one mid-trip. On the engine thread. */
    suspend fun tripsOnTheWay(route: SavedRoute): List<com.flash.headway.engine.RouteDeparture> = withContext(engineThread) {
        val p = prefs.value ?: return@withContext emptyList()
        engine(p).tripsOnTheWay(route, Clock.now())
    }

    /** Follows a trip already under way: the card and countdown pick it up wherever it's got to. */
    fun followOnTheWay(route: SavedRoute, d: com.flash.headway.engine.RouteDeparture) {
        val trip = followOf(route, d) ?: return
        Alerts.startTrip(getApplication(), route, trip)
        update { it.copy(trip = trip, reminder = null) }
    }

    /**
     * Joins [route] partway (after a bus, say): follows the first trip on from [station] to its
     * end, leaving once the rider gets there in [inMinutes]. Returns it, or null when none runs.
     */
    suspend fun followFrom(route: SavedRoute, station: String, inMinutes: Int): com.flash.headway.engine.RouteDeparture? {
        val d = withContext(engineThread) {
            val p = prefs.value ?: return@withContext null
            engine(p).tripFrom(route, station, Clock.now() + inMinutes * MINUTE)
        } ?: return null
        val trip = followOf(route, d, station) ?: return null
        Alerts.startTrip(getApplication(), route, trip)
        update { it.copy(trip = trip, reminder = null) }
        return d
    }

    /** Stops following a trip (and its countdown). */
    fun endTrip() {
        Alerts.stopCountdown(getApplication())
        update { it.copy(trip = null, reminder = null) }
    }

    /** Whether the countdown at [dep] on [route] would follow a trip with changes. */
    fun isTrip(route: SavedRoute, dep: Long): Boolean =
        (_statuses.value?.firstOrNull { it.route.id == route.id }?.departures?.firstOrNull { it.dep == dep }?.legs?.size ?: 1) > 1

    fun reminderToast(route: SavedRoute, dep: Long): String =
        if (isTrip(route, dep)) {
            "Following the ${clock(dep)} trip: it counts down to each train"
        } else {
            "Counting down to the ${clock(dep)} from ${Network.name(route.from)}"
        }

    fun setLastWarn(on: Boolean) = update { it.copy(lastWarn = on) }

    /** [minutes] past midnight IST, or null to go back to real time. */
    fun setMock(minutes: Int?) {
        val offset = minutes?.let { Clock.offsetFor(it) } ?: 0L
        Clock.offset = offset
        // A countdown set on the other clock would point at the wrong train.
        Alerts.stopCountdown(getApplication())
        update { it.copy(mockAt = minutes, mockOffset = offset, reminder = null, trip = null) }
    }

    fun setTheme(mode: ThemeMode) = update { it.copy(theme = mode) }

    fun setOverride(type: DayType?) = update { p ->
        p.copy(override = type?.let { DayOverride(serviceDate(Clock.now()), it) })
    }

    override fun onCleared() {
        engineThread.close()
    }
}
