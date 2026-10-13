package com.flash.headway.ui

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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
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
import com.flash.headway.data.Line
import com.flash.headway.data.Network
import com.flash.headway.engine.directDestinations
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw
import kotlinx.coroutines.launch

/** Station first, then a destination reachable without changing trains. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePicker(title: String, onPick: (from: String, to: String) -> Unit, onDismiss: () -> Unit) {
    val hw = LocalHw.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var from by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    fun close(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { then() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = hw.bg,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = hw.chev) },
    ) {
        AnimatedContent(
            targetState = from,
            transitionSpec = {
                val slide = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)
                if (targetState != null) {
                    (slideInHorizontally(slide) { it } + fadeIn()) togetherWith (slideOutHorizontally(slide) { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally(slide) { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally(slide) { it } + fadeOut())
                }
            },
            label = "pickerStep",
        ) { station ->
            Column(Modifier.fillMaxHeight(0.92f)) {
                if (station == null) {
                    SheetHeader(title, null, Icons.Rounded.Close, "Close") { close(onDismiss) }
                    SearchField(query) { query = it }
                    StationList(query) { from = it }
                } else {
                    SheetHeader("Where to from ${Network.name(station)}?", "Direct trains only", Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Back") {
                        from = null
                    }
                    DestinationList(station) { to -> close { onPick(station, to) } }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, sub: String?, icon: ImageVector, description: String, onIcon: () -> Unit) {
    val hw = LocalHw.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(icon, description, onIcon, background = hw.card)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = HwType.headline, color = hw.text)
            if (sub != null) Text(sub, style = HwType.footnote, color = hw.sub)
        }
        Spacer(Modifier.size(44.dp))
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val hw = LocalHw.current
    Row(
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(hw.card)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = hw.sub, modifier = Modifier.size(22.dp))
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

@Composable
private fun StationList(query: String, onPick: (String) -> Unit) {
    val q = query.trim().lowercase()
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Network.pickerGroups.forEach { (line, ids) ->
            val shown = ids.filter { id ->
                val st = Network.stations.getValue(id)
                q.isEmpty() || st.name.lowercase().contains(q) || st.long.orEmpty().lowercase().contains(q)
            }
            if (shown.isNotEmpty()) {
                SectionTitle(line.label)
                ListCard(
                    shown.map { id ->
                        @Composable {
                            val st = Network.stations.getValue(id)
                            ListRow(onClick = { onPick(id) }) {
                                RowText(st.name, st.long, modifier = Modifier.weight(1f))
                                st.lines.filter { it != line }.forEach { LineTag(it) }
                            }
                        }
                    },
                    inset = 16.dp,
                )
            }
        }
    }
}

@Composable
private fun DestinationList(from: String, onPick: (String) -> Unit) {
    val hw = LocalHw.current
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding().padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        directDestinations(from).forEach { g ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 4.dp, top = 6.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(hw.line(g.line)))
                Text("Toward ${g.terminals.joinToString(" or ") { Network.name(it) }}", style = HwType.section, color = hw.text)
            }
            ListCard(
                g.stations.map { (id, minutes) ->
                    @Composable {
                        val st = Network.stations.getValue(id)
                        ListRow(onClick = { onPick(id) }) {
                            Text(st.name, style = HwType.body, color = hw.text, modifier = Modifier.weight(1f))
                            st.lines.filter { it != g.line }.forEach { LineTag(it) }
                            Text("$minutes min", style = HwType.body, color = hw.sub)
                        }
                    }
                },
                inset = 16.dp,
            )
        }
    }
}

@Composable
fun LineTag(line: Line) {
    val hw = LocalHw.current
    Text(
        line.label,
        style = HwType.caption.copy(fontWeight = FontWeight.SemiBold),
        color = hw.line(line),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(hw.line(line).copy(alpha = 0.16f)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** Options for a saved route. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteMenu(
    title: String,
    returnTitle: String,
    onReturn: () -> Unit,
    onTop: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hw = LocalHw.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { then() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = hw.bg,
        shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = hw.chev) },
    ) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = HwType.headline, color = hw.text, modifier = Modifier.align(Alignment.CenterHorizontally))
            val danger = Color(0xFFD70015)
            ListCard(
                listOf<@Composable () -> Unit>(
                    { MenuRow(Icons.Rounded.SwapVert, "Add the return trip", returnTitle, hw.text) { close(onReturn) } },
                    { MenuRow(Icons.Rounded.VerticalAlignTop, "Move to the top", null, hw.text) { close(onTop) } },
                    { MenuRow(Icons.Rounded.Delete, "Remove route", null, danger) { close(onRemove) } },
                ),
                inset = 50.dp,
            )
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, sub: String?, color: Color, onClick: () -> Unit) {
    ListRow(onClick = onClick) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        RowText(title, sub, titleColor = color, modifier = Modifier.weight(1f))
    }
}
