package com.flash.headway.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flash.headway.data.Line
import com.flash.headway.data.MapLayout
import com.flash.headway.data.Network
import com.flash.headway.engine.DestinationGroup
import com.flash.headway.engine.RouteDeparture
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw

/** Next departures each way from a station: the direction, its platform, and trains with their terminal. */
class StationBoard(val group: DestinationGroup, val platform: String?, val trains: List<Pair<RouteDeparture, String>>)

@Composable
fun ColumnScope.MapScreen(onSettings: () -> Unit, onStation: (String) -> Unit) {
    TopBar(right = { RoundButton(Icons.Rounded.Settings, "Settings", onSettings) })
    LargeTitle("Map")
    NetworkMap(onStation)
    MapKey()
    Footnote("Tap a station for departures. Map data © OpenStreetMap contributors.")
}

/** The schematic network: pinch to zoom, drag to pan, tap a station. */
@Composable
private fun NetworkMap(onStation: (String) -> Unit) {
    val hw = LocalHw.current
    val measurer = rememberTextMeasurer()
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val labelStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, color = hw.text)

    // The card takes the map's own shape, so there's no empty band under it.
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(MapLayout.WIDTH.toFloat() / MapLayout.HEIGHT.toFloat())
            .clip(RouteCardShape)
            .background(hw.card),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        // At zoom 1 the whole network fits the card.
        val base = minOf(widthPx / MapLayout.WIDTH, heightPx / MapLayout.HEIGHT)

        fun clampPan(p: Offset, z: Float): Offset {
            val w = MapLayout.WIDTH * base * z
            val h = MapLayout.HEIGHT * base * z
            val minX = minOf(0f, widthPx - w)
            val minY = minOf(0f, heightPx - h)
            return Offset(p.x.coerceIn(minX, 0f), p.y.coerceIn(minY, 0f))
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val newZoom = (zoom * zoomChange).coerceIn(1f, 4f)
                        // Zoom about the fingers, then apply the drag.
                        val k = newZoom / zoom
                        val next = (pan - centroid) * k + centroid + panChange
                        zoom = newZoom
                        pan = clampPan(next, newZoom)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val s = base * zoom
                        val x = (tap.x - pan.x) / s
                        val y = (tap.y - pan.y) / s
                        val hit = MapLayout.stations.entries.minByOrNull { (_, p) ->
                            (p.first - x) * (p.first - x) + (p.second - y) * (p.second - y)
                        }
                        if (hit != null) {
                            val dx = hit.value.first - x
                            val dy = hit.value.second - y
                            // A finger-sized target (24 dp around the station), whatever the zoom.
                            val reach = 24.dp.toPx() / s
                            if (dx * dx + dy * dy < reach * reach) onStation(hit.key)
                        }
                    }
                },
        ) {
            val s = base * zoom
            translate(pan.x, pan.y) {
                // Water first, under the lines: sea and lakes filled, coast and rivers stroked.
                val waterFill = androidx.compose.ui.graphics.lerp(hw.card, hw.blue, 0.12f)
                val waterLine = androidx.compose.ui.graphics.lerp(hw.card, hw.blue, 0.25f)
                MapLayout.features.forEach { (kind, pts) ->
                    val path = Path()
                    pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * s, y * s) else path.lineTo(x * s, y * s) }
                    when (kind) {
                        "sea", "lake" -> {
                            path.close()
                            drawPath(path, waterFill)
                        }
                        "coast" -> drawPath(path, waterLine, style = Stroke(width = 2f * s))
                        else -> drawPath(path, waterLine, style = Stroke(width = 4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                fun at(id: String): Offset {
                    val p = MapLayout.stations.getValue(id)
                    return Offset(p.first * s, p.second * s)
                }
                MapLayout.lines.forEach { (line, ids) ->
                    val path = Path()
                    ids.forEachIndexed { i, id ->
                        val o = at(id)
                        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    }
                    drawPath(path, hw.line(line), style = Stroke(width = 12f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                // Labels go on interchanges first, then the rest; one that would overlap a label
                // already drawn is left out until zooming in makes room.
                val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
                val order = MapLayout.stations.entries.sortedByDescending { Network.stations.getValue(it.key).lines.size }
                MapLayout.stations.forEach { (id, _) ->
                    val o = at(id)
                    val lines = Network.stations.getValue(id).lines
                    if (lines.size > 1) {
                        drawCircle(hw.card, 13f * s, o)
                        drawCircle(hw.text, 13f * s, o, style = Stroke(5f * s))
                    } else {
                        drawCircle(hw.card, 8f * s, o)
                        drawCircle(hw.line(lines.first()), 8f * s, o, style = Stroke(5f * s))
                    }
                }
                order.forEach { (id, p) ->
                    val o = at(id)
                    // Never smaller than legible, whatever the zoom.
                    val text = measurer.measure(Network.name(id), labelStyle.copy(fontSize = maxOf((20f * s).toSp().value, 10f).sp))
                    val w = text.size.width.toFloat()
                    val h = text.size.height.toFloat()
                    val right = Offset(o.x + 14f * s, o.y - h / 2)
                    val left = Offset(o.x - 14f * s - w, o.y - h / 2)
                    val preferred = when (p.third) {
                        'l' -> left
                        'a' -> Offset(o.x + 10f * s, o.y - 12f * s - h)
                        'd' -> Offset(o.x + 10f * s, o.y + 12f * s)
                        'A' -> Offset(o.x - 10f * s - w, o.y - 12f * s - h)
                        'D' -> Offset(o.x - 10f * s - w, o.y + 12f * s)
                        else -> right
                    }
                    // The laid-out side first, else the other side: never cut off by the card's
                    // edge, never over another label.
                    val visible = androidx.compose.ui.geometry.Rect(-pan.x, -pan.y, -pan.x + size.width, -pan.y + size.height)
                    val spot = listOf(preferred, right, left).map { androidx.compose.ui.geometry.Rect(it, androidx.compose.ui.geometry.Size(w, h)) }
                        .firstOrNull { r -> r.left >= visible.left && r.right <= visible.right && r.top >= visible.top && r.bottom <= visible.bottom && placed.none { it.overlaps(r.inflate(2f)) } }
                    if (spot != null) {
                        placed += spot.inflate(2f)
                        drawText(text, topLeft = spot.topLeft)
                    }
                }
            }
        }
    }
}

/** Which colour is which line. */
@Composable
private fun MapKey() {
    val hw = LocalHw.current
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(Line.BLUE, Line.GREEN, Line.YELLOW).forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(14.dp, 4.dp).clip(CircleShape).background(hw.line(line)))
                Text(line.label.removeSuffix(" Line"), style = HwType.footnote, color = hw.sub, maxLines = 1)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).border(2.dp, hw.text, CircleShape))
            Text("Interchange", style = HwType.footnote, color = hw.sub, maxLines = 1)
        }
    }
}

/** The bottom sheet for a tapped station. */
@Composable
fun StationSheet(id: String, boards: List<StationBoard>, now: () -> Long, onRouteFrom: () -> Unit, onDismiss: () -> Unit) {
    val hw = LocalHw.current
    HwSheet(onDismiss = onDismiss) { close ->
        // The name stays put; an interchange's many directions scroll under it.
        Column(Modifier.align(Alignment.CenterHorizontally).padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Network.name(id), style = HwType.headline, color = hw.text)
            Text(Network.stations.getValue(id).lines.joinToString(" · ") { it.label }, style = HwType.footnote, color = hw.sub)
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            boards.forEach { b ->
                val many = b.group.terminals.size > 1
                Column(
                    Modifier.fillMaxWidth().clip(CardShape).background(hw.card).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(hw.line(b.group.line)))
                        Text(
                            "Towards ${b.group.terminals.joinToString(" or ") { Network.name(it) }}" + (b.platform?.let { " · $it" } ?: ""),
                            style = HwType.subheadStrong,
                            color = hw.text,
                        )
                    }
                    if (b.trains.isEmpty()) {
                        Text("No more trains today", style = HwType.subhead, color = hw.sub)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            b.trains.forEach { (d, to) ->
                                Column(
                                    Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)).background(hw.fill).edge(CircleShape).padding(vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(clock(d.dep), style = HwType.subheadStrong.copy(fontFeatureSettings = "tnum"), color = hw.text)
                                    Text(
                                        if (many) Network.name(to) else if (d.dep - now() < 30_000) "now" else "${mins(d.dep - now())} min",
                                        style = HwType.caption,
                                        color = hw.sub,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            PrimaryButton("Add route from here", Icons.Rounded.Add) { close(onRouteFrom) }
        }
    }
}

