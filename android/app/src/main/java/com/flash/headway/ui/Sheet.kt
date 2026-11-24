package com.flash.headway.ui

import androidx.compose.foundation.layout.heightIn
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.flash.headway.ui.theme.LocalHw
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * An M3 bottom sheet (28dp corners, surface-container-low, 32% scrim): drag the handle or pull a list down from its top to
 * dismiss. Scrolling to the end of a list never moves the sheet, so nothing jumps at the bottom.
 * [content] gets a `close` that animates the sheet away before running its callback.
 */
@Composable
fun HwSheet(
    onDismiss: () -> Unit,
    heightFraction: Float? = null,
    /** Full-screen search view: covers the app, no handle or rounded top. */
    fullScreen: Boolean = false,
    /**
     * For a full-screen sheet: the on-screen rect it grows out of (the search bar or add button),
     * so it opens as a container transform and shrinks back into it on close.
     */
    from: androidx.compose.ui.geometry.Rect? = null,
    content: @Composable ColumnScope.(close: (() -> Unit) -> Unit) -> Unit,
) {
    val hw = LocalHw.current
    val scope = rememberCoroutineScope()
    val openSpec = androidx.compose.material3.MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val closeSpec = androidx.compose.material3.MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val transform = fullScreen && from != null
    val fromRadius = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.toPx() }
    // 0 = fully open, 1 = fully hidden below the screen.
    var hidden by remember { mutableFloatStateOf(1f) }
    var heightPx by remember { mutableIntStateOf(1) }
    var closing by remember { mutableStateOf(false) }
    var anim by remember { mutableStateOf<Job?>(null) }

    fun settle(target: Float, then: (() -> Unit)? = null) {
        anim?.cancel()
        anim = scope.launch {
            // Opening rides the expressive spatial spring; closing is quick and doesn't bounce.
            val spec: AnimationSpec<Float> = if (target == 0f) openSpec else closeSpec
            // A close must always finish (run its action and dismiss), even if interrupted:
            // a sheet left half-closed would keep its scrim over the screen.
            try {
                animate(hidden, target, animationSpec = spec) { v, _ -> hidden = v }
            } finally {
                then?.invoke()
            }
        }
    }

    // A closed sheet must always leave composition: while it stays, its invisible scrim covers the
    // screen and swallows every tap. So run the action, then dismiss, even when the action itself
    // doesn't (opening Airtel or a UPI app from the top-up sheet didn't, and froze the app).
    val dismiss by rememberUpdatedState(onDismiss)
    fun close(then: () -> Unit) {
        if (closing) return
        closing = true
        settle(1f) {
            then()
            if (then !== dismiss) dismiss()
        }
    }

    LaunchedEffect(Unit) { settle(0f) }
    // Predictive back: the sheet follows the back swipe, then closes, or springs back if cancelled.
    androidx.activity.compose.PredictiveBackHandler { progress ->
        try {
            progress.collect { e -> if (!closing) hidden = e.progress * 0.35f }
            close(onDismiss)
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (!closing) settle(0f)
            throw e
        }
    }

    fun dragBy(dy: Float) {
        // Closing already: a drag or scroll now mustn't cancel the close.
        if (closing) return
        anim?.cancel()
        hidden = (hidden + dy / heightPx).coerceIn(0f, 1f)
    }

    fun release(velocity: Float) {
        if (closing) return
        if (hidden > 0.25f || velocity > 1800f) close(onDismiss) else settle(0f)
    }

    val connection = remember {
        object : NestedScrollConnection {
            // Finger moving up while the sheet is pulled down: raise the sheet before the list scrolls.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0 && hidden > 0f && source == NestedScrollSource.UserInput) {
                    val before = hidden
                    dragBy(available.y)
                    return Offset(0f, (hidden - before) * heightPx)
                }
                return Offset.Zero
            }

            // The list is at its top and the finger keeps pulling down: move the sheet.
            // Leftover scroll at the bottom of the list (available.y < 0) is ignored on purpose.
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 && source == NestedScrollSource.UserInput) {
                    dragBy(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (hidden > 0f) {
                    release(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - hidden }
                .background(Color.Black.copy(alpha = 0.32f))
                .pointerInput(Unit) { detectTapGestures { close(onDismiss) } },
        )
        // The sheet stops short of the status bar, so a tall one never pushes its heading under
        // the clock: its content scrolls instead.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().then(if (fullScreen) Modifier else Modifier.statusBarsPadding().padding(top = 12.dp))) {
        val cap = maxHeight * 0.8f
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(
                    when {
                        fullScreen -> Modifier.fillMaxHeight()
                        heightFraction != null -> Modifier.fillMaxHeight(heightFraction)
                        else -> Modifier.heightIn(max = cap)
                    },
                )
                .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
                .graphicsLayer {
                    if (transform) {
                        // Container transform: grow out of the tapped bar or button.
                        clip = true
                        shape = ContainerTransformShape(from!!, minOf(fromRadius, from.height / 2), 1f - hidden)
                    } else {
                        translationY = hidden * size.height
                    }
                }
                .clip(if (fullScreen) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(if (fullScreen) hw.button else hw.sheet)
                // The content fades in once the container has mostly grown (and out first on close).
                .graphicsLayer { if (transform) alpha = ((0.65f - hidden) / 0.65f).coerceIn(0f, 1f) }
                .then(if (fullScreen) Modifier.statusBarsPadding() else Modifier)
                .pointerInput(Unit) { detectTapGestures { } }
                .nestedScroll(connection),
        ) {
            if (!fullScreen) Box(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { dragBy(it) },
                        onDragStopped = { v -> release(v) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(32.dp, 4.dp).clip(CircleShape).background(hw.sub.copy(alpha = 0.4f)))
            }
            content(this) { then -> close(then) }
        }
        }
    }
}
