package dev.drecorder.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drecorder.audio.edit.EditHandle
import dev.drecorder.audio.edit.EditRange
import dev.drecorder.audio.edit.EditSelectionMath
import dev.drecorder.audio.edit.KeepRange
import dev.drecorder.audio.edit.PeakData
import dev.drecorder.audio.edit.WaveformBins
import dev.drecorder.audio.edit.WaveformColumns
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** A half-open time selection on the original timeline. */
data class TimeSelection(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs

    /**
     * The selection with both edges inside the window that is on screen.
     *
     * A drag that runs off the edge of the window would otherwise select
     * audio that is not visible — and the user cannot see what they are
     * about to cut.
     */
    fun clampedTo(startMs: Long, spanMs: Long): TimeSelection {
        val from = startMs
        val to = startMs + spanMs
        return TimeSelection(
            startMs = this.startMs.coerceIn(from, to),
            endMs = this.endMs.coerceIn(from, to),
        )
    }
}

/**
 * The waveform of one source file.
 *
 * The timeline is always the **original** file's timeline: removed stretches stay
 * where they were, drawn greyed out and struck through, so the user can always
 * see what they threw away and put it back (DESIGN §12.4).
 *
 * Gestures:
 * - one finger drags out a selection;
 * - two fingers pan and zoom;
 * - a touch near a selection edge grabs that edge's handle instead.
 *
 * Panning and zooming are reported upwards as deltas rather than applied here,
 * because the viewport is owned by the editor screen: two sources edited in one
 * session have to share one window.
 */
@Composable
fun WaveformView(
    peaks: PeakData,
    durationMs: Long,
    startMs: Long,
    spanMs: Long,
    removed: List<KeepRange>,
    selection: TimeSelection?,
    playheadMs: Long?,
    onSelectionChange: (TimeSelection?) -> Unit,
    onSelectionCommit: (TimeSelection?) -> Unit,
    onViewportGesture: (zoomFactor: Float, panFraction: Float, focusFraction: Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 168.dp,
    waveColor: Color = Color(0xFF3D7EFF),
    removedColor: Color = Color(0xFF9AA0A6),
    rulerColor: Color = Color(0x66000000),
    selectionColor: Color = Color(0x333D7EFF),
    playheadColor: Color = Color(0xFFE53935),
) {
    val textMeasurer = rememberTextMeasurer()
    var widthPx by remember { mutableIntStateOf(0) }

    // The gesture callback runs the whole time a finger is down, and panning
    // changes the viewport *while* the gesture is running. Reading these through
    // rememberUpdatedState keeps one long-lived pointerInput block alive: keying
    // it on startMs/spanMs would cancel the very gesture that changed them.
    val currentStart by rememberUpdatedState(startMs)
    val currentSpan by rememberUpdatedState(spanMs)
    val currentWidth by rememberUpdatedState(widthPx)
    val currentSelection by rememberUpdatedState(selection)
    val selectionChange by rememberUpdatedState(onSelectionChange)
    val selectionCommit by rememberUpdatedState(onSelectionCommit)
    val viewportGesture by rememberUpdatedState(onViewportGesture)

    // One aggregation per (peaks, window, width): the cached buckets are re-binned
    // into screen columns. It only changes when the viewport or the size changes.
    // The maths is in core-audio so it can be unit-tested off-device.
    val columns = remember(peaks, startMs, spanMs, widthPx) {
        if (widthPx <= 0) {
            WaveformColumns.EMPTY
        } else {
            WaveformBins.build(peaks, startMs, spanMs, widthPx)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val anchorMs = timeAt(down.position.x, currentWidth, currentStart, currentSpan)
                    var mode = GestureMode.UNDECIDED
                    var dragged: TimeSelection? = null

                    // A touch that lands on a selection edge grabs that edge
                    // instead of starting a new selection. The edge is drawn
                    // three pixels wide but is hit over 48, because a finger
                    // cannot aim at three pixels (DESIGN §12.4).
                    var grabbedHandle = EditHandle.None
                    val existing = currentSelection
                    if (existing != null && currentWidth > 0) {
                        grabbedHandle = EditSelectionMath.handleAt(
                            xPx = down.position.x,
                            widthPx = currentWidth.toFloat(),
                            startMs = currentStart,
                            spanMs = currentSpan,
                            range = EditRange(existing.startMs, existing.endMs),
                        )
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2) {
                            mode = GestureMode.TRANSFORM
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            val focus = if (currentWidth > 0) {
                                (centroid.x / currentWidth.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0.5f
                            }
                            val panFraction = if (currentWidth > 0) {
                                pan.x / currentWidth.toFloat()
                            } else {
                                0f
                            }
                            viewportGesture(zoom, panFraction, focus)
                            pressed.forEach { it.consume() }
                        } else {
                            val change = pressed.first()
                            if (mode == GestureMode.UNDECIDED &&
                                abs(change.position.x - down.position.x) > viewConfiguration.touchSlop
                            ) {
                                mode = GestureMode.SELECT
                            }
                            if (mode == GestureMode.SELECT) {
                                val currentMs = timeAt(
                                    change.position.x,
                                    currentWidth,
                                    currentStart,
                                    currentSpan,
                                )
                                val next = if (grabbedHandle != EditHandle.None && existing != null) {
                                    // Dragging a handle moves that edge only.
                                    val moved = EditSelectionMath.moveHandle(
                                        range = EditRange(existing.startMs, existing.endMs),
                                        handle = grabbedHandle,
                                        xPx = change.position.x,
                                        widthPx = currentWidth.toFloat(),
                                        startMs = currentStart,
                                        spanMs = currentSpan,
                                        totalMs = durationMs,
                                    )
                                    TimeSelection(moved.startMs, moved.endMs)
                                } else {
                                    // A drag that leaves the window does not
                                    // select audio that is not there: the edge
                                    // stops at the border of the window.
                                    TimeSelection(
                                        min(anchorMs, currentMs),
                                        max(anchorMs, currentMs),
                                    ).clampedTo(
                                        startMs = currentStart,
                                        spanMs = currentSpan,
                                    )
                                }
                                dragged = next
                                selectionChange(next)
                                change.consume()
                            }
                        }
                    }

                    when (mode) {
                        GestureMode.SELECT -> selectionCommit(dragged ?: currentSelection)
                        else -> Unit
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            if (widthPx <= 0) return@Canvas
            val rulerHeight = 18.dp.toPx()
            val waveTop = rulerHeight
            val waveBottom = size.height
            val midY = (waveTop + waveBottom) / 2f
            val halfHeight = (waveBottom - waveTop) / 2f - 2.dp.toPx()

            // --- removed stretches: greyed out, struck through, left in place ---
            for (range in removed) {
                val left = msToX(range.startMs, size.width, startMs, spanMs)
                val right = msToX(range.endMs, size.width, startMs, spanMs)
                if (right < 0f || left > size.width) continue
                val clippedLeft = max(0f, left)
                val clippedRight = min(size.width, right)
                drawRect(
                    color = Color(0x14000000),
                    topLeft = Offset(clippedLeft, waveTop),
                    size = Size(clippedRight - clippedLeft, waveBottom - waveTop),
                )
                // The strike-through is what makes "this is gone" readable at a
                // glance, the way every ordinary recorder draws a cut.
                val strike = Path().apply {
                    moveTo(clippedLeft, waveTop)
                    lineTo(clippedRight, waveBottom)
                }
                drawPath(
                    strike,
                    color = removedColor.copy(alpha = 0.45f),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            // --- the waveform itself ---
            if (columns.count > 0) {
                for (i in 0 until columns.count) {
                    val minV = columns.mins[i]
                    val maxV = columns.maxs[i]
                    if (minV == Int.MAX_VALUE) continue
                    val top = midY - (maxV / 32768f) * halfHeight
                    val bottom = midY - (minV / 32768f) * halfHeight
                    drawLine(
                        color = waveColor,
                        start = Offset(i + 0.5f, top.coerceAtMost(bottom)),
                        end = Offset(i + 0.5f, bottom.coerceAtLeast(top)),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }

            // --- selection overlay ---
            selection?.let { sel ->
                val left = msToX(sel.startMs, size.width, startMs, spanMs)
                val right = msToX(sel.endMs, size.width, startMs, spanMs)
                if (right >= 0f && left <= size.width) {
                    val clippedLeft = max(0f, left)
                    val clippedRight = min(size.width, right)
                    drawRect(
                        color = selectionColor,
                        topLeft = Offset(clippedLeft, waveTop),
                        size = Size(clippedRight - clippedLeft, waveBottom - waveTop),
                    )
                    drawLine(
                        color = waveColor,
                        start = Offset(clippedLeft, waveTop),
                        end = Offset(clippedLeft, waveBottom),
                        strokeWidth = 2.dp.toPx(),
                    )
                    drawLine(
                        color = waveColor,
                        start = Offset(clippedRight, waveTop),
                        end = Offset(clippedRight, waveBottom),
                        strokeWidth = 2.dp.toPx(),
                    )
                    // A grab bar at the top of each edge: the line itself is
                    // too thin to aim at, so the handle gets a visible head.
                    val handleHeight = 14.dp.toPx()
                    drawRect(
                        color = waveColor,
                        topLeft = Offset(clippedLeft - 2.dp.toPx(), waveTop),
                        size = Size(4.dp.toPx(), handleHeight),
                    )
                    drawRect(
                        color = waveColor,
                        topLeft = Offset(clippedRight - 2.dp.toPx(), waveTop),
                        size = Size(4.dp.toPx(), handleHeight),
                    )
                }
            }

            // --- playhead ---
            playheadMs?.let { ms ->
                val x = msToX(ms, size.width, startMs, spanMs)
                if (x in 0f..size.width) {
                    drawLine(
                        color = playheadColor,
                        start = Offset(x, 0f),
                        end = Offset(x, waveBottom),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }

            // --- time ruler ---
            drawRuler(
                textMeasurer = textMeasurer,
                width = size.width,
                startMs = startMs,
                spanMs = spanMs,
                rulerHeight = rulerHeight,
                rulerColor = rulerColor,
            )
        }
    }
}

private enum class GestureMode { UNDECIDED, SELECT, TRANSFORM }

/**
 * Re-bins the cached peaks into one min/max pair per screen column.
 *
 * The cached peaks are fixed at 100 buckets per second; when the user zooms in
 * past that, several buckets share a column and the column shows the extremes of
 * all of them. Zooming out merges many buckets and the column shows their
 * extremes too, which is exactly what a waveform should do.
 */
private fun timeAt(x: Float, widthPx: Int, startMs: Long, spanMs: Long): Long {
    if (widthPx <= 0) return startMs
    val fraction = (x / widthPx.toFloat()).coerceIn(0f, 1f)
    return startMs + (fraction * spanMs).roundToLong()
}

private fun msToX(ms: Long, width: Float, startMs: Long, spanMs: Long): Float {
    if (spanMs <= 0) return 0f
    return ((ms - startMs).toFloat() / spanMs.toFloat()) * width
}

private fun DrawScope.drawRuler(
    textMeasurer: TextMeasurer,
    width: Float,
    startMs: Long,
    spanMs: Long,
    rulerHeight: Float,
    rulerColor: Color,
) {
    val spacing = chooseTickSpacing(spanMs, width)
    var tick = if (startMs % spacing == 0L) startMs else startMs - (startMs % spacing) + spacing
    val style = TextStyle(fontSize = 10.sp, color = rulerColor)
    while (tick < startMs + spanMs) {
        if (tick >= 0) {
            val x = msToX(tick, width, startMs, spanMs)
            drawLine(
                color = rulerColor,
                start = Offset(x, 0f),
                end = Offset(x, rulerHeight),
                strokeWidth = 1f,
            )
            val layout = textMeasurer.measure(formatClock(tick), style)
            drawText(layout, topLeft = Offset(x + 2f, 1f))
        }
        tick += spacing
    }
}

/** A round tick spacing (1s, 2s, 5s, 10s, 30s, 1m …) that keeps labels readable. */
private fun chooseTickSpacing(spanMs: Long, width: Float): Long {
    val candidates = longArrayOf(
        1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 30_000L,
        60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L,
    )
    val minPixels = 64f
    for (candidate in candidates) {
        val pixels = candidate.toFloat() / spanMs.toFloat() * width
        if (pixels >= minPixels) return candidate
    }
    return candidates.last()
}

internal fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
