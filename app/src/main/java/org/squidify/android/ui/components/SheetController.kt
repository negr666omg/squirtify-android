package org.squidify.android.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Drives the Now Playing sheet with one progress value read by graphicsLayer.
 * Dragging follows the finger directly; release settles with a velocity-aware
 * spring rather than snapping to a fixed-duration tween.
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

    // Fallback velocity estimate for callers that do not supply a VelocityTracker.
    private var dragVelocity = 0f
    private var lastDragMs = 0L

    fun open() {
        composed = true
        glideTo(1f)
    }

    fun close() {
        if (progress <= 0f && !composed) return
        glideTo(0f)
    }

    /** Finger moved — update synchronously so the surface stays under the finger. */
    fun dragDelta(dy: Float, viewHeightPx: Float) {
        if (!composed || viewHeightPx <= 0f) return
        glide?.cancel()
        glide = null
        val now = android.os.SystemClock.uptimeMillis()
        if (lastDragMs > 0L) {
            val dt = (now - lastDragMs).coerceAtLeast(1L)
            dragVelocity = (dy / viewHeightPx) * 1000f / dt
        }
        lastDragMs = now
        progress = (progress - dy / viewHeightPx).coerceIn(0f, 1f)
    }

    /**
     * Settle based on both distance and release velocity. A quick short swipe
     * can dismiss; a slow short pull returns open without an artificial
     * mid-screen attraction.
     */
    fun dragEnd(fingerVelocityY: Float? = null, viewHeightPx: Float = 0f) {
        lastDragMs = 0L
        val progressVelocity = if (fingerVelocityY != null && viewHeightPx > 0f) {
            (-fingerVelocityY / viewHeightPx).coerceIn(-4f, 4f)
        } else {
            (-dragVelocity).coerceIn(-4f, 4f)
        }
        val draggedDown = 1f - progress
        dragVelocity = 0f

        val fastDownwardFling = progressVelocity < -1.05f && draggedDown > 0.025f
        val close = draggedDown > 0.28f || fastDownwardFling
        glideTo(if (close) 0f else 1f, initialVelocity = progressVelocity)
    }

    private fun glideTo(target: Float, initialVelocity: Float = 0f) {
        glide?.cancel()
        glide = scope.launch {
            val from = progress
            if (abs(from - target) < 0.0005f) {
                progress = target
                if (target <= 0f) composed = false
                return@launch
            }
            animate(
                initialValue = from,
                targetValue = target,
                initialVelocity = initialVelocity,
                animationSpec = SETTLE_SPEC,
            ) { value, _ ->
                progress = value.coerceIn(0f, 1f)
            }
            if (target <= 0f) composed = false
        }
    }

    companion object {
        // Critically controlled, low-bounce settle with enough response to
        // preserve the user's fling direction and speed.
        private val SETTLE_SPEC = spring<Float>(
            dampingRatio = 0.92f,
            stiffness = 420f,
            visibilityThreshold = 0.001f,
        )
    }
}
