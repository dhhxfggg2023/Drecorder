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
 * The rule is **one bucket per drawn column**, and it is a rule about the
 * renderer, not a matter of taste: a drawn column is never finer than a bucket
 * ([WaveformBins.stepMs] rounds it up to the bucket grid), so asking for two
 * buckets a column asked for detail the renderer would then throw away. The
 * earlier two-bucket rule was written for the *filled* outline, where a column
 * joined its neighbours into one body and a single bucket would have been
 * averaged into a grey line across several pixels. The drawing is one stick per
 * column now (「细火柴棍就行」, 2026-11-04), so one bucket is one stick and
 * there is nothing left to smooth.
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
     * Coarse enough to cover a whole file at 10 buckets/s (100 ms each), fine
     * enough to show individual samples' worth of shape at 1000 buckets/s
     * (1 ms each).
     */
    val LADDER: List<Int> = listOf(10, 20, 50, 100, 200, 500, 1_000)

    /** The resolution the on-disk cache holds; also the overview's resolution. */
    const val COARSE = 100

    /** The finest resolution the decoder produces. */
    const val FINEST = 1_000

    /**
     * How much extra resolution a decode has to buy before it is worth starting.
     *
     * Decoding a window means an extractor seek, a codec start, a decode of
     * several seconds of audio and a cache write — tens of milliseconds of real
     * work. A few percent more resolution is not worth any of that: the extra
     * buckets would be thinner than the stick that draws them, and the picture
     * would be indistinguishable. Only a doubling is a different picture.
     */
    private const val MATERIAL_GAIN = 2

    /**
     * The resolution a window of [spanMs] shown at [widthPx] pixels needs.
     *
     * Never finer than [FINEST]: past that the buckets are smaller than a
     * sample and there is nothing left to see.
     */
    fun bucketsPerSecondFor(spanMs: Long, widthPx: Int): Int {
        // No width yet: the first composition runs before layout has measured the
        // waveform, so there is nothing to fill. Answering FINEST here decoded a
        // window that was thrown away one frame later (and cached it).
        if (spanMs <= 0L || widthPx <= 0) return COARSE
        return LADDER.firstOrNull { it >= requiredBucketsPerSecond(spanMs, widthPx) } ?: FINEST
    }

    /** One bucket per drawn column, rounded up, as a raw resolution. */
    private fun requiredBucketsPerSecond(spanMs: Long, widthPx: Int): Long {
        if (spanMs <= 0L || widthPx <= 0) return COARSE.toLong()
        val columns = (widthPx + WaveformBins.MIN_COLUMN_PX - 1) / WaveformBins.MIN_COLUMN_PX
        return (columns.toLong() * 1_000L + spanMs - 1L) / spanMs
    }

    /**
     * Whether a window needs a finer waveform than the cached one.
     *
     * When it does not, the cached waveform is drawn as it is and nothing is
     * decoded: that is the whole point of computing the waveform while
     * recording. With the editor's own limits — a five second floor on the span,
     * two-pixel columns and a hundred-buckets-a-second cache — the answer is
     * *no* at every zoom the editor allows, which is the honest statement that
     * the cache already holds more resolution than the screen can show. The
     * window decode path stays because the three numbers that make it
     * unreachable are three separate decisions, and any one of them can change.
     */
    fun needsFinerWaveform(
        spanMs: Long,
        widthPx: Int,
        cachedBucketsPerSecond: Int,
    ): Boolean = requiredBucketsPerSecond(spanMs, widthPx) >
        cachedBucketsPerSecond.toLong() * MATERIAL_GAIN
}
