package com.flash.headway.ui

import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flash.headway.engine.MINUTE
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.flash.headway.app.DELAY_MAX
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw
import kotlin.math.abs

/** A train on a trip with changes, for its delay row: [key] is its line and direction ([com.flash.headway.engine.lineDirKey]). */
data class TripLine(val key: String, val name: String, val colors: List<Color>, val boards: String)

/**
 * One train's own delay: "The 8:13 AM train", on every day of its kind ([days], e.g. "Every
 * weekday") or just today. [own] is its delay, or null while it follows the route's
 * [routeDelay]. [onChange] sets it (minutes, just today?); null minutes follow the route again.
 * A trip with changes has no single delay: [lines] lists each of its trains' line and direction
 * instead, with that delay (as on the Lines tab), and the one-train controls are left out.
 */
@Composable
fun TrainDelaySheet(
    key: String,
    title: String,
    subtitle: String,
    days: String,
    own: Int?,
    ownToday: Boolean,
    routeDelay: Int,
    onChange: (Int?, Boolean) -> Unit,
    onDismiss: () -> Unit,
    lines: List<TripLine> = emptyList(),
    lineDelays: Map<String, Int> = emptyMap(),
    onLineDelay: (String, Int) -> Unit = { _, _ -> },
    /** A direct train's line and direction: its delay adds to this train's, so it's shown here too. */
    line: TripLine? = null,
    /** The train's timetabled departure, to show the time it'll have on the card. */
    at: Long? = null,
) {
    val hw = LocalHw.current
    // Its own value while dragging, so the thumb never waits on the saved setting.
    var d by remember(key) { mutableIntStateOf(own ?: routeDelay) }
    var following by remember(key) { mutableStateOf(own == null) }
    var today by remember(key) { mutableStateOf(ownToday) }
    val tone by animateColorAsState(
        when {
            d > 0 -> hw.warn
            d < 0 -> hw.tint
            else -> hw.sub
        },
        label = "train delay tone",
    )
    fun set(v: Int) {
        val c = v.coerceIn(-DELAY_MAX, DELAY_MAX)
        if (c == d && !following) return
        d = c
        following = false
        onChange(c, today)
    }

    HwSheet(onDismiss = onDismiss) { close ->
        // The heading stays put; everything under it scrolls on a short screen.
        Column(Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 16.dp).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = HwType.headline, color = hw.text)
            Text(subtitle, style = HwType.footnote, color = hw.sub)
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (lines.isNotEmpty()) {
                JourneyDelays(lines, lineDelays, onLineDelay)
            } else {
                // For every day like this one, or just today (a one-off: it's dropped tomorrow).
                Segmented(
                    options = listOf(false to days, true to "Today only"),
                    selected = today,
                    onSelect = {
                        today = it
                        if (!following) onChange(d, it)
                    },
                )
                Column(
                    Modifier.fillMaxWidth().clip(CardShape).background(hw.card).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                        if (d == 0) {
                            Text("On time", style = HwType.title, color = tone)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${abs(d)}", style = HwType.title.copy(fontSize = 44.sp, lineHeight = 52.sp), color = tone, modifier = Modifier.alignByBaseline())
                                Text(if (d > 0) "min late" else "min early", style = HwType.headline, color = tone, modifier = Modifier.alignByBaseline())
                            }
                        }
                    }
                    // What the card will show: this train's delay plus its line's, from the timetable.
                    val lineD = line?.let { lineDelays[it.key] } ?: 0
                    if (at != null) {
                        Text(
                            "Expected ${clock(at + (d + lineD) * MINUTE)}" +
                                if (lineD != 0) " · includes line delay" else "",
                            style = HwType.footnote,
                            color = hw.sub,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StepButton(Icons.Rounded.Remove, "1 min earlier", enabled = d > -DELAY_MAX) { set(d - 1) }
                        DelaySlider(d, tone, ::set, Modifier.weight(1f))
                        StepButton(Icons.Rounded.Add, "1 min later", enabled = d < DELAY_MAX) { set(d + 1) }
                    }
                    Text(
                        if (!following) {
                            "This train only${if (today) ", today" else ""}. Route delay: ${delayText(routeDelay).lowercase()}."
                        } else {
                            "Uses the route delay (${delayText(routeDelay).lowercase()})."
                        },
                        style = HwType.footnote,
                        color = hw.sub,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    if (!following) {
                        Row(
                            Modifier
                                .padding(top = 4.dp)
                                .height(40.dp)
                                .clip(RoundedCornerShapeFull)
                                .background(hw.tintSoft)
                                .press(scaleTo = 0.96f) {
                                    d = routeDelay
                                    following = true
                                    onChange(null, today)
                                }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Rounded.History, null, tint = hw.tint, modifier = Modifier.size(18.dp))
                            Text("Use route delay", style = HwType.subhead.copy(fontWeight = FontWeight.SemiBold), color = hw.tint)
                        }
                    }
                }
                if (line != null) {
                    // The whole line in this direction, as on the Lines tab: it moves this train too.
                    Column(
                        Modifier.fillMaxWidth().clip(CardShape).background(hw.card).padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LineDelayRow(line.name, "All trains in this direction", line.colors, lineDelays[line.key] ?: 0) { onLineDelay(line.key, it) }
                    }
                }
            }
        }
    }
}

/** A trip with changes: each train's line and direction, with its delay. */
@Composable
fun JourneyDelays(lines: List<TripLine>, lineDelays: Map<String, Int>, onLineDelay: (String, Int) -> Unit) {
    val hw = LocalHw.current
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(hw.button).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LeadIcon(androidx.compose.material.icons.Icons.Rounded.Schedule, hw.fill2, hw.sub)
            Column(Modifier.weight(1f)) {
                Text("Delay", style = HwType.headline, color = hw.text)
                Text("Each train uses its line’s delay, on every route", style = HwType.footnote, color = hw.sub)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            lines.forEach { l -> LineDelayRow(l.name, l.boards, l.colors, lineDelays[l.key] ?: 0) { onLineDelay(l.key, it) } }
        }
    }
}

private val RoundedCornerShapeFull = androidx.compose.foundation.shape.CircleShape
