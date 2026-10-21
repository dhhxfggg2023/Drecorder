package dev.drecorder.audio.edit

/**
 * The two-level waveform layout: an overview of the whole recording above a
 * detail view of the part the user is working on.
 *
 * This is the layout every serious audio editor uses on a small screen, and it
 * is the answer to "the whole 30 minutes is squeezed into one screen and I
 * cannot even scroll". A single strip cannot be both a map of the whole file
 * and a place to put a cut: at 30 minutes over 400 pixels one pixel is four
 * seconds, and no amount of pinching makes the *whole* strip useful at once
 * (DESIGN §12.4).
 *
 * The overview answers "where am I", the detail answers "what exactly is here".
 * The overview carries the viewport rectangle, so the user can always see
 * which part of the file the detail view is showing, and can drag it.
 *
 * Pure and Android-free: the geometry can be unit-tested off-device.
 */
object EditOverview {

    /**
     * The smallest the viewport rectangle may get, in pixels.
     *
     * A rectangle narrower than this cannot be grabbed with a finger, so the
     * user would be unable to move the viewport at all.
     */
    const val MIN_VIEWPORT_PX = 48f

    /**
     * The overview strip's rectangle.
     *
     * @param startMs the first moment of the recording shown in the detail view
     * @param spanMs how much of the recording the detail view shows
     */
    data class Viewport(val startMs: Long, val spanMs: Long) {
        /** The first moment after the visible window. */
        val endMs: Long get() = startMs + spanMs
    }

    /**
     * Where the viewport rectangle sits inside the overview strip.
     *
     * @param leftPx the left edge of the rectangle
     * @param widthPx the width of the rectangle
     */
    data class Rect(val leftPx: Float, val widthPx: Float)

    /**
     * The rectangle that shows where [viewport] is inside a strip of [widthPx].
     *
     * The rectangle is never narrower than [MIN_VIEWPORT_PX] — a very zoomed-in
     * view would otherwise make it impossible to grab — and it never leaves the
     * strip.
     */
    fun rectFor(viewport: Viewport, totalMs: Long, widthPx: Float): Rect {
        if (totalMs <= 0L || widthPx <= 0f) return Rect(0f, widthPx.coerceAtLeast(0f))
        val rawWidth = (viewport.spanMs.toDouble() / totalMs.toDouble() * widthPx).toFloat()
        val width = rawWidth.coerceIn(MIN_VIEWPORT_PX.coerceAtMost(widthPx), widthPx)
        val rawLeft = (viewport.startMs.toDouble() / totalMs.toDouble() * widthPx).toFloat()
        val left = rawLeft.coerceIn(0f, (widthPx - width).coerceAtLeast(0f))
        return Rect(left, width)
    }

    /**
     * Moves the window so that its rectangle sits at [leftPx].
     *
     * The span is **not** touched. The rectangle in the strip is at least
     * [MIN_VIEWPORT_PX] wide, so its width cannot be used to work out the
     * span: a window of thirty seconds inside a thirty minute recording is
     * drawn as a rectangle of eighteen pixels, which the minimum widens to
     * forty-eight — and reading the span back from that would turn a drag
     * into a zoom out. Dragging the map must move the map, and nothing else.
     */
    fun moveViewport(
        viewport: Viewport,
        leftPx: Float,
        stripWidthPx: Float,
        totalMs: Long,
    ): Viewport {
        if (totalMs <= 0L || stripWidthPx <= 0f) return viewport
        val total = totalMs.toDouble()
        val fraction = (leftPx.toDouble() / stripWidthPx.toDouble()).coerceIn(0.0, 1.0)
        val startMs = (fraction * total)
            .toLong()
            .coerceIn(0L, (totalMs - viewport.spanMs).coerceAtLeast(0L))
        return Viewport(startMs, viewport.spanMs)
    }

    /**
     * The window the detail view shows when the editor opens.
     *
     * Thirty seconds is the span the desktop editors call "a few seconds" and
     * the mobile ones settle on: it is long enough to see a phrase and short
     * enough that the waveform is not a hairline. A recording shorter than
     * that shows all of itself.
     */
    const val DEFAULT_SPAN_MS = 30_000L

    /** The detail view's span when the editor opens. */
    fun initialViewport(totalMs: Long): Viewport {
        val total = totalMs.coerceAtLeast(1L)
        val span = DEFAULT_SPAN_MS.coerceAtMost(total)
        return Viewport(0L, span)
    }
}
