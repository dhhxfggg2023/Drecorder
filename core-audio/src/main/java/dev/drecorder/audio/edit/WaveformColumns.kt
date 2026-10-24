package dev.drecorder.audio.edit

import kotlin.math.abs

/**
 * The waveform re-binned for the screen.
 *
 * The cached peaks are fixed at 100 buckets per second; the screen has a
 * different number of pixels. [WaveformBins] bridges the two by giving every
 * drawn column the extremes and the mean loudness of all the buckets that fall
 * into it (DESIGN §12.4).
 *
 * [mins] and [maxs] are the true extremes — what a bucket holds, and what the
 * hit-testing and the tests ask for. [levels] is the **mean** of each bucket's
 * amplitude, which is what the picture is drawn from: a column covering half a
 * second of speech would reach full scale on its loudest sample alone, so a
 * peak envelope saturates to a solid block at far zoom (「缩放到最小波形的高低
 * 差特别大，很奇怪」, 2026-10-23), while the mean keeps the loudness contour.
 *
 * Pure and Android-free, so the maths of the waveform can be unit-tested
 * off-device — the decoder is the only part that needs a phone.
 */
class WaveformColumns(
    val count: Int,
    val mins: IntArray,
    val maxs: IntArray,
    val levels: IntArray,
) {
    companion object {
        val EMPTY = WaveformColumns(0, IntArray(0), IntArray(0), IntArray(0))
    }
}

/** Builds [WaveformColumns] out of cached peaks. */
object WaveformBins {

    /** The most columns that are ever built; beyond that the pixels repeat. */
    const val MAX_COLUMNS = 4_096

    /**
     * Bins [bars] bars of [barMs] each, starting at [firstBarStartMs].
     *
     * The bars are anchored to the **recording's** time grid, not to the
     * screen: bar *i* always covers the same stretch of audio, wherever the
     * window happens to be. The caller lines the grid up with the window and
     * translates the whole row by the fraction of a bar it has scrolled, so the
     * waveform slides smoothly while no bar changes height — which is what a
     * moving waveform has to look like (「手感很烂，波形移动的时候」,
     * 2026-10-23). Re-binning per frame instead makes the bars shimmer, which is
     * what the previous screen-anchored `build` did: it is gone rather than left
     * unused, because a second way to bin the same audio is exactly the kind of
     * two-owner problem this project has paid for before.
     *
     * [firstBarStartMs] must be a multiple of [barMs]'s grid for the bars to be
     * stable; [gridStart] is what produces it.
     */
    fun buildBars(
        peaks: PeakData,
        firstBarStartMs: Long,
        barMs: Long,
        bars: Int,
    ): WaveformColumns {
        val count = bars.coerceIn(1, MAX_COLUMNS)
        val bar = barMs.coerceAtLeast(1L)
        return aggregate(
            peaks = peaks,
            count = count,
            startOf = { index -> firstBarStartMs + index * bar },
            endOf = { index -> firstBarStartMs + (index + 1) * bar },
        )
    }

    /**
     * The start of the bar grid at or before [startMs].
     *
     * The view bins from this moment and translates the row by the rest of a
     * bar, so that two frames of a scroll inside one bar draw the *same* bars
     * (moved), instead of re-binning the audio into new ones. Kotlin's [mod]
     * is floor-mod, so a window that reaches before the recording still lands
     * on the grid.
     */
    fun gridStart(startMs: Long, barMs: Long): Long =
        startMs - startMs.mod(barMs.coerceAtLeast(1L))

    /** The duration of one cached bucket, in ms. */
    fun bucketMs(peaks: PeakData): Long =
        (1_000L / peaks.bucketsPerSecond.coerceAtLeast(1)).coerceAtLeast(1L)

    /**
     * How much audio one drawn column covers, in ms.
     *
     * Zoomed out there are many buckets per pixel, so a column is a number of
     * whole buckets — the mean over them is what keeps the loudness contour
     * from saturating. Zoomed in the opposite happens, and drawing one column
     * per pixel is what turns every bucket into a flat block 5 pixels wide:
     * 「放大后看到一个个矩形」 (2026-10-23). So a column is never shorter than a
     * bucket: at 2 s across a phone the columns fall back to the bucket grid and
     * each one is drawn as its own point, which is what makes the peaks sharp
     * instead of square.
     *
     * The answer only changes when the span or the width changes, never when the
     * window scrolls, so the grid stays still while the picture moves.
     */
    fun stepMs(peaks: PeakData, spanMs: Long, widthPx: Int): Long {
        val bucket = bucketMs(peaks)
        if (widthPx <= 0 || spanMs <= 0L) return bucket
        val perPixelMs = (spanMs + widthPx - 1) / widthPx
        val bucketsPerColumn = (perPixelMs + bucket - 1) / bucket
        return bucket * bucketsPerColumn.coerceAtLeast(1L)
    }

    /**
     * The extremes of every column, given where each column starts and ends.
     *
     * The buckets are addressed by their own time range rather than by an index
     * clamped to the data: a window that reaches past the recording has to draw
     * nothing there, not the last bucket of the file. The offset matters too: a
     * waveform decoded for a window of a longer recording carries the window's
     * start time.
     */
    private fun aggregate(
        peaks: PeakData,
        count: Int,
        startOf: (Int) -> Long,
        endOf: (Int) -> Long,
    ): WaveformColumns {
        val mins = emptyMins(count)
        val maxs = emptyMaxs(count)
        // Per column: the sum of the buckets' amplitudes and how many there
        // were, turned into a mean at the end.
        val amplitudes = IntArray(count)
        val bucketsSeen = IntArray(count)
        val bucketCount = peaks.bucketCount
        val bucketsPerSecond = peaks.bucketsPerSecond

        for (index in 0 until count) {
            val columnStartMs = startOf(index)
            val columnEndMs = endOf(index)
            if (columnEndMs <= columnStartMs) continue

            val firstBucket = ((columnStartMs - peaks.offsetMs) * bucketsPerSecond / 1_000L).toInt()
            val lastBucket = ((columnEndMs - peaks.offsetMs) * bucketsPerSecond / 1_000L).toInt()
            for (bucket in firstBucket..lastBucket) {
                if (bucket < 0 || bucket >= bucketCount) continue
                val bucketStart = peaks.bucketStartMs(bucket)
                val bucketEnd = bucketStart + 1_000L / bucketsPerSecond
                // Only the part of the bucket that really falls into this column.
                if (bucketStart >= columnEndMs || bucketEnd <= columnStartMs) continue
                val minV = peaks.minAt(bucket)
                val maxV = peaks.maxAt(bucket)
                if (minV < mins[index]) mins[index] = minV
                if (maxV > maxs[index]) maxs[index] = maxV
                amplitudes[index] += (abs(minV) + abs(maxV)) / 2
                bucketsSeen[index]++
            }
        }
        val levels = IntArray(count) { index ->
            if (bucketsSeen[index] == 0) 0 else amplitudes[index] / bucketsSeen[index]
        }
        return WaveformColumns(count, mins, maxs, levels)
    }

    private fun emptyMins(count: Int): IntArray = IntArray(count) { Int.MAX_VALUE }

    private fun emptyMaxs(count: Int): IntArray = IntArray(count) { Int.MIN_VALUE }
}
