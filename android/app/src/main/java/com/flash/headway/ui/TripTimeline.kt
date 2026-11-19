package com.flash.headway.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flash.headway.app.Coach
import com.flash.headway.data.Network
import com.flash.headway.data.RouteId
import com.flash.headway.engine.Leg
import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.WalkSpeed
import com.flash.headway.engine.changeMinutes
import com.flash.headway.engine.directDestinations
import com.flash.headway.ui.theme.HwColors
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw

/* ----- trips with changes ----- */

private fun isInter(leg: Leg) = leg.trip.route == RouteId.INTER

/** A leg's colour: its line, or green for the inter-corridor service (it runs on Green Line track). */
fun legColor(leg: Leg, hw: HwColors): Color = if (isInter(leg)) hw.green else hw.line(leg.trip.line)

fun legLineName(leg: Leg): String = if (isInter(leg)) "Inter-corridor" else leg.trip.line.label

/** A leg's colours: its line's, or green then blue for the inter-corridor (Green Line track, then Blue). */
fun legColors(leg: Leg, hw: HwColors): List<Color> = if (isInter(leg)) listOf(hw.green, hw.blue) else listOf(hw.line(leg.trip.line))

/** A dot that shows every line of the trip, in order. */
fun legsBrush(legs: List<Leg>, hw: HwColors): Brush {
    val colors = legs.flatMap { legColors(it, hw) }
    return if (colors.size > 1) Brush.linearGradient(colors) else Brush.linearGradient(listOf(colors[0], colors[0]))
}

/** "Blue" / "Green" / "Yellow" / "Inter-corridor". */
fun legShort(leg: Leg): String = if (isInter(leg)) "Inter-corridor" else leg.trip.line.label.removeSuffix(" Line")

/** "● Blue → ● Inter-corridor": the shown trip's trains, in order and in their colours. */
@Composable
fun LegsLine(legs: List<Leg>) {
    val hw = LocalHw.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
        legs.forEachIndexed { i, leg ->
            if (i > 0) Text("→", style = HwType.subhead, color = hw.sub)
            Box(Modifier.size(8.dp).clip(CircleShape).background(legsBrush(listOf(leg), hw)))
            Text(legShort(leg), style = HwType.subhead.copy(fontWeight = FontWeight.Medium), color = hw.text, maxLines = 1)
        }
    }
}

/** A thin bar on a chip with each train's colour, by its share of the ride, so options read at a glance. */
@Composable
fun LegBar(legs: List<Leg>) {
    val hw = LocalHw.current
    Row(Modifier.padding(top = 4.dp).width(50.dp).height(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        legs.forEach { leg ->
            val colors = legColors(leg, hw)
            Box(
                Modifier
                    .weight(((leg.arr - leg.dep) / MINUTE).coerceAtLeast(1).toFloat())
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (colors.size > 1) Brush.horizontalGradient(colors) else Brush.linearGradient(listOf(colors[0], colors[0]))),
            )
        }
    }
}

/** "1 change at Alandur", "2 changes at Alandur and Vadapalani". */
fun changesLabel(legs: List<Leg>): String {
    val at = legs.drop(1).map { Network.name(it.from) }
    val where = if (at.size > 1) "${at.dropLast(1).joinToString(", ")} and ${at.last()}" else at.firstOrNull().orEmpty()
    return "${plural(at.size, "change")} at $where"
}

/** Platform for a leg at the station it's boarded, from the direction its train heads off in. */
fun legPlatform(leg: Leg): String? =
    directDestinations(leg.from).firstOrNull { g -> g.stations.any { it.first == leg.to } }?.let { platformAt(leg.from, it) }

/** How the rail looks above or below a dot: a train's colour (or two, faded), or dotted for a walk. */
private data class Rail(val color: Color, val walk: Boolean = false, val fade: Color? = null)

private val TimeWidth = 64.dp
private val RailWidth = 18.dp
private val Gap = 10.dp
private val DotRadius = 7.dp
private val DotCenter = 10.dp

/**
 * The trip step by step: board, ride, get off, walk to the next line, and on to arrival.
 * A coloured rail per train, dotted while walking between lines.
 */
@Composable
fun TripTimeline(legs: List<Leg>, modifier: Modifier = Modifier, now: (() -> Long)? = null, walk: WalkSpeed = WalkSpeed.NORMAL, coachFor: (Int, Leg) -> Coach? = { _, _ -> null }) {
    val hw = LocalHw.current
    val walkRail = Rail(hw.sub, walk = true)
    Column(modifier.fillMaxWidth()) {
        legs.forEachIndexed { i, leg ->
            val color = legColor(leg, hw)
            val ride = legColors(leg, hw).let { Rail(it.first(), fade = it.getOrNull(1)) }
            if (i > 0) {
                val prev = legs[i - 1]
                val walkMin = changeMinutes(leg.from, walk)
                val spare = ((leg.dep - prev.arr) / MINUTE).toInt() - walkMin
                val to = legLineName(leg)
                Segment(walkRail) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, tint = hw.sub, modifier = Modifier.size(18.dp))
                        Text(
                            "Change to $to · $walkMin min walk${if (spare > 0) " · $spare min spare" else ""}",
                            style = HwType.footnote.copy(fontWeight = FontWeight.Medium),
                            color = hw.text,
                        )
                    }
                }
            }
            val platform = legPlatform(leg)
            // Where to stand can differ train to train: each one's best coach, if set.
            val coach = coachFor(i, leg)?.let { " · ${it.label.replaceFirstChar { c -> c.uppercase() }} coach" }.orEmpty()
            StopRow(
                passed = now != null && leg.dep <= now(),
                time = clock(leg.dep),
                station = leg.from,
                sub = "${legLineName(leg)} towards ${Network.name(leg.trip.dest)}${platform?.let { " · $it" }.orEmpty()}$coach",
                color = color,
                filled = false,
                above = if (i > 0) walkRail else null,
                below = ride,
            )
            Segment(ride) {
                Text("${plural(leg.stops.size - 1, "stop")} · ${span(leg.arr - leg.dep)}", style = HwType.footnote, color = hw.sub)
            }
            val last = i == legs.lastIndex
            StopRow(
                passed = now != null && leg.arr <= now(),
                time = clock(leg.arr),
                station = leg.to,
                sub = if (last) "Arrive" else "Get off",
                color = color,
                filled = true,
                strong = last,
                above = ride,
                below = if (last) null else walkRail,
            )
        }
    }
}

@Composable
private fun StopRow(time: String, station: String, sub: String, color: Color, filled: Boolean, above: Rail?, below: Rail?, strong: Boolean = false, passed: Boolean = false) {
    val hw = LocalHw.current
    // On a followed trip, stops already behind you fade back.
    val ink = if (passed) hw.sub else hw.text
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Gap)) {
        Text(
            time,
            style = HwType.footnote.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            color = ink,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(TimeWidth).padding(top = 1.dp),
        )
        Canvas(Modifier.width(RailWidth).fillMaxHeight()) {
            val cx = size.width / 2
            val cy = DotCenter.toPx()
            above?.let { rail(it, Offset(cx, 0f), Offset(cx, cy)) }
            below?.let { rail(it, Offset(cx, cy), Offset(cx, size.height)) }
            val r = DotRadius.toPx()
            val ring = 3.5.dp.toPx()
            drawCircle(if (filled) color else hw.card, r, Offset(cx, cy))
            drawCircle(color, r - ring / 2, Offset(cx, cy), style = Stroke(ring))
        }
        Column(Modifier.weight(1f).padding(bottom = 2.dp)) {
            Text(
                Network.name(station),
                style = HwType.subhead.copy(fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold),
                color = ink,
            )
            Text(sub, style = HwType.footnote, color = hw.sub)
        }
    }
}

@Composable
private fun Segment(rail: Rail, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Gap)) {
        Box(Modifier.width(TimeWidth))
        Canvas(Modifier.width(RailWidth).fillMaxHeight()) {
            val cx = size.width / 2
            rail(rail, Offset(cx, 0f), Offset(cx, size.height))
        }
        Box(Modifier.weight(1f).heightIn(min = 30.dp).padding(vertical = 6.dp), contentAlignment = Alignment.CenterStart) { content() }
    }
}

private fun DrawScope.rail(rail: Rail, from: Offset, to: Offset) {
    if (rail.walk) {
        val dot = 2.5.dp.toPx()
        drawLine(
            rail.color,
            from,
            to,
            strokeWidth = dot,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, dot * 2.2f)),
        )
    } else if (rail.fade != null) {
        drawLine(Brush.verticalGradient(listOf(rail.color, rail.fade), startY = from.y, endY = to.y), from, to, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Butt)
    } else {
        drawLine(rail.color, from, to, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Butt)
    }
}

/**
 * Other ways on from a station of a followed trip: to take another train than the one planned,
 * or a later one after missing it. [options] is null while they're worked out.
 */
@Composable
fun TrainOptionsSheet(station: String, options: List<com.flash.headway.engine.RouteDeparture>?, onPick: (com.flash.headway.engine.RouteDeparture) -> Unit, onDismiss: () -> Unit) {
    val hw = LocalHw.current
    HwSheet(onDismiss = onDismiss) { close ->
        Column(Modifier.align(Alignment.CenterHorizontally).padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Other trains", style = HwType.headline, color = hw.text)
            Text("From ${Network.name(station)}", style = HwType.footnote, color = hw.sub)
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                options == null -> Footnote("Loading…")
                options.isEmpty() -> Footnote("No more trains from ${Network.name(station)} today.")
                else -> ListCard(
                    options.map { d ->
                        @Composable {
                            val legs = d.legs.orEmpty()
                            val first = legs.firstOrNull()
                            ListRow(onClick = { close { onPick(d) } }) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(legsBrush(legs, hw)))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${clock(d.dep)}${first?.let { " · ${legLineName(it)} towards ${Network.name(it.trip.dest)}" }.orEmpty()}",
                                        style = HwType.body,
                                        color = hw.text,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "Arrives ${clock(d.arr)}" + if (legs.size > 1) " · ${changesLabel(legs)}" else "",
                                        style = HwType.footnote,
                                        color = hw.sub,
                                    )
                                }
                            }
                        }
                    },
                    inset = 38.dp,
                )
            }
        }
    }
}

/**
 * "Which train are you on?": trips on a route already under way, newest first, each with where
 * it's got to; picking one follows it from there. Or join partway (after a bus, say): pick the
 * station and when you'll get there, and the rest of the trip is followed from it. [trips] is
 * null while they're worked out.
 */
@Composable
fun OnTheWaySheet(
    title: String,
    to: String,
    trips: List<com.flash.headway.engine.RouteDeparture>?,
    now: () -> Long,
    onPick: (com.flash.headway.engine.RouteDeparture) -> Unit,
    onJoinAt: (station: String, inMinutes: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val hw = LocalHw.current
    var joining by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var minutes by remember { mutableIntStateOf(0) }
    HwSheet(onDismiss = onDismiss, heightFraction = 0.9f) { close ->
        if (joining) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back", { joining = false }, background = hw.card)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Choose boarding station", style = HwType.headline, color = hw.text)
                    Text("To ${Network.name(to)}", style = HwType.footnote, color = hw.sub)
                }
                Box(Modifier.size(44.dp))
            }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Ready to board in", style = HwType.subheadStrong, color = hw.text, modifier = Modifier.padding(start = 4.dp))
                Segmented(
                    options = listOf(0 to "Now", 5 to "5 min", 10 to "10 min", 15 to "15 min", 20 to "20 min"),
                    selected = minutes,
                    onSelect = { minutes = it },
                )
            }
            SearchField(query) { query = it }
            Box(Modifier.weight(1f, fill = false)) {
                StationList(query) { station -> close { onJoinAt(station, minutes) } }
            }
            return@HwSheet
        }
        Column(Modifier.align(Alignment.CenterHorizontally).padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Already on board", style = HwType.headline, color = hw.text)
            Text(title, style = HwType.footnote, color = hw.sub)
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                trips == null -> Footnote("Loading…")
                trips.isEmpty() -> Footnote("No trips in progress on this route.")
                else -> {
                    Footnote("Select a trip to continue from its current step.")
                    ListCard(
                        trips.map { d ->
                            @Composable {
                                val legs = d.legs.orEmpty()
                                val t = now()
                                val i = legs.indexOfFirst { it.dep > t }
                                val where = when {
                                    i < 0 -> "Arrives ${clock(d.arr)}"
                                    else -> "Next: change at ${Network.name(legs[i].from)} · ${legShort(legs[i])} ${clock(legs[i].dep)}"
                                }
                                ListRow(onClick = { close { onPick(d) } }) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(legsBrush(legs, hw)))
                                    Column(Modifier.weight(1f)) {
                                        Text("${clock(d.dep)} from ${Network.name(legs.first().from)}", style = HwType.body, color = hw.text)
                                        Text(where, style = HwType.footnote, color = hw.sub)
                                    }
                                }
                            }
                        },
                        inset = 38.dp,
                    )
                }
            }
            // Part of the way by bus (or on foot): pick up the trip at another station.
            Text("Join mid-route", style = HwType.headline, color = hw.text, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
            ListCard(
                listOf<@Composable () -> Unit>({
                    ListRow(onClick = { joining = true }) {
                        Icon(Icons.Rounded.DirectionsBus, null, tint = hw.tint, modifier = Modifier.size(22.dp))
                        RowText("Start from another station", "Board somewhere other than the route’s start", modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = hw.chev, modifier = Modifier.size(20.dp))
                    }
                }),
                inset = 50.dp,
            )
        }
    }
}
