package dev.drecorder.audio.edit

/**
 * The part of a recording that is actually on screen.
 *
 * The playhead stands in the middle of the screen, so the window may reach
 * into the time **before** the recording started and past its end. Those
 * parts have no audio, and they must not be decoded (DESIGN §12.4).
 *
 * This is the arithmetic of that overlap, kept out of the UI so it can be
 * unit-tested off-device.
 */
object EditWindow {

    /**
     * The stretch of the file that the window shows, or null when the window
     * shows none of it.
     *
     * @param viewport the window on screen
     * @param totalMs the length of the recording
     * @return the first and the last millisecond of the recording that the
     *   window covers, or null when the window is entirely outside the file
     */
    fun fileRange(viewport: EditViewport, totalMs: Long): LongRange? {
        val total = totalMs.coerceAtLeast(0L)
        if (total == 0L) return null
        val start = viewport.startMs.coerceAtLeast(0L)
        val end = (viewport.startMs + viewport.spanMs).coerceAtMost(total)
        if (end <= start) return null
        return start until end
    }

    /**
     * Whether [peaks] covers the part of the file that the window shows.
     *
     * The waveform of a window is decoded for the part of the file it holds,
     * so it is compared with that part — not with the window, which may reach
     * outside the recording.
     *
     * @param toleranceMs how much shorter the waveform may be and still count
     */
    fun covers(
        peaks: PeakData?,
        viewport: EditViewport,
        totalMs: Long,
        requiredBucketsPerSecond: Int,
        toleranceMs: Long = 0L,
    ): Boolean {
        val range = fileRange(viewport, totalMs) ?: return false
        if (peaks == null) return false
        if (peaks.bucketsPerSecond < requiredBucketsPerSecond) return false
        return peaks.offsetMs <= range.first &&
            peaks.endMs + toleranceMs >= range.last + 1
    }
}
