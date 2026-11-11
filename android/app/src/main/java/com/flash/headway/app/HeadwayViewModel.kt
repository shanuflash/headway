package com.flash.headway.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flash.headway.data.DayType
import com.flash.headway.data.Network
import com.flash.headway.engine.DayInfo
import com.flash.headway.engine.DayOverride
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

    private val _statuses = MutableStateFlow<List<RouteStatus>?>(null)
    val statuses: StateFlow<List<RouteStatus>?> = _statuses.asStateFlow()

    private val _previewStatus = MutableStateFlow<RouteStatus?>(null)
    val previewStatus: StateFlow<RouteStatus?> = _previewStatus.asStateFlow()

    // Only touched on the engine thread.
    private var schedule = Schedule()
    private var scheduleOverride: DayOverride? = null

    private fun engine(p: Prefs): Schedule {
        if (p.override != scheduleOverride) {
            scheduleOverride = p.override
            schedule = Schedule(p.override)
        }
        return schedule
    }

    init {
        viewModelScope.launch {
            combine(prefs.filterNotNull(), _preview) { p, pv -> p to pv }.collectLatest { (p, pv) ->
                Clock.offset = p.mockOffset
                while (true) {
                    val now = Clock.now()
                    val (list, previewNow) = withContext(engineThread) {
                        val s = engine(p)
                        p.routes.map { routeStatus(s, it, now, p.lastWarn, p.count, p.delayOf(it.id)) } to
                            pv?.let { routeStatus(s, it, now, p.lastWarn, p.count) }
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
            // Keep the widgets' copy of the routes current and redraw them when anything changes.
            prefs.filterNotNull().collectLatest { p ->
                Widgets.mirror(getApplication(), p)
                Widgets.updateAll(getApplication())
            }
        }
    }

    /** Next trains each way from a station, for the map's station sheet. */
    fun stationBoard(id: String): List<com.flash.headway.ui.StationBoard> {
        val p = prefs.value ?: return emptyList()
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

    fun todayInfo(): DayInfo = dayTypeFor(serviceDate(Clock.now()), prefs.value?.override)

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

    /** The next train on [route] at [delayMin]: timetabled and shown departure, for the delay card. */
    fun delayPreview(route: SavedRoute, delayMin: Int): com.flash.headway.ui.DelayPreview? {
        val p = prefs.value ?: return null
        val off = delayMin * MINUTE
        val next = engine(p).delayedDepartures(route, Clock.now(), 1, delayMin).firstOrNull() ?: return null
        return com.flash.headway.ui.DelayPreview(timetabled = next.dep - off, shown = next.dep)
    }

    /**
     * Sets a route's static delay. A countdown running on that route follows its train to the
     * new time, so it keeps pointing at the same train.
     */
    fun setDelay(routeId: String, minutes: Int) {
        val p = prefs.value ?: return
        val d = minutes.coerceIn(-DELAY_MAX, DELAY_MAX)
        val before = p.delayOf(routeId)
        if (d == before) return
        val moved = (d - before) * MINUTE
        val reminder = p.reminder?.takeIf { it.routeId == routeId }?.let { Reminder(routeId, it.dep + moved) }
        val route = p.routes.firstOrNull { it.id == routeId }
        if (reminder != null && route != null) Alerts.startCountdown(getApplication(), route, reminder.dep, d)
        update { q ->
            q.copy(
                delays = if (d == 0) q.delays - routeId else q.delays + (routeId to d),
                reminder = if (reminder != null && q.reminder?.routeId == routeId) reminder else q.reminder,
            )
        }
    }

    fun setCount(n: Int) = update { it.copy(count = n) }

    fun showPreview(from: String, to: String) {
        _preview.value = SavedRoute(from, to)
    }

    fun closePreview() {
        _preview.value = null
    }

    fun savePreview() {
        val pv = _preview.value ?: return
        _preview.value = null
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
        Alerts.startCountdown(ctx, route, dep, p.delayOf(route.id))
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
