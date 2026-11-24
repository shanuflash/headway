@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.flash.headway.ui

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Commute
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.foundation.layout.RowScope

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
            LargeTitle("Routes")
            SearchBarButton("Search stations", actions.look)
            // In this column a hidden banner takes no space (a lazy item would leave a gap).
            if (prefs.mockAt != null) MockBanner(now, prefs.mockOffset, actions.mockOff)
            UpdateBanner(update, actions.update, actions.dismissUpdate)
            LoadCardBanner(now)
            Notices(feed, now)
        }
    }

    if (preview != null) item(key = "preview", contentType = "card") { Box(travelModifier()) { RouteCard(preview, prefs, now, actions, isPreview = true) } }
    if (statuses == null) return
    if (statuses.isEmpty() && preview == null) {
        item(key = "empty", contentType = "empty") { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { EmptyRoutes(actions) } }
        return
    }
    items(statuses, key = { it.route.id }, contentType = { "card" }) { s -> Box(travelModifier()) { RouteCard(s, prefs, now, actions, isPreview = false) } }
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
    Banner(Icons.Rounded.SystemUpdate, title, sub, hw.infoBg, hw.info) {
        if (button != null) Button(onClick = onUpdate, shapes = ButtonDefaults.shapes()) { Text(button) }
        if (state !is HeadwayViewModel.UpdateState.Downloading) {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes(), colors = ButtonDefaults.textButtonColors(contentColor = hw.info)) { Text("Dismiss") }
        }
    }
}

/** A filled banner card in a container role, with actions underneath. */
@Composable
fun Banner(
    icon: ImageVector,
    title: String,
    body: String,
    container: Color,
    content: Color,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(com.flash.headway.ui.theme.HwShape.largeIncreased).background(container).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = HwType.headline.copy(fontWeight = FontWeight.Bold), color = content)
            Text(body, style = HwType.subhead, color = content.copy(alpha = 0.9f))
            if (actions != null) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}

/** After a UPI top-up: the money is paid in, but only the Airtel screen writes it to the card. */
@Composable
fun LoadCardBanner(now: () -> Long) {
    val context = LocalContext.current
    // Re-read every few seconds (now ticks each second), so it appears on return from the UPI app.
    val pending = remember(now() / 5_000) { TopUp.loadPending(context) }
    var hidden by remember { mutableStateOf(false) }
    if (!pending || hidden) return
    val hw = LocalHw.current
    Banner(Icons.Rounded.CreditCard, "Top-up pending", "Tap the card in Airtel to add the balance.", hw.tertiaryBg, hw.onTertiaryBg) {
        Button(
            onClick = {
                TopUp.clearLoadPending(context)
                hidden = true
                TopUp.openAirtel(context)
            },
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary),
        ) { Text("Open Airtel") }
        TextButton(
            onClick = {
                TopUp.clearLoadPending(context)
                hidden = true
            },
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.textButtonColors(contentColor = hw.onTertiaryBg),
        ) { Text("Dismiss") }
    }
}

/** Shown while mock time is on, so a mocked schedule is never mistaken for the real one. */
@Composable
private fun MockBanner(now: () -> Long, offset: Long, onOff: () -> Unit) {
    val hw = LocalHw.current
    Row(
        Modifier.fillMaxWidth().clip(com.flash.headway.ui.theme.HwShape.largeIncreased).background(hw.warnBg).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Rounded.Schedule, null, tint = hw.onWarnBg, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LiveText({ "Mock time · ${clock(now())}" }, HwType.headline.copy(fontWeight = FontWeight.Bold), hw.onWarnBg)
            LiveText({ "Real time: ${clock(now() - offset)}" }, HwType.subhead, hw.onWarnBg)
            Button(
                onClick = onOff,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = hw.warn, contentColor = hw.onWarn),
                modifier = Modifier.padding(top = 10.dp),
            ) { Text("Turn off") }
        }
    }
}

/** The empty state: the app's one big "add" moment, with a slowly turning cookie shape. */
@Composable
private fun EmptyRoutes(actions: RouteActions) {
    val scheme = MaterialTheme.colorScheme
    val spin by androidx.compose.animation.core.rememberInfiniteTransition(label = "cookie").animateFloat(
        0f,
        360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(24_000, easing = androidx.compose.animation.core.LinearEasing)),
        label = "spin",
    )
    Column(
        Modifier.fillMaxWidth().clip(com.flash.headway.ui.theme.HwShape.extraExtraLarge).background(scheme.primaryContainer)
            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(bottom = 8.dp).size(96.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(96.dp).rotate(spin).clip(MaterialShapes.Cookie9Sided.toShape()).background(scheme.primary))
            Icon(Icons.Rounded.Commute, null, tint = scheme.onPrimary, modifier = Modifier.size(44.dp))
        }
        Text("No saved routes", style = HwType.title, color = scheme.onPrimaryContainer)
        Text("Save a route to see its next trains. Tap ⇅ to reverse it for the trip back.", style = HwType.body, color = scheme.onPrimaryContainer)
        Button(
            onClick = actions.add,
            modifier = Modifier.padding(top = 8.dp).height(56.dp),
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.MediumContentPadding,
        ) {
            Icon(Icons.Rounded.Add, null)
            Text("Add route", style = HwType.headline, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** A full-width filled button (56dp, round, morphs squarer while pressed). */
@Composable
fun PrimaryButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp), shapes = ButtonDefaults.shapes()) {
        Icon(icon, null)
        Text(text, style = HwType.headline, modifier = Modifier.padding(start = 8.dp))
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
        Modifier.fillMaxWidth().clip(RouteCardShape).background(hw.card).padding(top = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The departure shown big; the bell in the header counts down to it (or starts its trip).
        val shownDep = s.departures.getOrNull(index)
        val following = s.trip != null && !isPreview
        Row(Modifier.padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val titleSpatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
            AnimatedContent(
                targetState = r,
                transitionSpec = {
                    val spatial = titleSpatial
                    (androidx.compose.animation.slideInVertically(spatial) { it } + fadeIn()) togetherWith
                        (androidx.compose.animation.slideOutVertically(spatial) { -it } + fadeOut())
                },
                modifier = Modifier.weight(1f),
                label = "routeTitle",
            ) { shown ->
                // A small label: the countdown is what leads the card.
                Text(
                    "${Network.name(shown.from)} → ${Network.name(shown.to)}",
                    style = HwType.headline.copy(fontWeight = FontWeight.Bold),
                    color = hw.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Standard button group: countdown (a toggle that morphs squarer when on), reverse, options.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!isPreview && !following && shownDep != null && s.plan == null) {
                    val on = prefs.reminder?.let { it.routeId == r.id && it.dep == shownDep.dep } == true
                    FilledIconToggleButton(
                        checked = on,
                        onCheckedChange = { actions.remind(s, shownDep.dep) },
                        shapes = IconButtonDefaults.toggleableShapes(),
                        colors = IconButtonDefaults.filledIconToggleButtonColors(
                            containerColor = hw.selected,
                            contentColor = hw.onSelected,
                            checkedContainerColor = hw.tint,
                            checkedContentColor = hw.onTint,
                        ),
                    ) {
                        // Ring: a quick swing either way, dying out, pivoting at the top of the bell.
                        val ring = remember { androidx.compose.animation.core.Animatable(0f) }
                        val first = remember { booleanArrayOf(true) }
                        LaunchedEffect(on) {
                            if (first[0]) { first[0] = false; return@LaunchedEffect }
                            if (on) {
                                ring.animateTo(
                                    0f,
                                    androidx.compose.animation.core.keyframes {
                                        durationMillis = 700
                                        24f at 70
                                        -20f at 160
                                        14f at 250
                                        -9f at 340
                                        5f at 430
                                        -2f at 520
                                    },
                                )
                            }
                        }
                        Icon(
                            if (on) Icons.Rounded.NotificationsActive else Icons.Rounded.Notifications,
                            when {
                                on -> "Stop countdown"
                                shownDep.hasChange -> "Start trip"
                                else -> "Start countdown"
                            },
                            Modifier.graphicsLayer {
                                rotationZ = ring.value
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.1f)
                            },
                        )
                    }
                }
                // Filled when the route also switches by itself at a set time.
                val auto = if (isPreview) null else prefs.flips[pairKey(r)]?.takeIf { f -> prefs.routes.none { it.id == reverseId(r.id) } }
                FilledTonalIconButton(
                    onClick = { if (isPreview) actions.swapPreview() else actions.swap(r.id) },
                    shapes = IconButtonDefaults.shapes(),
                    colors = if (auto != null) {
                        IconButtonDefaults.filledTonalIconButtonColors(containerColor = hw.tint, contentColor = hw.onTint)
                    } else {
                        IconButtonDefaults.filledTonalIconButtonColors(containerColor = hw.fill, contentColor = hw.sub)
                    },
                ) {
                    Icon(Icons.Rounded.SwapVert, if (auto != null) "Reverse (automatic at ${minutesLabel(auto.at)})" else "Reverse")
                }
                if (isPreview) {
                    PillIcon(Icons.Rounded.Close, "Close quick look", onClick = actions.closePreview)
                } else {
                    PillIcon(Icons.Rounded.MoreVert, "Route options") { actions.menu(r.id) }
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
            var details by rememberSaveable(r.id) { mutableStateOf(false) }
            if (s.plan != null) PlanHero(s, shown, index) else Hero(s, shown, index, prefs, now, actions) { details = !details }
            val directPlatform = remember(r) { platformFor(r) }
            val saved = !isPreview && prefs.routes.any { it.id == r.id }
            val ownDelay = shown.timetabled?.let { prefs.delayRule(r.id).own(it, shown.trip.dayType) } != null
            LineBadges(
                shown,
                platform = shown.legs?.let { legPlatform(it.first()) } ?: directPlatform,
                coach = prefs.coaches[r.id],
                showDelay = saved,
                open = details,
            ) { details = !details }
            DepartureSwitcher(s, index) { picked = it }
            // Everything else one tap away: the trip step by step, this train's delay, and
            // following a trip that's already under way.
            val multi = (shown.legs?.size ?: 1) > 1
            if (multi || saved) {
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
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bedtime, null, tint = hw.warn, modifier = Modifier.size(20.dp))
                Text("Last train tonight: ${clock(last.dep)}", style = HwType.subhead, color = hw.sub)
            }
        }

        if (isPreview && prefs.routes.none { it.id == r.id }) {
            Box(Modifier.padding(horizontal = 20.dp)) { PrimaryButton("Save this route", Icons.Rounded.Add, actions.savePreview) }
        }
    }
}

/** A standard icon button (40dp in a 48dp target) that morphs squarer while pressed. */
@Composable
private fun PillIcon(icon: ImageVector, description: String, tint: Color? = null, onClick: () -> Unit) {
    val hw = LocalHw.current
    IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
        Icon(icon, contentDescription = description, tint = tint ?: hw.sub)
    }
}

private fun isTomorrow(s: RouteStatus, i: Int) = s.closed || (s.tomorrowFrom in 0..i)

private fun lastIndex(s: RouteStatus): Int = if (s.closed) -1 else s.departures.indexOfFirst { it.dep == s.last?.dep }

@Composable
private fun Hero(s: RouteStatus, d: RouteDeparture, i: Int, prefs: Prefs, now: () -> Long, actions: RouteActions, onToggle: () -> Unit) {
    val hw = LocalHw.current
    val tomorrow = isTomorrow(s, i)
    val lastIdx = lastIndex(s)
    // A label only when it says something the countdown doesn't: last trains, tomorrow, closed.
    val (label, warn) = when {
        s.closed && i == 0 -> (if (s.running) "No more trains tonight" else "Metro is closed") to true
        s.closed -> "Tomorrow" to false
        tomorrow -> (if (i == s.tomorrowFrom) "First train tomorrow" else "Tomorrow") to false
        i == lastIdx -> "Last train" to true
        s.late && lastIdx > i -> "Last ${lastIdx - i + 1} trains" to true
        else -> null to false
    }
    val counting = prefs.reminder?.let { it.routeId == s.route.id && it.dep == d.dep } == true
    val scheme = MaterialTheme.colorScheme
    val panel by androidx.compose.animation.animateColorAsState(
        when {
            counting -> scheme.primaryContainer
            warn -> hw.warnBg
            else -> hw.hero
        },
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "panel",
    )
    Column(
        Modifier.padding(horizontal = 8.dp).fillMaxWidth().clip(com.flash.headway.ui.theme.HwShape.extraLarge).background(panel)
            .press(onClick = onToggle).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (label != null) Text(label, style = HwType.subheadStrong, color = if (warn) hw.onWarnBg else hw.sub)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f)) {
                if (tomorrow) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val lastDep = remember { mutableLongStateOf(d.dep) }
                        val later = d.dep > lastDep.longValue
                        SideEffect { lastDep.longValue = d.dep }
                        RollingText(clockTime(d.dep), HwType.bigClock, hw.text, up = later)
                        Text(clockAmPm(d.dep), style = HwType.bigUnit, color = hw.sub, modifier = Modifier.padding(bottom = 8.dp))
                    }
                } else {
                    HeroCountdown(d.dep, now)
                }
            }
            // What you'd glance for on the platform: when you get there.
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 8.dp)) {
                Text(clock(d.arr), style = HwType.cardTitle.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold), color = if (warn) hw.onWarnBg else hw.text)
                Text("arrive · ${span(d.arr - d.dep)}", style = HwType.footnote, color = if (warn) hw.onWarnBg else hw.sub)
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            counting,
            enter = androidx.compose.animation.expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = androidx.compose.animation.shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.NotificationsActive, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                Text("Countdown on · leaves ${clock(d.dep)}", style = HwType.subheadStrong, color = scheme.onPrimaryContainer)
            }
        }
        // Leaves sooner than a later train that gets there just as early: say which.
        s.departures.drop(i + 1).firstOrNull { it.arr == d.arr }?.let { later ->
            Text("Same arrival as ${clock(later.dep)}", style = HwType.footnote, color = hw.sub)
        }
    }
}

/**
 * Which trains, as coloured line badges ("Inter-corridor › Yellow"), then when and where to board:
 * "6:03 PM · Platform 1 or 2 · Front coach · 5 min early". Tapping it opens the trip's details.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LineBadges(d: RouteDeparture, platform: String?, coach: Coach?, showDelay: Boolean, open: Boolean, onToggle: () -> Unit) {
    val hw = LocalHw.current
    val legs = d.legs.orEmpty()
    Column(
        Modifier.padding(horizontal = 20.dp).clip(RoundedCornerShape(12.dp)).press(scaleTo = 0.99f, onClick = onToggle),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            legs.forEachIndexed { k, leg ->
                if (k > 0) Text("›", style = HwType.subhead, color = hw.sub)
                val colors = legColors(leg, hw)
                val inter = colors.size > 1
                Text(
                    legShort(leg),
                    style = HwType.footnote.copy(fontWeight = FontWeight.Bold),
                    color = if (inter || leg.trip.line != Line.YELLOW) Color.White else Color.Black,
                    maxLines = 1,
                    modifier = Modifier.clip(com.flash.headway.ui.theme.HwShape.small)
                        .background(if (inter) Brush.horizontalGradient(colors) else androidx.compose.ui.graphics.SolidColor(colors[0]))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            if (legs.size == 1) Text("towards ${Network.name(legs[0].trip.dest)}", style = HwType.footnote, color = hw.sub, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    append(clock(d.dep))
                    platform?.let { append(" · $it") }
                    coach?.let { append(" · ${it.label.replaceFirstChar { c -> c.uppercase() }} coach") }
                    if (showDelay && d.timetabled != null && d.delay != 0) {
                        append(" · ")
                        withStyle(SpanStyle(color = if (d.delay > 0) hw.warn else hw.info)) { append(delayText(d.delay).lowercase()) }
                    }
                },
                style = HwType.footnote,
                color = hw.sub,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                if (open) "Hide trip details" else "Show trip details",
                tint = hw.sub,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The next departures as a track with a sliding thumb: picking a time moves the thumb there on the
 * spatial spring, its leading edge on the fast spring so it stretches on the way.
 */
@Composable
private fun DepartureSwitcher(s: RouteStatus, selected: Int, onPick: (Long) -> Unit) {
    val hw = LocalHw.current
    val motion = MaterialTheme.motionScheme
    val lastIdx = if (s.plan != null) -1 else lastIndex(s)
    val shown = s.departures.take(5)
    if (shown.size < 2) return
    val sel = selected.coerceIn(0, shown.lastIndex)
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(48.dp).clip(CircleShape).background(hw.fill).padding(4.dp),
    ) {
        val slot = maxWidth / shown.size
        val last = remember { intArrayOf(sel) }
        val right = remember(sel) { (sel >= last[0]).also { last[0] = sel } }
        val lead = motion.fastSpatialSpec<androidx.compose.ui.unit.Dp>()
        val trail = motion.defaultSpatialSpec<androidx.compose.ui.unit.Dp>()
        val start by animateDpAsState(slot * sel, if (right) trail else lead, label = "thumbStart")
        val end by animateDpAsState(slot * (sel + 1), if (right) lead else trail, label = "thumbEnd")
        Box(
            Modifier
                .offset(x = start)
                .width((end - start).coerceAtLeast(slot * 0.6f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(hw.secondary),
        )
        Row(Modifier.fillMaxSize()) {
            shown.forEachIndexed { k, d ->
                val on = k == sel
                val tomorrow = s.plan == null && isTomorrow(s, k)
                val fg by androidx.compose.animation.animateColorAsState(
                    when {
                        on -> hw.onSecondary
                        k == lastIdx -> hw.warn
                        tomorrow -> hw.sub.copy(alpha = 0.7f)
                        else -> hw.sub
                    },
                    motion.defaultEffectsSpec(),
                    label = "segFg",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .press { onPick(pickKey(d)) }
                        .semantics { contentDescription = "${clock(d.dep)}${if (on) ", selected" else ""}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        clockTime(d.dep),
                        style = HwType.subhead.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, fontFeatureSettings = "tnum"),
                        color = fg,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** "Leaving now ▾" / "Arrive by 9:30 AM tomorrow ▾" on a quick look: tap to plan another time. */
@Composable
private fun WhenPill(plan: PlanTime?, onClick: () -> Unit) {
    val hw = LocalHw.current
    val fg = if (plan != null) hw.onSelected else hw.text
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .height(32.dp)
            .clip(com.flash.headway.ui.theme.HwShape.small)
            .then(if (plan != null) Modifier.background(hw.selected) else Modifier.border(1.dp, hw.sep, com.flash.headway.ui.theme.HwShape.small))
            .press(onClick = onClick)
            .padding(start = 8.dp, end = 8.dp),
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
        FilledTonalIconButton(
            onClick = onEnd,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = hw.selected, contentColor = hw.onSelected),
        ) {
            Icon(Icons.Rounded.Close, contentDescription = "End trip")
        }
    }
}

/** A small action under the trip hero: a tonal button. */
@Composable
private fun TripPill(text: String, onClick: () -> Unit) {
    androidx.compose.material3.FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.SmallContentPadding) {
        Text(text, style = HwType.subheadStrong)
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
        modifier = Modifier.clip(com.flash.headway.ui.theme.HwShape.small).border(1.dp, hw.sep, com.flash.headway.ui.theme.HwShape.small).padding(horizontal = 12.dp, vertical = 6.dp),
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
            val corner by animateDpAsState(if (on) 28.dp else 16.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "chipShape")
            val shape = RoundedCornerShape(corner)
            val fixed = when {
                s.plan != null -> "to ${clockTime(d.arr)}"
                s.closed && i == 0 -> "first"
                tomorrow -> "tomorrow"
                else -> null
            }
            Column(
                Modifier
                    .widthIn(min = 84.dp)
                    .clip(shape)
                    .background(if (on) hw.tint else hw.fill)
                    .press { onPick(pickKey(d)) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    clock(d.dep),
                    style = HwType.headline.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Bold),
                    color = when {
                        on -> hw.onTint
                        tomorrow && !s.closed -> hw.sub
                        else -> hw.text
                    },
                )
                val subStyle = HwType.footnote.copy(fontWeight = if (i == lastIdx) FontWeight.SemiBold else FontWeight.Normal)
                val subColor = when {
                    on -> hw.onTint
                    i == lastIdx -> hw.warn
                    else -> hw.sub
                }
                if (fixed != null) Text(fixed, style = subStyle, color = subColor) else LiveText({ chipSpan(d.dep - now()) }, subStyle, subColor)
                // Bars only on cards that mix trips: then a direct train shows as one colour.
                if (anyChange) d.legs?.let { LegBar(it) }
            }
        }
    }
}

/** An M3 search bar (56dp, full corners) that opens the station search. */
@Composable
fun SearchBarButton(text: String, onClick: () -> Unit) {
    val hw = LocalHw.current
    val bounds = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .originOf { bounds[0] = it }
            .clip(CircleShape)
            .background(hw.button)
            .press {
                TapOrigin.rect = bounds[0]
                onClick()
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = hw.sub)
        Text(text, style = HwType.body, color = hw.sub)
    }
}

/** A lazy-list item that travels to its new place on the spatial spring when the list changes. */
@Composable
private fun androidx.compose.foundation.lazy.LazyItemScope.travelModifier(): Modifier {
    val motion = MaterialTheme.motionScheme
    return Modifier.animateItem(fadeInSpec = motion.defaultEffectsSpec(), placementSpec = motion.defaultSpatialSpec(), fadeOutSpec = motion.fastEffectsSpec())
}
