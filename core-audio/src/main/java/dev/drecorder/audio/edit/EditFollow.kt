package dev.drecorder.audio.edit

import kotlin.math.roundToLong

/**
 * The window that follows the playhead.
 *
 * This is what makes the editor behave like a tape: the playhead stands in
 * the **middle** of the screen and the recording slides under it. The red
 * line does not move — the audio does (DESIGN §12.4).
 *
 * The window therefore always places the playhead at the anchor, with no
 * dead zone and no rounding: the line stands at the same pixel from the
 * first frame of the playback to the last, and the waveform moves. A window
 * that chased the playhead would leave the line at the edge of the screen; a
 * window that only caught up in steps would let the line drift and then
 * jump back, which reads as "broken".
 *
 * Because the line stands in the middle, the window may start **before** the
 * recording: the first seconds of a file are shown in the right half of the
 * screen, with nothing to the left of zero. That empty part is not a bug —
 * it is the time before the recording started, and it is what keeps the line
 * in the middle from the very first frame.
 *
 * Pure and Android-free, so it can be unit-tested off-device.
 */
object EditFollow {

    /**
     * The fraction of the width the playhead is anchored at.
     *
     * The middle of the screen. Half, not three quarters: a line that is not
     * in the middle is a line that moves (DESIGN §12.4).
     */
    const val ANCHOR_FRACTION = 0.5f

    /**
     * The window to show when [playheadMs] plays in a recording of [totalMs].
     *
     * @param current the window that is on screen now
     * @param playheadMs where the playhead is
     * @param totalMs the length of the recording
     * @param follow whether the window should follow at all — it does not
     *   while the user is dragging, or while nothing is playing
     */
    fun follow(
        current: EditViewport,
        playheadMs: Long,
        totalMs: Long,
        follow: Boolean = true,
    ): EditViewport {
        if (!follow) return current
        return centered(current, playheadMs, totalMs)
    }

    /**
     * The window that puts [playheadMs] exactly at the anchor.
     *
     * This is the one rule the whole timeline obeys: **the playhead is a place
     * on the recording, not a place on the screen**. It is always drawn at the
     * anchor, so every gesture that moves the recording is expressed as "the
     * playhead is now at this moment", and the window is derived from it. That
     * is what makes the red line stand still while the waveform slides — the
     * line cannot drift, because nothing else is allowed to place the window
     * (DESIGN §12.4).
     *
     * @param playheadMs the moment that must stand at the anchor
     * @param spanMs how much of the recording is on screen
     * @param totalMs the length of the recording
     */
    fun pinned(playheadMs: Long, spanMs: Long, totalMs: Long): EditViewport {
        val total = totalMs.coerceAtLeast(1L)
        val span = spanMs.coerceIn(1L, total)
        val start = (playheadMs - (span * ANCHOR_FRACTION).toLong())
            .coerceIn(-span / 2, total - span / 2)
        return EditViewport(startMs = start, spanMs = span)
    }

    /**
     * The window that puts [targetMs] at the anchor.
     *
     * The window may reach outside the recording — that is how the playhead
     * can stand in the middle of the screen at the very beginning of the
     * file — but it is never pushed so far that the playhead would leave the
     * recording altogether.
     */
    fun centered(
        current: EditViewport,
        targetMs: Long,
        totalMs: Long,
    ): EditViewport = pinned(playheadMs = targetMs, spanMs = current.spanMs, totalMs = totalMs)

    /**
     * The window that shows [targetMs] after a drag.
     *
     * Dragging the waveform moves the *content*, and the content under the
     * anchor is the playhead: so a drag of [grabbedMs] to [targetMs] moves the
     * playhead by the same amount of time, in the opposite direction, and the
     * window is placed around it. The line stays at the anchor; the recording
     * is what moves (DESIGN §12.4).
     *
     * The window is allowed to start before the recording — the same reason
     * the follow rule allows it: the line has to be able to stand in the
     * middle of the screen whatever the moment is.
     */
    fun dragged(
        current: EditViewport,
        grabbedMs: Long,
        targetMs: Long,
        totalMs: Long,
    ): EditViewport {
        // The recording moved by (grabbed - target): dragging the content to
        // the left brings later audio under the anchor, so the playhead
        // advances by exactly that much.
        val movedPlayheadMs = anchorMs(current) + (grabbedMs - targetMs)
        return pinned(playheadMs = movedPlayheadMs, spanMs = current.spanMs, totalMs = totalMs)
    }

    /**
     * The window after a two-finger pinch and pan.
     *
     * A pinch changes how much of the recording is on screen; a two-finger
     * pan drags the recording, which moves the playhead. Neither is allowed
     * to move the line, because the line is the anchor: the window is
     * therefore rebuilt around the playhead after every gesture (DESIGN
     * §12.4).
     *
     * The point the gesture focused on is not preserved, and that is on
     * purpose: with a pinned playhead the thing that must stay put is the
     * line, so a pinch zooms around the playhead rather than around the
     * fingers.
     *
     * @param zoomFactor how much the user pinched; greater than 1 zooms in
     * @param panFraction how far the user dragged, as a fraction of the width
     */
    fun zoomed(
        current: EditViewport,
        playheadMs: Long,
        totalMs: Long,
        zoomFactor: Float,
        panFraction: Float,
    ): EditViewport {
        val total = totalMs.coerceAtLeast(1L)
        val minSpan = EditViewportMath.MIN_SPAN_MS.coerceAtMost(total)
        val factor = if (zoomFactor.isNaN() || zoomFactor <= 0f) 1f else zoomFactor
        val newSpan = (current.spanMs / factor)
            .coerceIn(minSpan.toFloat(), total.toFloat())
            .toLong()
        // Dragging the recording to the left (a negative pan) moves the
        // playhead forward in time, the same way a one-finger drag does.
        val movedPlayheadMs = playheadMs - (panFraction * newSpan).toLong()
        return pinned(playheadMs = movedPlayheadMs, spanMs = newSpan, totalMs = total)
    }

    /**
     * The moment the playhead stands at in a window.
     *
     * The anchor of the window: the moment in the middle of the screen.
     */
    fun anchorMs(viewport: EditViewport): Long =
        viewport.startMs + (viewport.spanMs * ANCHOR_FRACTION).toLong()

    /**
     * How many milliseconds one pixel covers in a window of [spanMs].
     *
     * Used to turn a touch target measured in pixels into a time range, so
     * that a marker is as easy to hit when the whole recording is on screen
     * as when two seconds of it are.
     */
    fun msPerPixel(spanMs: Long, widthPx: Float): Long {
        if (widthPx <= 0f || spanMs <= 0L) return 0L
        return (spanMs.toDouble() / widthPx.toDouble()).roundToLong().coerceAtLeast(1L)
    }

    /**
     * How many milliseconds a movement of [pixels] covers in a window of
     * [spanMs].
     *
     * The drag of the waveform is measured with this: the finger moves, and
     * the recording is scrolled by the same amount of time (DESIGN §12.4).
     */
    fun pixelsToMs(pixels: Float, spanMs: Long, widthPx: Float): Long {
        if (widthPx <= 0f || spanMs <= 0L) return 0L
        return (pixels.toDouble() / widthPx.toDouble() * spanMs.toDouble()).roundToLong()
    }
}
