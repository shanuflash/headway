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
import com.flash.headway.data.Line
import com.flash.headway.data.Network
import com.flash.headway.engine.DayOverride
import com.flash.headway.engine.Stop
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("headway")

enum class ThemeMode { LIGHT, DARK, AUTO }

data class TripKey(val from: String, val to: String)

data class RideLeg(val from: String, val to: String, val line: Line, val dest: String, val stops: List<Stop>)

data class Ride(val dep: Long, val arr: Long, val legs: List<RideLeg>) {
    val to: String get() = legs.last().to
}

data class Prefs(
    val from: String? = null,
    val to: String? = null,
    val walk: Int = 4,
    val remindLead: Int = 2,
    val lastWarn: Boolean = true,
    val theme: ThemeMode = ThemeMode.AUTO,
    val override: DayOverride? = null,
    val saved: List<TripKey> = emptyList(),
    val recents: List<TripKey> = emptyList(),
    val ride: Ride? = null,
    val rideAlert: Boolean = true,
    val reminderDep: Long? = null,
)

class PrefsStore(private val context: Context) {
    private object Keys {
        val from = stringPreferencesKey("from")
        val to = stringPreferencesKey("to")
        val walk = intPreferencesKey("walk")
        val remindLead = intPreferencesKey("remind_lead")
        val lastWarn = booleanPreferencesKey("last_warn")
        val theme = stringPreferencesKey("theme")
        val override = stringPreferencesKey("override")
        val saved = stringPreferencesKey("saved")
        val recents = stringPreferencesKey("recents")
        val ride = stringPreferencesKey("ride")
        val rideAlert = booleanPreferencesKey("ride_alert")
        val reminderDep = longPreferencesKey("reminder_dep")
    }

    val prefs: Flow<Prefs> = context.dataStore.data.map(::decode)

    /** Applies [transform] to the stored values atomically. */
    suspend fun update(transform: (Prefs) -> Prefs) {
        context.dataStore.edit { p -> write(p, transform(decode(p))) }
    }

    private fun decode(p: Preferences): Prefs =
        Prefs(
            from = p[Keys.from]?.takeIf { it in Network.stations },
            to = p[Keys.to]?.takeIf { it in Network.stations },
            walk = p[Keys.walk] ?: 4,
            remindLead = p[Keys.remindLead] ?: 2,
            lastWarn = p[Keys.lastWarn] ?: true,
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.AUTO,
            override = p[Keys.override]?.let(::decodeOverride),
            saved = decodeTrips(p[Keys.saved]),
            recents = decodeTrips(p[Keys.recents]),
            ride = p[Keys.ride]?.let(::decodeRide),
            rideAlert = p[Keys.rideAlert] ?: true,
            reminderDep = p[Keys.reminderDep],
        )

    private fun write(p: MutablePreferences, next: Prefs) {
        p.put(Keys.from, next.from)
        p.put(Keys.to, next.to)
        p[Keys.walk] = next.walk
        p[Keys.remindLead] = next.remindLead
        p[Keys.lastWarn] = next.lastWarn
        p[Keys.theme] = next.theme.name
        p.put(Keys.override, next.override?.let { "${it.date}|${it.type.name}" })
        p[Keys.saved] = encodeTrips(next.saved)
        p[Keys.recents] = encodeTrips(next.recents)
        p.put(Keys.ride, next.ride?.let(::encodeRide))
        p[Keys.rideAlert] = next.rideAlert
        if (next.reminderDep == null) p.remove(Keys.reminderDep) else p[Keys.reminderDep] = next.reminderDep
    }

    private fun MutablePreferences.put(key: Preferences.Key<String>, value: String?) {
        if (value == null) remove(key) else this[key] = value
    }

    // Compact text encodings keep DataStore free of a serialization dependency.

    private fun encodeTrips(trips: List<TripKey>) = trips.joinToString(";") { "${it.from}>${it.to}" }

    private fun decodeTrips(raw: String?): List<TripKey> =
        raw.orEmpty().split(';').mapNotNull { part ->
            val bits = part.split('>')
            if (bits.size == 2 && bits[0] in Network.stations && bits[1] in Network.stations) TripKey(bits[0], bits[1]) else null
        }

    private fun decodeOverride(raw: String): DayOverride? = runCatching {
        val (d, t) = raw.split('|')
        DayOverride(LocalDate.parse(d), DayType.valueOf(t))
    }.getOrNull()

    private fun encodeRide(r: Ride): String =
        "${r.dep}#${r.arr}#" + r.legs.joinToString("~") { l ->
            "${l.from}|${l.to}|${l.line.name}|${l.dest}|" + l.stops.joinToString(",") { "${it.station}@${it.at}" }
        }

    private fun decodeRide(raw: String): Ride? = runCatching {
        val (dep, arr, legs) = raw.split('#', limit = 3)
        Ride(
            dep.toLong(),
            arr.toLong(),
            legs.split('~').map { leg ->
                val (from, to, line, dest, stops) = leg.split('|')
                RideLeg(
                    from,
                    to,
                    Line.valueOf(line),
                    dest,
                    stops.split(',').map {
                        val (s, at) = it.split('@')
                        Stop(s, at.toLong())
                    },
                )
            },
        )
    }.getOrNull()
}
