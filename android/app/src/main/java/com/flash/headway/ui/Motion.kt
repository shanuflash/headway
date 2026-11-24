package com.flash.headway.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * M3 Expressive motion helpers: nothing pops, it travels. A container grows out of the thing that
 * was tapped (container transform), lists arrive in a stagger, and changes move along the spatial
 * springs from the theme's MotionScheme.
 */

/** Where the last container-transform source (search bar, add button) was on screen when tapped. */
object TapOrigin {
    var rect by mutableStateOf<Rect?>(null)
}

/** Remembers this element's on-screen bounds, so a tap on it can hand them to [TapOrigin]. */
@Composable
fun Modifier.originOf(onBounds: (Rect) -> Unit): Modifier = this.onGloballyPositioned { onBounds(it.boundsInRoot()) }

/**
 * The shape of a container mid-transform: from [from] (a rect in the same coordinates, with
 * [fromRadius] corners) to the full box at [t] = 1, corners un-rounding as it grows.
 */
class ContainerTransformShape(private val from: Rect, private val fromRadius: Float, private val t: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val k = t.coerceIn(0f, 1f)
        val r = lerp(from, Rect(0f, 0f, size.width, size.height), k)
        val radius = fromRadius * (1f - k)
        return Outline.Rounded(RoundRect(r, CornerRadius(radius)))
    }
}

/**
 * Staggered entrance: the [index]th item fades up a moment after the one before it (capped, so a
 * long list doesn't keep the last rows waiting).
 */
@Composable
fun Modifier.staggerIn(index: Int): Modifier {
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val alpha = remember { Animatable(0f) }
    val rise = remember { Animatable(1f) }
    val shift = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toPx() }
    LaunchedEffect(Unit) {
        delay(minOf(index, 12) * 30L)
        coroutineScope {
            launch { alpha.animateTo(1f, effects) }
            launch { rise.animateTo(0f, spatial) }
        }
    }
    return this.graphicsLayer {
        this.alpha = alpha.value
        translationY = rise.value * shift
    }
}

/** Hands out entrance order to the sections of one screen as they compose. */
class StaggerCounter {
    var next = 0
}

/** Set by a screen; absent (no stagger) in lazy lists and sheets that do their own. */
val LocalStagger = androidx.compose.runtime.staticCompositionLocalOf<StaggerCounter?> { null }

/** Staggers in after the sections composed before it on this screen. No-op outside a screen. */
@Composable
fun Modifier.autoStagger(): Modifier {
    val counter = LocalStagger.current ?: return this
    val index = remember(counter) { counter.next++ }
    return staggerIn(index)
}

/**
 * The clock floored to [every] ms. Reading it recomposes only when it moves on a step, unlike
 * reading the per-second clock, which would redo a whole screen each second.
 */
@Composable
fun rememberTick(now: () -> Long, every: Long): Long {
    val tick by remember(now, every) { androidx.compose.runtime.derivedStateOf { now() / every * every } }
    return tick
}

