package com.flash.headway.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw

val CardShape = RoundedCornerShape(26.dp)

/** Clickable with a springy press-down and a light haptic, the way the design's controls feel. */
@Composable
fun Modifier.press(enabled: Boolean = true, scaleTo: Float = 0.96f, role: Role = Role.Button, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleTo else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val haptics = LocalHapticFeedback.current
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

@Composable
fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color? = null, background: Color? = null) {
    val hw = LocalHw.current
    Box(
        modifier = Modifier
            .size(44.dp)
            .shadow(if (hw.dark) 0.dp else 2.dp, CircleShape, ambientColor = Color(0x1A14203A), spotColor = Color(0x1A14203A))
            .clip(CircleShape)
            .background(background ?: hw.button)
            .border(0.5.dp, hw.hair, CircleShape)
            .press(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint ?: hw.text, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun PillButton(text: String, onClick: () -> Unit) {
    val hw = LocalHw.current
    Box(
        modifier = Modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(hw.button)
            .border(0.5.dp, hw.hair, CircleShape)
            .press(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = HwType.subheadStrong, color = hw.text)
    }
}

@Composable
fun TopBar(left: @Composable RowScope.() -> Unit = {}, right: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        left()
        Spacer(Modifier.weight(1f))
        right()
    }
}

@Composable
fun LargeTitle(title: String, subtitle: (@Composable () -> Unit)? = null) {
    val hw = LocalHw.current
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = HwType.largeTitle, color = hw.text)
        subtitle?.invoke()
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = HwType.section,
        color = LocalHw.current.text,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

@Composable
fun Footnote(text: String) {
    Text(text, style = HwType.footnote, color = LocalHw.current.sub, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
fun HwCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, gap: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(LocalHw.current.card)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** A grouped list: rows separated by hairlines inset from the leading edge. */
@Composable
fun ListCard(rows: List<@Composable () -> Unit>, inset: Dp = 32.dp) {
    val hw = LocalHw.current
    Column(Modifier.fillMaxWidth().clip(CardShape).background(hw.card)) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.padding(start = inset).fillMaxWidth().height(0.5.dp).background(hw.sep))
            row()
        }
    }
}

@Composable
fun ListRow(onClick: (() -> Unit)? = null, minHeight: Dp = 52.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(if (onClick != null) Modifier.press(scaleTo = 0.98f, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun LineBar(color: Color, alpha: Float = 1f, height: Dp = 36.dp, width: Dp = 4.dp) {
    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(color.copy(alpha = color.alpha * alpha)),
    )
}

@Composable
fun RowText(title: String, subtitle: String? = null, titleStyle: TextStyle = HwType.body, titleColor: Color? = null, modifier: Modifier = Modifier) {
    val hw = LocalHw.current
    Column(modifier) {
        Text(title, style = titleStyle, color = titleColor ?: hw.text)
        if (!subtitle.isNullOrEmpty()) Text(subtitle, style = HwType.footnote, color = hw.sub)
    }
}

@Composable
fun BadgeIcon(icon: ImageVector, background: Color, tint: Color = Color.White, size: Dp = 30.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.6f))
    }
}

@Composable
fun TileIcon(icon: ImageVector, background: Color) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun HwSwitch(checked: Boolean, onChange: (Boolean) -> Unit, description: String) {
    val hw = LocalHw.current
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = hw.ok,
            checkedBorderColor = hw.ok,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = hw.fill2,
            uncheckedBorderColor = Color.Transparent,
        ),
        thumbContent = null,
        modifier = Modifier.padding(end = 4.dp).semantics { contentDescription = description },
    )
}

/** The hero countdown: digits roll up as the minutes change. */
@Composable
fun BigCountdown(msLeft: Long) {
    val hw = LocalHw.current
    val (number, unit) = bigParts(msLeft)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AnimatedContent(
            targetState = number,
            transitionSpec = {
                (slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { -it / 2 } + fadeIn()) togetherWith
                    (slideOutVertically { it / 2 } + fadeOut())
            },
            label = "countdown",
        ) { n ->
            Text(n, style = HwType.big, color = hw.text)
        }
        if (unit.isNotEmpty()) Text(unit, style = HwType.bigUnit, color = hw.text, modifier = Modifier.padding(bottom = 4.dp))
    }
}
