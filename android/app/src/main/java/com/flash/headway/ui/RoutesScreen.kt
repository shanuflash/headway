package com.flash.headway.ui

import android.net.Uri
import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreTime
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flash.headway.app.Coach
import com.flash.headway.app.TopUp
import com.flash.headway.app.Prefs
import com.flash.headway.app.RouteStatus
import com.flash.headway.app.SavedRoute
import com.flash.headway.app.pairKey
import com.flash.headway.app.reverseId
import com.flash.headway.app.PlanMode
import com.flash.headway.app.PlanTime
import com.flash.headway.app.timeKey
import com.flash.headway.data.Line
import com.flash.headway.data.Network
import com.flash.headway.data.Platforms
import com.flash.headway.data.RouteId
import com.flash.headway.engine.DestinationGroup
import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.RouteDeparture
import com.flash.headway.engine.routeGroup
import com.flash.headway.engine.Feed
import com.flash.headway.engine.activeNotices
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

class RouteActions(
    val add: () -> Unit,
    val look: () -> Unit,
    val settings: () -> Unit,
    val swap: (String) -> Unit,
    val remind: (RouteStatus, Long) -> Unit,
    val menu: (String) -> Unit,
    val closePreview: () -> Unit,
    val mockOff: () -> Unit,
    val topUp: () -> Unit,
    val savePreview: () -> Unit,
    /** Opens "When?" to plan the quick look for another time. */
    val whenOpen: () -> Unit = {},
    /** Opens one train's own delay: the route id and the train's timetabled departure. */
    val trainDelay: (String, Long) -> Unit = { _, _ -> },
    /** Flips the quick look (not a saved card with the same stations). */
    val swapPreview: () -> Unit = {},
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
private fun platformFor(r: SavedRoute): String? = routeGroup(r.from, r.to)?.let { platformAt(r.from, it) }

/** Platform text at [from] for a direction group (its line and next stop), e.g. "Platform 2". */
fun platformAt(from: String, g: DestinationGroup): String? {
    val line = when (g.line) {
        Line.BLUE -> RouteId.BLUE
        Line.GREEN -> RouteId.GREEN
        Line.YELLOW -> RouteId.YELLOW
    }
    val order = Network.routes.getValue(line).fwd.map { it.first }
    val i = order.indexOf(from)
    val j = order.indexOf(g.next)
    if (i < 0 || j < 0) return null
    return Platforms.at(from, g.line, if (j > i) order.last() else order.first())?.let { Platforms.text(it) }
}

/** Only inter-corridor trains serve this route (Green Line tracks, then Blue to the Airport). */
private fun interOnly(s: RouteStatus) = s.departures.isNotEmpty() && s.departures.all { it.trip.route == RouteId.INTER && (it.legs?.size ?: 1) == 1 }

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

/**
 * The routes tab as lazy list items: only the cards on screen are composed, so a long list of
 * routes stays smooth. Each card is keyed by its route, so it keeps its picked train as you scroll.
 */
fun LazyListScope.routesScreen(
    statuses: List<RouteStatus>?,
    preview: RouteStatus?,
    prefs: Prefs,
    feed: Feed?,
    now: () -> Long,
    actions: RouteActions,
) {
    item(key = "top", contentType = "top") {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TopBar(right = {
                GlassGroup(
                    listOf(
                        Triple(Icons.Rounded.CreditCard, "Metro card balance and top-up", actions.topUp),
                        Triple(Icons.Rounded.Add, "Add a route", actions.add),
                        Triple(Icons.Rounded.MoreHoriz, "Settings", actions.settings),
                    ),
                )
            })
            LargeTitle("Routes")
            // In this column a hidden banner takes no space (a lazy item would leave a gap).
            if (prefs.mockAt != null) MockBanner(now, prefs.mockOffset, actions.mockOff)
            LoadCardBanner(now)
            Notices(feed, now)
        }
    }

    if (preview != null) item(key = "preview", contentType = "card") { RouteCard(preview, prefs, now, actions, isPreview = true) }
    if (statuses == null) return
    if (statuses.isEmpty() && preview == null) {
        item(key = "empty", contentType = "empty") { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { EmptyRoutes(actions) } }
        return
    }
    items(statuses, key = { it.route.id }, contentType = { "card" }) { s -> RouteCard(s, prefs, now, actions, isPreview = false) }
}

/** CMRL advisories in force now (extended hours, holiday timings, disruptions). Tap for the announcement. */
@Composable
private fun Notices(feed: Feed?, now: () -> Long) {
    val notices = activeNotices(feed, now())
    if (notices.isEmpty()) return
    val hw = LocalHw.current
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        notices.forEach { n ->
            val (fg, bg) = if (n.level == "info") hw.info to hw.infoBg else hw.warn to hw.warnBg
            val link = n.link
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    .then(
                        if (link != null) {
                            Modifier.press(scaleTo = 0.98f) {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                            }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(n.title, style = HwType.subheadStrong, color = fg)
                n.body?.let { Text(it, style = HwType.footnote, color = fg, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** After a UPI top-up: the money is paid in, but only the Airtel screen writes it to the card. */
@Composable
private fun LoadCardBanner(now: () -> Long) {
    val context = LocalContext.current
    // Re-read every few seconds (now ticks each second), so it appears on return from the UPI app.
    val pending = remember(now() / 5_000) { TopUp.loadPending(context) }
    var hidden by remember { mutableStateOf(false) }
    if (!pending || hidden) return
    val hw = LocalHw.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(hw.tintSoft).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Load it onto your card", style = HwType.subheadStrong, color = hw.text)
            Text("Paid by UPI? Tap your card in Airtel to write the balance.", style = HwType.footnote, color = hw.sub)
        }
        Box(
            Modifier.height(36.dp).clip(CircleShape).background(hw.tint).press(scaleTo = 0.95f) {
                TopUp.clearLoadPending(context)
                hidden = true
                TopUp.openAirtel(context)
            }.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Open Airtel", style = HwType.subheadStrong, color = Color.White)
        }
        PillIcon(Icons.Rounded.Close, "Dismiss") {
            TopUp.clearLoadPending(context)
            hidden = true
        }
    }
}

/** Shown while mock time is on, so a mocked schedule is never mistaken for the real one. */
@Composable
private fun MockBanner(now: () -> Long, offset: Long, onOff: () -> Unit) {
    val hw = LocalHw.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(hw.warnBg).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            LiveText({ "Mock time: ${clock(now())}" }, HwType.subheadStrong, hw.warn)
            LiveText({ "Not the real schedule. Real time is ${clock(now() - offset)}." }, HwType.footnote, hw.warn)
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
private fun RouteCard(s: RouteStatus, prefs: Prefs, now: () -> Long, actions: RouteActions, isPreview: Boolean) {
    val hw = LocalHw.current
    val r = s.route
    // The train shown big. Tapping a chip picks another; it falls back to the next one once it leaves.
    var picked by rememberSaveable(r.id) { mutableStateOf<Long?>(null) }
    val index = s.departures.indexOfFirst { pickKey(it) == picked }.let { if (it < 0) s.best else it }

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
                        // From the next trip itself: where you change, or its train and where that's heading.
                        val legs = if (shown == r) (s.next ?: s.departures.firstOrNull())?.legs else null
                        if (legs != null) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(legsBrush(legs, hw)))
                            Text(
                                if (legs.size > 1) changesLabel(legs) else "${legLineName(legs[0])} · toward ${Network.name(legs[0].trip.dest)}",
                                style = HwType.subhead,
                                color = hw.sub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            val inter = shown == r && interOnly(s)
                            val label = remember(shown, inter) { routeLabel(shown, inter) }
                            val line = remember(shown) { routeLine(shown) }
                            Box(
                                Modifier.size(8.dp).clip(CircleShape).then(
                                    if (inter) Modifier.background(Brush.verticalGradient(listOf(hw.green, hw.blue))) else Modifier.background(hw.line(line)),
                                ),
                            )
                            Text(label, style = HwType.subhead, color = hw.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Row(Modifier.height(36.dp).clip(CircleShape).background(hw.fill).edge(CircleShape)) {
                // Blue when the route also switches by itself at a set time.
                val auto = if (isPreview) null else prefs.flips[pairKey(r)]?.takeIf { f -> prefs.routes.none { it.id == reverseId(r.id) } }
                PillIcon(
                    Icons.Rounded.SwapVert,
                    if (auto != null) "Switch direction (switches by itself at ${minutesLabel(auto.at)})" else "Switch direction",
                    tint = if (auto != null) hw.tint else null,
                ) { if (isPreview) actions.swapPreview() else actions.swap(r.id) }
                if (isPreview) {
                    PillIcon(Icons.Rounded.Close, "Close quick look", onClick = actions.closePreview)
                } else {
                    PillIcon(Icons.Rounded.MoreHoriz, "Route options") { actions.menu(r.id) }
                }
            }
        }

        if (isPreview) WhenPill(s.plan, actions.whenOpen)

        val shown = s.departures.getOrNull(index)
        if (shown == null) {
            Text(
                when (s.plan?.mode) {
                    PlanMode.ARRIVE -> "No trains get there by then."
                    PlanMode.LEAVE -> "No trains after then."
                    null -> "No trains between these stations."
                },
                style = HwType.subhead,
                color = hw.sub,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            if (s.plan != null) PlanHero(s, shown, index) else Hero(s, shown, index, prefs, now, actions)
            val directPlatform = remember(r) { platformFor(r) }
            val saved = !isPreview && prefs.routes.any { it.id == r.id }
            val ownDelay = shown.timetabled?.let { prefs.trainDelays[r.id]?.get(timeKey(it)) } != null
            Boarding(
                shown.legs?.let { legPlatform(it.first()) } ?: directPlatform,
                prefs.coaches[r.id],
                delay = if (saved && shown.timetabled != null) shown.delay else null,
                own = ownDelay,
            ) { shown.timetabled?.let { actions.trainDelay(r.id, it) } }
            Chips(s, index, now) { picked = it }
            shown.legs?.takeIf { it.size > 1 }?.let { TripTimeline(it, Modifier.padding(horizontal = 20.dp)) }
        }

        val last = s.last
        if (s.late && last != null) {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Bedtime, null, tint = hw.sub, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                Text("The ${clock(last.dep)} is the last train tonight.", style = HwType.footnote, color = hw.sub)
            }
        }

        if (isPreview && prefs.routes.none { it.id == r.id }) {
            Box(Modifier.padding(horizontal = 20.dp)) { PrimaryButton("Save this route", Icons.Rounded.Add, actions.savePreview) }
        }
    }
}

@Composable
private fun PillIcon(icon: ImageVector, description: String, tint: Color? = null, onClick: () -> Unit) {
    val hw = LocalHw.current
    Box(Modifier.size(42.dp, 36.dp).clip(CircleShape).press(scaleTo = 0.9f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = description, tint = tint ?: hw.text, modifier = Modifier.size(20.dp))
    }
}

private fun isTomorrow(s: RouteStatus, i: Int) = s.closed || (s.tomorrowFrom in 0..i)

private fun lastIndex(s: RouteStatus): Int = if (s.closed) -1 else s.departures.indexOfFirst { it.dep == s.last?.dep }

@Composable
private fun Hero(s: RouteStatus, d: RouteDeparture, i: Int, prefs: Prefs, now: () -> Long, actions: RouteActions) {
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
    val note = if (d.hasChange) " · ${span(d.arr - d.dep)}" else trainNote(d)?.let { " · $it" }.orEmpty()
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
                HeroCountdown(d.dep, now)
            }
            Text(line, style = HwType.subhead, color = hw.sub)
            d.legs?.takeIf { it.size > 1 }?.let { LegsLine(it) }
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

/** "Leaving now ▾" / "Arrive by 9:30 AM tomorrow ▾" on a quick look: tap to plan another time. */
@Composable
private fun WhenPill(plan: PlanTime?, onClick: () -> Unit) {
    val hw = LocalHw.current
    val fg = if (plan != null) hw.tint else hw.text
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .height(34.dp)
            .clip(CircleShape)
            .background(if (plan != null) hw.tintSoft else hw.fill2)
            .press(scaleTo = 0.96f, onClick = onClick)
            .padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.Schedule, null, tint = fg, modifier = Modifier.size(18.dp))
        Text(whenText(plan), style = HwType.subheadStrong, color = fg)
        Icon(Icons.Rounded.UnfoldMore, null, tint = hw.sub, modifier = Modifier.size(16.dp))
    }
}

/** The hero for a planned quick look: the departure time, not a countdown. */
@Composable
private fun PlanHero(s: RouteStatus, d: RouteDeparture, i: Int) {
    val hw = LocalHw.current
    val plan = s.plan ?: return
    val label = when (plan.mode) {
        PlanMode.ARRIVE -> if (i == s.best) "Latest to arrive by ${clock(plan.at)}" else "Earlier train"
        PlanMode.LEAVE -> if (i == 0) "First after ${clock(plan.at)}" else "Later train"
    }
    val note = if (!d.hasChange) trainNote(d)?.let { " · $it" }.orEmpty() else ""
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = HwType.subheadStrong, color = hw.tint)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val lastDep = remember { mutableLongStateOf(d.dep) }
            val later = d.dep > lastDep.longValue
            SideEffect { lastDep.longValue = d.dep }
            RollingText(clockTime(d.dep), HwType.bigClock, hw.text, up = later)
            Text(clockAmPm(d.dep), style = HwType.bigUnit, color = hw.text, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text("Arrives ${clock(d.arr)} · ${span(d.arr - d.dep)}$note", style = HwType.subhead, color = hw.sub)
        d.legs?.takeIf { it.size > 1 }?.let { LegsLine(it) }
    }
}

/** The hero's countdown on its own, so only it recomposes as the clock ticks. */
@Composable
private fun HeroCountdown(dep: Long, now: () -> Long) {
    BigCountdown(dep - now())
}

/** Text that reads the clock; isolated so a tick redraws just this text, not its card. */
@Composable
private fun LiveText(text: () -> String, style: TextStyle, color: Color) {
    Text(text(), style = style, color = color)
}

/** "Platform 2 · Front coach · 2 min late": where to wait, and the rider's delay. Hidden when none is set. */
@Composable
private fun Boarding(platform: String?, coach: Coach?, delay: Int?, own: Boolean, onDelayTap: () -> Unit) {
    if (platform == null && coach == null && delay == null) return
    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (platform != null) BoardingTag(platform)
        if (coach != null) BoardingTag("${coach.label.replaceFirstChar { it.uppercase() }} coach")
        if (delay != null) DelayTag(delay, own, onDelayTap)
    }
}

@Composable
private fun BoardingTag(text: String) {
    val hw = LocalHw.current
    Text(
        text,
        style = HwType.footnote.copy(fontWeight = FontWeight.SemiBold),
        color = hw.text,
        maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(hw.fill).edge(CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * The shown train's delay: "3 min late" in amber, "2 min early" in blue, "On time" in grey, with
 * "· this train" when it has its own. Tap to set this train's delay.
 */
@Composable
private fun DelayTag(delay: Int, own: Boolean, onClick: () -> Unit) {
    val hw = LocalHw.current
    val (fg, bg) = when {
        delay > 0 -> hw.warn to hw.warnBg
        delay < 0 -> hw.info to hw.infoBg
        else -> hw.sub to hw.fill2
    }
    Row(
        Modifier.press(onClick = onClick).clip(CircleShape).background(bg).padding(start = 9.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${delayText(delay)}${if (own) ", this train" else ""}. Change" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            when {
                delay > 0 -> Icons.Rounded.MoreTime
                delay < 0 -> Icons.Rounded.History
                else -> Icons.Rounded.Schedule
            },
            null,
            tint = fg,
            modifier = Modifier.size(16.dp),
        )
        Text(delayText(delay), style = HwType.footnote.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
        if (own) Text("· this train", style = HwType.footnote, color = fg.copy(alpha = 0.75f), maxLines = 1)
    }
}

/** A trip that takes more than one train. */
private val RouteDeparture.hasChange: Boolean get() = (legs?.size ?: 1) > 1

/** A chip and the picked train key on the timetabled time, so a delay change doesn't move them. */
private fun pickKey(d: RouteDeparture): Long = d.timetabled ?: d.dep

/** Every upcoming departure as a chip; the picked one is ringed and shown in the hero. */
@Composable
private fun Chips(s: RouteStatus, selected: Int, now: () -> Long, onPick: (Long) -> Unit) {
    val hw = LocalHw.current
    val lastIdx = if (s.plan != null) -1 else lastIndex(s)
    val anyChange = s.departures.any { it.hasChange }
    val scroll = rememberScrollState()
    // A planned card may start on a chip off to the right (arrive by: the last one).
    LaunchedEffect(s.plan, s.departures.size) {
        if (s.plan == null || s.departures.size < 2) return@LaunchedEffect
        // maxValue is Int.MAX_VALUE until the row is laid out: wait for the real one.
        val max = snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
        if (max > 0) scroll.scrollTo((max.toLong() * selected / (s.departures.size - 1)).toInt())
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        s.departures.forEachIndexed { i, d ->
            val on = i == selected
            val tomorrow = s.plan == null && isTomorrow(s, i)
            val shape = RoundedCornerShape(20.dp)
            val fixed = when {
                s.plan != null -> "to ${clockTime(d.arr)}"
                s.closed && i == 0 -> "first"
                tomorrow -> "tomorrow"
                else -> null
            }
            Column(
                Modifier
                    .widthIn(min = 74.dp)
                    .clip(shape)
                    .background(if (on) hw.tintSoft else hw.fill)
                    .then(if (on) Modifier.border(1.5.dp, hw.tint, shape) else Modifier.edge(shape))
                    .press(scaleTo = 0.94f) { onPick(pickKey(d)) }
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
                val subStyle = HwType.footnote.copy(fontWeight = if (i == lastIdx) FontWeight.SemiBold else FontWeight.Normal)
                val subColor = if (i == lastIdx) hw.warn else hw.sub
                if (fixed != null) Text(fixed, style = subStyle, color = subColor) else LiveText({ chipSpan(d.dep - now()) }, subStyle, subColor)
                // Bars only on cards that mix trips: then a direct train shows as one colour.
                if (anyChange) d.legs?.let { LegBar(it) }
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
