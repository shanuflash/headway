package com.flash.headway.ui

import androidx.compose.material.icons.rounded.Train
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TransferWithinAStation
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flash.headway.app.Coach
import com.flash.headway.data.Line
import com.flash.headway.data.Network
import com.flash.headway.engine.directDestinations
import com.flash.headway.engine.transferDestinations
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw
import kotlinx.coroutines.launch

/** Station first, then a destination reachable without changing trains. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePicker(title: String, onPick: (from: String, to: String) -> Unit, onDismiss: () -> Unit, startFrom: String? = null, from: androidx.compose.ui.geometry.Rect? = null) {
    val hw = LocalHw.current
    val origin = from
    var from by remember { mutableStateOf(startFrom) }
    var query by remember { mutableStateOf("") }
    val spatial = androidx.compose.material3.MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()

    HwSheet(onDismiss = onDismiss, fullScreen = true, from = origin) { close ->
        AnimatedContent(
            targetState = from,
            transitionSpec = {
                // Shared axis X with the expressive spatial spring.
                if (targetState != null) {
                    (slideInHorizontally(spatial) { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally(spatial) { -it / 4 } + fadeOut())
                } else {
                    (slideInHorizontally(spatial) { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally(spatial) { it / 4 } + fadeOut())
                }
            },
            label = "pickerStep",
        ) { station ->
            Column(Modifier.fillMaxSize().background(hw.button)) {
                if (station == null) {
                    SheetHeader(title, null, Icons.Rounded.Close, "Close") { close(onDismiss) }
                    SearchField(query) { query = it }
                    StationList(query) { from = it }
                } else {
                    var destQuery by remember(station) { mutableStateOf("") }
                    SheetHeader(
                        "Choose destination",
                        "From ${Network.name(station)}",
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        "Back",
                    ) {
                        from = null
                    }
                    SearchField(destQuery) { destQuery = it }
                    DestinationList(station, destQuery) { to -> close { onPick(station, to) } }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, sub: String?, icon: ImageVector, description: String, onIcon: () -> Unit) {
    val hw = LocalHw.current
    // Back (a step back) leads; close trails, as in an M3 search view.
    val back = icon != Icons.Rounded.Close
    Row(
        Modifier.fillMaxWidth().padding(start = if (back) 4.dp else 16.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (back) RoundButton(icon, description, onIcon, background = Color.Transparent)
        Column(Modifier.weight(1f)) {
            Text(title, style = HwType.sheetTitle, color = hw.text)
            if (sub != null) Text(sub, style = HwType.subhead, color = hw.sub)
        }
        if (!back) RoundButton(icon, description, onIcon)
    }
}

@Composable
internal fun SearchField(query: String, onChange: (String) -> Unit) {
    val hw = LocalHw.current
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth()
            .height(56.dp)
            .clip(CircleShape)
            .background(hw.fill)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = hw.sub)
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = HwType.body.copy(color = hw.text),
            cursorBrush = SolidColor(hw.tint),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text("Search stations", style = HwType.body, color = hw.sub)
                    inner()
                }
            },
        )
    }
}

/** "Guindy" with the part matching the search in bold, as the rows filter. */
private fun highlight(name: String, q: String): AnnotatedString = buildAnnotatedString {
    append(name)
    if (q.isNotEmpty()) {
        val i = name.lowercase().indexOf(q)
        if (i >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), i, i + q.length)
    }
}

/**
 * One row of an M3 Expressive segmented list inside a lazy list. Corners follow its place in the
 * group (and morph rounder while pressed); when the list filters it travels to its new place on
 * the spatial spring, fades in or out, and on first show it staggers in after the rows above it.
 */
@Composable
private fun LazyItemScope.Segment(i: Int, n: Int, order: Int, color: Color, content: @Composable () -> Unit) {
    val motion = androidx.compose.material3.MaterialTheme.motionScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val spec = motion.fastSpatialSpec<androidx.compose.ui.unit.Dp>()
    val top by animateDpAsState(if (pressed) 20.dp else if (i == 0) 16.dp else 4.dp, spec, label = "segTop")
    val bottom by animateDpAsState(if (pressed) 20.dp else if (i == n - 1) 16.dp else 4.dp, spec, label = "segBottom")
    Box(
        Modifier
            .animateItem(fadeInSpec = motion.defaultEffectsSpec(), placementSpec = motion.defaultSpatialSpec(), fadeOutSpec = motion.fastEffectsSpec())
            .staggerIn(order)
            .padding(bottom = 2.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(top, top, bottom, bottom))
            .background(color),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalPressSource provides source) { content() }
    }
}

/** A group heading that travels with its rows. */
@Composable
private fun LazyItemScope.GroupHeading(line: Line, text: String) {
    val hw = LocalHw.current
    val motion = androidx.compose.material3.MaterialTheme.motionScheme
    Row(
        Modifier
            .animateItem(fadeInSpec = motion.defaultEffectsSpec(), placementSpec = motion.defaultSpatialSpec(), fadeOutSpec = motion.fastEffectsSpec())
            .padding(start = 4.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(hw.line(line)))
        Text(text, style = HwType.section, color = hw.tint)
    }
}

private val listPadding: PaddingValues
    @Composable get() {
        val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        return PaddingValues(start = 16.dp, end = 16.dp, bottom = nav + 24.dp)
    }

@Composable
internal fun StationList(query: String, onPick: (String) -> Unit) {
    val hw = LocalHw.current
    val q = query.trim().lowercase()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
        var order = 0
        Network.pickerGroups.forEach { (line, ids) ->
            val shown = ids.filter { id ->
                val st = Network.stations.getValue(id)
                q.isEmpty() || st.name.lowercase().contains(q) || st.long.orEmpty().lowercase().contains(q)
            }
            if (shown.isNotEmpty()) {
                item(key = "h-${line.name}") { GroupHeading(line, line.label) }
                val base = order
                itemsIndexed(shown, key = { _, id -> "${line.name}-$id" }) { i, id ->
                    Segment(i, shown.size, base + i, hw.hero) {
                        val st = Network.stations.getValue(id)
                        ListRow(onClick = { onPick(id) }) {
                            Column(Modifier.weight(1f)) {
                                Text(highlight(st.name, q), style = HwType.body, color = hw.text)
                                st.long?.let { Text(it, style = HwType.subhead, color = hw.sub) }
                            }
                            st.lines.filter { it != line }.forEach { LineTag(it) }
                        }
                    }
                }
                order += shown.size
            }
        }
    }
}

/** Direct trains first, by direction; then every other station, by line, with where to change. */
@Composable
private fun DestinationList(from: String, query: String, onPick: (String) -> Unit) {
    val hw = LocalHw.current
    val q = query.trim().lowercase()
    fun matches(id: String): Boolean {
        val st = Network.stations.getValue(id)
        return q.isEmpty() || st.name.lowercase().contains(q) || st.long.orEmpty().lowercase().contains(q)
    }
    val direct = remember(from) { directDestinations(from) }
    val changes = remember(from) { transferDestinations(from) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
        var order = 0
        direct.forEachIndexed { gi, g ->
            val shown = g.stations.filter { matches(it.first) }
            if (shown.isEmpty()) return@forEachIndexed
            item(key = "dh-$gi") { GroupHeading(g.line, "Towards ${g.terminals.joinToString(" or ") { Network.name(it) }}") }
            val base = order
            itemsIndexed(shown, key = { _, it -> "d-$gi-${it.first}" }) { i, (id, minutes) ->
                Segment(i, shown.size, base + i, hw.hero) {
                    val st = Network.stations.getValue(id)
                    ListRow(onClick = { onPick(id) }) {
                        Text(highlight(st.name, q), style = HwType.body, color = hw.text, modifier = Modifier.weight(1f))
                        st.lines.filter { it != g.line }.forEach { LineTag(it) }
                        Text("$minutes min", style = HwType.subheadStrong.copy(fontWeight = FontWeight.Medium), color = hw.sub)
                    }
                }
            }
            order += shown.size
        }

        // Each station once, under its first line.
        val listed = HashSet<String>()
        val groups = Network.pickerGroups.map { (line, ids) -> line to ids.filter { it in changes && matches(it) && listed.add(it) } }.filter { it.second.isNotEmpty() }
        if (groups.isNotEmpty()) {
            item(key = "with-change") {
                Box(Modifier.animateItem()) { SectionTitle("With a change") }
            }
        }
        groups.forEach { (line, ids) ->
            item(key = "ch-${line.name}") { GroupHeading(line, line.label) }
            val base = order
            itemsIndexed(ids, key = { _, id -> "c-${line.name}-$id" }) { i, id ->
                Segment(i, ids.size, base + i, hw.hero) {
                    val st = Network.stations.getValue(id)
                    val via = changes.getValue(id).via.joinToString(", then ") { Network.name(it) }
                    ListRow(onClick = { onPick(id) }) {
                        Column(Modifier.weight(1f)) {
                            Text(highlight(st.name, q), style = HwType.body, color = hw.text)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Rounded.TransferWithinAStation, null, tint = hw.sub, modifier = Modifier.size(15.dp))
                                Text("Change at $via", style = HwType.footnote, color = hw.sub)
                            }
                        }
                        st.lines.filter { it != line }.forEach { LineTag(it) }
                    }
                }
            }
            order += ids.size
        }
    }
}

@Composable
fun LineTag(line: Line) {
    val hw = LocalHw.current
    Text(
        line.label,
        style = HwType.caption.copy(fontWeight = FontWeight.SemiBold),
        color = hw.lineText(line),
        modifier = Modifier.clip(com.flash.headway.ui.theme.HwShape.small).background(hw.line(line).copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** One train of a route to pick a best coach for: [station] null is the first one (or the only one). */
data class CoachStop(val station: String?, val label: String, val sub: String?, val coach: Coach?)

/** Options for a saved route. */
@Composable
fun RouteMenu(
    title: String,
    isTop: Boolean,
    onPlan: () -> Unit,
    onTop: () -> Unit,
    onRemove: () -> Unit,
    coaches: List<CoachStop>,
    onCoach: (String?, Coach?) -> Unit,
    routeId: String,
    /** Each train's line on a route reached with a change (it has no delay of its own); null for a direct route. */
    journeyLines: List<TripLine>?,
    lineDelays: Map<String, Int>,
    onLineDelay: (String, Int) -> Unit,
    delay: Int,
    delayPreview: suspend (Int) -> DelayPreview?,
    onDelay: (Int) -> Unit,
    flipCard: @Composable () -> Unit,
    onDismiss: () -> Unit,
) {
    val hw = LocalHw.current
    HwSheet(onDismiss = onDismiss) { close ->
        // The title stays put while the options scroll under it.
        Text(
            title,
            style = HwType.sheetTitle,
            color = hw.text,
            modifier = Modifier.padding(start = 20.dp, end = 16.dp, bottom = 12.dp),
        )
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().clip(CardShape).background(hw.button).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LeadIcon(Icons.Rounded.Train)
                    Column(Modifier.weight(1f)) {
                        Text("Where to board", style = HwType.headline, color = hw.text)
                        Text("The coach nearest your exit", style = HwType.footnote, color = hw.sub)
                    }
                }
                val options = listOf<Pair<Coach?, String>>(null to "Any", Coach.FRONT to "Front", Coach.MIDDLE to "Middle", Coach.REAR to "Rear")
                if (coaches.size <= 1) {
                    Segmented(options = options, selected = coaches.firstOrNull()?.coach, onSelect = { onCoach(null, it) })
                    Text("Shown on the card.", style = HwType.footnote, color = hw.sub, modifier = Modifier.padding(start = 4.dp))
                } else {
                    // A trip with changes: where to stand differs train to train.
                    coaches.forEach { c ->
                        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(c.label, style = HwType.subheadStrong, color = hw.text)
                                if (c.sub != null) Text("· ${c.sub}", style = HwType.footnote, color = hw.sub)
                            }
                            Segmented(options = options, selected = c.coach, onSelect = { onCoach(c.station, it) })
                        }
                    }
                    Text("Board the coach nearest your change or exit. Shown at each step of the trip.", style = HwType.footnote, color = hw.sub)
                }
            }
            if (journeyLines != null) {
                if (journeyLines.isEmpty()) {
                    Footnote("Trips with changes use each line’s delay. Set them in Lines.")
                } else {
                    JourneyDelays(journeyLines, lineDelays, onLineDelay)
                }
            } else {
                DelayCard(routeId, delay, delayPreview, onDelay)
            }
            flipCard()
            val danger = hw.danger
            ListCard(
                listOfNotNull<@Composable () -> Unit>(
                    { MenuRow(Icons.Rounded.Schedule, "Plan a trip", "Depart at or arrive by a set time", hw.text) { close(onPlan) } },
                    // Already first: nothing to move.
                    if (isTop) null else ({ MenuRow(Icons.Rounded.VerticalAlignTop, "Move to top", null, hw.text) { close(onTop) } }),
                    { MenuRow(Icons.Rounded.Delete, "Remove route", null, danger) { close(onRemove) } },
                ),
                inset = 50.dp,
                color = hw.button,
            )
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, sub: String?, color: Color, onClick: () -> Unit) {
    val hw = LocalHw.current
    val danger = color == hw.danger
    ListRow(onClick = onClick) {
        if (danger) LeadIcon(icon, hw.dangerBg, hw.onDangerBg) else LeadIcon(icon)
        RowText(title, sub, titleColor = if (danger) color else null, modifier = Modifier.weight(1f))
    }
}
