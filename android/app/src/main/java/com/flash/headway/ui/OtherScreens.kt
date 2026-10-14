package com.flash.headway.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flash.headway.app.Prefs
import com.flash.headway.app.ThemeMode
import com.flash.headway.data.BandKind
import com.flash.headway.data.DayType
import com.flash.headway.data.Line
import com.flash.headway.data.LineSpec
import com.flash.headway.data.Network
import com.flash.headway.data.RouteId
import com.flash.headway.data.Timetable
import com.flash.headway.engine.DayInfo
import com.flash.headway.engine.IST
import com.flash.headway.engine.bandAt
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw
import java.time.Instant

// ---------------- lines ----------------

@Composable
fun ColumnScope.LinesScreen(today: DayInfo, now: Long, onSettings: () -> Unit) {
    val hw = LocalHw.current
    val spec = Timetable.day(today.type)
    val z = Instant.ofEpochMilli(now).atZone(IST)
    val nowMin = z.hour * 60 + z.minute
    TopBar(right = { RoundButton(Icons.Rounded.MoreHoriz, "Settings", onSettings) })
    val reason = if (today.reason != "Weekday" && today.reason != "Saturday" && today.reason != "Sunday") ", ${today.reason}" else ""
    LargeTitle("Lines") {
        Text("${today.type.label} timetable$reason", style = HwType.body, color = hw.sub)
    }
    LineCard("Blue Line", RouteId.BLUE, hw.blue, spec.blue, nowMin)
    LineCard("Green Line", RouteId.GREEN, hw.green, spec.green, nowMin)
    // Green Line tracks, then Blue Line to the Airport.
    LineCard("Inter-corridor", RouteId.INTER, hw.green, spec.inter, nowMin, " via CMBT", Brush.verticalGradient(listOf(hw.green, hw.blue)))
    LineCard("Yellow Line", RouteId.YELLOW, hw.yellow, spec.yellow, nowMin)
    Footnote(
        (if (spec.loop != null) "Weekday peaks add short-loop trains between Washermenpet and Alandur, so that stretch sees a train every 3 min. " else "") +
            "Source: CMRL timetable, ${Timetable.PUBLISHED}.",
    )
}

@Composable
private fun LineCard(title: String, route: RouteId, color: Color, spec: LineSpec, nowMin: Int, extra: String = "", bar: Brush? = null) {
    val hw = LocalHw.current
    val r = Network.routes.getValue(route)
    val a = r.fwd.first().first
    val b = r.fwd.last().first
    val open = nowMin >= minOf(spec.firstFwd, spec.firstRev) && nowMin <= maxOf(spec.lastFwd, spec.lastRev)
    val current = if (open) bandAt(spec.bands, nowMin) else null
    fun hhmm(m: Int) = minutesClock(m)
    HwCard(gap = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (bar != null) {
                Box(Modifier.size(6.dp, 36.dp).clip(RoundedCornerShape(3.dp)).background(bar))
            } else {
                LineBar(color, width = 6.dp)
            }
            RowText(title, "${Network.name(a)} to ${Network.name(b)}$extra", titleStyle = HwType.headline)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text("From", style = HwType.footnote, color = hw.sub, modifier = Modifier.weight(1f))
                Text("First", style = HwType.footnote, color = hw.sub, modifier = Modifier.padding(end = 18.dp))
                Text("Last", style = HwType.footnote, color = hw.sub)
            }
            listOf(Triple(a, spec.firstFwd, spec.lastFwd), Triple(b, spec.firstRev, spec.lastRev)).forEach { (s, f, l) ->
                Row {
                    Text(Network.name(s), style = HwType.subhead, color = hw.text, modifier = Modifier.weight(1f))
                    Text(hhmm(f), style = HwType.subhead, color = hw.text, modifier = Modifier.padding(end = 18.dp))
                    Text(hhmm(l), style = HwType.subhead, color = hw.text)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            spec.bands.forEach { band ->
                val on = band == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) hw.selected else Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    val to = if (band.to >= 24 * 60) "close" else hhmm(band.to)
                    val peak = if (band.kind == BandKind.PEAK) ", peak" else ""
                    val style = if (on) HwType.subheadStrong else HwType.subhead
                    val c = if (on) hw.tint else hw.text
                    Text("${hhmm(band.from)} to $to$peak", style = style, color = c, modifier = Modifier.weight(1f))
                    Text("every ${band.headway} min", style = style, color = c)
                }
            }
        }
    }
}

// ---------------- settings ----------------

class SettingsActions(
    val back: () -> Unit,
    val override: (DayType?) -> Unit,
    val count: (Int) -> Unit,
    val lastWarn: (Boolean) -> Unit,
    val theme: (ThemeMode) -> Unit,
    val mock: (Int?) -> Unit,
    val allowNotifications: () -> Unit,
    val openTimetable: () -> Unit,
)

@Composable
fun ColumnScope.SettingsScreen(
    prefs: Prefs,
    today: DayInfo,
    notificationsAllowed: Boolean,
    actions: SettingsActions,
) {
    val hw = LocalHw.current
    TopBar(right = { RoundButton(Icons.Rounded.Check, "Done", actions.back, tint = Color.White, background = hw.tint) })
    LargeTitle("Settings")

    val overrideType = prefs.override?.takeIf { it.date == today.date }?.type
    SettingsCard {
        Text("Today's timetable", style = HwType.body, color = hw.text)
        Segmented(
            options = listOf(null to "Auto", DayType.WEEKDAY to "Weekday", DayType.SATURDAY to "Saturday", DayType.SUNDAY to "Holiday"),
            selected = overrideType,
            onSelect = actions.override,
        )
    }
    Footnote("Auto follows the calendar and public holidays (${today.reason}). Pick Holiday if CMRL announces a Sunday timetable for today.")

    SettingsCard {
        Text("Trains shown per route", style = HwType.body, color = hw.text)
        Segmented(
            options = com.flash.headway.app.COUNT_CHOICES.map { it to "$it" },
            selected = prefs.count,
            onSelect = actions.count,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Last train warning", style = HwType.body, color = hw.text)
                Text("In the final hour of service", style = HwType.footnote, color = hw.sub)
            }
            HwSwitch(prefs.lastWarn, actions.lastWarn, "Last train warning")
        }
    }

    if (!notificationsAllowed) {
        val rows = ArrayList<@Composable () -> Unit>()
        if (!notificationsAllowed) {
            rows.add {
                ListRow(onClick = actions.allowNotifications) {
                    RowText("Allow notifications", "Needed for the train countdown", modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = hw.chev, modifier = Modifier.size(20.dp))
                }
            }
        }
        ListCard(rows, inset = 16.dp)
    }

    SettingsCard {
        Text("Appearance", style = HwType.body, color = hw.text)
        Segmented(
            options = listOf(ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark", ThemeMode.AUTO to "Automatic"),
            selected = prefs.theme,
            onSelect = actions.theme,
        )
    }

    SettingsCard {
        Text("Mock time", style = HwType.body, color = hw.text)
        Segmented(
            options = listOf<Pair<Int?, String>>(null to "Off") + com.flash.headway.app.MOCK_CHOICES,
            selected = prefs.mockAt,
            onSelect = actions.mock,
        )
    }
    Footnote("For testing. The app runs as if it were that time now, and the clock keeps ticking from there.")

    ListCard(
        listOf<@Composable () -> Unit>({
            ListRow(onClick = actions.openTimetable) {
                Text("Official timetable", style = HwType.body, color = hw.text, modifier = Modifier.weight(1f))
                Text(Timetable.PUBLISHED, style = HwType.subhead, color = hw.sub)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = hw.chev, modifier = Modifier.size(20.dp))
            }
        }),
    )
    Footnote("First and last trains match CMRL's timetable. Times at stations in between are estimated, so allow a minute or two either way.")
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(LocalHw.current.card).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val hw = LocalHw.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(CircleShape)
            .background(hw.fill)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            val thumb = if (hw.dark) Brush.verticalGradient(listOf(Color(0xFF7A7A80), Color(0xFF5E5E63))) else Brush.verticalGradient(listOf(Color.White, Color.White))
            Box(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .then(if (on) Modifier.shadow(if (hw.dark) 4.dp else 3.dp, CircleShape) else Modifier)
                    .clip(CircleShape)
                    .then(if (on) Modifier.background(thumb).edge(CircleShape) else Modifier)
                    .press(scaleTo = 0.97f) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = HwType.subhead.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                    color = hw.text,
                )
            }
        }
    }
}

@Composable
private fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val hw = LocalHw.current
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(hw.fill).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp, 32.dp).clip(RoundedCornerShape(8.dp)).press(onClick = onMinus), contentAlignment = Alignment.Center) {
            Text("−", style = HwType.section.copy(fontWeight = FontWeight.Normal), color = hw.text)
        }
        Text(value, style = HwType.subheadStrong, color = hw.text, modifier = Modifier.padding(horizontal = 8.dp))
        Box(Modifier.size(40.dp, 32.dp).clip(RoundedCornerShape(8.dp)).press(onClick = onPlus), contentAlignment = Alignment.Center) {
            Text("+", style = HwType.section.copy(fontWeight = FontWeight.Normal), color = hw.text)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ThemeOption(label: String, on: Boolean, background: Brush, bar: Color, onClick: () -> Unit) {
    val hw = LocalHw.current
    Column(
        Modifier.weight(1f).press(scaleTo = 0.97f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(background)
                .border(if (on) 2.5.dp else 0.5.dp, if (on) hw.tint else hw.sep, RoundedCornerShape(14.dp))
                .padding(8.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(7.dp)).background(bar))
        }
        Text(
            label,
            style = HwType.footnote.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
            color = if (on) hw.tint else hw.text,
        )
    }
}
