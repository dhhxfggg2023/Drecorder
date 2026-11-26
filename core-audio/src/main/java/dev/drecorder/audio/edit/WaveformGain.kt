package dev.drecorder.audio.edit

/**
 * How much the waveform has to be magnified to be readable.
 *
 * A recording of other apps' playback is quiet: the capture sits far below full
 * scale, because it is a copy of a signal that was already mixed for comfortable
 * listening. Drawn at true amplitude the waveform uses a small fraction of the
 * height it is given and reads as a thin ribbon across an empty band. That is
 * what 「波形太小了」 meant, twice.
 *
 * The fix is a gain, not a lie: the shape is unchanged, it is scaled up to fill
 * the space it has, the way every recorder draws it. Three decisions make it
 * behave on real audio rather than on test tones:
 *
 * **Measured at a high percentile, not at the maximum.** One door slamming in an
 * hour of quiet talk would set the scale for the whole file and flatten everything
 * else to a hairline. The 99th percentile ignores the rare outlier and scales for
 * what the recording is *mostly* like; a handful of the loudest buckets then run
 * off the top of the band, which costs nothing — the eye reads "very loud" either
 * way and the shape of the rest survives.
 *
 * **Never attenuates.** A recording already at a sensible level is drawn at true
 * scale. Squeezing loud files down would make a quiet meeting and a loud concert
 * look identical, which is information the waveform is supposed to carry.
 *
 * **Capped, but high enough to matter.** A noise floor a thousandth of full scale
 * would otherwise ask for a thousandfold boost and turn hiss into a wall of ink.
 * The cap is about +36 dB, which covers the quietest capture this app produces
 * without amplifying a nearly-empty file into something that looks like content.
 *
 * Pure and Android-free, so the arithmetic can be checked off-device.
 */
object WaveformGain {

    /** The highest a quiet recording is magnified. About +36 dB. */
    const val MAX_GAIN = 64f

    /**
     * How much of the available height a peak should use.
     *
     * Not all of it: the tallest stick at the very edge of the band reads as
     * clipped, and a little air above the loudest moment is what makes the
     * loudest moment look loud.
     */
    const val HEADROOM = 0.90f

    /**
     * The percentile of bucket peaks the gain is scaled for.
     *
     * At 99, one bucket in a hundred runs off the top. That is the right trade
     * for audio: a single transient is not what the recording "is", and scaling
     * for it is how every quiet recording ends up a hairline on a flat line.
     */
    private const val PERCENTILE = 99

    /**
     * Histogram resolution: this many bins over the PCM16 range.
     *
     * Fine enough that a real noise floor is not lumped in with digital silence:
     * at 512 bins one bin is 64 PCM units, so a capture whose peaks sit around
     * -60 dBFS (30 units) landed in the silent bin and was reported as having no
     * audio at all. At 8192 bins one bin is four units.
     */
    private const val BINS = 8_192

    /**
     * The gain for [peaks]: enough to fill [HEADROOM] of the band, capped.
     *
     * Returns 1 for silence and for anything already loud: there is nothing to
     * magnify in the first case and nothing to gain in the second.
     */
    fun gainFor(peaks: PeakData?): Float {
        val representative = representativePeak(peaks) ?: return 1f
        if (representative <= 0) return 1f
        val fraction = representative / PeakData.FULL_SCALE
        if (fraction >= HEADROOM) return 1f
        return (HEADROOM / fraction).coerceIn(1f, MAX_GAIN)
    }

    /**
     * The peak the gain is scaled for: the [PERCENTILE]th percentile bucket peak.
     *
     * Computed with a histogram rather than a sort: an hour or two of audio is
     * hundreds of thousands of buckets, and this runs whenever the waveform is
     * opened. Null when there is no audio at all.
     */
    private fun representativePeak(peaks: PeakData?): Int? {
        if (peaks == null || peaks.bucketCount == 0) return null
        val fullScale = PeakData.FULL_SCALE.toInt()
        val histogram = IntArray(BINS)
        for (index in 0 until peaks.bucketCount) {
            // `value * BINS / fullScale`, in that order and in Int: dividing the
            // scale down first (`BINS / fullScale`) is 0 in integer arithmetic,
            // which puts every sample in bin 0 and makes every file look silent.
            val bin = (peaks.peakAt(index).toLong() * BINS / fullScale)
                .toInt().coerceIn(0, BINS - 1)
            histogram[bin]++
        }
        // Silence is not "very quiet": it has nothing to magnify, and a gain
        // computed for an all-zero file is division by a peak of zero — which is
        // how a muted recording ended up multiplied by the cap and drawn as a
        // band of noise that was never in it.
        if (histogram[0] == peaks.bucketCount) return null

        val target = peaks.bucketCount.toLong() * PERCENTILE / 100L
        var seen = 0L
        for (bin in 0 until BINS) {
            // Bin 0 is silence by definition; a percentile that falls inside it
            // has not reached real audio yet.
            if (bin == 0) {
                seen += histogram[bin]
                continue
            }
            seen += histogram[bin]
            if (seen > target) {
                // The top of the bin the percentile falls in: the gain should be
                // driven by the loudest audio in that bin, not its quietest, or
                // a recording whose peaks are spread across the bin shows less
                // than it has.
                return (((bin + 1).toLong() * fullScale) / BINS).toInt().coerceAtLeast(1)
            }
        }
        return null
    }
}
