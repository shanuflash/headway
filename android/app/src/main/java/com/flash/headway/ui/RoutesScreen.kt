package com.flash.headway.ui

import android.net.Uri
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flash.headway.app.Coach
import com.flash.headway.app.HeadwayViewModel
import androidx.compose.material.icons.rounded.Navigation
import com.flash.headway.app.nextLeg
import com.flash.headway.app.TopUp
import com.flash.headway.app.Prefs
import com.flash.headway.app.RouteStatus
import com.flash.headway.app.SavedRoute
import com.flash.headway.app.pairKey
import com.flash.headway.app.reverseId
import com.flash.headway.app.PlanMode
import com.flash.headway.app.PlanTime
import com.flash.headway.data.Line
import com.flash.headway.data.Network
import com.flash.headway.data.Platforms
import com.flash.headway.data.RouteId
import com.flash.headway.engine.DestinationGroup
import com.flash.headway.engine.Leg
import com.flash.headway.engine.changeMinutes
import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.RouteDeparture
import com.flash.headway.engine.routeGroup
import com.flash.headway.engine.Feed
import com.flash.headway.engine.activeNotices
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import com.flash.headway.data.DayType

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
    /** Opens one train's own delay: the route id, the train's timetabled departure, and its legs. */
    val trainDelay: (String, Long, DayType, List<Leg>?) -> Unit = { _, _, _, _ -> },
    /** Flips the quick look (not a saved card with the same stations). */
    val swapPreview: () -> Unit = {},
    /** Stops following a trip with changes. */
    val endTrip: () -> Unit = {},
    /** Opens the trips already under way on a route, to follow the one the rider's on. */
    val onTheWay: (String) -> Unit = {},
    /** The followed trip's next train was missed: take the backup (route id, backup). */
    val missed: (String, RouteDeparture) -> Unit = { _, _ -> },
    /** Opens other trains from a station on a followed trip: route id, station, after. */
    val otherTrains: (String, String, Long) -> Unit = { _, _, _ -> },
    /** The update banner's button, and hiding it. */
    val update: () -> Unit = {},
    val dismissUpdate: () -> Unit = {},
)

fun routeLabel(r: SavedRoute, inter: Boolean = false): String {
    if (inter) {
        // The inter-corridor runs Chennai Central <-> Airport; name the end this route heads to.
        val stops = Network.routes.getValue(RouteId.INTER).fwd.map { it.first }
        val end = if (stops.indexOf(r.from) < stops.indexOf(r.to)) stops.last() else stops.first()
        return "Inter-corridor · towards ${Network.name(end)}"
    }
    val g = routeGroup(r.from, r.to) ?: return ""
    return "${g.line.label} · towards ${g.terminals.joinToString(" or ") { Network.name(it) }}"
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
    update: HeadwayViewModel.UpdateState? = null,
) {
    item(key = "top", contentType = "top") {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TopBar(right = {
                GlassGroup(
                    listOf(
                        Triple(Icons.Rounded.CreditCard, "Metro card", actions.topUp),
                        Triple(Icons.Rounded.Add, "Add route", actions.add),
                        Triple(Icons.Rounded.Settings, "Settings", actions.settings),
                    ),
                )
            })
            LargeTitle("Routes")
            // In this column a hidden banner takes no space (a lazy item would leave a gap).
            if (prefs.mockAt != null) MockBanner(now, prefs.mockOffset, actions.mockOff)
            UpdateBanner(update, actions.update, actions.dismissUpdate)
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

/** A newer Headway from the project's releases: download and install it in place. */
@Composable
private fun UpdateBanner(state: HeadwayViewModel.UpdateState?, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    state ?: return
    val hw = LocalHw.current
    val (title, sub, button) = when (state) {
        is HeadwayViewModel.UpdateState.Available -> Triple("Update available", "Headway ${state.release.name}", "Update")
        is HeadwayViewModel.UpdateState.Downloading -> Triple("Downloading update", "${state.percent}%", null)
        is HeadwayViewModel.UpdateState.Ready -> Triple("Update ready", "Headway ${state.release.name}", "Install")
        is HeadwayViewModel.UpdateState.Failed -> Triple("Download failed", "Headway ${state.release.name}", "Retry")
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(hw.tintSoft).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, style = HwType.subheadStrong, color = hw.text)
            Text(sub, style = HwType.footnote, color = hw.sub)
        }
        if (button != null) {
            Box(
                Modifier.height(36.dp).clip(CircleShape).background(hw.tint).press(scaleTo = 0.95f, onClick = onUpdate).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(button, style = HwType.subheadStrong, color = Color.White)
            }
        }
        if (state !is HeadwayViewModel.UpdateState.Downloading) PillIcon(Icons.Rounded.Close, "Dismiss", onClick = onDismiss)
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
            Text("Top-up pending", style = HwType.subheadStrong, color = hw.text)
            Text("Tap the card in Airtel to add the balance.", style = HwType.footnote, color = hw.sub)
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
            LiveText({ "Mock time · ${clock(now())}" }, HwType.subheadStrong, hw.warn)
            LiveText({ "Real time: ${clock(now() - offset)}" }, HwType.footnote, hw.warn)
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
        Text("No saved routes", style = HwType.title, color = hw.text, modifier = Modifier.padding(top = 4.dp))
        Text(
            "Save a route to see its next trains. Tap ⇅ to reverse it for the trip back.",
            style = HwType.subhead,
            color = hw.sub,
        )
        PrimaryButton("Add route", Icons.Rounded.Add, actions.add)
    }
    SectionTitle("Or search a station")
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
                    Text(
                        "${Network.name(shown.from)} → ${Network.name(shown.to)}",
                        style = HwType.cardTitle.copy(fontSize = 18.sp, lineHeight = 23.sp),
                        color = hw.text,
                        // Two lines rather than cut the destination off.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        // From the next trip itself: where you change, or its train and where that's heading.
                        val legs = if (shown == r) (s.next ?: s.departures.firstOrNull())?.legs else null
                        if (legs != null) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(legsBrush(legs, hw)))
                            Text(
                                // Which trains, and where to change: said once, here.
                                if (legs.size > 1) {
                                    legs.joinToString(" → ") { legShort(it) } + " · change at " + legs.drop(1).joinToString(", ") { Network.name(it.from) }
                                } else {
                                    "${legLineName(legs[0])} · towards ${Network.name(legs[0].trip.dest)}"
                                },
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
                    if (auto != null) "Reverse (automatic at ${minutesLabel(auto.at)})" else "Reverse",
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
        val trip = s.trip?.takeIf { !isPreview }
        if (trip != null) {
            // On a trip with changes: count down to whatever's next, train by train.
            TripHero(
                trip, prefs, r.id, now, actions.endTrip,
                backup = s.tripBackup,
                onMissed = { b -> actions.missed(r.id, b) },
                onOther = { station, after -> actions.otherTrains(r.id, station, after) },
            )
            trip.legs?.let { legs ->
                TripTimeline(legs, Modifier.padding(horizontal = 20.dp), now = now, walk = prefs.walk) { i, leg -> prefs.coachFor(r.id, if (i == 0) null else leg.from) }
            }
        } else if (shown == null) {
            Text(
                when (s.plan?.mode) {
                    PlanMode.ARRIVE -> "No trains arrive by then."
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
            val ownDelay = shown.timetabled?.let { prefs.delayRule(r.id).own(it, shown.trip.dayType) } != null
            Boarding(shown.legs?.let { legPlatform(it.first()) } ?: directPlatform, prefs.coaches[r.id])
            Chips(s, index, now) { picked = it }
            // Everything else one tap away: the trip step by step, this train's delay, and
            // following a trip that's already under way.
            val multi = (shown.legs?.size ?: 1) > 1
            if (multi || saved) {
                var details by rememberSaveable(r.id) { mutableStateOf(false) }
                DetailsToggle(details, if (multi) "Trip details" else "Details") { details = !details }
                AnimatedVisibility(details, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (multi) {
                            shown.legs?.let { legs ->
                                TripTimeline(legs, Modifier.padding(horizontal = 20.dp), walk = prefs.walk) { i, leg -> prefs.coachFor(r.id, if (i == 0) null else leg.from) }
                            }
                        }
                        if (saved && shown.timetabled != null) {
                            DelayRow(shown.delay, ownDelay) { shown.timetabled?.let { actions.trainDelay(r.id, it, shown.trip.dayType, shown.legs) } }
                        }
                        if (multi && saved && s.plan == null) {
                            DetailsLink("Already on board?") { actions.onTheWay(r.id) }
                        }
                    }
                }
            }
        }

        val last = s.last
        if (s.late && last != null) {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Bedtime, null, tint = hw.sub, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                Text("Last train tonight: ${clock(last.dep)}", style = HwType.footnote, color = hw.sub)
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
    // Tint is kept for the selected train and actions; the label is plain unless it warns.
    val labelColor = when {
        warn -> hw.warn
        else -> hw.sub
    }
    val note = if (d.hasChange) " · ${span(d.arr - d.dep)}" else trainNote(d)?.let { " · $it" }.orEmpty()
    val times = when {
        s.closed && i == 0 -> "First train · arrives ${clock(d.arr)}"
        tomorrow -> "Arrives ${clock(d.arr)}$note"
        else -> "${clockTime(d.dep)} → ${clock(d.arr)}$note"
    }
    // The delay sits in the times, in its tone, instead of a chip of its own.
    val saved = !s.isPreview && prefs.routes.any { it.id == s.route.id }
    val line = buildAnnotatedString {
        append(times)
        if (saved && d.timetabled != null && d.delay != 0) {
            append(" · ")
            withStyle(SpanStyle(color = if (d.delay > 0) hw.warn else hw.info)) { append(delayText(d.delay).lowercase()) }
        }
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
            // (Which trains is in the card's header, so no second line of them here.)
            // Leaves sooner than a later train that gets there just as early: say which.
            s.departures.drop(i + 1).firstOrNull { it.arr == d.arr }?.let { later ->
                Text("Same arrival as ${clock(later.dep)}", style = HwType.footnote, color = hw.sub)
            }
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
                contentDescription = when {
                    reminderOn -> "Stop countdown"
                    d.hasChange -> "Start trip"
                    else -> "Start countdown"
                },
                tint = if (reminderOn) Color.White else hw.text,
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
        PlanMode.ARRIVE -> if (i == s.best) "Latest arriving by ${clock(plan.at)}" else "Earlier train"
        PlanMode.LEAVE -> if (i == 0) "First after ${clock(plan.at)}" else "Later train"
    }
    val note = if (!d.hasChange) trainNote(d)?.let { " · $it" }.orEmpty() else ""
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = HwType.subheadStrong, color = hw.sub)
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

/**
 * A followed trip with changes: what's next and a countdown to it. Before the first train, the
 * train to board; on the way, the train at the next change (where to get off, the walk, its
 * platform and coach); after the last one, arrival.
 */
@Composable
private fun TripHero(
    trip: RouteDeparture,
    prefs: Prefs,
    routeId: String,
    now: () -> Long,
    onEnd: () -> Unit,
    backup: RouteDeparture?,
    onMissed: (RouteDeparture) -> Unit,
    onOther: (station: String, after: Long) -> Unit,
) {
    val hw = LocalHw.current
    val legs = trip.legs ?: return
    val t = now()
    val i = trip.nextLeg(t)
    val leg = legs.getOrNull(i)
    fun coach(l: Leg, first: Boolean) = prefs.coachFor(routeId, if (first) null else l.from)?.let { "${it.label.replaceFirstChar { c -> c.uppercase() }} coach" }
    val label: String
    val target: Long
    val lines = ArrayList<String>()
    when {
        leg == null -> {
            label = "Arrive at ${Network.name(legs.last().to)}"
            target = trip.arr
            lines += "Arrives ${clock(trip.arr)}"
        }
        i == 0 -> {
            label = "Board at ${Network.name(leg.from)}"
            target = leg.dep
            lines += "${legLineName(leg)} towards ${Network.name(leg.trip.dest)} · ${clock(leg.dep)}"
            listOfNotNull(legPlatform(leg), coach(leg, true)).takeIf { it.isNotEmpty() }?.let { lines += it.joinToString(" · ") }
        }
        else -> {
            val prev = legs[i - 1]
            val walk = changeMinutes(leg.from, prefs.walk)
            label = "Change at ${Network.name(leg.from)}"
            target = leg.dep
            lines += "${legLineName(leg)} towards ${Network.name(leg.trip.dest)} · ${clock(leg.dep)}"
            val getOff = if (t < prev.arr) "Get off at ${clock(prev.arr)}" else null
            lines += listOfNotNull(getOff, "$walk min walk", legPlatform(leg), coach(leg, false)).joinToString(" · ")
        }
    }
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = HwType.subheadStrong, color = hw.tint)
            BigCountdown(target - t)
            lines.forEach { Text(it, style = HwType.subhead, color = hw.sub) }
            // In case the train to catch is missed: the next way on, and a choice of others.
            if (leg != null) {
                if (backup != null) {
                    Text("If missed: ${clock(backup.dep)} · arrives ${clock(backup.arr)}", style = HwType.footnote, color = hw.sub)
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (backup != null) TripPill("Missed it") { onMissed(backup) }
                    // Only trains that can be reached: still on the way, after arriving and walking over.
                    val ready = if (i > 0) maxOf(t, legs[i - 1].arr + changeMinutes(leg.from, prefs.walk) * MINUTE) else t
                    TripPill("Other trains") { onOther(leg.from, ready) }
                }
            }
        }
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(hw.fill).edge(CircleShape).press(scaleTo = 0.9f, onClick = onEnd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, contentDescription = "End trip", tint = hw.text, modifier = Modifier.size(24.dp))
        }
    }
}

/** A small action under the trip hero. */
@Composable
private fun TripPill(text: String, onClick: () -> Unit) {
    val hw = LocalHw.current
    Text(
        text,
        style = HwType.footnote.copy(fontWeight = FontWeight.SemiBold),
        color = hw.tint,
        maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(hw.tintSoft).press(scaleTo = 0.95f, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
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

/** "Platform 2 · Front coach": where to wait. Hidden when neither is known. */
@Composable
private fun Boarding(platform: String?, coach: Coach?) {
    if (platform == null && coach == null) return
    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (platform != null) BoardingTag(platform)
        if (coach != null) BoardingTag("${coach.label.replaceFirstChar { it.uppercase() }} coach")
    }
}

/** "Trip details ▾": shows or hides a card's details. */
@Composable
private fun DetailsToggle(open: Boolean, label: String, onClick: () -> Unit) {
    val hw = LocalHw.current
    Row(
        Modifier.padding(horizontal = 12.dp).clip(CircleShape).press(scaleTo = 0.97f, onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(if (open) "Hide details" else label, style = HwType.subheadStrong, color = hw.sub)
        Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = hw.sub, modifier = Modifier.size(20.dp))
    }
}

/** A text action inside a card's details. */
@Composable
private fun DetailsLink(text: String, onClick: () -> Unit) {
    val hw = LocalHw.current
    Text(
        text,
        style = HwType.subheadStrong,
        color = hw.tint,
        modifier = Modifier.padding(horizontal = 12.dp).clip(CircleShape).press(scaleTo = 0.97f, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** "Delay · 2 min late ›": this train's delay, tap to change it. */
@Composable
private fun DelayRow(delay: Int, own: Boolean, onClick: () -> Unit) {
    val hw = LocalHw.current
    val tone = when {
        delay > 0 -> hw.warn
        delay < 0 -> hw.info
        else -> hw.sub
    }
    Row(
        Modifier.fillMaxWidth().press(scaleTo = 0.98f, onClick = onClick).padding(horizontal = 20.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Delay, ${delayText(delay)}${if (own) ", this train" else ""}. Edit" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Delay", style = HwType.subhead, color = hw.text, modifier = Modifier.weight(1f))
        Text(delayText(delay) + if (own) " · this train" else "", style = HwType.subhead, color = tone)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = hw.chev, modifier = Modifier.size(20.dp))
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

