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

/** A saved daily route: board at [from], get off at [to], usually reach [from] in [arrive] minutes. */
data class SavedRoute(val from: String, val to: String, val arrive: Int = 0) {
    val id: String get() = "$from>$to"
}

data class Reminder(val routeId: String, val dep: Long)

data class Prefs(
    val routes: List<SavedRoute> = emptyList(),
    val expanded: String? = null,
    val remindLead: Int = 2,
    val lastWarn: Boolean = true,
    val theme: ThemeMode = ThemeMode.AUTO,
    val override: DayOverride? = null,
    val reminder: Reminder? = null,
)

val ARRIVE_CHOICES = listOf(0, 5, 10, 15, 20, 30)

class PrefsStore(private val context: Context) {
    private object Keys {
        val routes = stringPreferencesKey("routes")
        val expanded = stringPreferencesKey("expanded")
        val remindLead = intPreferencesKey("remind_lead")
        val lastWarn = booleanPreferencesKey("last_warn")
        val theme = stringPreferencesKey("theme")
        val override = stringPreferencesKey("override")
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
            expanded = p[Keys.expanded],
            remindLead = p[Keys.remindLead] ?: 2,
            lastWarn = p[Keys.lastWarn] ?: true,
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.AUTO,
            override = p[Keys.override]?.let(::decodeOverride),
            reminder = if (reminderRoute != null && reminderDep != null) Reminder(reminderRoute, reminderDep) else null,
        )
    }

    private fun legacyRoute(p: Preferences): List<SavedRoute> {
        val from = p[Keys.legacyFrom]?.takeIf { it in Network.stations } ?: return emptyList()
        val to = p[Keys.legacyTo]?.takeIf { it in Network.stations } ?: return emptyList()
        return listOf(SavedRoute(from, to))
    }

    private fun write(p: MutablePreferences, next: Prefs) {
        p[Keys.routes] = next.routes.joinToString(";") { "${it.from}>${it.to}>${it.arrive}" }
        p.put(Keys.expanded, next.expanded)
        p[Keys.remindLead] = next.remindLead
        p[Keys.lastWarn] = next.lastWarn
        p[Keys.theme] = next.theme.name
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
                SavedRoute(bits[0], bits[1], bits.getOrNull(2)?.toIntOrNull() ?: 0)
            } else {
                null
            }
        }

    private fun decodeOverride(raw: String): DayOverride? = runCatching {
        val (d, t) = raw.split('|')
        DayOverride(LocalDate.parse(d), DayType.valueOf(t))
    }.getOrNull()
}
