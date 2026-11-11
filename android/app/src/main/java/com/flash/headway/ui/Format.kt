package com.flash.headway.ui

import com.flash.headway.data.Network
import com.flash.headway.engine.IST
import com.flash.headway.engine.Journey
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

private val H12 = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val H12_TIME = DateTimeFormatter.ofPattern("h:mm", Locale.US)
private val AMPM = DateTimeFormatter.ofPattern("a", Locale.US)

/** "8:13 AM": 12-hour, in IST. */
fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(IST).format(H12)

/** "8:13" without AM/PM, for big numerals and tight spots. */
fun clockTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(IST).format(H12_TIME)

fun clockAmPm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(IST).format(AMPM)

/** Minutes past midnight as "5:30 AM". */
fun minutesClock(m: Int): String {
    val h = (m / 60) % 24
    return "${if (h % 12 == 0) 12 else h % 12}:${"%02d".format(m % 60)} ${if (h < 12) "AM" else "PM"}"
}

fun plural(n: Int, word: String): String = "$n $word${if (n == 1) "" else "s"}"

// Rounds up: departures are on the minute, so this matches subtracting the clock times shown.
fun mins(ms: Long): Long = ((ms + 59_999) / 60_000).coerceAtLeast(0)

/** "7 min" or "2 h 5 min". */
fun span(ms: Long): String {
    val m = (ms / 60_000.0).roundToLong().coerceAtLeast(0)
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

// Same rounding as the hero number, so "in 7 min" and a big "7" always agree.
fun inSpan(ms: Long): String = when {
    ms < 30_000 -> "now"
    ms < 60 * 60_000 -> "in ${mins(ms)} min"
    else -> "in ${span(ms)}"
}

fun leaveText(ms: Long): String = if (ms < 30_000) "Leave now" else "Leave in ${span(ms)}"

/** Hero number and unit: whole minutes, m:ss under two minutes, h:mm past an hour. */
fun bigParts(ms: Long): Pair<String, String> {
    if (ms < 30_000) return "Now" to ""
    if (ms <= 120_000) {
        val s = (ms + 999) / 1000
        return "${s / 60}:${"%02d".format(s % 60)}" to "min"
    }
    val m = mins(ms)
    return if (m < 60) "$m" to "min" else "${m / 60}:${"%02d".format(m % 60)}" to "h"
}

fun changesText(j: Journey): String =
    if (j.changes == 0) "Direct"
    else "${plural(j.changes, "change")}, at ${j.legs.drop(1).joinToString(" and ") { Network.name(it.from) }}"

/** "2 min late", "1 min early" or "On time": a route's static delay in words. */
fun delayText(d: Int): String = when {
    d > 0 -> "$d min late"
    d < 0 -> "${-d} min early"
    else -> "On time"
}
