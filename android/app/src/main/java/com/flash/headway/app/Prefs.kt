package com.flash.headway.app

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flash.headway.data.DayType
import com.flash.headway.data.Network
import com.flash.headway.engine.DayOverride
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("headway")

enum class ThemeMode { LIGHT, DARK, AUTO }

/** A saved daily route: board at [from], get off at [to]. */
data class SavedRoute(val from: String, val to: String) {
    val id: String get() = "$from>$to"
}

data class Reminder(val routeId: String, val dep: Long)

/** Where to stand on the platform for this route, set by the rider (no public data has it). */
enum class Coach(val label: String) { FRONT("front"), MIDDLE("middle"), REAR("rear") }

/** The furthest a route's static delay goes either way, in minutes. */
const val DELAY_MAX = 10

data class Prefs(
    val routes: List<SavedRoute> = emptyList(),
    /** How many departures each route card lists. */
    val count: Int = 5,
    val lastWarn: Boolean = true,
    val theme: ThemeMode = ThemeMode.AUTO,
    val override: DayOverride? = null,
    /** Mock time for testing: the preset picked (minutes past midnight) and the clock offset. */
    val mockAt: Int? = null,
    val mockOffset: Long = 0L,
    val reminder: Reminder? = null,
    /** Best coach per route id. */
    val coaches: Map<String, Coach> = emptyMap(),
    /** Minutes each route's trains run off the timetable, by route id: negative early, positive late. */
    val delays: Map<String, Int> = emptyMap(),
    /** Switch direction by time, by station pair ([pairKey]). */
    val flips: Map<String, AutoFlip> = emptyMap(),
) {
    fun delayOf(routeId: String): Int = delays[routeId] ?: 0
}

val COUNT_CHOICES = listOf(3, 5, 8)

class PrefsStore(private val context: Context) {
    private object Keys {
        val routes = stringPreferencesKey("routes")
        val count = intPreferencesKey("count")
        val remindLead = intPreferencesKey("remind_lead")
        val lastWarn = booleanPreferencesKey("last_warn")
        val theme = stringPreferencesKey("theme")
        val override = stringPreferencesKey("override")
        val mockAt = intPreferencesKey("mock_at")
        val coaches = stringPreferencesKey("coaches")
        val delays = stringPreferencesKey("delays")
        val flips = stringPreferencesKey("flips")
        val mockOffset = longPreferencesKey("mock_offset")
        val reminder = stringPreferencesKey("reminder_route")
        val reminderDep = longPreferencesKey("reminder_dep")

        // From the trip-planner version, read once to seed the first route.
        val legacyFrom = stringPreferencesKey("from")
        val legacyTo = stringPreferencesKey("to")
    }

    val prefs: Flow<Prefs> = context.dataStore.data.map(::decode)

    /** Applies [transform] to the stored values atomically. */
    suspend fun update(transform: (Prefs) -> Prefs) {
        context.dataStore.edit { p -> write(p, transform(decode(p))) }
    }

    private fun decode(p: Preferences): Prefs {
        val routes = p[Keys.routes]?.let(::decodeRoutes) ?: legacyRoute(p)
        val reminderRoute = p[Keys.reminder]
        val reminderDep = p[Keys.reminderDep]
        return Prefs(
            routes = routes,
            count = (p[Keys.count] ?: 5).takeIf { it in COUNT_CHOICES } ?: 5,
            coaches = p[Keys.coaches].orEmpty().split(';').mapNotNull { part ->
                val (id, c) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                runCatching { id to Coach.valueOf(c) }.getOrNull()
            }.toMap(),
            delays = decodeDelays(p[Keys.delays]),
            flips = decodeFlips(p[Keys.flips]),
            lastWarn = p[Keys.lastWarn] ?: true,
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.AUTO,
            override = p[Keys.override]?.let(::decodeOverride),
            mockAt = p[Keys.mockAt],
            mockOffset = if (p[Keys.mockAt] != null) p[Keys.mockOffset] ?: 0L else 0L,
            reminder = if (reminderRoute != null && reminderDep != null) Reminder(reminderRoute, reminderDep) else null,
        )
    }

    private fun legacyRoute(p: Preferences): List<SavedRoute> {
        val from = p[Keys.legacyFrom]?.takeIf { it in Network.stations } ?: return emptyList()
        val to = p[Keys.legacyTo]?.takeIf { it in Network.stations } ?: return emptyList()
        return listOf(SavedRoute(from, to))
    }

    private fun write(p: MutablePreferences, next: Prefs) {
        p[Keys.routes] = next.routes.joinToString(";") { "${it.from}>${it.to}" }
        p[Keys.count] = next.count
        p[Keys.coaches] = next.coaches.entries.joinToString(";") { "${it.key}=${it.value.name}" }
        p[Keys.delays] = encodeDelays(next.delays)
        p[Keys.flips] = encodeFlips(next.flips)
        p.remove(Keys.remindLead)
        p[Keys.lastWarn] = next.lastWarn
        p[Keys.theme] = next.theme.name
        val mockAt = next.mockAt
        if (mockAt != null) {
            p[Keys.mockAt] = mockAt
            p[Keys.mockOffset] = next.mockOffset
        } else {
            p.remove(Keys.mockAt)
            p.remove(Keys.mockOffset)
        }
        p.put(Keys.override, next.override?.let { "${it.date}|${it.type.name}" })
        val reminder = next.reminder
        if (reminder == null) {
            p.remove(Keys.reminder)
            p.remove(Keys.reminderDep)
        } else {
            p[Keys.reminder] = reminder.routeId
            p[Keys.reminderDep] = reminder.dep
        }
        p.remove(Keys.legacyFrom)
        p.remove(Keys.legacyTo)
    }

    private fun MutablePreferences.put(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else this[key] = value
    }

    private fun decodeRoutes(raw: String): List<SavedRoute> =
        raw.split(';').mapNotNull { part ->
            val bits = part.split('>')
            if (bits.size >= 2 && bits[0] in Network.stations && bits[1] in Network.stations) {
                SavedRoute(bits[0], bits[1])
            } else {
                null
            }
        }

    private fun decodeOverride(raw: String): DayOverride? = runCatching {
        val (d, t) = raw.split('|')
        DayOverride(LocalDate.parse(d), DayType.valueOf(t))
    }.getOrNull()
}

/** "a>b=2;c>d=-1": route id to minutes, the same text in DataStore and the widgets' mirror. */
fun encodeDelays(delays: Map<String, Int>): String =
    delays.entries.filter { it.value != 0 }.joinToString(";") { "${it.key}=${it.value}" }

fun decodeDelays(raw: String?): Map<String, Int> =
    raw.orEmpty().split(';').mapNotNull { part ->
        val (id, d) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        val minutes = d.toIntOrNull()?.coerceIn(-DELAY_MAX, DELAY_MAX) ?: return@mapNotNull null
        (id to minutes).takeIf { minutes != 0 }
    }.toMap()
