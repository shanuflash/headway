package com.flash.headway.app

import com.flash.headway.engine.IST
import com.flash.headway.engine.serviceDate
import java.time.Instant

/** Switch time when the rider turns it on: the ride home from 5 PM. */
const val FLIP_DEFAULT = 17 * 60
const val FLIP_MIN = 6 * 60
const val FLIP_MAX = 23 * 60 + 30
const val FLIP_STEP = 30

/**
 * Switch a route's direction by time: show the trip from [home] until [at] (minutes past
 * midnight), and the ride back after it. [applied] is the half-day ("2026-11-11:pm") last acted
 * on, so each change-over flips the route once and a swap by hand lasts until the next one.
 */
data class AutoFlip(val at: Int, val home: String, val applied: String? = null)

/** One key for both directions of a route, so the setting survives a swap. */
fun pairKey(r: SavedRoute): String = listOf(r.from, r.to).sorted().joinToString("|")

/** The other direction of a route id, "a>b" to "b>a". */
fun reverseId(id: String): String = id.split('>').reversed().joinToString(">")

/** Whether [nowMs] is past [at] in its service day (which runs to 3 AM), and that half-day's key. */
fun flipPeriod(nowMs: Long, at: Int): Pair<Boolean, String> {
    val z = Instant.ofEpochMilli(nowMs).atZone(IST)
    val minutes = z.hour * 60 + z.minute + if (z.hour < 3) 24 * 60 else 0
    val pm = minutes >= at
    return pm to "${serviceDate(nowMs)}:${if (pm) "pm" else "am"}"
}

/** The way [r] should face: from home before the switch time, back to it after. */
fun facing(r: SavedRoute, f: AutoFlip, pm: Boolean): SavedRoute {
    val other = if (r.from == f.home) r.to else r.from
    return if (pm) SavedRoute(other, f.home) else SavedRoute(f.home, other)
}

/**
 * The direction [r] should turn to now, or null when there's nothing to do: no setting, this
 * change-over was already handled, it already faces the right way, or the other direction is
 * saved as a route of its own.
 */
fun flipDue(r: SavedRoute, flips: Map<String, AutoFlip>, routes: List<SavedRoute>, nowMs: Long): SavedRoute? {
    val f = flips[pairKey(r)] ?: return null
    if (f.home != r.from && f.home != r.to) return null
    val (pm, key) = flipPeriod(nowMs, f.at)
    if (f.applied == key) return null
    val want = facing(r, f, pm)
    if (want.id == r.id || routes.any { it.id == want.id }) return null
    return want
}

/** [p] with every due change-over applied, or [p] itself when nothing changed. */
fun applyFlips(p: Prefs, nowMs: Long): Prefs {
    if (p.flips.isEmpty()) return p
    var routes = p.routes
    var flips = p.flips
    var reminder = p.reminder
    var changed = false
    for (r in p.routes) {
        val k = pairKey(r)
        val f = flips[k] ?: continue
        if (f.home != r.from && f.home != r.to) continue
        val key = flipPeriod(nowMs, f.at).second
        if (f.applied == key) continue
        val want = flipDue(r, flips, routes, nowMs)
        flips = flips + (k to f.copy(applied = key))
        changed = true
        if (want != null) {
            routes = routes.map { if (it.id == r.id) want else it }
            if (reminder?.routeId == r.id) reminder = null
        }
    }
    return if (changed) p.copy(routes = routes, flips = flips, reminder = reminder) else p
}

/** "a|b=1020,a,2026-11-11:pm;…", the same text in DataStore and the widgets' mirror. */
fun encodeFlips(flips: Map<String, AutoFlip>): String =
    flips.entries.joinToString(";") { (k, f) -> "$k=${f.at},${f.home},${f.applied.orEmpty()}" }

fun decodeFlips(raw: String?): Map<String, AutoFlip> =
    raw.orEmpty().split(';').mapNotNull { part ->
        val (k, v) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        val bits = v.split(',')
        if (bits.size != 3) return@mapNotNull null
        val at = bits[0].toIntOrNull()?.coerceIn(FLIP_MIN, FLIP_MAX) ?: return@mapNotNull null
        k to AutoFlip(at, bits[1], bits[2].ifEmpty { null })
    }.toMap()
