package dev.drecorder.audio.edit

import kotlin.math.roundToLong

/**
 * The window of the recording the waveform is showing.
 *
 * The timeline always refers to the **original** file, not to the edited result,
 * so the window is expressed in source time (DESIGN §12.4).
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

    /** The moment in the middle of the window: where the red line stands. */
    val anchorMs: Long get() = timeAtFraction(EditFollow.ANCHOR_FRACTION)
}

/**
 * The state of the timeline: which moment the red line is on, and how much of
 * the recording is on screen.
 *
 * These two numbers are the whole viewport; the window is [window], derived and
 * never stored. That is the point: the line is always in the middle of the
 * screen, so there is no separate "where the window starts" that could end up
 * disagreeing with it — which is how v0.3.6 let the line drift across the screen
 * and then snap back (2026-10-23 「红色光标固定在屏幕中间，不要动」).
 *
 * Dragging the waveform moves [playheadMs] and pinching changes [spanMs]; with
 * only these two there is nothing for a gesture and the follow loop to fight
 * over (m03765) — one gesture, one state.
 */
data class EditCursor(
    val playheadMs: Long,
    val spanMs: Long,
) {
    /** The window this cursor shows: the line in the middle. */
    val window: EditViewport get() = EditFollow.window(playheadMs, spanMs)
}
