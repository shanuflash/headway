package com.flash.headway.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flash.headway.app.AutoFlip
import com.flash.headway.app.FLIP_MAX
import com.flash.headway.app.FLIP_MIN
import com.flash.headway.app.FLIP_STEP
import com.flash.headway.app.SavedRoute
import com.flash.headway.app.flipPeriod
import com.flash.headway.data.Network
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw

/**
 * Route-menu card: switch the route's direction by time. Shows the day's two trips, which one is
 * on now, and the switch time. Hidden behind a note when both directions are saved as routes.
 */
@Composable
fun FlipCard(
    route: SavedRoute,
    flip: AutoFlip?,
    bothSaved: Boolean,
    now: Long,
    onToggle: (Boolean) -> Unit,
    onMoveTime: (Int) -> Unit,
    onSwapHome: () -> Unit,
) {
    val hw = LocalHw.current
    val on = flip != null && !bothSaved
    Column(
        Modifier.fillMaxWidth().clip(CardShape).background(hw.card).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(hw.tintSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.SwapVert, null, tint = hw.tint, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Reverse automatically", style = HwType.headline, color = hw.text)
                Text("Show the return trip after a set time", style = HwType.footnote, color = hw.sub)
            }
            if (!bothSaved) HwSwitch(on, onToggle, "Reverse automatically")
        }
        if (bothSaved) {
            Footnote("Both directions are saved as separate routes.")
            return@Column
        }
        if (flip == null) return@Column

        val home = flip.home
        val other = if (route.from == home) route.to else route.from
        val pm = flipPeriod(now, flip.at).first
        val time = minutesLabel(flip.at)

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(hw.fill).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TripRow(!pm, Icons.Rounded.LightMode, "Until $time", home, other)
                TripRow(pm, Icons.Rounded.Bedtime, "From $time", other, home)
            }
            Box(
                Modifier
                    .size(36.dp)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(hw.card)
                    .press(scaleTo = 0.88f, onClick = onSwapHome)
                    .semantics { contentDescription = "Swap morning and evening" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.SwapVert, null, tint = hw.tint, modifier = Modifier.size(20.dp))
            }
        }

        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Reverse at", style = HwType.subhead, color = hw.text, modifier = Modifier.weight(1f))
            StepButton(Icons.Rounded.Remove, "30 min earlier", enabled = flip.at > FLIP_MIN) { onMoveTime(-FLIP_STEP) }
            Text(
                time,
                style = HwType.headline.copy(fontFeatureSettings = "tnum"),
                color = hw.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 84.dp),
            )
            StepButton(Icons.Rounded.Add, "30 min later", enabled = flip.at < FLIP_MAX) { onMoveTime(FLIP_STEP) }
        }
        Text(
            "Tap ⇅ on the card to reverse it any time.",
            style = HwType.footnote,
            color = hw.sub,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** One of the day's two trips; the one that's on now is raised and marked. */
@Composable
private fun TripRow(active: Boolean, icon: ImageVector, whenText: String, from: String, to: String) {
    val hw = LocalHw.current
    val bg by animateColorAsState(if (active) hw.card else Color.Transparent, label = "trip row")
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (active) Modifier.shadow(2.dp, RoundedCornerShape(12.dp)) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (active) hw.tintSoft else hw.fill2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (active) hw.tint else hw.sub, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(whenText, style = HwType.caption, color = hw.sub)
                if (active) {
                    Text(
                        "Now",
                        style = HwType.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        color = hw.tint,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(hw.tintSoft).padding(horizontal = 6.dp),
                    )
                }
            }
            Text(
                "${Network.name(from)} → ${Network.name(to)}",
                style = HwType.subhead.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
                color = if (active) hw.text else hw.sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
