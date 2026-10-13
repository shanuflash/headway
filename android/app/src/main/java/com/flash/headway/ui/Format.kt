package com.flash.headway.ui

import com.flash.headway.data.Network
import com.flash.headway.engine.IST
import com.flash.headway.engine.Journey
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(IST).format(HHMM)

fun plural(n: Int, word: String): String = "$n $word${if (n == 1) "" else "s"}"

fun mins(ms: Long): Long = (ms / 60_000).coerceAtLeast(0)

/** "7 min" or "2 h 5 min". */
fun span(ms: Long): String {
    val m = (ms / 60_000.0).roundToLong().coerceAtLeast(0)
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

// Floors like the hero number, so "in 7 min" and a big "7" always agree.
fun inSpan(ms: Long): String = when {
    ms < 60_000 -> "now"
    ms < 60 * 60_000 -> "in ${mins(ms)} min"
    else -> "in ${span(ms)}"
}

fun leaveText(ms: Long): String = if (ms < 30_000) "Leave now" else "Leave in ${span(ms)}"

/** Hero number and unit: whole minutes, m:ss under two minutes, h:mm past an hour. */
fun bigParts(ms: Long): Pair<String, String> {
    if (ms < 30_000) return "Now" to ""
    if (ms < 120_000) {
        val s = ms / 1000
        return "${s / 60}:${"%02d".format(s % 60)}" to "min"
    }
    val m = mins(ms)
    return if (m < 60) "$m" to "min" else "${m / 60}:${"%02d".format(m % 60)}" to "h"
}

fun changesText(j: Journey): String =
    if (j.changes == 0) "Direct"
    else "${plural(j.changes, "change")}, at ${j.legs.drop(1).joinToString(" and ") { Network.name(it.from) }}"
