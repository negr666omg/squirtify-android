package org.squidify.android.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drives the Now-Playing sheet motion with a single float that is ONLY ever
 * read inside [androidx.compose.ui.graphics.graphicsLayer] blocks.
 *
 * Every frame of the open/close glide — and every pixel of a finger drag —
 * costs exactly one graphics-layer update: no recomposition, no re-layout.
 * That is what keeps the sheet at the display's full frame rate even on
 * older GPUs, where the previous AnimatedVisibility-based sheet visibly
 * dropped to ~20 fps because the whole screen recomposed 10x per second
 * (position ticker) right through the animation.
 *
 * Drags write [progress] SYNCHRONOUSLY (same trick the swipe-dismiss code
 * used): a coroutine hop per delta would lag behind the finger. The glides
 * run in a coroutine that cancels itself whenever a finger takes over, so
 * opening/closing is fully interruptible and reversible mid-flight — tap the
 * mini player while the sheet is still closing and it glides straight back
 * up without ever re-creating its content.
 */
@Stable
class SheetController(private val scope: CoroutineScope) {

    /** 1f = fully open, 0f = fully closed. */
    var progress by mutableFloatStateOf(0f)
        private set

    /** True while the sheet content must exist (open, or still animating). */
    var composed by mutableStateOf(false)
        private set

    private var glide: Job? = null

    // swipe-dismiss velocity, in screen-heights per second (> 0 = downward)
    private var dragVelocity = 0f
    private var lastDragMs = 0L

    fun open() {
        composed = true
        glideTo(1f, OPEN_SPEC)
    }

    fun close() {
        if (progress <= 0f && !composed) return
        glideTo(0f, CLOSE_SPEC)
    }

    /** Finger moved — the sheet tracks it 1:1 with no coroutine hop. */
    fun dragDelta(dy: Float, viewHeightPx: Float) {
        if (!composed || viewHeightPx <= 0f) return
        glide?.cancel()
        glide = null
        val now = SystemClock.uptimeMillis()
        if (lastDragMs > 0) {
            val dt = (now - lastDragMs).coerceAtLeast(1)
            dragVelocity = (dy / viewHeightPx) * 1000f / dt
        }
        lastDragMs = now
        progress = (progress - dy / viewHeightPx).coerceIn(0f, 1f)
    }

    /** Finger lifted — settle back open or finish the dismissal. */
    fun dragEnd() {
        lastDragMs = 0
        val draggedDown = 1f - progress
        val dismiss = draggedDown > 0.22f || (dragVelocity > 2.2f && draggedDown > 0.06f)
        dragVelocity = 0f
        if (dismiss) close() else open()
    }

    private fun glideTo(target: Float, spec: TweenSpec<Float>) {
        glide?.cancel()
        glide = scope.launch {
            val from = progress
            if (from == target) {
                if (target <= 0f) composed = false
                return@launch
            }
            animate(initialValue = from, targetValue = target, animationSpec = spec) { v, _ ->
                progress = v
            }
            // dispose the content only after the closing glide has fully
            // finished — removing it mid-animation would pop it out of sight
            if (target <= 0f) composed = false
        }
    }

    companion object {
        // The Android 12+ activity/sheet interpolators: a strong decelerate
        // on the way in (fast start, long silky glide to rest) and an
        // accelerate on the way out. This is what "standard Android
        // animations" feel like.
        private val SheetDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        private val SheetAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

        val OPEN_SPEC: TweenSpec<Float> = tween(420, easing = SheetDecelerate)
        val CLOSE_SPEC: TweenSpec<Float> = tween(300, easing = SheetAccelerate)
    }
}
