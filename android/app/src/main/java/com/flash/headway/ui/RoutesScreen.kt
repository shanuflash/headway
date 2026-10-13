package com.flash.headway.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flash.headway.app.Prefs
import com.flash.headway.app.RouteStatus
import com.flash.headway.app.SavedRoute
import com.flash.headway.data.Line
import com.flash.headway.data.Network
import com.flash.headway.data.RouteId
import com.flash.headway.engine.RouteDeparture
import com.flash.headway.engine.routeGroup
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw

class RouteActions(
    val add: () -> Unit,
    val look: () -> Unit,
    val settings: () -> Unit,
    val swap: (String) -> Unit,
    val remind: (RouteStatus) -> Unit,
    val menu: (String) -> Unit,
    val closePreview: () -> Unit,
    val savePreview: () -> Unit,
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

    if (preview != null) RouteCard(preview, prefs, now, actions, isPreview = true)
    if (statuses == null) return
    if (statuses.isEmpty() && preview == null) {
        EmptyRoutes(actions)
        return
    }
    statuses.forEach { s -> RouteCard(s, prefs, now, actions, isPreview = false) }
}

@Composable
private fun EmptyRoutes(actions: RouteActions) {
    val hw = LocalHw.current
    HwCard(gap = 16.dp) {
        Text("Add your daily route", style = HwType.title, color = hw.text, modifier = Modifier.padding(top = 4.dp))
        Text(
            "Save where you board and where you get off. Headway lists the next trains on it, and one tap flips it for the ride home.",
            style = HwType.subhead,
            color = hw.sub,
        )
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
fun PrimaryButton(text: String, icon: ImageVector, onClick: () -> Unit) {
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
private fun RouteCard(s: RouteStatus, prefs: Prefs, now: Long, actions: RouteActions, isPreview: Boolean) {
    val hw = LocalHw.current
    val r = s.route

    HwCard {
        // Header: the route, and a button that flips it to the other direction in place.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LineBar(hw.line(routeLine(r)))
            AnimatedContent(
                targetState = r,
                transitionSpec = { fadeIn(spring(stiffness = Spring.StiffnessMedium)) togetherWith fadeOut() },
                modifier = Modifier.weight(1f),
                label = "routeTitle",
            ) { shown ->
                Column {
                    Text("${Network.name(shown.from)} → ${Network.name(shown.to)}", style = HwType.headline, color = hw.text)
                    Text(routeLabel(shown), style = HwType.subhead, color = hw.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            MiniButton(Icons.Rounded.SwapVert, "Switch direction") { actions.swap(r.id) }
            if (isPreview) {
                MiniButton(Icons.Rounded.Close, "Close quick look", actions.closePreview)
            } else {
                MiniButton(Icons.Rounded.MoreHoriz, "Route options") { actions.menu(r.id) }
            }
        }

        val next = s.next
        if (next == null) {
            Text("No direct trains between these stations.", style = HwType.subhead, color = hw.sub)
        } else {
            NextTrain(s, next, prefs, now, actions)
            Departures(s, now)
        }

        val last = s.last
        val tomorrowFirst = s.departures.getOrNull(s.tomorrowFrom.coerceAtLeast(0))?.takeIf { s.tomorrowFrom >= 0 }
        if (s.late && last != null && tomorrowFirst != null && s.tomorrowFrom > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Bedtime, null, tint = hw.sub, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                Text(
                    "The ${clock(last.dep)} is the last train tonight. The first one tomorrow is at ${clock(tomorrowFirst.dep)}.",
                    style = HwType.footnote,
                    color = hw.sub,
                )
            }
        }

        if (isPreview) PrimaryButton("Save this route", Icons.Rounded.Add, actions.savePreview)
    }
}

@Composable
private fun NextTrain(s: RouteStatus, next: RouteDeparture, prefs: Prefs, now: Long, actions: RouteActions) {
    val hw = LocalHw.current
    val reminderOn = prefs.reminder?.let { it.routeId == s.route.id && it.dep == next.dep } == true
    val label = when {
        s.closed && s.running -> "No more trains tonight"
        s.closed -> "Metro is closed"
        s.late && s.remainingToday <= 2 -> if (s.remainingToday == 1) "Last train" else "Last 2 trains"
        else -> "Next train"
    }
    val warn = s.closed || s.late

    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(label, style = HwType.footnote.copy(fontWeight = FontWeight.SemiBold), color = if (warn) hw.warn else hw.tint)
            if (s.closed) {
                Text(clock(next.dep), style = HwType.big.copy(fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = (-1).sp), color = hw.text)
            } else {
                BigCountdown(next.dep - now)
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 6.dp)) {
            Text(if (s.closed) "first train" else clock(next.dep), style = HwType.headline, color = hw.text)
            Text("arrives ${clock(next.arr)}", style = HwType.subhead, color = hw.sub)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .padding(bottom = 6.dp)
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (reminderOn) hw.tint else hw.fill)
                .press(scaleTo = 0.9f) { actions.remind(s) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (reminderOn) Icons.Rounded.NotificationsActive else Icons.Rounded.Notifications,
                contentDescription = if (reminderOn) "Turn off the reminder" else "Remind me before this train",
                tint = if (reminderOn) Color.White else hw.tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The departures after the next one, with a divider where tomorrow starts. */
@Composable
private fun Departures(s: RouteStatus, now: Long) {
    val hw = LocalHw.current
    val rest = s.departures.drop(1)
    if (rest.isEmpty()) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(hw.fill)) {
        rest.forEachIndexed { i, d ->
            val index = i + 1
            val startsTomorrow = !s.closed && index == s.tomorrowFrom
            if (startsTomorrow) {
                Text(
                    "Tomorrow",
                    style = HwType.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = hw.sub,
                    modifier = Modifier.padding(start = 12.dp, top = if (i == 0) 10.dp else 12.dp, bottom = 2.dp),
                )
            } else if (i > 0) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(hw.sep))
            }
            val isLast = s.last?.dep == d.dep
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(clock(d.dep), style = HwType.headline, color = hw.text, modifier = Modifier.width(52.dp))
                Text(
                    buildString {
                        if (isLast) append("last train, ") else trainNote(d)?.let { append("$it, ") }
                        append("arrives ${clock(d.arr)}")
                    },
                    style = HwType.footnote,
                    color = if (isLast) hw.warn else hw.sub,
                    modifier = Modifier.weight(1f),
                )
                val tomorrow = s.tomorrowFrom in 0..index
                Text(if (tomorrow) "" else inSpan(d.dep - now), style = HwType.subhead, color = hw.sub)
            }
        }
    }
}

@Composable
fun MiniButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val hw = LocalHw.current
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(hw.fill).press(scaleTo = 0.9f, onClick = onClick),
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
        style = HwType.footnote.copy(fontWeight = FontWeight.SemiBold),
        color = hw.warn,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(hw.warnBg).padding(horizontal = 9.dp, vertical = 4.dp),
    )
}
