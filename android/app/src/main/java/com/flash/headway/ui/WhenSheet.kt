package com.flash.headway.ui

import android.app.TimePickerDialog
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flash.headway.app.Clock
import com.flash.headway.app.PlanMode
import com.flash.headway.app.PlanTime
import com.flash.headway.engine.IST
import com.flash.headway.engine.MINUTE
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw
import java.time.Instant
import java.time.LocalTime

/** What the sheet is set to: now, or leave at / arrive by a time today or tomorrow. */
private enum class WhenMode { NOW, LEAVE, ARRIVE }

/** That day (0 today, 1 tomorrow) at [minutes] past midnight, in IST. */
private fun whenAt(day: Int, minutes: Int): Long =
    Instant.ofEpochMilli(Clock.now()).atZone(IST).toLocalDate().plusDays(day.toLong())
        .atTime(LocalTime.of(minutes / 60, minutes % 60)).atZone(IST).toInstant().toEpochMilli()

/**
 * "When?": plan a quick look for another time. Leave at or arrive by a time, today or tomorrow;
 * a time that's already gone today moves to tomorrow.
 */
@Composable
fun WhenSheet(title: String, plan: PlanTime?, onDone: (PlanTime?) -> Unit, onDismiss: () -> Unit) {
    val hw = LocalHw.current
    val context = LocalContext.current
    val start = remember {
        val now = Clock.now()
        val base = plan?.at ?: ((now + 15 * MINUTE + 15 * MINUTE - 1) / (15 * MINUTE) * (15 * MINUTE))
        val z = Instant.ofEpochMilli(base).atZone(IST)
        val today = Instant.ofEpochMilli(now).atZone(IST).toLocalDate()
        Triple(z.hour * 60 + z.minute, if (z.toLocalDate().isAfter(today)) 1 else 0, base)
    }
    var mode by remember { mutableStateOf(if (plan?.mode == PlanMode.ARRIVE) WhenMode.ARRIVE else WhenMode.LEAVE) }
    var day by remember { mutableIntStateOf(start.second) }
    var minutes by remember { mutableIntStateOf(start.first) }
    // The open time picker, closed with the sheet (and on rotation, so its window doesn't leak).
    var picker by remember { mutableStateOf<TimePickerDialog?>(null) }
    DisposableEffect(Unit) { onDispose { picker?.dismiss() } }

    HwSheet(onDismiss = onDismiss) { close ->
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.align(Alignment.CenterHorizontally), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Plan a trip", style = HwType.headline, color = hw.text)
                Text(title, style = HwType.footnote, color = hw.sub)
            }
            Column(
                Modifier.fillMaxWidth().clip(CardShape).background(hw.card).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Segmented(
                    options = listOf(WhenMode.NOW to "Now", WhenMode.LEAVE to "Depart at", WhenMode.ARRIVE to "Arrive by"),
                    selected = mode,
                    onSelect = { mode = it },
                )
                if (mode == WhenMode.NOW) {
                    Text("Live departures from now.", style = HwType.footnote, color = hw.sub)
                } else {
                    Segmented(options = listOf(0 to "Today", 1 to "Tomorrow"), selected = day, onSelect = { day = it })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (mode == WhenMode.ARRIVE) "Arrive by" else "Depart at", style = HwType.body, color = hw.text, modifier = Modifier.weight(1f))
                        Box(
                            Modifier
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(hw.fill)
                                .press {
                                    // In the app's light or dark look, and 12-hour like every time in the app.
                                    picker?.dismiss()
                                    picker = TimePickerDialog(
                                        context,
                                        if (hw.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert,
                                        { _, h, m ->
                                            minutes = h * 60 + m
                                            // A time that's already gone today means tomorrow.
                                            if (day == 0 && whenAt(0, minutes) < Clock.now()) day = 1
                                        },
                                        minutes / 60,
                                        minutes % 60,
                                        false,
                                    ).also { it.show() }
                                }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(minutesLabel(minutes), style = HwType.body.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = hw.tint)
                                Icon(Icons.Rounded.Schedule, null, tint = hw.sub, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            PrimaryButton(
                "Show trains",
                if (mode == WhenMode.NOW) Icons.Rounded.Schedule else Icons.Rounded.Search,
            ) {
                val result = when (mode) {
                    WhenMode.NOW -> null
                    WhenMode.LEAVE -> PlanTime(PlanMode.LEAVE, whenAt(day, minutes))
                    WhenMode.ARRIVE -> PlanTime(PlanMode.ARRIVE, whenAt(day, minutes))
                }
                close { onDone(result) }
            }
        }
    }
}

/** "Leave at 8:00 AM tomorrow", "Arrive by 9:30 AM", or "Leaving now". */
fun whenText(plan: PlanTime?): String {
    if (plan == null) return "Depart now"
    val today = Instant.ofEpochMilli(Clock.now()).atZone(IST).toLocalDate()
    val date = Instant.ofEpochMilli(plan.at).atZone(IST).toLocalDate()
    val day = when (date) {
        today -> ""
        today.plusDays(1) -> " tomorrow"
        else -> " on ${date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}"
    }
    return "${if (plan.mode == PlanMode.ARRIVE) "Arrive by" else "Depart at"} ${clock(plan.at)}$day"
}
