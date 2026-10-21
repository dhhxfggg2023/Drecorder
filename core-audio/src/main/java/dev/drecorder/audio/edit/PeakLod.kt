package dev.drecorder.audio.edit

/**
 * Which resolution the waveform should be drawn at.
 *
 * A waveform is not one picture: it is a family of pictures, one per zoom
 * level. The overview of a 30 minute recording needs about one bucket per
 * screen column of the *whole file*; the same recording opened on a two second
 * window needs a thousand times more resolution. Drawing the overview when the
 * user zoomed in shows a staircase; drawing the detail when the user zoomed out
 * shows noise (DESIGN §12.4).
 *
 * The rule here is the one every audio editor uses: **two buckets per screen
 * pixel**. Two, not one, because a column that holds a single bucket shows
 * exactly that bucket — a loud transient next to a silent one would be
 * averaged into one grey line. With two, the column always holds the extremes
 * of at least two buckets and the shape survives.
 *
 * The resolutions are a ladder rather than a continuum so that a given window
 * always asks for the same one: the same zoom level then always decodes the
 * same amount of audio, which is what makes the progressive loading cacheable
 * and testable.
 *
 * Pure and Android-free, so the choice of level can be unit-tested off-device.
 */
object PeakLod {

    /**
     * The resolutions the subsystem can produce, in buckets per second.
     *
     * Coarse enough to cover a whole file at 100 buckets/s (10 ms each), fine
     * enough to show individual samples' worth of shape at 1000 buckets/s
     * (1 ms each).
     */
    val LADDER: List<Int> = listOf(10, 20, 50, 100, 200, 500, 1_000)

    /** The resolution the on-disk cache holds; also the overview's resolution. */
    const val COARSE = 100

    /** The finest resolution the decoder produces. */
    const val FINEST = 1_000

    /**
     * The resolution a window of [spanMs] shown at [widthPx] pixels needs.
     *
     * Never finer than [FINEST]: past that the buckets are smaller than a
     * sample and there is nothing left to see.
     */
    fun bucketsPerSecondFor(spanMs: Long, widthPx: Int): Int {
        if (spanMs <= 0L || widthPx <= 0) return FINEST
        // bucketsPerSecond * spanMs / 1000 >= 2 * widthPx
        val required = (2L * widthPx * 1_000L + spanMs - 1L) / spanMs
        return LADDER.firstOrNull { it >= required } ?: FINEST
    }

    /**
     * Whether a window needs a finer waveform than the cached one.
     *
     * When it does not, the cached waveform is drawn as it is and nothing is
     * decoded: that is the whole point of computing the waveform while
     * recording.
     */
    fun needsFinerWaveform(
        spanMs: Long,
        widthPx: Int,
        cachedBucketsPerSecond: Int,
    ): Boolean = bucketsPerSecondFor(spanMs, widthPx) > cachedBucketsPerSecond
}
