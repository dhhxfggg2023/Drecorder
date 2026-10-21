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
    ): EditViewport {
        val total = totalMs.coerceAtLeast(1L)
        val span = current.spanMs.coerceIn(1L, total)
        val start = (targetMs - (span * ANCHOR_FRACTION).toLong())
            .coerceIn(-span / 2, total - span / 2)
        return EditViewport(startMs = start, spanMs = span)
    }

    /**
     * The window that shows [targetMs] after a drag.
     *
     * Dragging the waveform moves the *content*, not the window: the moment
     * the user grabbed stays under their finger. The window is then moved so
     * that moment sits where the finger is (DESIGN §12.4).
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
        val total = totalMs.coerceAtLeast(1L)
        val span = current.spanMs.coerceIn(1L, total)
        val start = (current.startMs + (grabbedMs - targetMs))
            .coerceIn(-span / 2, total - span / 2)
        return EditViewport(startMs = start, spanMs = span)
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
