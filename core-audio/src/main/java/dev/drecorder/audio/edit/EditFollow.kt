package dev.drecorder.audio.edit

import kotlin.math.roundToLong

/**
 * The timeline's cursor: where the red line is, and what follows it.
 *
 * One rule decides everything here: **the red line stands in the middle of the
 * screen and does not move — the audio slides under it.** The user has said so
 * three times (2026-10-21 「红色的那个线应该在中间固定不动」, 2026-10-23
 * 「光标不能移动的禁令」, 2026-10-23 「红色光标固定在屏幕中间，不要动」), so the
 * window is not a piece of state at all: it is [window], derived from the
 * moment and the zoom. There is no dead zone, no "scroll when the line touches
 * the edge", and nothing to jump back from.
 *
 * The consequence, and the reason the model is this small: **the audio under
 * the line is always the moment playback is at.** Dragging the waveform
 * therefore changes the moment — the line keeps its pixel, so scrolling the
 * content under it is what changes the time — and so does pinching, because
 * the same moment has to stay under the line. A gesture never moves the line;
 * it moves the time.
 *
 * Pure and Android-free, so all of it can be unit-tested off-device.
 */
object EditFollow {

    /** The fraction of the width the red line stands at. The middle. */
    const val ANCHOR_FRACTION = 0.5f

    /**
     * The narrowest window the user can zoom into.
     *
     * Two seconds is about the length of a pause between two sentences, which
     * is the finest distinction the cut points of a conversation need.
     */
    const val MIN_SPAN_MS = 2_000L

    /**
     * The window that shows [playheadMs] under the line.
     *
     * The window may reach before the recording or past its end — the line at
     * the first sample leaves the left half of the screen empty — and that is
     * the price of a line that never moves. It is not clamped: a window pushed
     * back inside the recording would move the line, which is the thing this
     * function exists to prevent.
     */
    fun window(playheadMs: Long, spanMs: Long): EditViewport {
        val span = spanMs.coerceAtLeast(1L)
        return EditViewport(
            startMs = playheadMs - (span * ANCHOR_FRACTION).toLong(),
            spanMs = span,
        )
    }

    /**
     * The cursor after the waveform was dragged by [deltaMs].
     *
     * Dragging the content by one window of time changes the moment under the
     * line by the same amount: the audio moved, the line did not. The moment is
     * held inside the recording, because it is a moment of the recording —
     * there is nothing to listen to before the first sample or after the last.
     */
    fun scrubbed(cursor: EditCursor, deltaMs: Long, totalMs: Long): EditCursor {
        val total = totalMs.coerceAtLeast(1L)
        return EditCursor(
            playheadMs = (cursor.playheadMs + deltaMs).coerceIn(0L, total),
            spanMs = cursor.spanMs.coerceIn(MIN_SPAN_MS.coerceAtMost(total), total),
        )
    }

    /** The cursor after the user asked to hear from [atMs] (a tap, a jump). */
    fun seeked(cursor: EditCursor, atMs: Long, totalMs: Long): EditCursor =
        scrubbed(cursor, atMs - cursor.playheadMs, totalMs)

    /**
     * The cursor after one zoom/pan gesture.
     *
     * The pinch zooms **about the line**: the moment being listened to stays
     * under the line and the audio is stretched around it. Zooming about the
     * fingers instead would change which moment the player is at while the user
     * is only trying to look closer, and the line is the one thing that must
     * not move on its own (2026-10-23).
     *
     * @param cursor the cursor before the gesture
     * @param totalMs the length of the recording being viewed
     * @param zoomFactor how much the user pinched; greater than 1 zooms in
     * @param panFraction how far the user dragged, as a fraction of the width
     */
    fun zoomed(
        cursor: EditCursor,
        totalMs: Long,
        zoomFactor: Float,
        panFraction: Float,
    ): EditCursor {
        val total = totalMs.coerceAtLeast(1L)
        // A recording shorter than the minimum span cannot be zoomed into, and
        // the minimum must not be larger than the recording itself.
        val minSpan = MIN_SPAN_MS.coerceAtMost(total)
        val factor = if (zoomFactor.isNaN() || zoomFactor <= 0f) 1f else zoomFactor
        val span = (cursor.spanMs / factor)
            .coerceIn(minSpan.toFloat(), total.toFloat())
            .toLong()
        // Two fingers moving together scroll the timeline exactly like one
        // finger does: the moment changes, the zoom does not.
        val playhead = cursor.playheadMs - (panFraction * span).toLong()

        return EditCursor(
            playheadMs = playhead.coerceIn(0L, total),
            spanMs = span,
        )
    }

    /**
     * How many milliseconds one pixel covers in a window of [spanMs].
     *
     * Used to turn a touch target measured in pixels into a time range, so
     * that a cut point is as easy to hit at every zoom level.
     */
    fun msPerPixel(spanMs: Long, widthPx: Float): Long {
        if (widthPx <= 0f || spanMs <= 0L) return 0L
        return (spanMs.toDouble() / widthPx.toDouble()).roundToLong().coerceAtLeast(1L)
    }

    /**
     * How many milliseconds a movement of [pixels] covers in a window of
     * [spanMs].
     *
     * Also what a fling is measured with: the velocity of the finger in pixels
     * per second becomes the time the timeline keeps scrolling per second.
     */
    fun pixelsToMs(pixels: Float, spanMs: Long, widthPx: Float): Long {
        if (widthPx <= 0f || spanMs <= 0L) return 0L
        return (pixels.toDouble() / widthPx.toDouble() * spanMs.toDouble()).roundToLong()
    }
}
