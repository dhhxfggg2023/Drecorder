package dev.drecorder.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.drecorder.audio.edit.EditOverview
import dev.drecorder.audio.edit.PeakData
import dev.drecorder.audio.edit.WaveformBins
import dev.drecorder.audio.edit.WaveformColumns
import kotlin.math.abs

/**
 * The overview strip: the whole recording in a thin bar, with a rectangle that
 * says which part of it the detail view below is showing.
 *
 * This is the answer to "the whole thirty minutes is squeezed into one screen
 * and I cannot even scroll". The strip gives the map, the detail view gives the
 * ground, and the rectangle can be dragged to move the ground (DESIGN §12.4).
 *
 * The strip is drawn from the **coarse** waveform — the one computed while
 * recording, or the one the cache holds. It is never re-decoded for the
 * overview: at 30 minutes over a few hundred pixels, a finer waveform would
 * only turn into a grey smear.
 */
@Composable
fun OverviewStrip(
    peaks: PeakData?,
    totalMs: Long,
    viewportStartMs: Long,
    viewportSpanMs: Long,
    onViewportMove: (startMs: Long, spanMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    waveColor: Color = Color(0xFF3D7EFF),
    viewportColor: Color = Color(0xFF1B5FD9),
) {
    var widthPx by remember { mutableIntStateOf(0) }
    val currentViewportStart by rememberUpdatedState(viewportStartMs)
    val currentViewportSpan by rememberUpdatedState(viewportSpanMs)
    val currentTotal by rememberUpdatedState(totalMs)
    val currentWidth by rememberUpdatedState(widthPx)
    val viewportMove by rememberUpdatedState(onViewportMove)

    // One aggregation per (peaks, width): the whole recording is re-binned into
    // one column per pixel. It only changes when the size changes.
    val columns = remember(peaks, widthPx) {
        if (peaks == null || widthPx <= 0) {
            WaveformColumns.EMPTY
        } else {
            WaveformBins.build(peaks, 0L, totalMs.coerceAtLeast(1L), widthPx)
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
                    var moved = false
                    var lastX = down.position.x
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val change = pressed.first()
                        if (abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) {
                            moved = true
                        }
                        lastX = change.position.x
                        change.consume()
                    }
                    if (!moved) return@awaitEachGesture
                    // The rectangle follows the finger: the window that the
                    // touch position stands for becomes the new viewport.
                    // The span is left alone — a drag in the map must not
                    // change how much of the recording the detail view shows.
                    if (currentWidth <= 0) return@awaitEachGesture
                    val viewport = EditOverview.Viewport(currentViewportStart, currentViewportSpan)
                    val rect = EditOverview.rectFor(viewport, currentTotal, currentWidth.toFloat())
                    val wantedLeft = lastX - rect.widthPx / 2f
                    val next = EditOverview.moveViewport(
                        viewport = viewport,
                        leftPx = wantedLeft,
                        stripWidthPx = currentWidth.toFloat(),
                        totalMs = currentTotal,
                    )
                    viewportMove(next.startMs, next.spanMs)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            if (widthPx <= 0) return@Canvas
            val midY = size.height / 2f
            val halfHeight = size.height / 2f - 1.dp.toPx()

            if (columns.count > 0) {
                for (i in 0 until columns.count) {
                    val minV = columns.mins[i]
                    val maxV = columns.maxs[i]
                    if (minV == Int.MAX_VALUE) continue
                    val top = midY - (maxV / 32768f) * halfHeight
                    val bottom = midY - (minV / 32768f) * halfHeight
                    drawLine(
                        color = waveColor.copy(alpha = 0.55f),
                        start = Offset(i + 0.5f, top.coerceAtMost(bottom)),
                        end = Offset(i + 0.5f, bottom.coerceAtLeast(top)),
                        strokeWidth = 1f,
                    )
                }
            }

            // The viewport rectangle: a frame, so the waveform inside it stays
            // readable, plus a translucent fill to make it obvious.
            val viewport = EditOverview.Viewport(viewportStartMs, viewportSpanMs)
            val rect = EditOverview.rectFor(viewport, totalMs, size.width)
            drawRect(
                color = viewportColor.copy(alpha = 0.12f),
                topLeft = Offset(rect.leftPx, 0f),
                size = Size(rect.widthPx, size.height),
            )
            drawRect(
                color = viewportColor,
                topLeft = Offset(rect.leftPx, 0f),
                size = Size(rect.widthPx, size.height),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}
