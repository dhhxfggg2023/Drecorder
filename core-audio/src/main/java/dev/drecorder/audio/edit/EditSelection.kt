package dev.drecorder.audio.edit

/**
 * The edges of a selection and the arithmetic around them.
 *
 * Selecting a range is where a waveform editor is won or lost. Dragging across
 * the waveform is fine, but it cannot express "cut exactly here" — a cut has to
 * land on a particular millisecond, and a finger on glass is nowhere near that
 * precise. Every serious editor therefore gives the selection **handles** that
 * can be dragged, plus a way to **type** the exact moment and to **nudge** it
 * by a known step (DESIGN §12.4).
 *
 * The arithmetic is here, in core-audio, because it is the part that has to be
 * exactly right: a handle that can cross its opposite edge, a nudge that runs
 * past the end of the recording, or a hit area so small that the user misses it
 * are all bugs that only a test can catch.
 *
 * Pure and Android-free, so it is unit-tested off-device.
 */

/** Which edge of a selection a touch refers to. */
enum class EditHandle {
    /** The edge where the selection starts. */
    Start,

    /** The edge where the selection ends. */
    End,

    /** Not close to either edge. */
    None,
}

/**
 * A selected stretch of a recording.
 *
 * @param startMs the first selected moment
 * @param endMs the moment after the last selected one
 */
data class EditRange(val startMs: Long, val endMs: Long) {

    /** How long the selection is. */
    val durationMs: Long get() = endMs - startMs
}

/**
 * The arithmetic of the selection handles.
 *
 * The rules the results always satisfy:
 *
 *  - a selection never leaves the recording;
 *  - a selection never becomes empty or inverted: the two edges keep their
 *    order, and a minimum of [MIN_SELECTION_MS] is always between them;
 *  - a handle only moves when the touch is close to it, and the touch area is
 *    [HIT_RADIUS_PX] wide even though the handle is drawn three pixels wide.
 */
object EditSelectionMath {

    /**
     * How close a touch has to be to a handle to grab it, in pixels.
     *
     * Forty-eight pixels is the smallest reliably tappable target. The handle
     * itself is drawn as a thin line, because a wide handle would cover the
     * waveform, but it is *hit* over the whole 48 pixels.
     */
    const val HIT_RADIUS_PX = 48f

    /**
     * The shortest selection that is still a selection.
     *
     * Ten milliseconds is one audio frame at a hundred frames per second: long
     * enough to be audible as a cut, short enough to remove a click.
     */
    const val MIN_SELECTION_MS = 10L

    /**
     * The step the "nudge" buttons move an edge by.
     *
     * Ten milliseconds is below what the user can hear as a difference in the
     * *result* but is exactly the kind of adjustment a cut needs.
     */
    const val NUDGE_MS = 10L

    /**
     * The bigger step the "nudge" buttons move an edge by.
     */
    const val NUDGE_LARGE_MS = 100L

    /**
     * Keeps [range] inside a recording of [totalMs].
     *
     * An empty range stays empty: the caller decides whether that is legal.
     */
    fun clamp(range: EditRange, totalMs: Long): EditRange {
        val total = totalMs.coerceAtLeast(0L)
        val start = range.startMs.coerceIn(0L, total)
        val end = range.endMs.coerceIn(0L, total)
        return if (end < start) EditRange(end, start) else EditRange(start, end)
    }

    /**
     * The handle a touch at [xPx] grabs.
     *
     * The touch has to be within [HIT_RADIUS_PX] of an edge. When both edges
     * are in reach the nearer one wins, so a touch in the middle of a short
     * selection is not ambiguous.
     */
    fun handleAt(
        xPx: Float,
        widthPx: Float,
        startMs: Long,
        spanMs: Long,
        range: EditRange,
    ): EditHandle {
        if (widthPx <= 0f || spanMs <= 0L) return EditHandle.None
        val startX = msToX(range.startMs, widthPx, startMs, spanMs)
        val endX = msToX(range.endMs, widthPx, startMs, spanMs)
        val toStart = kotlin.math.abs(xPx - startX)
        val toEnd = kotlin.math.abs(xPx - endX)
        val withinStart = toStart <= HIT_RADIUS_PX
        val withinEnd = toEnd <= HIT_RADIUS_PX
        return when {
            withinStart && withinEnd -> if (toStart <= toEnd) EditHandle.Start else EditHandle.End
            withinStart -> EditHandle.Start
            withinEnd -> EditHandle.End
            else -> EditHandle.None
        }
    }

    /**
     * The selection after the edge [handle] was dragged to [xPx].
     *
     * The opposite edge does not move, and the two edges never cross.
     */
    fun moveHandle(
        range: EditRange,
        handle: EditHandle,
        xPx: Float,
        widthPx: Float,
        startMs: Long,
        spanMs: Long,
        totalMs: Long,
    ): EditRange {
        if (handle == EditHandle.None) return range
        if (widthPx <= 0f || spanMs <= 0L) return range
        val time = xToMs(xPx, widthPx, startMs, spanMs)
        return when (handle) {
            EditHandle.Start -> {
                val limit = (range.endMs - MIN_SELECTION_MS).coerceAtLeast(0L)
                EditRange(time.coerceIn(0L, limit), range.endMs)
            }

            EditHandle.End -> {
                val limit = (range.startMs + MIN_SELECTION_MS).coerceAtLeast(0L)
                EditRange(range.startMs, time.coerceIn(limit, totalMs.coerceAtLeast(0L)))
            }

            EditHandle.None -> range
        }
    }

    /**
     * The selection after an edge was nudged by [deltaMs].
     *
     * A positive delta moves the edge forward in time. Which edge moves is the
     * caller's choice — the two nudge buttons nudge the edge that was grabbed,
     * and the numeric fields nudge whichever field the user is editing.
     */
    fun nudge(
        range: EditRange,
        handle: EditHandle,
        deltaMs: Long,
        totalMs: Long,
    ): EditRange = when (handle) {
        EditHandle.Start -> {
            val limit = (range.endMs - MIN_SELECTION_MS).coerceAtLeast(0L)
            EditRange((range.startMs + deltaMs).coerceIn(0L, limit), range.endMs)
        }

        EditHandle.End -> {
            val limit = (range.startMs + MIN_SELECTION_MS).coerceAtLeast(0L)
            val total = totalMs.coerceAtLeast(0L)
            EditRange(range.startMs, (range.endMs + deltaMs).coerceIn(limit, total))
        }

        EditHandle.None -> range
    }

    /**
     * The selection after its length was set to [durationMs].
     *
     * The edge that is moved depends on [handle]: the start edge grows the
     * selection backwards, the end edge grows it forwards. A selection that
     * would leave the recording is pulled back in rather than refused, so the
     * field always shows a number the user can work with.
     */
    fun setDuration(
        range: EditRange,
        handle: EditHandle,
        durationMs: Long,
        totalMs: Long,
    ): EditRange {
        val total = totalMs.coerceAtLeast(0L)
        val wanted = durationMs.coerceAtLeast(MIN_SELECTION_MS)
        return when (handle) {
            EditHandle.Start -> {
                val end = range.endMs.coerceIn(0L, total)
                val start = (end - wanted).coerceIn(0L, end)
                EditRange(start, end)
            }

            EditHandle.End -> {
                val start = range.startMs.coerceIn(0L, total)
                val end = (start + wanted).coerceIn(start, total)
                EditRange(start, end)
            }

            EditHandle.None -> range
        }
    }

    /** The moment at [xPx] in a window of [spanMs] that starts at [startMs]. */
    fun xToMs(xPx: Float, widthPx: Float, startMs: Long, spanMs: Long): Long {
        if (widthPx <= 0f) return startMs
        val fraction = (xPx / widthPx).coerceIn(0f, 1f)
        return startMs + (fraction * spanMs).toLong()
    }

    /** The position of [ms] in a window of [spanMs] that starts at [startMs]. */
    fun msToX(ms: Long, widthPx: Float, startMs: Long, spanMs: Long): Float {
        if (widthPx <= 0f || spanMs <= 0L) return 0f
        return ((ms - startMs).toFloat() / spanMs.toFloat()) * widthPx
    }
}
