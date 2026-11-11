package com.flash.headway.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flash.headway.app.DELAY_MAX
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** The next train at some delay: its timetabled departure and the time the card will show. */
data class DelayPreview(val timetabled: Long, val shown: Long)

private val ThumbRadius = 14.dp
private val StepSize = 36.dp
private val StepGap = 6.dp

/**
 * Route-menu card: how early or late this route's trains run, from [DELAY_MAX] early to
 * [DELAY_MAX] late. The card keeps its own value while the sheet is open, so the slider never
 * waits on the saved preference; [onDelay] saves each change.
 */
@Composable
fun DelayCard(routeId: String, delay: Int, preview: (Int) -> DelayPreview?, onDelay: (Int) -> Unit) {
    val hw = LocalHw.current
    var d by remember(routeId) { mutableIntStateOf(delay) }
    fun change(v: Int) {
        val c = v.coerceIn(-DELAY_MAX, DELAY_MAX)
        if (c != d) {
            d = c
            onDelay(c)
        }
    }
    val tone by animateColorAsState(
        when {
            d > 0 -> hw.warn
            d < 0 -> hw.tint
            else -> hw.sub
        },
        label = "delay tone",
    )
    val toneBg by animateColorAsState(
        when {
            d > 0 -> hw.warnBg
            d < 0 -> hw.tintSoft
            else -> hw.fill2
        },
        label = "delay tone bg",
    )

    Column(Modifier.fillMaxWidth().clip(CardShape).background(hw.card).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(toneBg), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Schedule, null, tint = tone, modifier = Modifier.size(20.dp))
            }
            Column {
                Text("Running early or late?", style = HwType.headline, color = hw.text)
                Text("Shift this route's train times", style = HwType.footnote, color = hw.sub)
            }
        }

        // The big readout: "3 min late", or "On time".
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp), contentAlignment = Alignment.Center) {
            if (d == 0) {
                Text("On time", style = HwType.title, color = tone)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${abs(d)}",
                        style = HwType.title.copy(fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = (-1).sp),
                        color = tone,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Text(
                        if (d > 0) "min late" else "min early",
                        style = HwType.headline,
                        color = tone,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StepGap)) {
            StepButton(Icons.Rounded.Remove, "One minute earlier", enabled = d > -DELAY_MAX) { change(d - 1) }
            DelaySlider(d, tone, ::change, Modifier.weight(1f))
            StepButton(Icons.Rounded.Add, "One minute later", enabled = d < DELAY_MAX) { change(d + 1) }
        }

        // Scale under the track; the middle resets. Inset so its ends sit under the track's ends.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = StepSize + StepGap + ThumbRadius),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Early", style = HwType.caption, color = hw.sub, modifier = Modifier.weight(1f))
            Text(
                "On time",
                style = HwType.caption.copy(fontWeight = if (d != 0) FontWeight.SemiBold else FontWeight.Normal),
                color = if (d != 0) hw.tint else hw.sub,
                modifier = Modifier.clip(CircleShape).press(enabled = d != 0) { change(0) }.padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Text("Late", style = HwType.caption, color = hw.sub, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }

        DelayPreviewRow(preview(d), d, tone)
    }
}

@Composable
internal fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val hw = LocalHw.current
    Box(
        Modifier
            .size(StepSize)
            .alpha(if (enabled) 1f else 0.35f)
            .press(enabled = enabled, scaleTo = 0.88f, onClick = onClick)
            .clip(CircleShape)
            .background(hw.fill2)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = hw.text, modifier = Modifier.size(20.dp))
    }
}

/**
 * A notched track from -[DELAY_MAX] to +[DELAY_MAX]: a tick a minute, a taller one at on time,
 * and a fill that grows from the centre out to the value. Tap a notch or drag the thumb.
 */
@Composable
private fun DelaySlider(value: Int, tone: Color, onValue: (Int) -> Unit, modifier: Modifier = Modifier) {
    val hw = LocalHw.current
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val onChange by rememberUpdatedState(onValue)
    var dragging by remember { mutableStateOf(false) }
    val at by animateFloatAsState(value.toFloat(), spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium), label = "thumb")
    val grow by animateFloatAsState(if (dragging) 1.12f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "thumb size")

    fun set(v: Int) {
        val c = v.coerceIn(-DELAY_MAX, DELAY_MAX)
        if (c != current) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onChange(c)
        }
    }

    Canvas(
        modifier
            .height(44.dp)
            .pointerInput(Unit) {
                val r = ThumbRadius.toPx()
                detectTapGestures { set(valueAt(it.x, size.width.toFloat(), r)) }
            }
            .pointerInput(Unit) {
                val r = ThumbRadius.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    set(valueAt(change.position.x, size.width.toFloat(), r))
                }
            }
            .semantics {
                contentDescription = "How early or late trains run, in minutes"
                stateDescription = delayText(value)
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), -DELAY_MAX.toFloat()..DELAY_MAX.toFloat(), steps = DELAY_MAX * 2 - 1)
                setProgress { target ->
                    set(target.roundToInt())
                    true
                }
            },
    ) {
        val r = ThumbRadius.toPx()
        val left = r
        val width = size.width - 2 * r
        val cy = size.height / 2
        val track = 6.dp.toPx()
        fun xOf(v: Float) = left + (v + DELAY_MAX) / (2 * DELAY_MAX) * width

        drawRoundRect(hw.fill2, Offset(left, cy - track / 2), Size(width, track), CornerRadius(track / 2))
        val x0 = xOf(0f)
        val x1 = xOf(at)
        drawRoundRect(tone, Offset(min(x0, x1), cy - track / 2), Size(abs(x1 - x0), track), CornerRadius(track / 2))
        for (k in -DELAY_MAX..DELAY_MAX) {
            val x = xOf(k.toFloat())
            // On time is tallest, and every 5 min a little taller to count by.
            val half = when {
                k == 0 -> 8.dp.toPx()
                k % 5 == 0 -> 5.dp.toPx()
                else -> track / 2
            }
            val color = when {
                k == 0 -> hw.sub
                k % 5 == 0 -> hw.chev.copy(alpha = 0.8f)
                else -> hw.chev.copy(alpha = 0.55f)
            }
            drawLine(color, Offset(x, cy - half), Offset(x, cy + half), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
        // Thumb: a white disc with a soft shadow, a little larger while held.
        val tr = r * grow
        drawCircle(Color.Black.copy(alpha = 0.06f), tr + 2.dp.toPx(), Offset(x1, cy + 3.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.08f), tr + 0.5.dp.toPx(), Offset(x1, cy + 1.dp.toPx()))
        drawCircle(Color.White, tr, Offset(x1, cy))
    }
}

/** The whole-minute value under [x] on a track inset by the thumb radius [r]. */
private fun valueAt(x: Float, width: Float, r: Float): Int {
    val f = ((x - r) / (width - 2 * r)).coerceIn(0f, 1f)
    return (f * 2 * DELAY_MAX - DELAY_MAX).roundToInt()
}

/** "Timetable 8:41 AM → You'll see 8:44 AM", so the rider sees what the delay does. */
@Composable
private fun DelayPreviewRow(p: DelayPreview?, d: Int, tone: Color) {
    val hw = LocalHw.current
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(hw.fill)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            p == null -> Text("No direct trains on this route.", style = HwType.footnote, color = hw.sub)
            d == 0 -> {
                PreviewCell("Next train", clock(p.shown), hw.text)
                Text("as timetabled", style = HwType.footnote, color = hw.sub)
            }
            else -> {
                PreviewCell("Timetable", clock(p.timetabled), hw.sub, struck = true)
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = hw.sub, modifier = Modifier.size(20.dp))
                PreviewCell("You'll see", clock(p.shown), tone)
            }
        }
    }
}

@Composable
private fun PreviewCell(label: String, time: String, color: Color, struck: Boolean = false) {
    val hw = LocalHw.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = HwType.caption, color = hw.sub)
        Text(
            time,
            style = HwType.headline.copy(textDecoration = if (struck) TextDecoration.LineThrough else null),
            color = color,
        )
    }
}
