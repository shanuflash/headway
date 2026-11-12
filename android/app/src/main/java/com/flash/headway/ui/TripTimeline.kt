package com.flash.headway.ui

import androidx.compose.foundation.Canvas
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
import com.flash.headway.data.Network
import com.flash.headway.data.RouteId
import com.flash.headway.engine.Leg
import com.flash.headway.engine.MINUTE
import com.flash.headway.engine.directDestinations
import com.flash.headway.ui.theme.HwColors
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw

/* ----- trips with changes ----- */

private fun isInter(leg: Leg) = leg.trip.route == RouteId.INTER

/** A leg's colour: its line, or green for the inter-corridor service (it runs on Green Line track). */
fun legColor(leg: Leg, hw: HwColors): Color = if (isInter(leg)) hw.green else hw.line(leg.trip.line)

fun legLineName(leg: Leg): String = if (isInter(leg)) "Inter-corridor" else leg.trip.line.label

/** A dot that shows every line of the trip, in order. */
fun legsBrush(legs: List<Leg>, hw: HwColors): Brush {
    val colors = legs.map { legColor(it, hw) }
    return if (colors.size > 1) Brush.linearGradient(colors) else Brush.linearGradient(listOf(colors[0], colors[0]))
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

/** How the rail looks above or below a dot: a train's colour, or dotted for a walk. */
private data class Rail(val color: Color, val walk: Boolean = false)

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
fun TripTimeline(legs: List<Leg>, modifier: Modifier = Modifier) {
    val hw = LocalHw.current
    val walkRail = Rail(hw.sub, walk = true)
    Column(modifier.fillMaxWidth()) {
        legs.forEachIndexed { i, leg ->
            val color = legColor(leg, hw)
            val ride = Rail(color)
            if (i > 0) {
                val prev = legs[i - 1]
                val walk = Network.transfer(leg.from)
                val spare = ((leg.dep - prev.arr) / MINUTE).toInt() - walk
                val to = if (isInter(leg)) "an Inter-corridor train" else "the ${legLineName(leg)}"
                Segment(walkRail) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, tint = hw.sub, modifier = Modifier.size(18.dp))
                        Text(
                            "Change to $to, $walk min walk${if (spare > 0) " · $spare min to spare" else ""}",
                            style = HwType.footnote.copy(fontWeight = FontWeight.Medium),
                            color = hw.text,
                        )
                    }
                }
            }
            val platform = legPlatform(leg)
            StopRow(
                time = clock(leg.dep),
                station = leg.from,
                sub = "${legLineName(leg)} toward ${Network.name(leg.trip.dest)}${platform?.let { " · $it" }.orEmpty()}",
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
private fun StopRow(time: String, station: String, sub: String, color: Color, filled: Boolean, above: Rail?, below: Rail?, strong: Boolean = false) {
    val hw = LocalHw.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Gap)) {
        Text(
            time,
            style = HwType.footnote.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            color = hw.text,
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
                color = hw.text,
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
    } else {
        drawLine(rail.color, from, to, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Butt)
    }
}
