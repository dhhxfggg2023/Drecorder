package dev.drecorder.audio.edit

/**
 * Splits the peaks of one capture session into the peaks of its files.
 *
 * A segmented session writes several files, but the capture engine sees one
 * continuous stream of PCM, so it buckets that stream once. The files are
 * contiguous slices of the same timeline, which means the buckets can be
 * handed out by time rather than decoded per file — the whole point of
 * computing the waveform while recording (DESIGN §12.4).
 *
 * Pure and Android-free, so it can be unit-tested off-device.
 */
object PeakSplitter {

    /**
     * Splits [data] into one [PeakData] per segment of [segmentDurationsMs].
     *
     * The durations are the ones the writer reported, in order. A list that
     * is empty means the session wrote a single file, and [data] is returned
     * unchanged.
     *
     * The buckets are handed out by time, so a bucket that straddles a file
     * boundary belongs to the file the boundary falls inside — the waveform of
     * a file therefore covers exactly the audio that file holds.
     */
    fun split(data: PeakData, segmentDurationsMs: List<Long>): List<PeakData> {
        if (segmentDurationsMs.isEmpty()) return listOf(data)
        if (segmentDurationsMs.size == 1) return listOf(data)

        val bucketsPerSecond = data.bucketsPerSecond
        val total = data.bucketCount

        // The first bucket of every segment, and the end of the last one.
        val starts = ArrayList<Int>(segmentDurationsMs.size)
        var elapsedMs = 0L
        for (duration in segmentDurationsMs) {
            starts += bucketIndexAt(elapsedMs, bucketsPerSecond, total)
            elapsedMs += duration
        }

        val result = ArrayList<PeakData>(starts.size)
        for (index in starts.indices) {
            val from = starts[index]
            val to = if (index + 1 < starts.size) starts[index + 1] else total
            result += data.slice(from, to)
        }
        return result
    }

    /** The bucket that holds the time [timeMs]. */
    private fun bucketIndexAt(timeMs: Long, bucketsPerSecond: Int, total: Int): Int {
        if (timeMs <= 0L) return 0
        val index = (timeMs * bucketsPerSecond / 1_000L).toInt()
        return index.coerceIn(0, total)
    }
}

/**
 * The buckets `[from, to)` of this data as their own [PeakData].
 *
 * A range that is empty produces a data set with no buckets, which is a
 * legitimate result: a file that held no audio has an empty waveform.
 *
 * The slice starts at zero: it becomes the waveform of a **file**, and every
 * file has its own time axis starting at its first sample.
 */
fun PeakData.slice(from: Int, to: Int): PeakData {
    val start = from.coerceIn(0, bucketCount)
    val end = to.coerceIn(start, bucketCount)
    val count = end - start
    if (count == bucketCount) return this
    return PeakData(
        sampleRate = sampleRate,
        channelCount = channelCount,
        bucketsPerSecond = bucketsPerSecond,
        mins = mins.copyOfRange(start, end),
        maxs = maxs.copyOfRange(start, end),
        offsetMs = 0L,
    )
}
