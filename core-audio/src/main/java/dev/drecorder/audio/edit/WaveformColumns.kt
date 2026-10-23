package dev.drecorder.audio.edit

/**
 * The waveform re-binned for the screen.
 *
 * The cached peaks are fixed at 100 buckets per second; the screen has a
 * different number of pixels. [WaveformBins] bridges the two by giving every
 * screen column the extremes of all the buckets that fall into it, which is
 * what a waveform should show: zooming in splits a bucket across columns and
 * zooming out merges many buckets into one (DESIGN §12.4).
 *
 * Pure and Android-free, so the maths of the waveform can be unit-tested
 * off-device — the decoder is the only part that needs a phone.
 */
class WaveformColumns(
    val count: Int,
    val mins: IntArray,
    val maxs: IntArray,
) {
    companion object {
        val EMPTY = WaveformColumns(0, IntArray(0), IntArray(0))
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
            }
        }
        return WaveformColumns(count, mins, maxs)
    }

    private fun emptyMins(count: Int): IntArray = IntArray(count) { Int.MAX_VALUE }

    private fun emptyMaxs(count: Int): IntArray = IntArray(count) { Int.MIN_VALUE }
}
