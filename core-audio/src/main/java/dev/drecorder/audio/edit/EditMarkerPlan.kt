package dev.drecorder.audio.edit

/**
 * The zoom levels of the single-track timeline.
 *
 * The editor shows one waveform, and the window onto it has to be chosen:
 * wide enough to see a phrase, narrow enough to see a wave (DESIGN §12.4).
 *
 * The spans are a fixed ladder rather than anything continuous, so that the
 * same window always asks for the same resolution of waveform and the
 * result can be cached and compared.
 *
 * Pure and Android-free, so the choice can be unit-tested off-device.
 */
object EditMarkerPlan {

    /**
     * The spans the zoom buttons step through, in ms.
     *
     * Thirty seconds is what a phone screen can show as a waveform that is
     * still a waveform; two seconds is the finest a cut needs to be.
     */
    val SPANS: List<Long> = listOf(
        2_000L,
        5_000L,
        10_000L,
        30_000L,
        60_000L,
        120_000L,
        300_000L,
        600_000L,
    )

    /** The span the timeline opens on, for a recording of [totalMs]. */
    fun initialSpanMs(totalMs: Long): Long {
        val total = totalMs.coerceAtLeast(1L)
        return DEFAULT_SPAN_MS.coerceAtMost(total)
    }

    /**
     * The span that shows the whole recording.
     *
     * A recording shorter than the finest span shows all of itself.
     */
    fun fitSpanMs(totalMs: Long): Long {
        val total = totalMs.coerceAtLeast(1L)
        return total.coerceAtMost(MAX_FIT_SPAN_MS)
    }

    /**
     * The next span in the ladder, away from [current].
     *
     * @param zoomIn true to zoom in (a shorter span), false to zoom out
     */
    fun nextSpanMs(current: Long, zoomIn: Boolean, totalMs: Long): Long {
        val total = totalMs.coerceAtLeast(1L)
        val ladder = SPANS.filter { it <= total || it == SPANS.first() }
        if (ladder.isEmpty()) return current.coerceIn(1L, total)
        val index = ladder.indexOfFirst { it >= current }
        val position = if (index < 0) ladder.lastIndex else index
        val next = if (zoomIn) {
            ladder.getOrNull(position - 1) ?: ladder.first()
        } else {
            ladder.getOrNull(position + 1) ?: ladder.last()
        }
        return next.coerceIn(1L, total)
    }

    /** The span the editor opens on: thirty seconds, or the whole recording. */
    const val DEFAULT_SPAN_MS = 30_000L

    /**
     * The longest span the "fit" button shows.
     *
     * Ten minutes. A recording longer than that is opened on
     * [DEFAULT_SPAN_MS] instead, because a waveform squeezed into that many
     * pixels is a flat line.
     */
    const val MAX_FIT_SPAN_MS = 600_000L
}
