package com.flash.headway.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DirectionsSubway
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subway
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.flash.headway.app.DepGroup
import com.flash.headway.app.NowState
import com.flash.headway.app.Prefs
import com.flash.headway.app.routeLabel
import com.flash.headway.data.Line
import com.flash.headway.data.Network
import com.flash.headway.data.RouteId
import com.flash.headway.data.Timetable
import com.flash.headway.engine.Journey
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw

class NowActions(
    val pickFrom: () -> Unit,
    val pickTo: () -> Unit,
    val settings: () -> Unit,
    val openTrip: (Long) -> Unit,
    val remind: (Journey) -> Unit,
    val quick: (String) -> Unit,
    val openRide: () -> Unit,
    val reminderTime: (Journey) -> Long,
)

@Composable
fun ColumnScope.NowScreen(state: NowState, prefs: Prefs, now: Long, actions: NowActions) {
    val hw = LocalHw.current
    TopBar(right = {
        RoundButton(Icons.Rounded.Add, "New trip", actions.pickTo)
        RoundButton(Icons.Rounded.MoreHoriz, "Settings", actions.settings)
    })
    LargeTitle("Now") {
        Row(
            modifier = Modifier.press(scaleTo = 0.97f, onClick = actions.pickFrom),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                prefs.from?.let { "From ${Network.name(it)}" } ?: "Set a starting station",
                style = HwType.body,
                color = hw.tint,
            )
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = hw.tint, modifier = Modifier.size(20.dp))
        }
    }

    prefs.ride?.let { ride ->
        ListCard(listOf<@Composable () -> Unit>({
            ListRow(onClick = actions.openRide) {
                BadgeIcon(Icons.Rounded.DirectionsSubway, hw.tint)
                RowText(
                    "On board to ${Network.name(ride.to)}",
                    "Arriving ${clock(ride.arr)}, ${inSpan(ride.arr - now)}",
                    titleStyle = HwType.headline,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = hw.chev, modifier = Modifier.size(20.dp))
            }
        }))
    }

    when (state) {
        NowState.Loading -> Unit
        is NowState.Empty -> EmptyHero(actions)
        is NowState.NoService -> HwCard {
            RowText("No trains found", "There's no scheduled service between these stations.", titleStyle = HwType.headline)
        }
        is NowState.Ready -> {
            if (state.closed) ClosedHero(state, prefs, now, actions) else TripHero(state, prefs, now, actions)
            Departures(state, prefs, now)
        }
    }
}

@Composable
private fun EmptyHero(actions: NowActions) {
    val hw = LocalHw.current
    HwCard(padding = 16.dp) {
        Text("Where to?", style = HwType.title, color = hw.text, modifier = Modifier.padding(top = 4.dp))
        Text(
            "Pick where you start and where you're going. Headway shows your next train, when to leave, and the last train home.",
            style = HwType.subhead,
            color = hw.sub,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(CircleShape)
                .background(hw.fill)
                .press(scaleTo = 0.98f, onClick = actions.pickTo)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Search, null, tint = hw.sub, modifier = Modifier.size(22.dp))
            Text("Search stations", style = HwType.body, color = hw.sub)
        }
        val picks = listOf("central" to "Chennai Central", "airport" to "Airport", "koyambedu" to "Koyambedu", "vadapalani" to "Vadapalani")
        picks.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (id, label) ->
                    Box(
                        Modifier
                            .height(36.dp)
                            .clip(CircleShape)
                            .border(0.5.dp, hw.sep, CircleShape)
                            .press { actions.quick(id) }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = HwType.subhead, color = hw.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun TripHero(s: NowState.Ready, prefs: Prefs, now: Long, actions: NowActions) {
    val hw = LocalHw.current
    val j = s.journey
    val leg = j.legs.first()
    val reminderOn = prefs.reminderDep == j.dep
    HwCard {
        Row(
            modifier = Modifier.fillMaxWidth().press(scaleTo = 0.98f) { actions.openTrip(j.dep) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BadgeIcon(Icons.Rounded.Subway, hw.tint)
            Column(Modifier.weight(1f)) {
                Text("To ${Network.name(prefs.to)}", style = HwType.headline, color = hw.text)
                val changes = if (j.changes > 0) ", ${plural(j.changes, "change")}" else ""
                Text(
                    "${routeLabel(leg.trip.route, leg.trip.line)} toward ${Network.name(leg.trip.dest)}$changes",
                    style = HwType.subhead,
                    color = hw.sub,
                )
            }
            if (s.late) {
                val n = s.remaining
                Tag(if (n != null && n <= 2) "Last ${plural(n, "train")}" else "Last hour")
            } else {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = hw.chev, modifier = Modifier.size(20.dp))
            }
        }

        BigRow(j.dep - now, clock(j.dep), "arrives ${clock(j.arr)}")

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
                if (prefs.walk > 0) {
                    Text(leaveText(s.leaveAt - now), style = HwType.subheadStrong, color = hw.warn)
                    Text("${prefs.walk} min walk to the platform", style = HwType.footnote, color = hw.warn.copy(alpha = 0.85f))
                } else {
                    Text("Be on the platform by ${clock(j.dep)}", style = HwType.subheadStrong, color = hw.warn)
                    Text("Set your walk time in Settings", style = HwType.footnote, color = hw.warn.copy(alpha = 0.85f))
                }
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (reminderOn) hw.warn else hw.warnButton)
                    .press(scaleTo = 0.9f) { actions.remind(j) },
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

        if (s.later.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.later.forEach { x ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(hw.fill)
                            .press { actions.openTrip(x.dep) }
                            .padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(clock(x.dep), style = HwType.subheadStrong, color = hw.text)
                        val note = if (x.legs.first().trip.route == RouteId.LOOP) "short loop" else "arr ${clock(x.arr)}"
                        Text(note, style = HwType.caption, color = hw.sub)
                    }
                }
            }
        }

        val last = s.last
        val nextDay = s.nextDay
        if (s.late && last != null && nextDay != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Bedtime, null, tint = hw.sub, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                Text(
                    "After ${clock(last.dep)}, the next train to ${Network.name(prefs.to)} is at ${clock(nextDay.dep)} tomorrow.",
                    style = HwType.footnote,
                    color = hw.sub,
                )
            }
        }
    }
}

@Composable
private fun ClosedHero(s: NowState.Ready, prefs: Prefs, now: Long, actions: NowActions) {
    val hw = LocalHw.current
    val j = s.journey
    val reminderOn = prefs.reminderDep == j.dep
    val remindAt = actions.reminderTime(j)
    val yellow = j.legs.any { it.trip.line == Line.YELLOW }
    val spec = Timetable.day(s.today.type).yellow
    HwCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BadgeIcon(Icons.Rounded.Bedtime, hw.fill, tint = hw.warn)
            RowText(
                if (s.running) "No more trains tonight" else "Metro is closed",
                "First trip to ${Network.name(prefs.to)}",
                titleStyle = HwType.headline,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(clock(j.dep), style = HwType.big, color = hw.text, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 6.dp)) {
                Text(inSpan(j.dep - now), style = HwType.headline, color = hw.text)
                Text("arrives ${clock(j.arr)}", style = HwType.subhead, color = hw.sub)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (reminderOn) hw.tint else hw.fill)
                .press(scaleTo = 0.98f) { actions.remind(j) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val color = if (reminderOn) androidx.compose.ui.graphics.Color.White else hw.tint
            Icon(Icons.Rounded.Alarm, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(
                if (reminderOn) "Reminder set for ${clock(remindAt)}" else "Remind me at ${clock(remindAt)}",
                style = HwType.subheadStrong,
                color = color,
            )
        }
        Text(
            if (yellow) "The Yellow Line runs ${hhmm(spec.firstFwd)} to ${hhmm(spec.lastFwd)}." else "Trains run from about 05:00 to 23:00.",
            style = HwType.footnote,
            color = hw.sub,
        )
    }
}

private fun hhmm(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

@Composable
fun Tag(text: String) {
    val hw = LocalHw.current
    Text(
        text,
        style = HwType.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        color = hw.warn,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(hw.warnBg).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
fun BigRow(msLeft: Long, time: String, sub: String) {
    val hw = LocalHw.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) { BigCountdown(msLeft) }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 6.dp)) {
            Text(time, style = HwType.headline, color = hw.text)
            Text(sub, style = HwType.subhead, color = hw.sub)
        }
    }
}

@Composable
private fun Departures(s: NowState.Ready, prefs: Prefs, now: Long) {
    val hw = LocalHw.current
    if (s.departures.isEmpty()) return
    SectionTitle(if (s.overnight) "First trains" else if (s.late) "Last departures" else "Departures")
    val rows = ArrayList<@Composable () -> Unit>()
    s.departures.forEach { g -> rows.add { DepartureRow(g, s.overnight, now) } }
    val last = s.last
    if (!s.closed && last != null && !s.late) {
        rows.add {
            ListRow {
                LineBar(hw.sub)
                RowText(
                    "Last train to ${Network.name(prefs.to)}",
                    "Tonight, arrives ${clock(last.arr)}",
                    modifier = Modifier.weight(1f),
                )
                Text(clock(last.dep), style = HwType.headline, color = hw.text)
            }
        }
    }
    ListCard(rows)
}

@Composable
private fun DepartureRow(g: DepGroup, overnight: Boolean, now: Long) {
    val hw = LocalHw.current
    ListRow {
        LineBar(hw.line(g.line), alpha = if (g.route == RouteId.LOOP) 0.45f else 1f)
        RowText(Network.name(g.dest), g.sub, modifier = Modifier.weight(1f))
        val times = if (overnight) g.times.joinToString(", ") { clock(it) } else g.times.joinToString(", ") { "${mins(it - now)}" } + " min"
        Text(times, style = HwType.headline, color = if (g.warn) hw.warn else hw.text)
    }
}
