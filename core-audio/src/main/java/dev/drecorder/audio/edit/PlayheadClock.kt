package dev.drecorder.audio.edit

import kotlin.math.roundToLong

/**
 * A playhead that advances with the frame clock and nothing else.
 *
 * Every version of the editor before this one asked the media player where it
 * was on every frame and tried to smooth, extrapolate or servo the answer.
 * That cannot produce uniform motion: an audio sink reports its position in
 * coarse steps (tens of milliseconds), and any clamp, deadband or correction
 * built on that step reintroduces it as a visible jump. Five rounds of tuning
 * that loop did not fix the stutter; the loop itself was the bug.
 *
 * Playback is real time, so the playhead advances one millisecond per
 * millisecond of frame clock. Uniformity is a property of the construction,
 * not of a filter. The player is consulted only when something actually
 * discontinuous happens: a seek, a pause, a resume — and then [alignTo] is
 * called explicitly, not polled.
 *
 * Pure and Android-free: the motion can be asserted in a unit test instead of
 * being judged by eye on a phone.
 */
class PlayheadClock(
    /** Where playback began, in ms. */
    startMs: Long,
    /** The end of the track; the clock never runs past it. */
    private val totalMs: Long,
) {
    /** Fractional milliseconds — Long would quantise away sub-millisecond frames. */
    private var position: Double = startMs.toDouble()
    private var lastFrameMs: Long? = null

    /** The moment under the line, in whole ms (sub-ms is below one pixel). */
    val valueMs: Long get() = position.roundToLong().coerceIn(0L, totalMs)

    /**
     * Advances by the frame clock. Returns false once the end is reached.
     *
     * @param frameMs the frame timestamp from `withFrameMillis`
     */
    fun advance(frameMs: Long): Boolean {
        val previous = lastFrameMs
        lastFrameMs = frameMs
        if (previous == null) return position < totalMs
        val dt = (frameMs - previous).coerceIn(0L, MAX_FRAME_MS)
        position = (position + dt).coerceAtMost(totalMs.toDouble())
        return position < totalMs
    }

    /** A real discontinuity: seek, pause/resume, drag. */
    fun alignTo(ms: Long) {
        position = ms.coerceIn(0L, totalMs).toDouble()
        lastFrameMs = null
    }

    private companion object {
        /** A backgrounded app can see a long gap between frames; do not jump. */
        const val MAX_FRAME_MS = 100L
    }
}
