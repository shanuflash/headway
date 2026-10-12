package com.flash.headway.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Commute
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TransferWithinAStation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flash.headway.app.Prefs
import com.flash.headway.app.Ride
import com.flash.headway.data.Line
import com.flash.headway.data.Network
import com.flash.headway.engine.Journey
import com.flash.headway.engine.Stop
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw

// ---------------- trip detail ----------------

@Composable
fun ColumnScope.TripScreen(
    journey: Journey?,
    prefs: Prefs,
    now: Long,
    onBack: () -> Unit,
    onToggleSaved: () -> Unit,
    onRemind: (Journey) -> Unit,
) {
    val hw = LocalHw.current
    val from = prefs.from
    val to = prefs.to
    val saved = from != null && to != null && prefs.saved.any { it.from == from && it.to == to }
    TopBar(
        left = { RoundButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back", onBack) },
        right = {
            RoundButton(
                if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                if (saved) "Remove from saved trips" else "Save trip",
                onToggleSaved,
                tint = if (saved) hw.tint else null,
            )
        },
    )
    if (journey == null) {
        HwCard { Text("No upcoming trains for this trip.", style = HwType.subhead, color = hw.sub) }
        return
    }
    val j = journey
    val leaveAt = j.dep - prefs.walk * 60_000L
    val reminderOn = prefs.reminderDep == j.dep

    HwCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BadgeIcon(Icons.Rounded.Commute, hw.tint)
            Column(Modifier.weight(1f)) {
                Text("To ${Network.name(to)}", style = HwType.headline, color = hw.text)
                Text("From ${Network.name(from)}, ${changesText(j).lowercase()}", style = HwType.subhead, color = hw.sub)
            }
        }
        BigRow(j.dep - now, clock(j.dep), "arrives ${clock(j.arr)}")

        // Ride time per line in colour, waits at interchanges in grey.
        Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            j.legs.forEachIndexed { i, leg ->
                if (i > 0) {
                    val wait = (leg.dep - j.legs[i - 1].arr).coerceAtLeast(60_000L).toFloat()
                    Box(Modifier.weight(wait).height(8.dp).clip(RoundedCornerShape(4.dp)).background(hw.fill2))
                }
                Box(
                    Modifier
                        .weight((leg.arr - leg.dep).coerceAtLeast(60_000L).toFloat())
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(hw.line(leg.trip.line)),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(hw.warnBg)
                .padding(start = 12.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, tint = hw.warn, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(leaveText(leaveAt - now), style = HwType.subheadStrong, color = hw.warn)
                Text("${prefs.walk} min walk to the platform", style = HwType.footnote, color = hw.warn.copy(alpha = 0.85f))
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (reminderOn) hw.warn else hw.warnButton)
                    .press(scaleTo = 0.9f) { onRemind(j) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (reminderOn) Icons.Rounded.NotificationsActive else Icons.Rounded.Notifications,
                    contentDescription = if (reminderOn) "Turn off the leave reminder" else "Remind me when to leave",
                    tint = if (reminderOn) hw.card else hw.warn,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    SectionTitle("Steps")
    val rows = ArrayList<@Composable () -> Unit>()
    j.legs.forEachIndexed { i, leg ->
        if (i > 0) {
            val walk = Network.transfer(leg.from)
            val spare = ((leg.dep - j.legs[i - 1].arr) / 60_000L).toInt() - walk
            rows.add {
                ListRow(minHeight = 44.dp) {
                    Box(Modifier.width(4.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, tint = hw.sub, modifier = Modifier.size(20.dp).width(20.dp))
                    }
                    Text(
                        "Change at ${Network.name(leg.from)}, $walk min walk" + if (spare > 0) ", $spare min spare" else "",
                        style = HwType.subhead,
                        color = hw.sub,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
            }
        }
        val stops = leg.stops.size - 1
        val detail = if (leg.trip.line == Line.YELLOW && stops == 1) "non-stop, ${span(leg.arr - leg.dep)}" else plural(stops, "stop")
        rows.add {
            ListRow {
                LineBar(hw.line(leg.trip.line))
                RowText(
                    "${leg.trip.line.label} to ${Network.name(leg.to)}",
                    "Toward ${Network.name(leg.trip.dest)}, $detail",
                    modifier = Modifier.weight(1f),
                )
                Text(clock(leg.dep), style = HwType.headline, color = hw.text)
            }
        }
    }
    rows.add {
        ListRow {
            LineBar(hw.sub)
            RowText(
                "Arrive at ${Network.name(to)}",
                "${span(j.arr - j.dep)} from the platform",
                titleStyle = HwType.headline,
                modifier = Modifier.weight(1f),
            )
            Text(clock(j.arr), style = HwType.headline, color = hw.text)
        }
    }
    ListCard(rows)
}

// ---------------- on board ----------------

private data class RideStop(val stop: Stop, val line: Line, val change: Boolean)

private fun rideStops(ride: Ride): List<RideStop> {
    val out = ArrayList<RideStop>()
    ride.legs.forEachIndexed { li, leg ->
        leg.stops.forEachIndexed { si, s ->
            if (li > 0 && si == 0) return@forEachIndexed
            out += RideStop(s, leg.line, li < ride.legs.lastIndex && si == leg.stops.lastIndex)
        }
    }
    return out
}

@Composable
fun ColumnScope.RideScreen(ride: Ride, now: Long, onMinimise: () -> Unit, onEnd: () -> Unit) {
    val hw = LocalHw.current
    val stops = rideStops(ride)
    var idx = stops.indexOfFirst { it.stop.at > now }
    if (idx == -1) idx = stops.lastIndex
    if (idx < 1) idx = 1
    val next = stops[idx]
    val waiting = now < ride.dep
    val done = stops.drop(1).count { it.stop.at <= now }
    val left = stops.size - 1 - done
    val first = ride.legs.first()

    TopBar(
        left = { RoundButton(Icons.Rounded.KeyboardArrowDown, "Minimise", onMinimise) },
        right = { PillButton("End trip", onEnd) },
    )
    LargeTitle("On board") {
        Text("${first.line.label} toward ${Network.name(first.dest)}", style = HwType.body, color = hw.sub)
    }

    HwCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BadgeIcon(Icons.Rounded.Flag, hw.tint)
            RowText("Get off at ${Network.name(ride.to)}", "${plural(left, "stop")} to go", titleStyle = HwType.headline)
        }
        BigRow(ride.arr - now, clock(ride.arr), "arrival")

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 1 until stops.size) {
                val a = stops[i - 1].stop.at
                val b = stops[i].stop.at
                val fraction = when {
                    now >= b -> 1f
                    now > a -> ((now - a).toFloat() / (b - a)).coerceIn(0f, 1f)
                    else -> 0f
                }
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(hw.fill2)) {
                    if (fraction > 0f) {
                        Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp)).background(hw.tint))
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(hw.infoBg)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val icon = when {
                waiting -> Icons.Rounded.Schedule
                next.change -> Icons.Rounded.TransferWithinAStation
                else -> Icons.Rounded.ArrowDownward
            }
            Icon(icon, null, tint = hw.info, modifier = Modifier.size(20.dp))
            val label = when {
                waiting -> "Departs ${Network.name(first.from)}"
                next.change -> "Change at ${Network.name(next.stop.station)}"
                else -> "Next: ${Network.name(next.stop.station)}"
            }
            Text(label, style = HwType.subheadStrong, color = hw.info, modifier = Modifier.weight(1f))
            Text(inSpan((if (waiting) ride.dep else next.stop.at) - now), style = HwType.subhead, color = hw.info)
        }
    }

    val upcoming = if (waiting) stops.subList(1, stops.size - 1) else stops.subList((idx + 1).coerceAtMost(stops.size - 1), stops.size - 1)
    if (upcoming.isNotEmpty()) {
        SectionTitle("Coming up")
        val rows = ArrayList<@Composable () -> Unit>()
        upcoming.take(3).forEach { s ->
            rows.add {
                ListRow(minHeight = 48.dp) {
                    Text(Network.name(s.stop.station), style = HwType.body, color = hw.text, modifier = Modifier.weight(1f))
                    if (s.change) {
                        Text(
                            "Change",
                            style = HwType.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = hw.line(s.line),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(hw.line(s.line).copy(alpha = 0.16f)).padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                    Text(clock(s.stop.at), style = HwType.body, color = hw.sub)
                }
            }
        }
        if (upcoming.size > 3) {
            rows.add {
                ListRow(minHeight = 48.dp) {
                    Text(plural(upcoming.size - 3, "more stop"), style = HwType.body, color = hw.sub)
                }
            }
        }
        ListCard(rows, inset = 16.dp)
    }
}
