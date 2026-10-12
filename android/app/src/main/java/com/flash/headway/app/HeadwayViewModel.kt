package com.flash.headway.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flash.headway.data.DayType
import com.flash.headway.data.Line
import com.flash.headway.data.Network
import com.flash.headway.data.RouteId
import com.flash.headway.engine.DayInfo
import com.flash.headway.engine.DayOverride
import com.flash.headway.engine.Departure
import com.flash.headway.engine.Journey
import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.Schedule
import com.flash.headway.engine.dayTypeFor
import com.flash.headway.engine.serviceDate
import com.flash.headway.ui.clock
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DepGroup(
    val dest: String,
    val line: Line,
    val route: RouteId,
    val times: List<Long>,
    val warn: Boolean,
    val sub: String,
)

sealed interface NowState {
    data object Loading : NowState

    data class Empty(val today: DayInfo) : NowState

    data class NoService(val today: DayInfo) : NowState

    data class Ready(
        val today: DayInfo,
        val journey: Journey,
        val later: List<Journey>,
        val leaveAt: Long,
        /** The first catchable trip is on the next service day. */
        val closed: Boolean,
        /** Some line still runs from this station today. */
        val running: Boolean,
        val last: Journey?,
        val late: Boolean,
        val remaining: Int?,
        val nextDay: Journey?,
        val departures: List<DepGroup>,
    ) : NowState {
        val overnight: Boolean get() = closed && !running
    }
}

class HeadwayViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PrefsStore(app)
    private val engineThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "headway-engine").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    val prefs: StateFlow<Prefs?> = store.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _now = MutableStateFlow<NowState>(NowState.Loading)
    val now: StateFlow<NowState> = _now.asStateFlow()

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
            prefs.filterNotNull().collectLatest { p ->
                while (true) {
                    val state = withContext(engineThread) { computeNow(engine(p), p, System.currentTimeMillis()) }
                    _now.value = state
                    // Recompute the moment the shown train can no longer be caught, else every 20 s.
                    val boundary = (state as? NowState.Ready)?.takeIf { !it.closed }?.leaveAt
                    val wait = boundary?.let { (it - System.currentTimeMillis() + 500).coerceIn(500L, 20_000L) } ?: 20_000L
                    delay(wait)
                }
            }
        }
        viewModelScope.launch {
            // Clear a finished ride a few minutes after arrival.
            while (true) {
                val ride = prefs.value?.ride
                if (ride != null && System.currentTimeMillis() > ride.arr + 3 * MINUTE) endRide()
                delay(30_000)
            }
        }
    }

    private fun computeNow(s: Schedule, p: Prefs, now: Long): NowState {
        val today = s.today(now)
        val from = p.from
        val to = p.to
        if (from == null || to == null) return NowState.Empty(today)
        val walk = p.walk * MINUTE
        val js = s.journeys(from, to, now + walk, 4)
        val board = s.departures(from, now)
        if (js.isEmpty()) return NowState.NoService(today)

        val j = js.first()
        val day = j.legs.first().trip.serviceDate
        val closed = day != today.date
        val last = if (closed) null else s.lastJourney(from, to, now)
        val sameDay = js.count { it.legs.first().trip.serviceDate == day }
        val late = !closed && last != null && p.lastWarn && last.dep - now < 60 * MINUTE
        val nextDay = if (late && last != null) s.plan(from, to, last.dep + 1) else null
        val running = board.any { it.trip.serviceDate == today.date }

        return NowState.Ready(
            today = today,
            journey = j,
            later = js.drop(1).take(3),
            leaveAt = j.dep - walk,
            closed = closed,
            running = running,
            last = last,
            late = late,
            remaining = if (sameDay < js.size) sameDay else null,
            nextDay = nextDay,
            departures = groups(board, today.date, overnight = closed && !running, tripMode = !closed, lastWarn = p.lastWarn),
        )
    }

    private fun groups(board: List<Departure>, today: LocalDate, overnight: Boolean, tripMode: Boolean, lastWarn: Boolean): List<DepGroup> {
        val byPattern = LinkedHashMap<String, MutableList<Departure>>()
        for (d in board) byPattern.getOrPut("${d.trip.route}:${d.trip.dir}") { ArrayList() } += d
        return byPattern.values
            // While trains run, a pattern with nothing left today (peak short loops) drops out.
            .filter { list -> overnight || list.any { it.trip.serviceDate == today } }
            .take(5)
            .map { list ->
                val trip = list.first().trip
                val todays = list.filter { it.trip.serviceDate == today }
                val warn = tripMode && lastWarn && todays.size in 1..2
                val shown = (if (overnight || todays.isEmpty()) list else todays).take(2)
                val sub = when {
                    warn -> "${routeLabel(trip.route, trip.line)}, last ${clock(todays.last().at)}"
                    trip.route == RouteId.LOOP -> "Short loop"
                    trip.route == RouteId.INTER -> "Green Line via CMBT"
                    else -> trip.line.label
                }
                DepGroup(trip.dest, trip.line, trip.route, shown.map { it.at }, warn, sub)
            }
    }

    // ---------- on-demand queries ----------

    suspend fun tripOptions(): List<Journey> {
        val p = prefs.value ?: return emptyList()
        val from = p.from ?: return emptyList()
        val to = p.to ?: return emptyList()
        return withContext(engineThread) { engine(p).journeys(from, to, System.currentTimeMillis() + p.walk * MINUTE, 4) }
    }

    /** Travel time from [origin] to every other station, for the picker. */
    suspend fun plansFrom(origin: String): Map<String, Journey?> {
        val p = prefs.value ?: return emptyMap()
        return withContext(engineThread) {
            val s = engine(p)
            val start = System.currentTimeMillis() + p.walk * MINUTE
            Network.stations.keys.filter { it != origin }.associateWith { s.plan(origin, it, start) }
        }
    }

    suspend fun nextFor(trips: List<TripKey>): Map<TripKey, Journey?> {
        val p = prefs.value ?: return emptyMap()
        return withContext(engineThread) {
            val s = engine(p)
            val start = System.currentTimeMillis() + p.walk * MINUTE
            trips.associateWith { s.plan(it.from, it.to, start) }
        }
    }

    fun todayInfo(): DayInfo = dayTypeFor(serviceDate(System.currentTimeMillis()), prefs.value?.override)

    // ---------- actions ----------

    private fun update(transform: (Prefs) -> Prefs) {
        viewModelScope.launch { store.update(transform) }
    }

    fun setTrip(from: String, to: String) {
        Alerts.cancelReminder(getApplication())
        update { p ->
            val key = TripKey(from, to)
            p.copy(from = from, to = to, recents = (listOf(key) + p.recents.filter { it != key }).take(10), reminderDep = null)
        }
    }

    fun setFrom(from: String) = update { it.copy(from = from) }

    fun setTo(to: String) = update { it.copy(to = to) }

    fun toggleReminder(j: Journey) {
        val p = prefs.value ?: return
        val ctx = getApplication<Application>()
        if (p.reminderDep == j.dep) {
            Alerts.cancelReminder(ctx)
            update { it.copy(reminderDep = null) }
            return
        }
        val at = maxOf(System.currentTimeMillis() + 5_000, j.dep - (p.walk + p.remindLead) * MINUTE)
        Alerts.scheduleReminder(
            ctx,
            at,
            if (p.walk > 0) "Time to leave" else "Train soon",
            "${clock(j.dep)} from ${Network.name(j.from)} to ${Network.name(j.to)}",
        )
        update { it.copy(reminderDep = j.dep) }
    }

    fun reminderTime(j: Journey): Long {
        val p = prefs.value ?: return j.dep
        return maxOf(System.currentTimeMillis() + 5_000, j.dep - (p.walk + p.remindLead) * MINUTE)
    }

    fun toggleSaved() = update { p ->
        val from = p.from ?: return@update p
        val to = p.to ?: return@update p
        val key = TripKey(from, to)
        p.copy(saved = if (key in p.saved) p.saved - key else p.saved + key)
    }

    fun startRide(j: Journey) {
        val p = prefs.value ?: return
        val ride = Ride(
            dep = j.dep,
            arr = j.arr,
            legs = j.legs.map { RideLeg(it.from, it.to, it.trip.line, it.trip.dest, it.stops) },
        )
        Alerts.cancelReminder(getApplication())
        Alerts.startRide(getApplication(), ride, p.rideAlert)
        update { it.copy(ride = ride, reminderDep = null) }
    }

    fun endRide() {
        Alerts.endRide(getApplication())
        update { it.copy(ride = null) }
    }

    fun setRideAlert(on: Boolean) {
        val ride = prefs.value?.ride
        if (ride != null) Alerts.startRide(getApplication(), ride, on)
        update { it.copy(rideAlert = on) }
    }

    fun setWalk(minutes: Int) = update { it.copy(walk = minutes.coerceIn(0, 30), reminderDep = null) }

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

fun routeLabel(route: RouteId, line: Line): String = when (route) {
    RouteId.LOOP -> "${line.label}, short loop"
    RouteId.INTER -> "${line.label} via CMBT"
    else -> line.label
}
