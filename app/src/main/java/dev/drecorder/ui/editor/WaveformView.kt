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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drecorder.audio.edit.ColumnPageCache
import dev.drecorder.audio.edit.EditFollow
import dev.drecorder.audio.edit.EditMarkers
import dev.drecorder.audio.edit.EditSegment
import dev.drecorder.audio.edit.PeakData
import dev.drecorder.audio.edit.WaveformBins
import dev.drecorder.audio.edit.WaveformColumns
import dev.drecorder.audio.edit.WaveformGain
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
    spanMs: Long,
    // Read as a function, never as a value: the playhead changes every frame
    // while the player runs, and reading it in composition would recompose the
    // whole editor page (「波形移动的帧率太低，看起来卡卡的」, 2026-10-23). The
    // draw block reads it instead, which costs a redraw.
    playhead: () -> Long,
    markers: List<Long>,
    onTimelineDrag: (windowDeltaMs: Long) -> Unit,
    onMarkerSelect: (ms: Long) -> Unit = {},
    onSegmentTap: (timeMs: Long) -> Unit = {},
    onViewportGesture: (zoomFactor: Float, panFraction: Float) -> Unit,
    onDraggingChanged: (dragging: Boolean) -> Unit = {},
    /** How fast the timeline should keep scrolling after the finger lifts, in ms/s. */
    onFling: (velocityMsPerSecond: Float) -> Unit = {},
    /** The width the waveform got, so the editor can ask for the detail it needs. */
    onWidthPxChanged: (widthPx: Int) -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * The height of the drawn waveform band, or null to fill the whole area.
     *
     * Only the picture is this tall. The **touch area** is the whole composable,
     * which is why the two are separate: a finger returning to the timeline aims
     * at the sound, not at a 264 dp strip, and a pinch that only works inside that
     * strip is a pinch the user cannot find (「我想用上下更大的区域缩放」). The band
     * stays vertically centred in whatever space it is given.
     */
    bandHeight: Dp? = 200.dp,
    waveColor: Color = Color(0xFF3D7EFF),
    rulerColor: Color = Color(0x66000000),
    markerColor: Color = Color(0xFFFFA000),
    playheadColor: Color = Color(0xFFE53935),
    /** The stretch the user has picked, drawn as a highlight under the audio. */
    selectedRange: EditSegment? = null,
    selectionColor: Color = Color(0x553D7EFF),
) {
    val textMeasurer = rememberTextMeasurer()
    var widthPx by remember { mutableIntStateOf(0) }

    // One drawn column is one slice of the cached peaks — never finer, see
    // WaveformBins.stepMs — and the whole outline is filled as one shape.

    // The gesture callback runs the whole time a finger is down, and the
    // gestures change the viewport *while* they run. Reading these through
    // rememberUpdatedState keeps one long-lived pointerInput block alive:
    // keying it on startMs/spanMs would cancel the very gesture that changed
    // them.
    val currentPlayhead by rememberUpdatedState(playhead)
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
    // 时候」. The height of a column now only changes when the window has moved a
    // whole column, by which time the picture is the same, shifted by exactly
    // one column.
    //
    // The binning itself happens in the draw block, not here: the window start
    // is a function of the playhead, and computing it in composition is what
    // made every frame a recomposition. The maths is in core-audio and
    // unit-tested off-device.
    val rulerLabels = remember { mutableMapOf<Long, TextLayoutResult>() }

    Box(
        // Fills the space the editor gives it: this is the touch area, and it is
        // deliberately much taller than the picture drawn inside it.
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .onSizeChanged {
                widthPx = it.width
                onWidthPxChanged(it.width)
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    // What the finger was doing when it left is what a fling
                    // is: a tracker over the last movements is the difference
                    // between the timeline coasting and the timeline stopping
                    // dead under the finger (「灵动性太差」, 2026-10-23).
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)

                    // Where the window starts, as of this touch. A drag moves
                    // the playhead, so the mapping from pixels to moments is
                    // only meaningful before the finger moves.
                    val downStartMs = EditFollow.window(currentPlayhead(), currentSpan).startMs

                    // What the finger grabbed decides only what a *tap* means:
                    // a cut point, or the stretch around it. A drag always
                    // drags the recording, cut point or not — a cut point is
                    // never moved (m01839).
                    val grabbedMarker = hitMarker(
                        down.position.x,
                        currentWidth.toFloat(),
                        downStartMs,
                        currentSpan,
                        currentMarkers,
                    )
                    var lastX = down.position.x
                    var mode = GestureMode.UNDECIDED
                    // Tracked separately from [mode]: a second finger turns the
                    // gesture into a pinch, which used to leave the drag flag set
                    // for ever. The follow loop then refused to move the window
                    // again until the user completed a fresh drag.
                    var dragged = false

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
                                dragged = true
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
                    // Cleared for any gesture that ever became a drag, whatever
                    // it ended as. Guarding on `mode == DRAG` left the flag set
                    // when a second finger joined mid-drag.
                    if (dragged) draggingChanged(false)

                    if (mode == GestureMode.DRAG) {
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
                                    downStartMs,
                                    currentSpan,
                                ),
                            )
                        }
                    }
                }
            },
    ) {
        // How much audio one drawn column covers.
        //
        // It depends on the data, the span and the width — never on the playhead
        // — so it is decided here, in composition, and a scroll inside one
        // column changes nothing.
        val columnMs = remember(peaks, spanMs, widthPx) {
            WaveformBins.stepMs(peaks, spanMs, widthPx)
        }
        // How much the waveform is magnified so a quiet recording fills the band
        // instead of being a ribbon in an empty one (「波形太小了」). Decided from
        // the whole recording, not the window, so the picture does not change
        // scale as it scrolls.
        val gain = remember(peaks) { WaveformGain.gainFor(peaks) }
        // The reduced columns, kept between frames and reduced a page at a time
        // (see [ColumnPage]): the reduction is the expensive half of drawing,
        // and the draw block runs on every playhead tick.
        val pages = remember { ColumnPageCache() }

        Canvas(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            if (widthPx <= 0) return@Canvas
            // The one place the playhead is read: inside the draw block. A
            // state read here invalidates the draw, not the composition, so the
            // picture can move sixty times a second without the page above being
            // rebuilt sixty times a second.
            val startMs = EditFollow.window(playhead(), spanMs).startMs
            // How much audio one drawn column covers. Never finer than a cached
            // bucket: finer than that is the same bucket repeated across several
            // pixels, which is what made a zoomed-in waveform a row of
            // rectangles instead of peaks (2026-10-23).
            val alignedStartMs = WaveformBins.gridStart(startMs, columnMs)
            // One extra column for the part scrolled off the left edge, one for
            // the part coming in on the right, and a margin so the rounding of
            // the pixel width never leaves a gap at the end.
            val barCount = ((spanMs / columnMs).toInt() + 3).coerceAtLeast(1)
            val columns = pages.columnsFor(peaks, alignedStartMs, columnMs, barCount)
            // Where those columns land on screen — a column is [stepPx] wide, not
            // necessarily one pixel.
            //
            // Never narrower than one stick: below that the sticks would draw
            // over each other, and the row would be pressed together while the
            // shift below kept the unscaled width, sliding the picture away from
            // the audio it belongs to. Clamped here, once, so the drawing and the
            // shift cannot disagree.
            val stepPx = (
                if (spanMs > 0L) {
                    widthPx.toFloat() * columnMs.toFloat() / spanMs.toFloat()
                } else {
                    widthPx.toFloat()
                }
                ).coerceAtLeast(STICK_WIDTH_PX)
            // How far the window has scrolled into the first column, in pixels.
            val shiftPx = (startMs - alignedStartMs).toFloat() / columnMs.toFloat() * stepPx
            // The picture occupies a band of [bandHeight], centred in the touch
            // area. Everything below is expressed in the band's own coordinates,
            // so the drawing code never has to know how tall the touch area is —
            // and the gestures never have to know how tall the picture is.
            val rulerHeight = 20.dp.toPx()
            val band = if (bandHeight != null) {
                bandHeight.toPx().coerceAtMost(size.height)
            } else {
                size.height
            }
            val bandTop = (size.height - band) / 2f
            val waveTop = bandTop + rulerHeight
            val waveBottom = bandTop + band
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

            // There is no "removed stretch" overlay any more: the editor's track
            // is a list of clips (EditTrack), so a deleted stretch is not
            // in the track at all and its greyed-out, struck-through ghost below
            // was drawn from a list the screen always passed empty. Drawing a
            // feature from a caller that can never supply it is worse than
            // missing: it reads as working code.

            // --- the waveform itself: thin sticks, one per column ---
            //
            // The user asked for this shape twice: not a discrete row of
            // capsules (2026-10-23), and not a filled blob either
            // (「细火柴棍就行」, 2026-11-04).
            drawEnvelope(
                columns = columns,
                shiftPx = shiftPx,
                stepPx = stepPx,
                midY = midY,
                halfHeight = halfHeight,
                gain = gain,
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
            // Always exactly in the middle, always: the window is derived from
            // the playhead, so nothing can move the line — the audio slides
            // under it (2026-10-21/22/23 「红色光标固定在屏幕中间，不要动」).
            run {
                val x = size.width / 2f
                drawLine(
                    color = playheadColor,
                    start = Offset(x, bandTop),
                    end = Offset(x, waveBottom),
                    strokeWidth = 2.dp.toPx(),
                )
                // A knob at the top, so the line is readable against a busy
                // waveform.
                drawCircle(
                    color = playheadColor,
                    radius = 7.dp.toPx(),
                    center = Offset(x, bandTop + 9.dp.toPx()),
                )
            }

            // --- time ruler ---
            drawRuler(
                textMeasurer = textMeasurer,
                labels = rulerLabels,
                width = size.width,
                startMs = startMs,
                spanMs = spanMs,
                rulerHeight = rulerHeight,
                rulerTop = bandTop,
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
 * The waveform as thin matchsticks.
 *
 * One vertical stick per column, from the quietest sample the column holds to
 * the loudest, mirrored around the centre line — the way every ordinary recorder
 * draws sound. The filled outline this replaced joined neighbouring columns into
 * one continuous body, and the user rejected it twice over: first as discrete
 * blocks when the columns were coarse (2026-10-23), then as a blob when they
 * were joined (「波形不要边缘连续，不好看，细火柴棍就行」, 2026-11-04). Sticks are
 * what was asked for both times: the quietest-to-loudest span is the honest
 * content of the column, and a silent stretch reads as a thin line on the
 * centre instead of a filled slab claiming there is sound.
 *
 * The sticks are drawn as **one path of vertical segments** rather than as one
 * `drawLine` per column. At two-pixel columns a phone-wide waveform is five
 * hundred sticks, six hundred times a minute while the picture plays: each
 * `drawLine` is a draw call across the JNI boundary, and five hundred of them
 * per frame is the difference between a waveform that glides and one that
 * stutters (「波形移动的帧率太低，看起来卡卡的」, 2026-10-23). One path is one
 * call however many sticks it holds.
 *
 * Pure drawing: the column data and the shift are computed by the caller.
 */
private fun DrawScope.drawEnvelope(
    columns: WaveformColumns,
    shiftPx: Float,
    stepPx: Float,
    midY: Float,
    halfHeight: Float,
    /** How much to magnify; see [WaveformGain]. */
    gain: Float,
    color: Color,
) {
    if (columns.count == 0) return

    val step = stepPx.coerceAtLeast(STICK_WIDTH_PX)
    var drawn = false
    // Two passes over the same columns: the peak span in the quiet colour, then
    // the mean amplitude in the loud one. The peak alone is a hairy wall — every
    // column reaches as high as its loudest sample, so speech and noise look the
    // same — while the mean is the loudness contour, which is what the eye reads
    // as "someone is talking here". Drawing the mean *inside* the peak keeps
    // both facts visible at once instead of choosing between them.
    val body = Path()
    val core = Path()

    for (index in 0 until columns.count) {
        // A column with no decoded audio draws nothing: past the end of the
        // recording there is nothing to claim.
        if (columns.isEmpty(index)) continue
        val x = index * step - shiftPx
        if (x < -STICK_WIDTH_PX || x > size.width + STICK_WIDTH_PX) continue
        // Screen y grows downwards, so the loudest sample is drawn *above* the
        // centre and the quietest *below* it: subtracting, not adding. Adding
        // flips the waveform upside down, which is invisible on symmetric test
        // audio and wrong on every real recording.
        val above = (columns.maxAt(index) / 32768f * gain).coerceIn(-1f, 1f) * halfHeight
        val below = (columns.minAt(index) / 32768f * gain).coerceIn(-1f, 1f) * halfHeight
        // Zero-length segments are what a silent stretch looks like, and a
        // moveTo/lineTo pair of the same point would draw nothing at all — the
        // centre line has to be *there* to say "this is silence", not missing.
        val top = midY - above
        val bottom = midY - below
        body.moveTo(x, top)
        body.lineTo(x, if (bottom - top < MIN_STICK_PX) top + MIN_STICK_PX else bottom)

        // The mean, mirrored the same way. Never taller than the peak span it
        // sits inside, and never shorter than a dot: a quiet column still has to
        // show that there is audio there.
        val level = (columns.meanAt(index) / 32768f * gain).coerceIn(0f, 1f) * halfHeight
        val coreTop = midY - level
        val coreBottom = midY + level
        core.moveTo(x, coreTop)
        core.lineTo(
            x,
            if (coreBottom - coreTop < MIN_STICK_PX) coreTop + MIN_STICK_PX else coreBottom,
        )
        drawn = true
    }
    if (!drawn) return
    // The peak first, thin and dim, so the core reads as light in front of it.
    drawPath(
        path = body,
        color = color.copy(alpha = PEAK_ALPHA),
        style = Stroke(width = STICK_WIDTH_PX, cap = StrokeCap.Round),
    )
    drawPath(
        path = core,
        color = lerp(color, Color.White, CORE_WHITE_MIX),
        style = Stroke(width = STICK_WIDTH_PX, cap = StrokeCap.Round),
    )
}

/** How wide one matchstick is drawn, in pixels. */
private const val STICK_WIDTH_PX = 2f

/**
 * How opaque the peak span is, relative to the mean core.
 *
 * The peak reaches the loudest sample of the column, which for speech is nearly
 * always near full scale — opaque, every column would be a tall solid stick and
 * the picture a solid block. Held back to a third, it reads as the reach of the
 * sound while the core carries the shape.
 */
private const val PEAK_ALPHA = 0.34f

/**
 * How far the mean core is lightened towards white.
 *
 * The core is the same width as the peak stick — a *wider* core was rejected:
 * at four pixels the neighbouring cores touch and the picture collapses back
 * into the continuous filled body the user turned down (「波形不要边缘连续」).
 * Colour is what separates the loudness contour from the reach of the sound,
 * and the matchstick shape survives untouched.
 */
private const val CORE_WHITE_MIX = 0.45f

/**
 * The shortest a stick is ever drawn, in pixels.
 *
 * Silence is not nothing: a quiet stretch has to read as a line on the centre,
 * the way every recorder draws it, or the waveform looks like it has holes in
 * it. A round cap two pixels wide needs about this much length to show.
 */
private const val MIN_STICK_PX = 1.5f

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

/** The moment under [x], for a tap that says where to listen from. */
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
    labels: MutableMap<Long, TextLayoutResult>,
    width: Float,
    startMs: Long,
    spanMs: Long,
    rulerHeight: Float,
    /** Where the band starts, so the ruler sits on top of it wherever it is. */
    rulerTop: Float,
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
                start = Offset(x, rulerTop),
                end = Offset(x, rulerTop + rulerHeight),
                strokeWidth = 1f,
            )
            // Measured once per label value, not once per frame: text layout is
            // the most expensive thing in this draw block, and the labels are
            // the same strings every frame. The map is capped: every zoom level
            // and every scroll position produces new label values, and an
            // unbounded map of laid-out text would follow the screen around.
            if (labels.size > MAX_RULER_LABELS) labels.clear()
            val layout = labels.getOrPut(tick) {
                textMeasurer.measure(formatClock(tick), style)
            }
            drawText(layout, topLeft = Offset(x + 2f, rulerTop + 1f))
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

/** How many laid-out ruler labels are kept before the cache is thrown away. */
private const val MAX_RULER_LABELS = 512


