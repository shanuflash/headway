package com.flash.headway.ui

import androidx.compose.foundation.background
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import com.flash.headway.engine.Dir
import com.flash.headway.engine.WalkSpeed
import com.flash.headway.engine.lineDirKey
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw
import java.time.Instant

// ---------------- lines ----------------

@Composable
fun ColumnScope.LinesScreen(
    today: DayInfo,
    now: () -> Long,
    lineDelays: Map<String, Int>,
    onLineDelay: (String, Int) -> Unit,
    boards: Map<RouteId, LineBoard>,
    onPickStation: (RouteId) -> Unit,
    onDelayChip: (key: String, title: String) -> Unit,
    onSettings: () -> Unit,
) {
    val hw = LocalHw.current
    val spec = Timetable.day(today.type)
    val z = Instant.ofEpochMilli(now()).atZone(IST)
    val nowMin = z.hour * 60 + z.minute
    TopBar(right = { RoundButton(Icons.Rounded.Settings, "Settings", onSettings) })
    val reason = if (today.reason != "Weekday" && today.reason != "Saturday" && today.reason != "Sunday") ", ${today.reason}" else ""
    LargeTitle("Lines") {
        Text("${today.type.label} timetable$reason", style = HwType.body, color = hw.sub)
    }
    LineCard("Blue Line", RouteId.BLUE, hw.blue, spec.blue, nowMin, lineDelays, onLineDelay, boards[RouteId.BLUE], now, { onPickStation(RouteId.BLUE) }, onDelayChip)
    LineCard("Green Line", RouteId.GREEN, hw.green, spec.green, nowMin, lineDelays, onLineDelay, boards[RouteId.GREEN], now, { onPickStation(RouteId.GREEN) }, onDelayChip)
    // Green Line tracks, then Blue Line to the Airport.
    LineCard(
        "Inter-corridor", RouteId.INTER, hw.green, spec.inter, nowMin, lineDelays, onLineDelay, boards[RouteId.INTER], now, { onPickStation(RouteId.INTER) }, onDelayChip,
        " via CMBT", Brush.verticalGradient(listOf(hw.green, hw.blue)),
    )
    LineCard("Yellow Line", RouteId.YELLOW, hw.yellow, spec.yellow, nowMin, lineDelays, onLineDelay, boards[RouteId.YELLOW], now, { onPickStation(RouteId.YELLOW) }, onDelayChip)
    Footnote(
        (if (spec.loop != null) "Weekday peaks add short loops every 3 min, Washermenpet to Alandur. " else "") +
            "CMRL timetable, ${Timetable.PUBLISHED}.",
    )
}

@Composable
private fun LineCard(
    title: String,
    route: RouteId,
    color: Color,
    spec: LineSpec,
    nowMin: Int,
    delays: Map<String, Int>,
    onDelay: (String, Int) -> Unit,
    board: LineBoard?,
    now: () -> Long,
    onPickStation: () -> Unit,
    onDelayChip: (key: String, title: String) -> Unit,
    extra: String = "",
    bar: Brush? = null,
) {
    val hw = LocalHw.current
    val r = Network.routes.getValue(route)
    val a = r.fwd.first().first
    val b = r.fwd.last().first
    val open = nowMin >= minOf(spec.firstFwd, spec.firstRev) && nowMin <= maxOf(spec.lastFwd, spec.lastRev)
    val current = if (open) bandAt(spec.bands, nowMin) else null
    fun hhmm(m: Int) = minutesClock(m)
    var timetable by rememberSaveable(route) { mutableStateOf(false) }
    HwCard(gap = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (bar != null) {
                Box(Modifier.size(6.dp, 36.dp).clip(RoundedCornerShape(3.dp)).background(bar))
            } else {
                LineBar(color, width = 6.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = HwType.headline, color = hw.text)
                // How often trains run right now: what most people come here for.
                Text(
                    if (current != null) {
                        "Every ${current.headway} min now" + if (current.to < 24 * 60) " · until ${hhmm(current.to)}" else ""
                    } else {
                        "Closed · first train ${hhmm(minOf(spec.firstFwd, spec.firstRev))}"
                    },
                    style = HwType.footnote,
                    color = if (current != null) hw.tint else hw.sub,
                )
            }
        }
        if (board != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Departures from", style = HwType.subhead, color = hw.sub, modifier = Modifier.weight(1f))
                Row(
                    Modifier.height(32.dp).clip(CircleShape).background(hw.fill2).press(scaleTo = 0.95f, onClick = onPickStation).padding(start = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(Network.name(board.station), style = HwType.footnote.copy(fontWeight = FontWeight.SemiBold), color = hw.text, maxLines = 1)
                    Icon(Icons.Rounded.UnfoldMore, "Change station", tint = hw.sub, modifier = Modifier.size(16.dp))
                }
            }
            // A direction that ends at this station has nothing to show.
            listOf(Dir.FWD to b, Dir.REV to a).filter { (_, end) -> end != board.station }.forEach { (dir, end) ->
                val key = lineDirKey(route, dir)
                val dirTitle = "Towards ${Network.name(end)}"
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(dirTitle, style = HwType.subheadStrong, color = hw.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(nextText(board, dir, end, now()), style = HwType.footnote, color = hw.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DelayChip(delays[key] ?: 0) { onDelayChip(key, "$title · $dirTitle") }
                }
            }
        }
        Row(
            Modifier.clip(CircleShape).press(scaleTo = 0.97f) { timetable = !timetable }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(if (timetable) "Hide timetable" else "Timetable", style = HwType.subheadStrong, color = hw.sub)
            Icon(if (timetable) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = hw.sub, modifier = Modifier.size(20.dp))
        }
        if (timetable) {
            Text("${Network.name(a)} to ${Network.name(b)}$extra", style = HwType.footnote, color = hw.sub)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text("From", style = HwType.footnote, color = hw.sub, modifier = Modifier.weight(1f))
                    Text("First", style = HwType.footnote, color = hw.sub, modifier = Modifier.padding(end = 18.dp))
                    Text("Last", style = HwType.footnote, color = hw.sub)
                }
                listOf(Triple(a, spec.firstFwd, spec.lastFwd), Triple(b, spec.firstRev, spec.lastRev)).forEach { (st, first, last) ->
                    Row {
                        Text(Network.name(st), style = HwType.subhead, color = hw.text, modifier = Modifier.weight(1f))
                        Text(hhmm(first), style = HwType.subhead, color = hw.text, modifier = Modifier.padding(end = 18.dp))
                        Text(hhmm(last), style = HwType.subhead, color = hw.text)
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
}

/** A direction's delay as a small chip: "On time", "+2 min" (late) or "−2 min" (early). Tap to change it. */
@Composable
private fun DelayChip(value: Int, onClick: () -> Unit) {
    val hw = LocalHw.current
    val (fg, bg) = when {
        value > 0 -> hw.warn to hw.warnBg
        value < 0 -> hw.info to hw.infoBg
        else -> hw.sub to hw.fill2
    }
    Text(
        when {
            value > 0 -> "+$value min"
            value < 0 -> "−${-value} min"
            else -> "On time"
        },
        style = HwType.footnote.copy(fontWeight = FontWeight.SemiBold),
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .press(scaleTo = 0.94f, onClick = onClick)
            .semantics { contentDescription = "Delay, ${delayText(value)}. Edit" }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** One direction's delay: a readout, a stepper and slider, and back to on time. */
@Composable
fun LineDelaySheet(title: String, value: Int, onChange: (Int) -> Unit, onDismiss: () -> Unit) {
    val hw = LocalHw.current
    val tone = when {
        value > 0 -> hw.warn
        value < 0 -> hw.tint
        else -> hw.sub
    }
    HwSheet(onDismiss = onDismiss) { _ ->
        Column(
            Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.align(Alignment.CenterHorizontally), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Delay", style = HwType.headline, color = hw.text)
                Text(title, style = HwType.footnote, color = hw.sub)
            }
            Column(
                Modifier.fillMaxWidth().clip(CardShape).background(hw.card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(delayText(value), style = HwType.title, color = tone)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StepButton(Icons.Rounded.Remove, "1 min earlier", enabled = value > -com.flash.headway.app.DELAY_MAX) { onChange(value - 1) }
                    DelaySlider(value, tone, onChange, Modifier.weight(1f))
                    StepButton(Icons.Rounded.Add, "1 min later", enabled = value < com.flash.headway.app.DELAY_MAX) { onChange(value + 1) }
                }
                if (value != 0) {
                    Text(
                        "Reset to on time",
                        style = HwType.subheadStrong,
                        color = hw.tint,
                        modifier = Modifier.clip(CircleShape).press { onChange(0) }.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Footnote("Applies to every train in this direction: route cards, trips, the widget and countdowns.")
        }
    }
}

/** One line's next trains at a station, toward each end: shown times (line delays applied). */
data class LineBoard(val station: String, val byDir: Map<Dir, List<Long>>)

/** "8:42 AM · 3 min, then 8:48": the next trains toward [end], or why there are none. */
private fun nextText(board: LineBoard, dir: Dir, end: String, now: Long): String {
    if (board.station == end) return "Last stop"
    val times = board.byDir[dir].orEmpty().filter { it >= now - 30_000 }
    val first = times.firstOrNull() ?: return "No more trains today"
    val left = first - now
    val wait = when {
        left < 30_000 -> "now"
        left < 60 * 60_000 -> "in ${mins(left)} min"
        else -> null
    }
    val then = times.getOrNull(1)?.let { ", then ${clockTime(it)}" }.orEmpty()
    return "${wait?.let { "${it.replaceFirstChar { c -> c.uppercase() }} · " }.orEmpty()}${clock(first)}$then"
}

// ---------------- settings ----------------

class SettingsActions(
    val back: () -> Unit,
    val override: (DayType?) -> Unit,
    val count: (Int) -> Unit,
    val lastWarn: (Boolean) -> Unit,
    val walk: (WalkSpeed) -> Unit,
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
    SectionTitle("Timetable")
    SettingsCard {
        Text("Today", style = HwType.body, color = hw.text)
        Segmented(
            options = listOf(null to "Auto", DayType.WEEKDAY to "Weekday", DayType.SATURDAY to "Saturday", DayType.SUNDAY to "Holiday"),
            selected = overrideType,
            onSelect = actions.override,
        )
    }
    Footnote("Auto follows the calendar and public holidays (today: ${today.reason}). Choose Holiday when CMRL runs a Sunday timetable.")

    SectionTitle("Trips")
    SettingsCard {
        Text("Departures per route", style = HwType.body, color = hw.text)
        Segmented(
            options = com.flash.headway.app.COUNT_CHOICES.map { it to "$it" },
            selected = prefs.count,
            onSelect = actions.count,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Last-train warning", style = HwType.body, color = hw.text)
                Text("Shown in the final hour of service", style = HwType.footnote, color = hw.sub)
            }
            HwSwitch(prefs.lastWarn, actions.lastWarn, "Last-train warning")
        }
    }

    SettingsCard {
        Text("Walking speed", style = HwType.body, color = hw.text)
        Segmented(
            options = WalkSpeed.entries.map { it to it.label },
            selected = prefs.walk,
            onSelect = actions.walk,
        )
    }
    Footnote("Sets the time to change trains. Fast takes tighter connections.")

    if (!notificationsAllowed) {
        SectionTitle("Notifications")
        val rows = ArrayList<@Composable () -> Unit>()
        run {
            rows.add {
                ListRow(onClick = actions.allowNotifications) {
                    RowText("Allow notifications", "Required for countdowns", modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = hw.chev, modifier = Modifier.size(20.dp))
                }
            }
        }
        ListCard(rows, inset = 16.dp)
    }

    SectionTitle("Appearance")
    SettingsCard {
        Text("Theme", style = HwType.body, color = hw.text)
        Segmented(
            options = listOf(ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark", ThemeMode.AUTO to "System"),
            selected = prefs.theme,
            onSelect = actions.theme,
        )
    }

    SectionTitle("Metro card")
    MetroCardSettings()

    SectionTitle("About")


    ListCard(
        listOf<@Composable () -> Unit>({
            ListRow(onClick = actions.openTimetable) {
                Text("Official timetable", style = HwType.body, color = hw.text, modifier = Modifier.weight(1f))
                Text(Timetable.PUBLISHED, style = HwType.subhead, color = hw.sub)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = hw.chev, modifier = Modifier.size(20.dp))
            }
        }),
    )
    Footnote("First and last trains are from CMRL’s timetable. Times at other stations are estimates (±2 min).")

    // For testing only, so it stays folded away.
    var developer by rememberSaveable { mutableStateOf(prefs.mockAt != null) }
    ListCard(
        listOf<@Composable () -> Unit>({
            ListRow(onClick = { developer = !developer }) {
                Text("Developer", style = HwType.body, color = hw.text, modifier = Modifier.weight(1f))
                Icon(if (developer) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = hw.chev, modifier = Modifier.size(20.dp))
            }
        }),
    )
    if (developer) {
        SettingsCard {
            Text("Mock time", style = HwType.body, color = hw.text)
            Segmented(
                options = listOf<Pair<Int?, String>>(null to "Off") + com.flash.headway.app.MOCK_CHOICES,
                selected = prefs.mockAt,
                onSelect = actions.mock,
            )
        }
        Footnote("For testing. Runs the app from the chosen time.")
    }
}

/** The card's UPI ID, for "Pay by UPI" top-ups. Stays on this phone. */
@Composable
private fun MetroCardSettings() {
    val hw = LocalHw.current
    val context = LocalContext.current
    var text by remember { mutableStateOf(com.flash.headway.app.TopUp.upiId(context).orEmpty()) }
    var bad by remember { mutableStateOf(false) }
    SettingsCard {
        Text("UPI ID", style = HwType.body, color = hw.text)
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(hw.fill).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    bad = !com.flash.headway.app.TopUp.setUpiId(context, it)
                },
                singleLine = true,
                textStyle = HwType.subhead.copy(color = hw.text),
                cursorBrush = SolidColor(hw.tint),
                modifier = Modifier.weight(1f).padding(end = 8.dp).clipToBounds(),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text("ncmc.1234…@mairtel", style = HwType.subhead, color = hw.sub)
                        inner()
                    }
                },
            )
            Text(
                "Scan QR",
                style = HwType.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
                modifier = Modifier.clip(CircleShape).background(hw.tint).press {
                    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                    GmsBarcodeScanning.getClient(context, options).startScan()
                        .addOnSuccessListener { code ->
                            val id = com.flash.headway.app.TopUp.upiIdFromQr(code.rawValue)
                            if (id != null && com.flash.headway.app.TopUp.setUpiId(context, id)) {
                                text = id
                                bad = false
                            } else {
                                Toast.makeText(context, "Not a metro card top-up QR", Toast.LENGTH_LONG).show()
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(context, "Scanner unavailable. Enter the ID instead.", Toast.LENGTH_LONG).show()
                        }
                }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Text(
            if (bad) "Enter a valid UPI ID" else "From the “Scan to recharge” QR on the card. Enables top-up by UPI.",
            style = HwType.footnote,
            color = if (bad) hw.warn else hw.sub,
        )
    }
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
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
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

/** Which station a line card's next trains are for: every station on the line, in order. */
@Composable
fun LineStationSheet(title: String, route: RouteId, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val hw = LocalHw.current
    val stations = remember(route) { Network.routes.getValue(route).fwd.map { it.first } }
    HwSheet(onDismiss = onDismiss) { close ->
        Column(Modifier.align(Alignment.CenterHorizontally).padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Departures from", style = HwType.headline, color = hw.text)
            Text(title, style = HwType.footnote, color = hw.sub)
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 20.dp),
        ) {
            ListCard(
                stations.map { id ->
                    @Composable {
                        ListRow(onClick = { close { onPick(id) } }) {
                            Text(Network.name(id), style = HwType.body, color = hw.text, modifier = Modifier.weight(1f))
                            if (id == selected) Icon(Icons.Rounded.Check, "Selected", tint = hw.tint, modifier = Modifier.size(20.dp))
                        }
                    }
                },
                inset = 16.dp,
            )
        }
    }
}
