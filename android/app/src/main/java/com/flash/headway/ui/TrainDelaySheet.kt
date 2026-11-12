package com.flash.headway.ui

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
import androidx.compose.ui.unit.sp
import com.flash.headway.app.DELAY_MAX
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw
import kotlin.math.abs

/**
 * One train's own delay: "The 8:13 AM train", on every day of its kind ([days], e.g. "Every
 * weekday") or just today. [own] is its delay, or null while it follows the route's
 * [routeDelay]. [onChange] sets it (minutes, just today?); null minutes follow the route again.
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
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.align(Alignment.CenterHorizontally), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = HwType.headline, color = hw.text)
                Text(subtitle, style = HwType.footnote, color = hw.sub)
            }
            // For every day like this one, or just today (a one-off: it's dropped tomorrow).
            Segmented(
                options = listOf(false to days, true to "Just today"),
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StepButton(Icons.Rounded.Remove, "One minute earlier", enabled = d > -DELAY_MAX) { set(d - 1) }
                    DelaySlider(d, tone, ::set, Modifier.weight(1f))
                    StepButton(Icons.Rounded.Add, "One minute later", enabled = d < DELAY_MAX) { set(d + 1) }
                }
                Text(
                    if (!following) {
                        "Just this train${if (today) ", today only" else ""}. Other trains on this route: ${delayText(routeDelay).lowercase()}."
                    } else {
                        "Following the route (${delayText(routeDelay).lowercase()}). Change it here for just this train."
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
                        Text("Follow the route again", style = HwType.subhead.copy(fontWeight = FontWeight.SemiBold), color = hw.tint)
                    }
                }
            }
        }
    }
}

private val RoundedCornerShapeFull = androidx.compose.foundation.shape.CircleShape
