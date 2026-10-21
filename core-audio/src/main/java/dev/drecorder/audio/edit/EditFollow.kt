package dev.drecorder.audio.edit

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The window that follows the playhead.
 *
 * This is what makes the editor behave like a video editor: while the audio
 * plays, the waveform scrolls and the playhead stays where it is on screen
 * (DESIGN §12.4).
 *
 * The rule every editor uses is that the playhead is **anchored**: it keeps a
 * fixed place on the screen and the waveform moves under it. If the window
 * instead chased the playhead, the playhead would sit still at the edge and
 * the user would lose the thread; if the window jumped every time the
 * playhead moved, the waveform would jitter.
 *
 * The window therefore only moves when the playhead comes too close to the
 * edge of the screen, and it moves by exactly as much as is needed to put the
 * playhead back in the middle. The result is a waveform that scrolls in
 * steady steps instead of drifting every frame.
 *
 * Pure and Android-free, so it can be unit-tested off-device.
 */
object EditFollow {

    /**
     * The fraction of the width the playhead is anchored at.
     *
     * Three quarters from the left: the audio the user is about to hear is
     * ahead of the playhead, so most of the screen shows what is coming.
     */
    const val ANCHOR_FRACTION = 0.75f

    /**
     * How close the playhead may get to the edge of the screen, as a fraction
     * of the width, before the window follows.
     *
     * The window is moved when the playhead would leave the middle band of
     * the screen. Without a dead zone the window would move on every single
     * frame, and the waveform would shimmer.
     */
    const val DEAD_ZONE_FRACTION = 0.1f

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
        val total = totalMs.coerceAtLeast(1L)
        val span = current.spanMs.coerceIn(1L, total)
        val maxStart = (total - span).coerceAtLeast(0L)

        // Where the playhead is on screen, as a fraction of the width.
        val fraction = (playheadMs - current.startMs).toFloat() / span.toFloat()
        val low = ANCHOR_FRACTION - DEAD_ZONE_FRACTION
        val high = ANCHOR_FRACTION + DEAD_ZONE_FRACTION

        if (fraction in low..high) return current

        // The playhead has left the band: put it back at the anchor, which
        // means moving the window by exactly that much.
        val wanted = playheadMs - (span * ANCHOR_FRACTION).toLong()
        val start = wanted.coerceIn(0L, maxStart)
        if (start == current.startMs) return current
        return EditViewport(startMs = start, spanMs = span)
    }

    /**
     * The window that shows [targetMs] after a drag.
     *
     * Dragging the waveform moves the *content*, not the window: the moment
     * the user grabbed stays under their finger. The window is then moved so
     * that moment sits where the finger is (DESIGN §12.4).
     */
    fun dragged(
        current: EditViewport,
        grabbedMs: Long,
        targetMs: Long,
        totalMs: Long,
    ): EditViewport {
        val total = totalMs.coerceAtLeast(1L)
        val span = current.spanMs.coerceIn(1L, total)
        val maxStart = (total - span).coerceAtLeast(0L)
        val start = (current.startMs + (grabbedMs - targetMs)).coerceIn(0L, maxStart)
        return EditViewport(startMs = start, spanMs = span)
    }

    /**
     * The window that shows [targetMs] in the middle of the screen.
     *
     * Used when the user jumps to a marker or to a moment that was typed in.
     */
    fun centered(
        current: EditViewport,
        targetMs: Long,
        totalMs: Long,
    ): EditViewport {
        val total = totalMs.coerceAtLeast(1L)
        val span = current.spanMs.coerceIn(1L, total)
        val maxStart = (total - span).coerceAtLeast(0L)
        val start = (targetMs - span / 2).coerceIn(0L, maxStart)
        return EditViewport(startMs = start, spanMs = span)
    }

    /**
     * The window and the playhead, for a scrub.
     *
     * Scrubbing is the user moving the playhead with a finger. The playhead
     * goes where the finger is, and the waveform slides under it once the
     * finger reaches the edge of the window — that is what makes it possible
     * to scrub through a long recording without letting go.
     *
     * The window only moves in the edge zones (the outer [EDGE_FRACTION] at
     * either end), so the moment under the finger does not jump while the
     * user is still in the middle of the window.
     *
     * @param current the window that is on screen now
     * @param fraction where the finger is, as a fraction of the width
     * @param totalMs the length of the recording
     */
    fun scrub(
        current: EditViewport,
        fraction: Float,
        totalMs: Long,
    ): Pair<EditViewport, Long> {
        val total = totalMs.coerceAtLeast(1L)
        val span = current.spanMs.coerceIn(1L, total)
        val maxStart = (total - span).coerceAtLeast(0L)
        val f = fraction.coerceIn(0f, 1f)

        // The window slides by exactly as much as the finger is past the
        // edge, so the playhead stays under the finger and the content moves
        // under it. The amount is rounded: a float like 0.9f - 0.85f is
        // 0.049999997, and truncating that would lose a millisecond per
        // gesture.
        var start = current.startMs
        if (f > EDGE_FRACTION) {
            start += ((f - EDGE_FRACTION) * span).roundToLong()
        } else if (f < 1f - EDGE_FRACTION) {
            start -= (((1f - EDGE_FRACTION) - f) * span).roundToLong()
        }
        start = start.coerceIn(0L, maxStart)

        val window = EditViewport(startMs = start, spanMs = span)
        // The playhead is the moment under the finger in the window *after* it
        // moved: the content slid, so the finger now points at a later (or
        // earlier) moment, and the playhead has to follow it there.
        val moment = window.startMs + (f * span).toLong()
        return window to moment.coerceIn(0L, total)
    }

    /**
     * The fraction of the width at which the waveform starts to slide under
     * the finger.
     *
     * The outer fifteen percent at either end. A finger needs that much room
     * to aim at, and the dead zone in the middle keeps the playhead steady
     * while the user is reading the waveform.
     */
    const val EDGE_FRACTION = 0.85f

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
     * The distance in milliseconds between two moments, as a fraction of a span.
     *
     * Small helper for the "how far did the finger move" question, kept here
     * so the rounding matches the rest of the arithmetic.
     */
    fun deltaMs(fromMs: Long, toMs: Long, spanMs: Long, widthPx: Float): Long {
        if (widthPx <= 0f || spanMs <= 0L) return 0L
        val pixels = (toMs - fromMs).toFloat()
        return (pixels / widthPx * spanMs).roundToLong()
    }

    /**
     * Whether the window has to move at all for the playhead to stay visible.
     *
     * True when the playhead has left the screen entirely — the case where
     * the follow rule cannot simply scroll, because there is nothing to scroll
     * to: the recording has to be seeked instead.
     */
    fun playheadLeftScreen(current: EditViewport, playheadMs: Long): Boolean =
        abs(playheadMs - current.startMs) > current.spanMs
}
