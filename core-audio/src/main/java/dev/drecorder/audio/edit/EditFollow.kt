package dev.drecorder.audio.edit

import kotlin.math.roundToLong

/**
 * The window that follows the playhead.
 *
 * One rule decides everything here: **the red line does not move — the audio
 * does.** While the recording plays, the window is derived from the playhead
 * and the line's place on the screen, so the line stands at the same pixel from
 * the first frame to the last and the waveform slides under it. The user has
 * said this twice (2026-10-21 「红色的那个线应该在中间固定不动」, 2026-10-23
 * 「光标不能移动的禁令」), and a page that scrolls in jumps breaks it: the line
 * sweeps across the screen, then snaps back, and the audio stands still until
 * it does. So there is no dead zone, no "scroll when it touches the edge", and
 * no step at all — [followed] is called every frame and always answers with the
 * window the line implies.
 *
 * The line is at the middle unless the user puts it elsewhere. A drag moves the
 * waveform and nothing else (it never changes the moment the line is on), so
 * the place the line ends up at is read back with [lineFraction] and playback
 * then keeps it there. Re-centring the line after a drag would undo the gesture
 * that just moved the window, which is the one thing a gesture handler must not
 * do (m03765).
 *
 * Nothing here clamps the window to the recording's length: the line can stand
 * in the middle of the screen at the first sample, and the time before the
 * recording started shows as empty space. That empty half is not a bug — it is
 * what keeps the line still from the very first frame.
 *
 * Pure and Android-free, so it can be unit-tested off-device.
 */
object EditFollow {

    /**
     * The fraction of the width the playhead is anchored at.
     *
     * The middle of the screen. Half, not three quarters: a line that is not
     * in the middle is a line the user did not put there.
     */
    const val ANCHOR_FRACTION = 0.5f

    /**
     * The window that puts [playheadMs] at [lineFraction] of the width.
     *
     * This is what a frame of playback asks for. It is deliberately not
     * clamped: a window that is pushed back inside the recording would move
     * the line, which is the thing this function exists to prevent.
     *
     * @param playheadMs the moment that must stand at [lineFraction]
     * @param spanMs how much of the recording is on screen
     * @param lineFraction where on the screen the line stands; 0.5 = middle
     */
    fun followed(
        playheadMs: Long,
        spanMs: Long,
        lineFraction: Float = ANCHOR_FRACTION,
    ): EditViewport {
        val span = spanMs.coerceAtLeast(1L)
        return EditViewport(
            startMs = playheadMs - (span * sane(lineFraction)).toLong(),
            spanMs = span,
        )
    }

    /**
     * Where the line stands on the screen in [current].
     *
     * Read after a gesture that moved the window: the line is a moment on the
     * recording, so the window says where that moment is on screen, and that
     * place is what playback has to keep from then on. A moment outside the
     * window is answered with the nearest edge — the line is off screen, and
     * the edge is the direction it went.
     */
    fun lineFraction(current: EditViewport, playheadMs: Long): Float {
        if (current.spanMs <= 0L) return ANCHOR_FRACTION
        val fraction = (playheadMs - current.startMs).toFloat() / current.spanMs.toFloat()
        if (fraction.isNaN()) return ANCHOR_FRACTION
        return fraction.coerceIn(0f, 1f)
    }

    /**
     * The window after the finger dragged the waveform by [deltaMs].
     *
     * Dragging scrolls the timeline: the content moves with the finger and the
     * window moves by the same amount of time. The playhead is **not** touched
     * — it is a moment on the recording, and scrolling does not change where
     * the recording is (m00678).
     *
     * The window is not stopped at the first and last sample of the recording —
     * it may be dragged a whole window past either end, leaving empty space. A
     * timeline that dies at the edge it happens to be near reads as broken, and
     * that is what 「波形滑动太死」 was: the recording's length, not the cursor,
     * decided whether the waveform could still move (m00678).
     */
    fun panned(current: EditViewport, deltaMs: Long, totalMs: Long): EditViewport {
        val span = current.spanMs.coerceAtLeast(1L)
        val total = totalMs.coerceAtLeast(0L)
        // One full window of empty space on each side: enough that the very
        // first and very last moment can be pulled to the middle of the screen,
        // bounded so the recording cannot be scrolled out of sight entirely.
        val minStart = -span
        val maxStart = (total - 1L).coerceAtLeast(0L)
        return EditViewport(
            startMs = (current.startMs + deltaMs).coerceIn(minStart, maxStart),
            spanMs = span,
        )
    }

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

    /** A fraction that can be used as a position: not NaN, inside the width. */
    private fun sane(fraction: Float): Float =
        if (fraction.isNaN()) ANCHOR_FRACTION else fraction.coerceIn(0f, 1f)
}
