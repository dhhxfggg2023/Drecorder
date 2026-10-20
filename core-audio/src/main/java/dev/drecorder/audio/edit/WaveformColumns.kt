package dev.drecorder.audio.edit

/**
 * The waveform re-binned for the screen.
 *
 * The cached peaks are fixed at 100 buckets per second; the screen has a
 * different number of pixels. [WaveformBins.build] bridges the two by giving
 * every screen column the extremes of all the buckets that fall into it, which
 * is what a waveform should show: zooming in splits a bucket across columns and
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
     * Bins the peaks covering `[startMs, startMs + spanMs)` into [widthPx]
     * columns.
     *
     * A column with no audio in it keeps the sentinel extremes, which the
     * renderer draws as nothing — a silent stretch therefore shows as a flat
     * line rather than as a gap.
     */
    fun build(
        peaks: PeakData,
        startMs: Long,
        spanMs: Long,
        widthPx: Int,
    ): WaveformColumns {
        val count = widthPx.coerceIn(1, MAX_COLUMNS)
        val mins = IntArray(count) { Int.MAX_VALUE }
        val maxs = IntArray(count) { Int.MIN_VALUE }
        if (spanMs <= 0) return WaveformColumns(count, mins, maxs)

        val msPerColumn = spanMs.toDouble() / count
        val bucketCount = peaks.bucketCount
        val bucketsPerSecond = peaks.bucketsPerSecond

        for (i in 0 until count) {
            val columnStartMs = startMs + (i * msPerColumn).toLong()
            val columnEndMs = startMs + ((i + 1) * msPerColumn).toLong()
            if (columnEndMs <= columnStartMs) continue

            // The buckets are addressed by their own time range rather than by an
            // index clamped to the data: a window that reaches past the recording
            // has to draw nothing there, not the last bucket of the file.
            val firstBucket = (columnStartMs * bucketsPerSecond / 1_000L).toInt()
            val lastBucket = (columnEndMs * bucketsPerSecond / 1_000L).toInt()
            for (bucket in firstBucket..lastBucket) {
                if (bucket < 0 || bucket >= bucketCount) continue
                val bucketStart = peaks.bucketStartMs(bucket)
                val bucketEnd = bucketStart + 1_000L / bucketsPerSecond
                // Only the part of the bucket that really falls into this column.
                if (bucketStart >= columnEndMs || bucketEnd <= columnStartMs) continue
                val minV = peaks.minAt(bucket)
                val maxV = peaks.maxAt(bucket)
                if (minV < mins[i]) mins[i] = minV
                if (maxV > maxs[i]) maxs[i] = maxV
            }
        }
        return WaveformColumns(count, mins, maxs)
    }
}
