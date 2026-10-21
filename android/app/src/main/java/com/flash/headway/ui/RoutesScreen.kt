package com.flash.headway.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flash.headway.app.Coach
import com.flash.headway.app.Prefs
import com.flash.headway.app.RouteStatus
import com.flash.headway.app.SavedRoute
import com.flash.headway.data.Line
import com.flash.headway.data.Network
import com.flash.headway.data.Platforms
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
    val swap: (String) -> Unit,
    val remind: (RouteStatus, Long) -> Unit,
    val menu: (String) -> Unit,
    val closePreview: () -> Unit,
    val mockOff: () -> Unit,
    val savePreview: () -> Unit,
)

fun routeLabel(r: SavedRoute, inter: Boolean = false): String {
    if (inter) {
        // The inter-corridor runs Chennai Central <-> Airport; name the end this route heads to.
        val stops = Network.routes.getValue(RouteId.INTER).fwd.map { it.first }
        val end = if (stops.indexOf(r.from) < stops.indexOf(r.to)) stops.last() else stops.first()
        return "Inter-corridor · toward ${Network.name(end)}"
    }
    val g = routeGroup(r.from, r.to) ?: return ""
    return "${g.line.label} · toward ${g.terminals.joinToString(" or ") { Network.name(it) }}"
}

/** Platform at the boarding station, from the direction the train heads off in. */
private fun platformFor(r: SavedRoute): String? {
    val g = routeGroup(r.from, r.to) ?: return null
    val line = when (g.line) {
        Line.BLUE -> RouteId.BLUE
        Line.GREEN -> RouteId.GREEN
        else -> return null
    }
    val order = Network.routes.getValue(line).fwd.map { it.first }
    val i = order.indexOf(r.from)
    val j = order.indexOf(g.next)
    if (i < 0 || j < 0) return null
    return Platforms.at(r.from, g.line, if (j > i) order.last() else order.first())
}

/** Only inter-corridor trains serve this route (Green Line tracks, then Blue to the Airport). */
private fun interOnly(s: RouteStatus) = s.departures.isNotEmpty() && s.departures.all { it.trip.route == RouteId.INTER }

private fun routeLine(r: SavedRoute): Line = routeGroup(r.from, r.to)?.line ?: Line.BLUE

private fun trainNote(d: RouteDeparture): String? = when (d.trip.route) {
    RouteId.LOOP -> "short loop"
    RouteId.INTER -> "via CMBT"
    else -> null
}

/** "4 min", "1 h 5", or "now": short enough for a chip. */
private fun chipSpan(ms: Long): String {
    val m = mins(ms)
    return when {
        ms < 30_000 -> "now"
        m < 60 -> "$m min"
        else -> "${m / 60} h ${m % 60}"
    }
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
        GlassGroup(
            listOf(
                Triple(Icons.Rounded.Add, "Add a route", actions.add),
                Triple(Icons.Rounded.MoreHoriz, "Settings", actions.settings),
            ),
        )
    })
    LargeTitle("Routes")
    if (prefs.mockAt != null) MockBanner(now, prefs.mockOffset, actions.mockOff)

    if (preview != null) RouteCard(preview, prefs, now, actions, isPreview = true)
    if (statuses == null) return
    if (statuses.isEmpty() && preview == null) {
        EmptyRoutes(actions)
        return
    }
    statuses.forEach { s -> RouteCard(s, prefs, now, actions, isPreview = false) }
}

/** Shown while mock time is on, so a mocked schedule is never mistaken for the real one. */
@Composable
private fun MockBanner(now: Long, offset: Long, onOff: () -> Unit) {
    val hw = LocalHw.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(hw.warnBg).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Mock time: ${clock(now)}", style = HwType.subheadStrong, color = hw.warn)
            Text("Not the real schedule. Real time is ${clock(now - offset)}.", style = HwType.footnote, color = hw.warn)
        }
        Box(
            Modifier.height(36.dp).clip(CircleShape).background(hw.warnButton).press(scaleTo = 0.95f, onClick = onOff).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Turn off", style = HwType.subheadStrong, color = hw.warn)
        }
    }
}

@Composable
private fun EmptyRoutes(actions: RouteActions) {
    val hw = LocalHw.current
    Column(
        Modifier.fillMaxWidth().clip(RouteCardShape).background(hw.card).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
    // The train shown big. Tapping a chip picks another; it falls back to the next one once it leaves.
    var picked by remember(r.id) { mutableStateOf<Long?>(null) }
    val index = s.departures.indexOfFirst { it.dep == picked }.coerceAtLeast(0)

    Column(
        Modifier.fillMaxWidth().clip(RouteCardShape).background(hw.card).padding(top = 18.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AnimatedContent(
                targetState = r,
                transitionSpec = { fadeIn(spring(stiffness = Spring.StiffnessMedium)) togetherWith fadeOut() },
                modifier = Modifier.weight(1f),
                label = "routeTitle",
            ) { shown ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${Network.name(shown.from)} → ${Network.name(shown.to)}", style = HwType.cardTitle, color = hw.text)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        val inter = shown == r && interOnly(s)
                        Box(
                            Modifier.size(8.dp).clip(CircleShape).then(
                                if (inter) Modifier.background(Brush.verticalGradient(listOf(hw.green, hw.blue))) else Modifier.background(hw.line(routeLine(shown))),
                            ),
                        )
                        Text(routeLabel(shown, inter), style = HwType.subhead, color = hw.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.height(36.dp).clip(CircleShape).background(hw.fill).edge(CircleShape)) {
                PillIcon(Icons.Rounded.SwapVert, "Switch direction") { actions.swap(r.id) }
                if (isPreview) {
                    PillIcon(Icons.Rounded.Close, "Close quick look", actions.closePreview)
                } else {
                    PillIcon(Icons.Rounded.MoreHoriz, "Route options") { actions.menu(r.id) }
                }
            }
        }

        val shown = s.departures.getOrNull(index)
        if (shown == null) {
            Text(
                "No direct trains between these stations.",
                style = HwType.subhead,
                color = hw.sub,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            Hero(s, shown, index, prefs, now, actions)
            Boarding(platformFor(r), prefs.coaches[r.id])
            Chips(s, index, now) { picked = it }
        }

        val last = s.last
        if (s.late && last != null) {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Bedtime, null, tint = hw.sub, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                Text("The ${clock(last.dep)} is the last train tonight.", style = HwType.footnote, color = hw.sub)
            }
        }

        if (isPreview) {
            Box(Modifier.padding(horizontal = 20.dp)) { PrimaryButton("Save this route", Icons.Rounded.Add, actions.savePreview) }
        }
    }
}

@Composable
private fun PillIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    val hw = LocalHw.current
    Box(Modifier.size(42.dp, 36.dp).clip(CircleShape).press(scaleTo = 0.9f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, tint = hw.text, modifier = Modifier.size(20.dp))
    }
}

private fun isTomorrow(s: RouteStatus, i: Int) = s.closed || (s.tomorrowFrom in 0..i)

private fun lastIndex(s: RouteStatus): Int = if (s.closed) -1 else s.departures.indexOfFirst { it.dep == s.last?.dep }

@Composable
private fun Hero(s: RouteStatus, d: RouteDeparture, i: Int, prefs: Prefs, now: Long, actions: RouteActions) {
    val hw = LocalHw.current
    val tomorrow = isTomorrow(s, i)
    val lastIdx = lastIndex(s)
    val (label, warn) = when {
        s.closed && i == 0 -> (if (s.running) "No more trains tonight" else "Metro is closed") to true
        s.closed -> "Tomorrow" to false
        tomorrow -> (if (i == s.tomorrowFrom) "First train tomorrow" else "Tomorrow") to false
        i == lastIdx -> "Last train" to true
        s.late && lastIdx > i -> "Last ${lastIdx - i + 1} trains" to true
        i == 0 -> "Next train" to false
        else -> "Later train" to false
    }
    val labelColor = when {
        warn -> hw.warn
        tomorrow -> hw.sub
        else -> hw.tint
    }
    val note = trainNote(d)?.let { " · $it" }.orEmpty()
    val line = when {
        s.closed && i == 0 -> "First train · arrives ${clock(d.arr)}"
        tomorrow -> "Arrives ${clock(d.arr)}$note"
        else -> "Leaves ${clock(d.dep)} · arrives ${clock(d.arr)}$note"
    }
    val reminderOn = prefs.reminder?.let { it.routeId == s.route.id && it.dep == d.dep } == true
    val bellBg by animateColorAsState(if (reminderOn) hw.tint else hw.fill, label = "bell")

    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = HwType.subheadStrong, color = labelColor)
            if (tomorrow) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val lastDep = remember { mutableLongStateOf(d.dep) }
                    val later = d.dep > lastDep.longValue
                    SideEffect { lastDep.longValue = d.dep }
                    RollingText(clockTime(d.dep), HwType.bigClock, hw.text, up = later)
                    Text(clockAmPm(d.dep), style = HwType.bigUnit, color = hw.text, modifier = Modifier.padding(bottom = 4.dp))
                }
            } else {
                BigCountdown(d.dep - now)
            }
            Text(line, style = HwType.subhead, color = hw.sub)
        }
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(bellBg)
                .edge(CircleShape)
                .press(scaleTo = 0.9f) { actions.remind(s, d.dep) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (reminderOn) Icons.Rounded.NotificationsActive else Icons.Rounded.Notifications,
                contentDescription = if (reminderOn) "Stop the countdown" else "Count down to this train",
                tint = if (reminderOn) Color.White else hw.tint,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** "Platform 2 · Front coach": where to wait. Hidden when neither is known. */
@Composable
private fun Boarding(platform: String?, coach: Coach?) {
    if (platform == null && coach == null) return
    val hw = LocalHw.current
    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (platform != null) BoardingTag("Platform $platform")
        if (coach != null) BoardingTag("${coach.label.replaceFirstChar { it.uppercase() }} coach")
    }
}

@Composable
private fun BoardingTag(text: String) {
    val hw = LocalHw.current
    Text(
        text,
        style = HwType.footnote.copy(fontWeight = FontWeight.SemiBold),
        color = hw.text,
        modifier = Modifier.clip(CircleShape).background(hw.fill).edge(CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Every upcoming departure as a chip; the picked one is ringed and shown in the hero. */
@Composable
private fun Chips(s: RouteStatus, selected: Int, now: Long, onPick: (Long) -> Unit) {
    val hw = LocalHw.current
    val lastIdx = lastIndex(s)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        s.departures.forEachIndexed { i, d ->
            val on = i == selected
            val tomorrow = isTomorrow(s, i)
            val shape = RoundedCornerShape(20.dp)
            val sub = when {
                s.closed && i == 0 -> "first"
                tomorrow -> "tomorrow"
                else -> chipSpan(d.dep - now)
            }
            Column(
                Modifier
                    .widthIn(min = 74.dp)
                    .clip(shape)
                    .background(if (on) hw.tintSoft else hw.fill)
                    .then(if (on) Modifier.border(1.5.dp, hw.tint, shape) else Modifier.edge(shape))
                    .press(scaleTo = 0.94f) { onPick(d.dep) }
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    clock(d.dep),
                    style = HwType.headline.copy(fontFeatureSettings = "tnum"),
                    color = when {
                        on -> hw.tint
                        tomorrow && !s.closed -> hw.sub
                        else -> hw.text
                    },
                )
                Text(
                    sub,
                    style = HwType.footnote.copy(fontWeight = if (i == lastIdx) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (i == lastIdx) hw.warn else hw.sub,
                )
            }
        }
    }
}

@Composable
fun MiniButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val hw = LocalHw.current
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(hw.fill).edge(CircleShape).press(scaleTo = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = hw.sub, modifier = Modifier.size(20.dp))
    }
}
