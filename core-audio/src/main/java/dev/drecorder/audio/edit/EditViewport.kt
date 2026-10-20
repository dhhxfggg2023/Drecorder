package dev.drecorder.audio.edit

import kotlin.math.roundToLong

/**
 * The window of the recording the waveform is showing.
 *
 * The timeline always refers to the **original** file, not to the edited result,
 * so the viewport is expressed in source time (DESIGN §12.4).
 *
 * @param startMs the first moment of the recording that is on screen
 * @param spanMs how much of the recording is on screen
 */
data class EditViewport(
    val startMs: Long,
    val spanMs: Long,
) {
    /** The first moment after the visible window. */
    val endMs: Long get() = startMs + spanMs

    /** The moment at [fraction] (0 = left edge, 1 = right edge) of the window. */
    fun timeAtFraction(fraction: Float): Long =
        startMs + (spanMs * fraction).roundToLong()
}

/**
 * The zoom and pan of the waveform window.
 *
 * These are pure functions of the viewport, the length of the recording and the
 * gesture, so they can be unit-tested without a device — and they have to be:
 * a viewport that drifts out of the recording shows a waveform that does not
 * correspond to the audio, and a viewport that jumps to the wrong place after a
 * pinch is the difference between "feels right" and "broken".
 *
 * The rules the result always satisfies:
 *
 *  - `spanMs` is between [MIN_SPAN_MS] and the length of the recording, so the
 *    window can never be empty or wider than the file;
 *  - `startMs` is between `0` and `length - spanMs`, so the window always shows
 *    a part of the recording that exists;
 *  - the point the gesture focused on stays at the same place on screen.
 */
object EditViewportMath {

    /**
     * The narrowest window the user can zoom into.
     *
     * Two seconds is about the length of a pause between two sentences, which is
     * the finest distinction the cut points of a conversation need.
     */
    const val MIN_SPAN_MS = 2_000L

    /**
     * Applies one zoom/pan gesture to [current].
     *
     * @param current the window before the gesture
     * @param totalMs the length of the recording being viewed
     * @param zoomFactor how much the user pinched; greater than 1 zooms in
     * @param panFraction how far the user dragged, as a fraction of the width
     * @param focusFraction where the gesture happened, as a fraction of the width
     */
    fun apply(
        current: EditViewport,
        totalMs: Long,
        zoomFactor: Float,
        panFraction: Float,
        focusFraction: Float,
    ): EditViewport {
        val total = totalMs.coerceAtLeast(1L)
        // A recording shorter than the minimum span cannot be zoomed into, and
        // the minimum must not be larger than the recording itself.
        val minSpan = MIN_SPAN_MS.coerceAtMost(total)
        val factor = if (zoomFactor.isNaN() || zoomFactor <= 0f) 1f else zoomFactor

        val newSpan = (current.spanMs / factor)
            .coerceIn(minSpan.toFloat(), total.toFloat())
            .toLong()

        // The point the user focused on keeps its place on screen: its time is
        // read from the old window and the new window is placed around it.
        val focus = focusFraction.coerceIn(0f, 1f)
        val focusTime = current.timeAtFraction(focus)
        var newStart = focusTime - (newSpan * focus).toLong()
        newStart -= (panFraction * newSpan).toLong()

        val maxStart = (total - newSpan).coerceAtLeast(0L)
        return EditViewport(
            startMs = newStart.coerceIn(0L, maxStart),
            spanMs = newSpan,
        )
    }
}
