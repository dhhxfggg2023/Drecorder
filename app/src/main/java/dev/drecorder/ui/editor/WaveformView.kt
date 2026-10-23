package dev.drecorder.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drecorder.audio.edit.EditFollow
import dev.drecorder.audio.edit.EditMarkers
import dev.drecorder.audio.edit.EditSegment
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
 * - one finger drags the timeline: the audio moves with the finger, and a flick
 *   keeps it moving and slowing down after the finger lifts;
 * - a tap says where to listen from — the red line cannot move, so the audio
 *   scrolls until the tapped moment stands under it;
 * - a tap on a cut point picks that cut point;
 * - two fingers zoom and pan.
 *
 * The gestures live on the **whole** waveform area, not on the drawn bars: a
 * pinch that only works inside a strip a few hundred pixels tall is a pinch the
 * user cannot find (m00678).
 *
 * There is no gesture that drags the playhead itself: the line is pinned to the
 * middle of the screen and a drag moves the recording under it (2026-10-23
 * 「红色光标固定在屏幕中间，不要动」).
 *
 * There is no gesture that drags a cut point either: a cut point is a decision
 * the user made about where the audio should be divided, and a finger that
 * slides one by a few milliseconds while scrolling turns that decision into a
 * different one by accident (m01839). Cut points are dropped, picked and
 * removed — never moved.
 *
 * The viewport is owned by the editor screen: several recordings edited in one
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
    onTimelineDrag: (windowDeltaMs: Long) -> Unit,
    onMarkerSelect: (ms: Long) -> Unit = {},
    onSegmentTap: (timeMs: Long) -> Unit = {},
    onViewportGesture: (zoomFactor: Float, panFraction: Float) -> Unit,
    onDraggingChanged: (dragging: Boolean) -> Unit = {},
    /** How fast the timeline should keep scrolling after the finger lifts, in ms/s. */
    onFling: (velocityMsPerSecond: Float) -> Unit = {},
    modifier: Modifier = Modifier,
    /** A fixed height, or null to take whatever height the parent offers. */
    height: Dp? = 200.dp,
    waveColor: Color = Color(0xFF3D7EFF),
    removedColor: Color = Color(0xFF9AA0A6),
    rulerColor: Color = Color(0x66000000),
    markerColor: Color = Color(0xFFFFA000),
    playheadColor: Color = Color(0xFFE53935),
    /** The stretch the user has picked, drawn as a highlight under the audio. */
    selectedRange: EditSegment? = null,
    selectionColor: Color = Color(0x553D7EFF),
) {
    val textMeasurer = rememberTextMeasurer()
    var widthPx by remember { mutableIntStateOf(0) }

    // One column of the binning is one *pixel*, and the columns are filled as a
    // single continuous outline: a waveform, not a fence. The old drawing put a
    // capsule every 4 dp with a gap between them, which the user described as
    // 「离散图像」 (a picture made of separate pieces) — bars you can count are
    // not a waveform (2026-10-23).
    val columnStepPx = 1f

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
    val markerSelect by rememberUpdatedState(onMarkerSelect)
    val segmentTap by rememberUpdatedState(onSegmentTap)
    val viewportGesture by rememberUpdatedState(onViewportGesture)
    val draggingChanged by rememberUpdatedState(onDraggingChanged)
    val fling by rememberUpdatedState(onFling)

    // The columns are anchored to the **recording's** time grid, not to the
    // screen: one column is one slice of audio, fixed, and the whole outline is
    // translated by the fraction of a column the window has scrolled past.
    // Binning them to the screen instead re-groups the same audio into
    // different columns on every frame, so every column changes height as it
    // travels and the waveform shimmers — which is part of 「手感很烂，波形移动的
    // 时候」. The heights now only change when the window has moved a whole
    // column, by which time the columns are the same picture shifted by exactly
    // one column. The maths is in core-audio and unit-tested off-device.
    val columnsAcross = if (widthPx > 0) widthPx else 1
    val columnMs = if (spanMs > 0L) (spanMs / columnsAcross).coerceAtLeast(1L) else 1L
    val alignedStartMs = WaveformBins.gridStart(startMs, columnMs)
    val columns = remember(peaks, alignedStartMs, columnMs, columnsAcross, widthPx) {
        if (widthPx <= 0) {
            WaveformColumns.EMPTY
        } else {
            // Two extra columns: one for the part scrolled off the left edge
            // and one for the part that just came in on the right.
            WaveformBins.buildBars(peaks, alignedStartMs, columnMs, columnsAcross + 2)
        }
    }
    // How far the window has scrolled into the first column, in pixels.
    val shiftPx = (startMs - alignedStartMs).toFloat() / columnMs.toFloat() * columnStepPx

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (height != null) Modifier.height(height) else Modifier.fillMaxHeight())
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    // What the finger was doing when it left is what a fling
                    // is: a tracker over the last movements is the difference
                    // between the timeline coasting and the timeline stopping
                    // dead under the finger (「灵动性太差」, 2026-10-23).
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)

                    // What the finger grabbed decides only what a *tap* means:
                    // a cut point, or the stretch around it. A drag always
                    // drags the recording, cut point or not — a cut point is
                    // never moved (m01839).
                    val grabbedMarker = hitMarker(
                        down.position.x,
                        currentWidth.toFloat(),
                        currentStart,
                        currentSpan,
                        currentMarkers,
                    )
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
                            val panFraction = if (currentWidth > 0) {
                                pan.x / currentWidth.toFloat()
                            } else {
                                0f
                            }
                            viewportGesture(zoom, panFraction)
                            pressed.forEach { it.consume() }
                        } else {
                            val change = pressed.first()
                            val dx = change.position.x - lastX
                            lastX = change.position.x
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (mode == GestureMode.UNDECIDED &&
                                abs(change.position.x - down.position.x) > viewConfiguration.touchSlop
                            ) {
                                mode = GestureMode.DRAG
                                // The editor has to know that a drag started, not
                                // just that one ended: while the finger moves the
                                // recording, the playhead must not also drag the
                                // window, or the two cancel out and the waveform
                                // stands still under a moving finger (m03765).
                                draggingChanged(true)
                            }
                            if (mode == GestureMode.DRAG) {
                                // The recording is dragged: the content moves
                                // with the finger, so a finger moving right
                                // (positive dx) shows *earlier* audio, which is
                                // a negative move of the window. The playhead is
                                // not touched by a drag — it is a position on
                                // the recording, and scrolling does not change
                                // where the recording is (m00678).
                                val deltaMs = -EditFollow.pixelsToMs(
                                    dx,
                                    currentSpan,
                                    currentWidth.toFloat(),
                                )
                                if (deltaMs != 0L) timelineDrag(deltaMs)
                                change.consume()
                            }
                        }
                    }

                    // The editor has to know that the finger is down: while
                    // it is, the playhead does not drive the window, because
                    // the finger already does.
                    if (mode == GestureMode.DRAG) {
                        draggingChanged(false)
                        // The finger let go while still moving: the timeline
                        // keeps going at that speed and slows down, the way a
                        // scroll does everywhere else.
                        val speedPxPerSecond = tracker.calculateVelocity().x
                        if (abs(speedPxPerSecond) > FLING_MIN_SPEED_PX_PER_S) {
                            fling(
                                -EditFollow.pixelsToMs(
                                    speedPxPerSecond,
                                    currentSpan,
                                    currentWidth.toFloat(),
                                ).toFloat(),
                            )
                        }
                    }

                    // A finger that went down and never moved is a tap. On a
                    // cut point it picks that cut point; on the audio it picks
                    // the stretch between the two cut points around it **and**
                    // says where to listen from, so the line lands under the
                    // finger and the waveform does not move (m00678: 「不就是在
                    // 光标处开始播放」).
                    if (mode == GestureMode.UNDECIDED) {
                        val marker = grabbedMarker
                        if (marker != null) {
                            markerSelect(marker)
                        } else {
                            segmentTap(
                                timeAt(
                                    down.position.x,
                                    currentWidth,
                                    currentStart,
                                    currentSpan,
                                ),
                            )
                        }
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            if (widthPx <= 0) return@Canvas
            val rulerHeight = 20.dp.toPx()
            val waveTop = rulerHeight
            val waveBottom = size.height
            val midY = (waveTop + waveBottom) / 2f
            val halfHeight = (waveBottom - waveTop) / 2f - 4.dp.toPx()

            // --- the picked stretch: a band under the audio ---
            //
            // It is drawn first, so the waveform stays readable on top of it,
            // and it is what the small buttons below the waveform act on.
            selectedRange?.let { range ->
                val left = msToX(range.startMs, size.width, startMs, spanMs)
                val right = msToX(range.endMs, size.width, startMs, spanMs)
                if (right >= 0f && left <= size.width) {
                    val clippedLeft = max(0f, left)
                    val clippedRight = min(size.width, right)
                    drawRect(
                        color = selectionColor,
                        topLeft = Offset(clippedLeft, waveTop),
                        size = Size(clippedRight - clippedLeft, waveBottom - waveTop),
                    )
                    // The two edges are what the user aimed at, so they are
                    // drawn: a selection that is only a tint is hard to read
                    // against a busy waveform.
                    for (x in floatArrayOf(clippedLeft, clippedRight)) {
                        drawLine(
                            color = selectionColor.copy(alpha = 1f),
                            start = Offset(x, waveTop),
                            end = Offset(x, waveBottom),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                }
            }

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

            // --- the waveform itself: one continuous envelope ---
            //
            // Drawn as a single filled outline rather than a row of separate
            // capsules: the user asked for a continuous waveform, not a fence
            // (「波形要连续，不要离散图像」, 2026-10-23). Per column the outline
            // goes to the loudest and the quietest sample of that column, which
            // is what a waveform is — the shape of the audio, not a decoration.
            drawEnvelope(
                columns = columns,
                shiftPx = shiftPx,
                columnStepPx = columnStepPx,
                midY = midY,
                halfHeight = halfHeight,
                color = waveColor,
            )

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
            // The line stands where it stands — and during playback the window
            // is what moves, so it stands *still*: the follow rule keeps its
            // moment at the anchor and the audio slides under it (m00678,
            // 「光标不能移动」). It is off the anchor only when the user has put it
            // there, by tapping somewhere else or by dragging the window away
            // from it; then a stub on the edge says which way it went.
            playheadMs?.let { at ->
                val x = msToX(at, size.width, startMs, spanMs)
                if (x < 0f || x > size.width) {
                    // The window was dragged away from the playhead: a stub on
                    // the edge says which way it went, so it can be found again.
                    val stubX = if (x < 0f) 4.dp.toPx() else size.width - 4.dp.toPx()
                    drawCircle(
                        color = playheadColor.copy(alpha = 0.55f),
                        radius = 5.dp.toPx(),
                        center = Offset(stubX, waveTop + 10.dp.toPx()),
                    )
                } else {
                    drawLine(
                        color = playheadColor,
                        start = Offset(x, 0f),
                        end = Offset(x, waveBottom),
                        strokeWidth = 2.dp.toPx(),
                    )
                    // A knob at the top, so the line is readable against a busy
                    // waveform.
                    drawCircle(
                        color = playheadColor,
                        radius = 7.dp.toPx(),
                        center = Offset(x, 9.dp.toPx()),
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

private enum class GestureMode { UNDECIDED, DRAG, TRANSFORM }

/**
 * How wide the flag of a marker is, in pixels.
 *
 * The flag is what the user aims at, so it is drawn big: a flag that is
 * twenty pixels wide can be hit with a finger, one that is three cannot.
 */
private const val MARKER_FLAG_PX = 24f

/**
 * How fast a finger has to be moving when it lifts to keep the timeline
 * scrolling, in pixels per second.
 *
 * Below this the gesture was a placement, not a throw: a slow drag that ends
 * with the finger rolling off should leave the timeline where it was put.
 */
private const val FLING_MIN_SPEED_PX_PER_S = 120f

/**
 * The waveform as one filled outline.
 *
 * Each column puts the top of the shape at its loudest sample and the bottom at
 * its quietest, and consecutive columns are joined, so the result is a
 * continuous shape rather than a row of separate bars (「波形要连续，不要离散图像」,
 * 2026-10-23). Columns with no audio in them break the shape instead of being
 * drawn as silence: past the end of the recording there is nothing to draw, and
 * a flat line there would claim there is.
 *
 * Pure drawing: the column data and the shift are computed by the caller.
 */
private fun DrawScope.drawEnvelope(
    columns: WaveformColumns,
    shiftPx: Float,
    columnStepPx: Float,
    midY: Float,
    halfHeight: Float,
    color: Color,
) {
    if (columns.count == 0) return

    fun fillRun(from: Int, to: Int) {
        val path = Path()
        for (index in from..to) {
            val x = index * columnStepPx - shiftPx
            val y = midY - (columns.maxs[index] / 32768f) * halfHeight
            if (index == from) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (index in to downTo from) {
            val x = index * columnStepPx - shiftPx
            val y = midY - (columns.mins[index] / 32768f) * halfHeight
            path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color)
    }

    var runStart = -1
    var index = 0
    while (index <= columns.count) {
        val empty = index == columns.count || columns.mins[index] == Int.MAX_VALUE
        if (empty) {
            if (runStart >= 0) {
                fillRun(runStart, index - 1)
                runStart = -1
            }
        } else if (runStart < 0) {
            runStart = index
        }
        index++
    }
}

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
