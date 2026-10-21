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
import dev.drecorder.audio.edit.EditFollow
import dev.drecorder.audio.edit.EditMarkers
import dev.drecorder.audio.edit.KeepRange
import dev.drecorder.audio.edit.PeakData
import dev.drecorder.audio.edit.WaveformBins
import dev.drecorder.audio.edit.WaveformColumns
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The waveform of one source file, on a single track.
 *
 * This is the video-editor layout the user asked for: **one** waveform, not a
 * map above a detail view. The playhead stands on it, the markers are pinned
 * to it, and dragging it moves the recording under the finger (DESIGN §12.4).
 *
 * Gestures:
 * - one finger drags the recording, which moves the playhead through time;
 * - one finger on a marker drags that marker;
 * - two fingers pan and zoom.
 *
 * There is no gesture that drags the playhead itself, because the playhead is
 * not a thing on the screen: it is the anchor. Every gesture changes the
 * moment the anchor stands at, and the window is derived from it, so the red
 * line can never move (DESIGN §12.4).
 *
 * The viewport is owned by the editor screen: two sources edited in one
 * session share one window, so the gesture is reported upwards as a request
 * rather than applied here.
 */
@Composable
fun WaveformView(
    peaks: PeakData,
    durationMs: Long,
    startMs: Long,
    spanMs: Long,
    removed: List<KeepRange>,
    markers: List<Long>,
    playheadMs: Long?,
    onTimelineDrag: (playheadDeltaMs: Long) -> Unit,
    onMarkerDrag: (fromMs: Long, toMs: Long) -> Long,
    onMarkerSelect: (ms: Long) -> Unit = {},
    onViewportGesture: (zoomFactor: Float, panFraction: Float, focusFraction: Float) -> Unit,
    onDraggingChanged: (dragging: Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
    waveColor: Color = Color(0xFF3D7EFF),
    removedColor: Color = Color(0xFF9AA0A6),
    rulerColor: Color = Color(0x66000000),
    markerColor: Color = Color(0xFFFFA000),
    playheadColor: Color = Color(0xFFE53935),
) {
    val textMeasurer = rememberTextMeasurer()
    var widthPx by remember { mutableIntStateOf(0) }

    // The gesture callback runs the whole time a finger is down, and the
    // gestures change the viewport *while* they run. Reading these through
    // rememberUpdatedState keeps one long-lived pointerInput block alive:
    // keying it on startMs/spanMs would cancel the very gesture that changed
    // them.
    val currentStart by rememberUpdatedState(startMs)
    val currentSpan by rememberUpdatedState(spanMs)
    val currentWidth by rememberUpdatedState(widthPx)
    val currentMarkers by rememberUpdatedState(markers)
    val timelineDrag by rememberUpdatedState(onTimelineDrag)
    val markerDrag by rememberUpdatedState(onMarkerDrag)
    val markerSelect by rememberUpdatedState(onMarkerSelect)
    val viewportGesture by rememberUpdatedState(onViewportGesture)
    val draggingChanged by rememberUpdatedState(onDraggingChanged)

    // One aggregation per (peaks, window, width): the cached buckets are
    // re-binned into screen columns. It only changes when the viewport or the
    // size changes. The maths is in core-audio so it is unit-tested
    // off-device.
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

                    // What the finger grabbed decides what the drag does: a
                    // marker, or the recording itself. There is no third case:
                    // the playhead cannot be grabbed, because it is the anchor
                    // and never moves (DESIGN §12.4).
                    val grabbedMarker = hitMarker(
                        down.position.x,
                        currentWidth.toFloat(),
                        currentStart,
                        currentSpan,
                        currentMarkers,
                    )
                    // A dragged marker's own time changes as it moves, so the
                    // gesture has to carry its current time: reporting the
                    // grabbed time every event would look like a brand new
                    // marker each time.
                    var markerNowMs: Long? = grabbedMarker
                    var lastX = down.position.x
                    var mode = GestureMode.UNDECIDED

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
                            val dx = change.position.x - lastX
                            lastX = change.position.x
                            if (mode == GestureMode.UNDECIDED &&
                                abs(change.position.x - down.position.x) > viewConfiguration.touchSlop
                            ) {
                                mode = GestureMode.DRAG
                            }
                            if (mode == GestureMode.DRAG) {
                                val marker = markerNowMs
                                if (marker != null) {
                                    // The marker follows the finger; what it
                                    // was moved to becomes the time the next
                                    // step starts from.
                                    val target = timeAt(
                                        change.position.x,
                                        currentWidth,
                                        currentStart,
                                        currentSpan,
                                    )
                                    markerNowMs = markerDrag(marker, target)
                                } else {
                                    // The recording is dragged. The anchor
                                    // stands still on the screen, so the
                                    // recording moving left means the anchor
                                    // is reading a later moment: the playhead
                                    // moves by the opposite of the finger.
                                    val deltaMs = -EditFollow.pixelsToMs(
                                        dx,
                                        currentSpan,
                                        currentWidth.toFloat(),
                                    )
                                    if (deltaMs != 0L) timelineDrag(deltaMs)
                                }
                                change.consume()
                            }
                        }
                    }

                    // The editor has to know that the finger is down: while
                    // it is, the playhead does not drive the window, because
                    // the finger already does.
                    draggingChanged(mode == GestureMode.DRAG)

                    // A finger that went down on a marker and never moved is a
                    // tap: it selects the cut point, so the user can delete it
                    // from the bar. A tap on the recording itself does
                    // nothing — moving the playhead is a drag, and a tap that
                    // also jumped would make the two gestures fight.
                    if (mode == GestureMode.UNDECIDED && grabbedMarker != null) {
                        markerSelect(grabbedMarker)
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            if (widthPx <= 0) return@Canvas
            val rulerHeight = 20.dp.toPx()
            val waveTop = rulerHeight
            val waveBottom = size.height
            val midY = (waveTop + waveBottom) / 2f
            val halfHeight = (waveBottom - waveTop) / 2f - 4.dp.toPx()

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
                // The strike-through is what makes "this is gone" readable at
                // a glance, the way every ordinary recorder draws a cut.
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

            // --- markers: the cut points ---
            for (marker in markers) {
                val x = msToX(marker, size.width, startMs, spanMs)
                if (x < -MARKER_FLAG_PX || x > size.width + MARKER_FLAG_PX) continue
                drawMarker(
                    x = x,
                    top = waveTop,
                    bottom = waveBottom,
                    color = markerColor,
                )
            }

            // --- playhead ---
            //
            // The line is drawn **at the anchor**, not at whatever pixel the
            // playhead's time happens to fall on. The two agree — the window
            // is placed so that the anchor is exactly the playhead's moment —
            // but drawing the anchor is what makes the line unable to move:
            // even a window placed by a bug, or a rounding of one millisecond,
            // cannot shift it by a pixel (DESIGN §12.4).
            playheadMs?.let {
                val x = size.width * EditFollow.ANCHOR_FRACTION
                drawLine(
                    color = playheadColor,
                    start = Offset(x, 0f),
                    end = Offset(x, waveBottom),
                    strokeWidth = 3.dp.toPx(),
                )
                // A knob at the top, so the line is readable against a busy
                // waveform.
                drawCircle(
                    color = playheadColor,
                    radius = 9.dp.toPx(),
                    center = Offset(x, 9.dp.toPx()),
                )
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

private enum class GestureMode { UNDECIDED, DRAG, TRANSFORM }

/**
 * How wide the flag of a marker is, in pixels.
 *
 * The flag is what the user aims at, so it is drawn big: a flag that is
 * twenty pixels wide can be hit with a finger, one that is three cannot.
 */
private const val MARKER_FLAG_PX = 24f

/**
 * The marker within reach of a touch, or null.
 *
 * The reach is a number of pixels — the flag is what the user aims at — but
 * it is **capped in time**: when the whole recording is on screen, forty
 * pixels can be half a minute, and a touch would then grab a marker the user
 * was not aiming at. Capping the reach at [MAX_GRAB_MS] keeps the hit target
 * honest at every zoom level, and the arithmetic itself lives in
 * [EditMarkers.hit] so it can be unit-tested.
 */
private fun hitMarker(
    xPx: Float,
    widthPx: Float,
    startMs: Long,
    spanMs: Long,
    markers: List<Long>,
): Long? {
    if (widthPx <= 0f || markers.isEmpty()) return null
    val fraction = (xPx / widthPx).coerceIn(0f, 1f)
    val touchMs = startMs + (fraction * spanMs).roundToLong()
    val perPixel = EditFollow.msPerPixel(spanMs, widthPx)
    val radiusMs = (MARKER_HIT_PX * perPixel).toLong().coerceAtMost(MAX_GRAB_MS)
    return EditMarkers.hit(markers = markers, timeMs = touchMs, radiusMs = radiusMs)
}

/**
 * How close a touch has to be to a marker to grab it, in pixels.
 */
private const val MARKER_HIT_PX = 40f

/**
 * The longest reach a marker can have, in milliseconds.
 *
 * Half a second is about as much as two adjacent cut points can be apart in
 * an edited recording, so a touch never has to choose between them.
 */
private const val MAX_GRAB_MS = 500L

/** Draws the flag of a marker. */
private fun DrawScope.drawMarker(
    x: Float,
    top: Float,
    bottom: Float,
    color: Color,
) {
    val flagWidth = MARKER_FLAG_PX
    // The line of the cut point runs the whole height of the waveform.
    drawLine(
        color = color.copy(alpha = 0.9f),
        start = Offset(x, top),
        end = Offset(x, bottom),
        strokeWidth = 2.dp.toPx(),
    )
    // The flag is a triangle at the top, pointing to the right, the way every
    // video editor draws a marker.
    val flagHeight = 18.dp.toPx()
    val path = Path().apply {
        moveTo(x, top)
        lineTo(x + flagWidth, top + flagHeight / 2f)
        lineTo(x, top + flagHeight)
        close()
    }
    drawPath(path, color = color)
}

/**
 * Re-bins the cached peaks into one min/max pair per screen column.
 *
 * The cached peaks are fixed at 100 buckets per second; when the user zooms in
 * past that, several buckets share a column and the column shows the extremes
 * of all of them. Zooming out merges many buckets and the column shows their
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
