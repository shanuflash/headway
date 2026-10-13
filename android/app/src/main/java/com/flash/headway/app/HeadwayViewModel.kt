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
                while (true) {
                    val now = System.currentTimeMillis()
                    val (list, previewNow) = withContext(engineThread) {
                        val s = engine(p)
                        p.routes.map { routeStatus(s, it, now, p.lastWarn, p.count) } to pv?.let { routeStatus(s, it, now, p.lastWarn, p.count) }
                    }
                    _statuses.value = list
                    _previewStatus.value = previewNow
                    // Recompute the moment a shown train leaves, else every 15 s.
                    val boundary = (list + listOfNotNull(previewNow)).mapNotNull { it.nextChange }.minOrNull()
                    val wait = boundary?.let { (it - System.currentTimeMillis() + 300).coerceIn(300L, 15_000L) } ?: 15_000L
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

    fun todayInfo(): DayInfo = dayTypeFor(serviceDate(System.currentTimeMillis()), prefs.value?.override)

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
        if (prefs.value?.reminder?.routeId == id) Alerts.cancelReminder(getApplication())
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
        if (prefs.value?.reminder?.routeId == id) Alerts.cancelReminder(getApplication())
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

    fun toggleReminder(status: RouteStatus) {
        val p = prefs.value ?: return
        val next = status.next ?: return
        val ctx = getApplication<Application>()
        val current = p.reminder
        if (current != null && current.routeId == status.route.id && current.dep == next.dep) {
            Alerts.cancelReminder(ctx)
            update { it.copy(reminder = null) }
            return
        }
        Alerts.setReminder(ctx, status.route, next.dep, reminderTime(next.dep))
        update { it.copy(reminder = Reminder(status.route.id, next.dep)) }
    }

    fun reminderTime(dep: Long): Long {
        val lead = prefs.value?.remindLead ?: 5
        return maxOf(System.currentTimeMillis() + 5_000, dep - lead * MINUTE)
    }

    fun reminderToast(route: SavedRoute, dep: Long): String =
        "We'll remind you at ${clock(reminderTime(dep))} for the ${clock(dep)} from ${Network.name(route.from)}"

    fun setRemindLead(minutes: Int) = update { it.copy(remindLead = minutes) }

    fun setLastWarn(on: Boolean) = update { it.copy(lastWarn = on) }

    fun setTheme(mode: ThemeMode) = update { it.copy(theme = mode) }

    fun setOverride(type: DayType?) = update { p ->
        p.copy(override = type?.let { DayOverride(serviceDate(System.currentTimeMillis()), it) })
    }

    override fun onCleared() {
        engineThread.close()
    }
}
