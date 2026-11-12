package com.flash.headway.ui

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
 * A bottom sheet that behaves like iOS: drag the grabber or pull a list down from its top to
 * dismiss. Scrolling to the end of a list never moves the sheet, so nothing jumps at the bottom.
 * [content] gets a `close` that animates the sheet away before running its callback.
 */
@Composable
fun HwSheet(
    onDismiss: () -> Unit,
    heightFraction: Float? = null,
    content: @Composable ColumnScope.(close: (() -> Unit) -> Unit) -> Unit,
) {
    val hw = LocalHw.current
    val scope = rememberCoroutineScope()
    // 0 = fully open, 1 = fully hidden below the screen.
    var hidden by remember { mutableFloatStateOf(1f) }
    var heightPx by remember { mutableIntStateOf(1) }
    var closing by remember { mutableStateOf(false) }
    var anim by remember { mutableStateOf<Job?>(null) }

    fun settle(target: Float, then: (() -> Unit)? = null) {
        anim?.cancel()
        anim = scope.launch {
            val spec: AnimationSpec<Float> = if (target == 0f) spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow) else tween(220)
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
    BackHandler { close(onDismiss) }

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
                .background(Color.Black.copy(alpha = if (hw.dark) 0.6f else 0.32f))
                .pointerInput(Unit) { detectTapGestures { close(onDismiss) } },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(if (heightFraction != null) Modifier.fillMaxHeight(heightFraction) else Modifier)
                .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
                .graphicsLayer { translationY = hidden * size.height }
                .clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp))
                .background(hw.bg)
                .pointerInput(Unit) { detectTapGestures { } }
                .nestedScroll(connection),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { dragBy(it) },
                        onDragStopped = { v -> release(v) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(36.dp, 5.dp).clip(CircleShape).background(hw.chev))
            }
            content(this) { then -> close(then) }
        }
    }
}
