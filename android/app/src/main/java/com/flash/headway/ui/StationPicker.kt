package com.flash.headway.ui

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
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flash.headway.data.Network
import com.flash.headway.engine.Journey
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw
import kotlinx.coroutines.launch

enum class PickField { FROM, TO }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationPicker(
    asking: PickField,
    origin: String?,
    current: String?,
    recents: List<String>,
    plans: Map<String, Journey?>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val hw = LocalHw.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }

    // Let the sheet slide away before the trip changes underneath it.
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
        Column(Modifier.fillMaxHeight(0.92f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(Icons.Rounded.Close, "Close", { close(onDismiss) }, background = hw.card)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (asking == PickField.FROM) "Starting from" else "Going to", style = HwType.headline, color = hw.text)
                    if (origin != null) Text("from ${Network.name(origin)}", style = HwType.footnote, color = hw.sub)
                }
                Spacer(Modifier.size(44.dp))
            }

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
                    onValueChange = { query = it },
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

            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val q = query.trim().lowercase()
                val matches = { id: String ->
                    val st = Network.stations.getValue(id)
                    q.isEmpty() || st.name.lowercase().contains(q) || st.long.orEmpty().lowercase().contains(q)
                }
                val pick = { id: String -> close { onPick(id) } }

                if (q.isEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(Triple("central", Icons.Rounded.Apartment, "Central"), Triple("airport", Icons.Rounded.Flight, "Airport"))
                            .filter { it.first != origin }
                            .forEach { (id, icon, label) ->
                                Shortcut(icon, label, plans[id]?.let { span(it.arr - it.dep) } ?: Network.name(id), Modifier.weight(1f)) { pick(id) }
                            }
                    }
                    val recent = recents.filter { it != origin }.distinct().take(4)
                    if (recent.isNotEmpty()) {
                        SectionTitle("Recent")
                        ListCard(recent.map { id -> @Composable { StationRow(id, null, origin, current, plans[id]) { pick(id) } } }, inset = 16.dp)
                    }
                }

                Network.pickerGroups.forEach { (line, ids) ->
                    val shown = ids.filter(matches)
                    if (shown.isNotEmpty()) {
                        SectionTitle(line.label)
                        ListCard(shown.map { id -> @Composable { StationRow(id, line, origin, current, plans[id]) { pick(id) } } }, inset = 16.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Shortcut(icon: ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    val hw = LocalHw.current
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(hw.card)
            .press(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BadgeIcon(icon, hw.fill, tint = hw.text, size = 36.dp)
        Column {
            Text(title, style = HwType.subheadStrong, color = hw.text)
            Text(sub, style = HwType.footnote, color = hw.sub, maxLines = 1)
        }
    }
}

@Composable
private fun StationRow(
    id: String,
    groupLine: com.flash.headway.data.Line?,
    origin: String?,
    current: String?,
    plan: Journey?,
    onClick: () -> Unit,
) {
    val hw = LocalHw.current
    val st = Network.stations.getValue(id)
    ListRow(onClick = onClick) {
        Column(Modifier.weight(1f)) {
            Text(
                st.name,
                style = if (id == current) HwType.body.copy(fontWeight = FontWeight.SemiBold) else HwType.body,
                color = if (id == current) hw.tint else hw.text,
            )
            val sub = plan?.let { changesText(it) } ?: st.long
            if (!sub.isNullOrEmpty()) Text(sub, style = HwType.footnote, color = hw.sub)
        }
        st.lines.filter { it != groupLine && groupLine != null }.forEach { line ->
            Text(
                line.label,
                style = HwType.caption.copy(fontWeight = FontWeight.SemiBold),
                color = hw.line(line),
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(hw.line(line).copy(alpha = 0.16f)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        when {
            plan != null -> Text(span(plan.arr - plan.dep), style = HwType.body, color = hw.sub)
            id == origin -> Text("Starting here", style = HwType.body, color = hw.sub)
        }
    }
}
