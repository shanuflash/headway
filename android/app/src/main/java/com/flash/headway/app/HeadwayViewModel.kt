package com.flash.headway.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flash.headway.data.DayType
import com.flash.headway.data.Network
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

    // Only touched on the engine thread.
    private var schedule = Schedule()
    private var scheduleOverride: DayOverride? = null
    private var scheduleFeed: Feed? = null

    private fun engine(p: Prefs): Schedule {
        val f = _feed.value
        if (p.override != scheduleOverride || f !== scheduleFeed) {
            scheduleOverride = p.override
            scheduleFeed = f
            schedule = Schedule(p.override, f)
        }
        return schedule
    }

    init {
        viewModelScope.launch {
            combine(prefs.filterNotNull(), _preview, _plan, _feed) { p, pv, plan, _ -> Triple(p, pv, plan) }.collectLatest { (p, pv, plan) ->
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
                        shown.routes.map { routeStatus(s, it, now, p.lastWarn, p.count, p.delayRule(it.id)) } to
                            pv?.let { if (plan != null) planStatus(s, it, plan, p.count) else routeStatus(s, it, now, p.lastWarn, p.count) }
                    }
                    _statuses.value = list
                    _previewStatus.value = previewNow
                    // Recompute the moment a shown train leaves, else every 15 s.
                    val boundary = (list + listOfNotNull(previewNow)).mapNotNull { it.nextChange }.minOrNull()
                    val wait = boundary?.let { (it - Clock.now() + 300).coerceIn(300L, 15_000L) } ?: 15_000L
                    delay(wait)
                }
            }
        }
        viewModelScope.launch {
            // CMRL advisories: check on open, then every half hour. A failed check keeps the last copy.
            while (true) {
                Advisories.refresh(getApplication())?.let { fresh ->
                    if (fresh != _feed.value) {
                        _feed.value = fresh
                        Widgets.updateAllAsync(getApplication())
                    }
                }
                delay(30 * 60_000L)
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
        return com.flash.headway.engine.directDestinations(id).map { g ->
            val trains = g.terminals
                .flatMap { t -> s.routeDepartures(id, t, now, 4).map { it to t } }
                .sortedBy { it.first.dep }
                .distinctBy { it.first.dep }
                .take(4)
            com.flash.headway.ui.StationBoard(g, com.flash.headway.ui.platformAt(id, g), trains)
        }
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
        val pv = _preview.value
        if (pv != null && pv.id == id) {
            _preview.value = SavedRoute(pv.to, pv.from)
            return
        }
        if (prefs.value?.reminder?.routeId == id) Alerts.stopCountdown(getApplication())
        update { p ->
            val r = p.routes.firstOrNull { it.id == id } ?: return@update p
            val flipped = SavedRoute(r.to, r.from)
            p.copy(
                routes = p.routes.filterNot { it.id == flipped.id }.map { if (it.id == id) flipped else it },
                reminder = p.reminder?.takeIf { it.routeId != id },
            )
        }
    }

    fun addReturn(id: String) {
        val r = prefs.value?.routes?.firstOrNull { it.id == id } ?: return
        addRoute(r.to, r.from)
    }

    fun removeRoute(id: String) {
        if (prefs.value?.reminder?.routeId == id) Alerts.stopCountdown(getApplication())
        update { p ->
            p.copy(
                routes = p.routes.filterNot { it.id == id },
                reminder = p.reminder?.takeIf { it.routeId != id },
            )
        }
    }

    fun moveToTop(id: String) = update { p ->
        val r = p.routes.firstOrNull { it.id == id } ?: return@update p
        p.copy(routes = listOf(r) + p.routes.filterNot { it.id == id })
    }

    fun setCoach(routeId: String, coach: Coach?) = update { p ->
        p.copy(coaches = if (coach == null) p.coaches - routeId else p.coaches + (routeId to coach))
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

    /** Sets one train's own delay (by its timetabled departure), or with null, lets it follow the route. */
    fun setTrainDelay(routeId: String, timetabled: Long, minutes: Int?) = keepingCountdown(routeId) { q ->
        val mine = q.trainDelays[routeId].orEmpty().toMutableMap()
        if (minutes == null) mine.remove(timeKey(timetabled)) else mine[timeKey(timetabled)] = minutes.coerceIn(-DELAY_MAX, DELAY_MAX)
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
            val base = p.delayRule(routeId).timetabledOf(it.dep)
            Reminder(routeId, base + next.delayRule(routeId).forDeparture(base) * MINUTE)
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
            update { it.copy(reminder = null) }
            return
        }
        Alerts.startCountdown(ctx, route, dep, p.delayRule(route.id))
        update { it.copy(reminder = Reminder(route.id, dep)) }
    }

    fun reminderToast(route: SavedRoute, dep: Long): String =
        "Counting down to the ${clock(dep)} from ${Network.name(route.from)}"

    fun setLastWarn(on: Boolean) = update { it.copy(lastWarn = on) }

    /** [minutes] past midnight IST, or null to go back to real time. */
    fun setMock(minutes: Int?) {
        val offset = minutes?.let { Clock.offsetFor(it) } ?: 0L
        Clock.offset = offset
        // A countdown set on the other clock would point at the wrong train.
        Alerts.stopCountdown(getApplication())
        update { it.copy(mockAt = minutes, mockOffset = offset, reminder = null) }
    }

    fun setTheme(mode: ThemeMode) = update { it.copy(theme = mode) }

    fun setOverride(type: DayType?) = update { p ->
        p.copy(override = type?.let { DayOverride(serviceDate(Clock.now()), it) })
    }

    override fun onCleared() {
        engineThread.close()
    }
}
