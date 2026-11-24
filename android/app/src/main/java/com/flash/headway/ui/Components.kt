package com.flash.headway.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.flash.headway.ui.theme.HwShape
import com.flash.headway.ui.theme.HwType
import com.flash.headway.ui.theme.LocalHw

/** Cards: the M3 extra-large corner (28dp). */
val CardShape = HwShape.extraLarge
val RouteCardShape = HwShape.extraLarge

/** Clickable with the M3 ripple and a light haptic. */
@Composable
fun Modifier.press(enabled: Boolean = true, scaleTo: Float = 1f, role: Role = Role.Button, onClick: () -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = source, indication = ripple(), enabled = enabled, role = role) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
}

/** A tonal surface (surface-container) with an optional fill. Replaces the old glass. */
@Composable
fun Glass(modifier: Modifier = Modifier, shape: Shape = CircleShape, tinted: Color? = null, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.clip(shape).background(tinted ?: LocalHw.current.card),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** No lit edges in M3: kept as a no-op so call sites stay simple. */
fun Modifier.edge(@Suppress("UNUSED_PARAMETER") shape: Shape): Modifier = this

/** A standard 40dp icon button in a 48dp target, tonal. */
@Composable
fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color? = null, background: Color? = null) {
    val hw = LocalHw.current
    FilledTonalIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = background ?: hw.fill,
            contentColor = tint ?: hw.sub,
        ),
    ) {
        Icon(icon, contentDescription = description)
    }
}

/** A tonal button, for secondary actions such as the widget setup's "Open Headway". */
@Composable
fun PillButton(text: String, onClick: () -> Unit) {
    androidx.compose.material3.FilledTonalButton(onClick = onClick, shapes = androidx.compose.material3.ButtonDefaults.shapes()) { Text(text) }
}

@Composable
fun TopBar(left: @Composable RowScope.() -> Unit = {}, right: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        left()
        Box(Modifier.weight(1f))
        right()
    }
}

/** The large flexible app bar's title area. */
@Composable
fun LargeTitle(title: String, subtitle: (@Composable () -> Unit)? = null) {
    val hw = LocalHw.current
    Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 40.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = HwType.largeTitle, color = hw.text)
        subtitle?.invoke()
    }
}

/** Section label: label-large emphasized in primary. */
@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = HwType.section,
        color = LocalHw.current.tint,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
    )
}

@Composable
fun Footnote(text: String) {
    Text(text, style = HwType.footnote, color = LocalHw.current.sub, modifier = Modifier.padding(horizontal = 16.dp))
}

/** A filled card (surface-container, 28dp corners). */
@Composable
fun HwCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, gap: Dp = 12.dp, color: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(color ?: LocalHw.current.card)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/**
 * An M3 Expressive segmented list: each row is its own container, 2dp apart, with large outer
 * corners and small inner ones. [inset] is kept for old call sites and ignored.
 */
@Composable
fun ListCard(rows: List<@Composable () -> Unit>, @Suppress("UNUSED_PARAMETER") inset: Dp = 0.dp, color: Color? = null) {
    val hw = LocalHw.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, row ->
            val top = if (i == 0) 16.dp else 4.dp
            val bottom = if (i == rows.lastIndex) 16.dp else 4.dp
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(top, top, bottom, bottom)).background(color ?: hw.card)) { row() }
        }
    }
}

@Composable
fun ListRow(onClick: (() -> Unit)? = null, minHeight: Dp = 56.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(if (onClick != null) Modifier.press(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/** A 40dp leading icon in a tonal circle, as in M3 list items. */
@Composable
fun LeadIcon(icon: ImageVector, background: Color? = null, tint: Color? = null) {
    val hw = LocalHw.current
    Box(Modifier.size(40.dp).clip(CircleShape).background(background ?: hw.selected), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint ?: hw.onSelected, modifier = Modifier.size(22.dp))
    }
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
        if (!subtitle.isNullOrEmpty()) Text(subtitle, style = HwType.subhead, color = hw.sub)
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

/** The M3 switch, with a check in the selected handle. */
@Composable
fun HwSwitch(checked: Boolean, onChange: (Boolean) -> Unit, description: String) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        thumbContent = if (checked) {
            { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
        } else {
            null
        },
        modifier = Modifier.semantics { contentDescription = description },
    )
}

/** The hero countdown. Only the digits that change roll, like NumberFlow. */
@Composable
fun BigCountdown(msLeft: Long, color: Color? = null) {
    val hw = LocalHw.current
    val (number, unit) = bigParts(msLeft)
    // Ticks count down; a jump up means a later train was picked, so roll the other way.
    val last = remember { mutableLongStateOf(msLeft) }
    val up = msLeft > last.longValue + 1_500
    SideEffect { last.longValue = msLeft }
    // Read as a whole ("5 minutes"), not digit by digit.
    val spoken = when (unit) {
        "min" -> "$number minutes"
        "h" -> "$number hours"
        else -> number
    }
    Row(
        Modifier.clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RollingText(number, HwType.big, if (number == "Now") hw.tint else color ?: hw.text, up)
        if (unit.isNotEmpty()) Text(unit, style = HwType.bigUnit, color = hw.sub, modifier = Modifier.padding(bottom = 8.dp))
    }
}

/**
 * Text whose characters animate one by one: a changed digit rolls down (we count down), an
 * unchanged one stays still. Slots are keyed from the right so "10" -> "9" keeps the ones place.
 * Uses the theme's expressive spatial spring.
 */
@Composable
fun RollingText(text: String, style: TextStyle, color: Color, up: Boolean = false) {
    val tabular = style.copy(fontFeatureSettings = "tnum")
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    // One string to TalkBack, though it's drawn a character at a time.
    Row(Modifier.clearAndSetSemantics { contentDescription = text }) {
        text.forEachIndexed { i, ch ->
            key(text.length - i) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        val dir = if (up) -1 else 1 // down: new digit enters from the top
                        ((slideInVertically(spec) { -it * dir } + fadeIn()) togetherWith (slideOutVertically(spec) { it * dir } + fadeOut()))
                            .using(SizeTransform(clip = true))
                    },
                    label = "digit",
                ) { c ->
                    Text(c.toString(), style = tabular, color = color)
                }
            }
        }
    }
}
