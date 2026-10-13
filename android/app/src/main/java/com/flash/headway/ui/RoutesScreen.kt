package com.flash.headway.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flash.headway.app.ARRIVE_CHOICES
import com.flash.headway.app.Prefs
import com.flash.headway.app.RouteStatus
import com.flash.headway.app.SavedRoute
import com.flash.headway.data.Line
import com.flash.headway.data.Network
import com.flash.headway.data.RouteId
import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.RouteDeparture
import com.flash.headway.engine.routeGroup
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw

class RouteActions(
    val add: () -> Unit,
    val look: () -> Unit,
    val settings: () -> Unit,
    val expand: (String) -> Unit,
    val arrive: (String, Int) -> Unit,
    val remind: (RouteStatus) -> Unit,
    val menu: (String) -> Unit,
    val closePreview: () -> Unit,
    val savePreview: () -> Unit,
    val reminderTime: (SavedRoute, Long) -> Long,
)

fun routeLabel(r: SavedRoute): String {
    val g = routeGroup(r.from, r.to) ?: return ""
    return "${g.line.label} toward ${g.terminals.joinToString(" or ") { Network.name(it) }}"
}

private fun routeLine(r: SavedRoute): Line = routeGroup(r.from, r.to)?.line ?: Line.BLUE

private fun trainNote(d: RouteDeparture): String? = when (d.trip.route) {
    RouteId.LOOP -> "short loop"
    RouteId.INTER -> "via CMBT"
    else -> null
}

@Composable
fun ColumnScope.RoutesScreen(
    statuses: List<RouteStatus>?,
    preview: RouteStatus?,
    prefs: Prefs,
    now: Long,
    actions: RouteActions,
) {
    TopBar(right = {
        RoundButton(Icons.Rounded.Add, "Add a route", actions.add)
        RoundButton(Icons.Rounded.MoreHoriz, "Settings", actions.settings)
    })
    LargeTitle("Routes")

    if (preview != null) RouteCard(preview, prefs, now, actions, expanded = true, isPreview = true)
    if (statuses == null) return
    if (statuses.isEmpty() && preview == null) {
        EmptyRoutes(actions)
        return
    }
    val expandedId = prefs.expanded?.takeIf { id -> statuses.any { it.route.id == id } } ?: statuses.firstOrNull()?.route?.id
    statuses.forEach { s ->
        RouteCard(s, prefs, now, actions, expanded = preview == null && s.route.id == expandedId, isPreview = false)
    }
}

@Composable
private fun EmptyRoutes(actions: RouteActions) {
    val hw = LocalHw.current
    HwCard(gap = 16.dp) {
        Text("Add your daily route", style = HwType.title, color = hw.text, modifier = Modifier.padding(top = 4.dp))
        Text(
            "Save where you board and where you get off. Headway shows every train you can still make, even when you're coming off a bus.",
            style = HwType.subhead,
            color = hw.sub,
        )
        listOf("Pick the station you board at", "Pick where you get off", "Tap how soon you'll be there").forEachIndexed { i, text ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(hw.fill), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = HwType.subheadStrong, color = hw.text)
                }
                Text(text, style = HwType.body, color = hw.text)
            }
        }
        PrimaryButton("Add a route", Icons.Rounded.Add, actions.add)
    }
    SectionTitle("Or check any station")
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(hw.card)
            .press(scaleTo = 0.98f, onClick = actions.look)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = hw.sub, modifier = Modifier.size(22.dp))
        Text("Search stations", style = HwType.body, color = hw.sub)
    }
}

@Composable
fun PrimaryButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val hw = LocalHw.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(hw.tint)
            .press(scaleTo = 0.97f, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = HwType.headline, color = Color.White)
    }
}

@Composable
private fun RouteCard(s: RouteStatus, prefs: Prefs, now: Long, actions: RouteActions, expanded: Boolean, isPreview: Boolean) {
    val hw = LocalHw.current
    val r = s.route
    val next = s.next

    if (!expanded) {
        ListCard(listOf<@Composable () -> Unit>({
            ListRow(onClick = { actions.expand(r.id) }) {
                LineBar(hw.line(routeLine(r)))
                RowText(
                    "${Network.name(r.from)} → ${Network.name(r.to)}",
                    routeLabel(r),
                    titleStyle = HwType.headline,
                    modifier = Modifier.weight(1f),
                )
                if (next == null) {
                    Text("No trains", style = HwType.body, color = hw.sub)
                } else {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(clock(next.dep), style = HwType.headline, color = hw.text)
                        Text(if (s.closed) "tomorrow" else inSpan(next.dep - now), style = HwType.footnote, color = hw.sub)
                    }
                }
            }
        }))
        return
    }

    HwCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LineBar(hw.line(routeLine(r)))
            Column(Modifier.weight(1f)) {
                Text("${Network.name(r.from)} → ${Network.name(r.to)}", style = HwType.headline, color = hw.text)
                Text(routeLabel(r), style = HwType.subhead, color = hw.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                isPreview -> MiniButton(Icons.Rounded.Close, "Close quick look", actions.closePreview)
                s.late -> Tag(if (s.remainingToday <= 2) "Last ${plural(s.remainingToday, "train")}" else "Last hour")
                else -> MiniButton(Icons.Rounded.MoreHoriz, "Route options") { actions.menu(r.id) }
            }
        }

        ArriveChips(r) { actions.arrive(r.id, it) }

        when {
            next == null -> Text("No direct trains between these stations.", style = HwType.subhead, color = hw.sub)
            s.closed -> ClosedBody(s, next, prefs, now, actions)
            else -> OpenBody(s, next, prefs, now, actions)
        }

        if (isPreview) PrimaryButton("Save this route", Icons.Rounded.Add, actions.savePreview)
    }
}

@Composable
private fun ArriveChips(r: SavedRoute, onPick: (Int) -> Unit) {
    val hw = LocalHw.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("I'll be at ${Network.name(r.from)} in", style = HwType.footnote, color = hw.sub)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ARRIVE_CHOICES.forEach { m ->
                val on = m == r.arrive
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(if (on) hw.tint else hw.fill)
                        .press(scaleTo = 0.92f, role = Role.RadioButton) { onPick(m) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (m == 0) "Now" else "${m}m",
                        style = if (on) HwType.subheadStrong else HwType.subhead,
                        color = if (on) hw.card else hw.text,
                    )
                }
            }
        }
    }
}

@Composable
private fun OpenBody(s: RouteStatus, next: RouteDeparture, prefs: Prefs, now: Long, actions: RouteActions) {
    val hw = LocalHw.current
    val r = s.route
    val reminderOn = prefs.reminder?.let { it.routeId == r.id && it.dep == next.dep } == true
    val wait = ((next.dep - s.reach) / MINUTE).toInt()
    val note = trainNote(next)

    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text("Catch the ${clock(next.dep)}", style = HwType.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = hw.tint)
            BigCountdown(next.dep - now)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 6.dp)) {
            Text(clock(next.dep), style = HwType.headline, color = hw.text)
            Text("arrives ${clock(next.arr)}", style = HwType.subhead, color = hw.sub)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(hw.infoBg)
            .padding(start = 12.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.HourglassTop, null, tint = hw.info, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            val title = when {
                r.arrive == 0 -> "Leaves ${inSpan(next.dep - now)}"
                wait < 1 -> "It leaves as you get there"
                else -> "You'll wait $wait min"
            }
            val sub = buildString {
                if (r.arrive == 0) append("Tap when you'll get there to skip trains you can't make.")
                else append("Reach the platform by ${clock(s.reach)}.")
                if (note != null) append(" This one is a $note.")
            }
            Text(title, style = HwType.subheadStrong, color = hw.info)
            Text(sub, style = HwType.footnote, color = hw.info.copy(alpha = 0.85f))
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (reminderOn) hw.info else hw.info.copy(alpha = 0.14f))
                .press(scaleTo = 0.9f) { actions.remind(s) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (reminderOn) Icons.Rounded.NotificationsActive else Icons.Rounded.Notifications,
                contentDescription = if (reminderOn) "Turn off the reminder" else "Remind me before this train",
                tint = if (reminderOn) hw.card else hw.info,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    val rows = ArrayList<@Composable () -> Unit>()
    s.more.forEach { d ->
        val isLast = s.last?.dep == d.dep
        rows.add {
            TrainRow(
                time = clock(d.dep),
                note = buildString {
                    if (isLast) append("last train, ") else trainNote(d)?.let { append("$it, ") }
                    append("arrives ${clock(d.arr)}")
                },
                right = if (r.arrive > 0) "wait ${((d.dep - s.reach) / MINUTE)} min" else inSpan(d.dep - now),
                rightColor = if (isLast) hw.warn else hw.sub,
            )
        }
    }
    val missed = s.missed
    if (missed != null && r.arrive > 0) {
        val by = ((s.reach - missed.dep) / MINUTE).coerceAtLeast(1)
        rows.add {
            Box(Modifier.alpha(0.55f)) {
                TrainRow(clock(missed.dep), "leaves $by min before you get there", "missed", hw.sub)
            }
        }
    }
    if (rows.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(hw.fill)) {
            rows.forEachIndexed { i, row ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(hw.sep))
                row()
            }
        }
    }

    val last = s.last
    val tomorrow = s.tomorrow
    if (s.late && last != null && tomorrow != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Bedtime, null, tint = hw.sub, modifier = Modifier.size(16.dp).padding(top = 1.dp))
            Text(
                "After ${clock(last.dep)}, the next train to ${Network.name(r.to)} is at ${clock(tomorrow.dep)} tomorrow.",
                style = HwType.footnote,
                color = hw.sub,
            )
        }
    }
}

@Composable
private fun TrainRow(time: String, note: String, right: String, rightColor: Color) {
    val hw = LocalHw.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(time, style = HwType.headline, color = hw.text, modifier = Modifier.width(52.dp))
        Text(note, style = HwType.footnote, color = hw.sub, modifier = Modifier.weight(1f))
        Text(right, style = HwType.subhead, color = rightColor)
    }
}

@Composable
private fun ClosedBody(s: RouteStatus, next: RouteDeparture, prefs: Prefs, now: Long, actions: RouteActions) {
    val hw = LocalHw.current
    val r = s.route
    val last = s.last
    val reminderOn = prefs.reminder?.let { it.routeId == r.id && it.dep == next.dep } == true
    val remindAt = actions.reminderTime(r, next.dep)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.Bedtime, null, tint = hw.warn, modifier = Modifier.size(20.dp))
        Text(
            when {
                s.missedLast && last != null -> "You'll miss the last train at ${clock(last.dep)}"
                s.running -> "No more trains tonight"
                else -> "Metro is closed"
            },
            style = HwType.subheadStrong,
            color = hw.warn,
        )
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(clock(next.dep), style = HwType.big, color = hw.text, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 6.dp)) {
            Text(inSpan(next.dep - now), style = HwType.headline, color = hw.text)
            Text("first train, arrives ${clock(next.arr)}", style = HwType.subhead, color = hw.sub)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (reminderOn) hw.tint else hw.fill)
            .press(scaleTo = 0.98f) { actions.remind(s) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val color = if (reminderOn) Color.White else hw.tint
        Icon(Icons.Rounded.Alarm, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (reminderOn) "Reminder set for ${clock(remindAt)}" else "Remind me at ${clock(remindAt)}", style = HwType.subheadStrong, color = color)
    }
}

@Composable
fun MiniButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val hw = LocalHw.current
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(hw.fill).press(scaleTo = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = hw.sub, modifier = Modifier.size(20.dp))
    }
}

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
