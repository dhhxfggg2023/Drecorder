package dev.drecorder.audio.edit

/**
 * Snapping a cut to the quietest moment near it.
 *
 * Cutting a recording in the middle of a word is the single most common way to
 * make an edit sound wrong, so a cut is nudged to the nearest pause (DESIGN §12.4).
 * The search is over the cached peaks, so it costs nothing at interaction time:
 * the peaks are already in memory because the waveform is on screen.
 *
 * The snapping is a *preference*, not a rule: [DEFAULT_RADIUS_MS] bounds how far a
 * cut may travel, and the user can turn it off entirely.
 *
 * Pure functions over [PeakData], so they are unit-tested without a device.
 */
object EditSnapping {

    /**
     * How far a cut may be moved to reach a pause.
     *
     * Two seconds is long enough to find the gap between two sentences and short
     * enough that the cut still lands where the user put it.
     */
    const val DEFAULT_RADIUS_MS = 2_000L

    /**
     * The quietest moment within [radiusMs] of [timeMs].
     *
     * Returns [timeMs] unchanged when snapping is off, when there is no waveform
     * to search, or when the search radius is zero.
     */
    fun snapTime(
        peaks: PeakData?,
        timeMs: Long,
        radiusMs: Long = DEFAULT_RADIUS_MS,
        enabled: Boolean = true,
    ): Long {
        if (!enabled || peaks == null || radiusMs <= 0) return timeMs
        if (peaks.bucketCount == 0) return timeMs

        val candidate = peaks.quietestNear(timeMs, radiusMs)
        // The search always returns *some* bucket inside the radius, even when
        // everything in it is equally loud. Moving the cut to the edge of the
        // radius in that case is worse than not moving it at all, so the cut
        // only travels when the target is genuinely quieter than where it is.
        val candidatePeak = peaks.peakAt(peaks.bucketIndexAt(candidate))
        val currentPeak = peaks.peakAt(peaks.bucketIndexAt(timeMs))
        if (candidatePeak >= currentPeak) return timeMs
        return candidate
    }

    /**
     * [range] with both of its edges snapped to a pause.
     *
     * The original range is returned when the snapped edges would cross or
     * collapse: a selection that ends up empty is worse than one that is a few
     * milliseconds off the ideal cut.
     */
    fun snapRange(
        peaks: PeakData?,
        range: KeepRange,
        radiusMs: Long = DEFAULT_RADIUS_MS,
        enabled: Boolean = true,
    ): KeepRange {
        if (!enabled || peaks == null || radiusMs <= 0) return range
        val start = snapTime(peaks, range.startMs, radiusMs)
        val end = snapTime(peaks, range.endMs, radiusMs)
        if (end <= start) return range
        return KeepRange(start, end)
    }
}
