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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon

/** Next departures each way from a station: the direction, its platform, and trains with their terminal. */
class StationBoard(val group: DestinationGroup, val platform: String?, val trains: List<Pair<RouteDeparture, String>>)

/**
 * The map tab, edge to edge behind the floating bar. The map is landscape, so on a phone it opens
 * filling the screen height and centred on the network; pinch or the zoom toolbar goes from the
 * whole network (zoom 1) up to 4x.
 */
@Composable
fun MapScreen(onStation: (String) -> Unit) {
    NetworkMap(onStation)
}

/** The schematic network: pinch to zoom, drag to pan, tap a station. */
@Composable
private fun NetworkMap(onStation: (String) -> Unit) {
    val hw = LocalHw.current
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val labelStyle = TextStyle(fontFamily = com.flash.headway.ui.theme.SansFlex, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = hw.text)
    val zoomSpec = androidx.compose.material3.MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    BoxWithConstraints(Modifier.fillMaxSize().background(hw.hero)) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        // At zoom 1 the whole network fits the screen; "cover" fills it.
        val base = minOf(widthPx / MapLayout.WIDTH, heightPx / MapLayout.HEIGHT)
        val cover = maxOf(widthPx / MapLayout.WIDTH, heightPx / MapLayout.HEIGHT) / base
        val maxZoom = maxOf(4f, cover * 2f)

        fun clampPan(p: Offset, z: Float): Offset {
            val w = MapLayout.WIDTH * base * z
            val h = MapLayout.HEIGHT * base * z
            // Smaller than the screen on an axis: centre it there; larger: keep the edges inside.
            val x = if (w <= widthPx) (widthPx - w) / 2 else p.x.coerceIn(widthPx - w, 0f)
            val y = if (h <= heightPx) (heightPx - h) / 2 else p.y.coerceIn(heightPx - h, 0f)
            return Offset(x, y)
        }

        // Opens filled to the screen and centred on the middle of the network.
        val mid = remember {
            val xs = MapLayout.stations.values.map { it.first }
            val ys = MapLayout.stations.values.map { it.second }
            Offset((xs.min() + xs.max()) / 2f, (ys.min() + ys.max()) / 2f)
        }
        var zoom by remember(widthPx, heightPx) { mutableFloatStateOf(cover) }
        var pan by remember(widthPx, heightPx) {
            mutableStateOf(clampPan(Offset(widthPx / 2 - mid.x * base * cover, heightPx / 2 - mid.y * base * cover), cover))
        }

        /** Zooms about the middle of the screen, with the expressive spatial spring. */
        fun zoomBy(k: Float) {
            val from = zoom
            val to = (zoom * k).coerceIn(1f, maxZoom)
            val c = Offset(widthPx / 2, heightPx / 2)
            val startPan = pan
            scope.launch {
                androidx.compose.animation.core.animate(from, to, animationSpec = zoomSpec) { z, _ ->
                    pan = clampPan((startPan - c) * (z / from) + c, z)
                    zoom = z
                }
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val newZoom = (zoom * zoomChange).coerceIn(1f, maxZoom)
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
                val waterFill = androidx.compose.ui.graphics.lerp(hw.hero, hw.blue, 0.12f)
                val waterLine = androidx.compose.ui.graphics.lerp(hw.hero, hw.blue, 0.25f)
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
                        drawCircle(hw.hero, 13f * s, o)
                        drawCircle(hw.text, 13f * s, o, style = Stroke(5f * s))
                    } else {
                        drawCircle(hw.hero, 8f * s, o)
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

        // Floating title card, top left, with the line key.
        Column(
            Modifier
                .statusBarsPadding()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp)
                .staggerIn(0)
                .shadow(6.dp, com.flash.headway.ui.theme.HwShape.extraLarge)
                .clip(com.flash.headway.ui.theme.HwShape.extraLarge)
                .background(hw.card)
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Map", style = HwType.sheetTitle.copy(fontSize = 28.sp, lineHeight = 36.sp), color = hw.text)
            Text("Tap a station for departures", style = HwType.footnote, color = hw.sub)
            MapKey()
        }

        // Vibrant vertical floating toolbar for zoom, 16dp in, above the navigation bar.
        androidx.compose.material3.VerticalFloatingToolbar(
            expanded = true,
            colors = androidx.compose.material3.FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .staggerIn(2)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 96.dp),
        ) {
            androidx.compose.material3.IconButton(onClick = { zoomBy(1.6f) }, enabled = zoom < maxZoom, shapes = androidx.compose.material3.IconButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Add, contentDescription = "Zoom in")
            }
            androidx.compose.material3.IconButton(onClick = { zoomBy(1 / 1.6f) }, enabled = zoom > 1f, shapes = androidx.compose.material3.IconButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Remove, contentDescription = "Zoom out")
            }
        }

        Text(
            "© OpenStreetMap contributors",
            style = HwType.caption,
            color = hw.sub,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 16.dp, bottom = 92.dp)
                .clip(com.flash.headway.ui.theme.HwShape.small)
                .background(hw.hero.copy(alpha = 0.8f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Which colour is which line. */
@Composable
private fun MapKey() {
    val hw = LocalHw.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
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

