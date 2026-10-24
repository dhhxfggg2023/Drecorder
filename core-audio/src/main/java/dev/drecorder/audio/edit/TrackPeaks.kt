package dev.drecorder.audio.edit

import java.io.File

/**
 * The waveform of the whole track, laid out in the time the user sees.
 *
 * The editor draws one timeline, but the audio comes from clips that may begin
 * anywhere in any recording, may be repeated, and may be laid in any order. The
 * waveform therefore cannot be one file's [PeakData] drawn at an offset: it has
 * to be a new waveform *of the track*, sampled through the clips.
 *
 * Building it is cheap — the source waveforms are already decoded, so it is an
 * aggregation per bucket — and it is rebuilt whenever the clips change, which is
 * once per edit rather than once per frame. Zoomed in it is also rebuilt for the
 * stretch on screen, at the resolution that stretch needs ([bucketsPerSecond]
 * and the window), because the overview's twenty milliseconds per bucket turn
 * into five-pixel blocks at two seconds across the screen
 * (「放大了就像一个个很宽的东西挤在一起」, m00001).
 *
 * Pure and off-device, like the rest of this package.
 */
object TrackPeaks {

    /**
     * How many buckets per second the track waveform is built at.
     *
     * Fifty is one bucket per 20 ms: a phone screen shows a minute across a few
     * hundred pixels, so this is already more resolution than one viewport can
     * use, and a ten-minute track costs thirty thousand buckets.
     */
    const val BUCKETS_PER_SECOND = 50

    /**
     * Builds the waveform of [clips], or null when the track is empty.
     *
     * [peaksOf] answers with a recording's waveform, or null while it is still
     * being decoded — a clip whose recording has no waveform yet draws as
     * silence rather than as a hole, so the timeline keeps its shape while the
     * rest of the waveforms arrive.
     *
     * @param bucketsPerSecond the resolution of the *result*; a source waveform
     *   finer than this is aggregated, one that is coarser is sampled, so the
     *   result is as detailed as the sources allow
     * @param fromMs the first moment of the track to build, in ms
     * @param toMs the end of that stretch, exclusive; the whole track by default
     */
    fun build(
        clips: List<EditClip>,
        peaksOf: (File) -> PeakData?,
        bucketsPerSecond: Int = BUCKETS_PER_SECOND,
        fromMs: Long = 0L,
        toMs: Long = Long.MAX_VALUE,
    ): PeakData? {
        if (clips.isEmpty()) return null
        val totalMs = EditTrack.totalMs(clips)
        if (totalMs <= 0L) return null

        val bps = bucketsPerSecond.coerceAtLeast(1)
        val windowStartMs = fromMs.coerceIn(0L, totalMs - 1L)
        val windowEndMs = toMs.coerceIn(windowStartMs + 1L, totalMs)
        if (windowEndMs <= windowStartMs) return null
        val count = ceilToBuckets(windowEndMs - windowStartMs, bps).coerceAtLeast(1)

        val mins = ShortArray(count)
        val maxs = ShortArray(count)
        // Whether a bucket got any audio at all: a bucket between two clips, or
        // one in a recording that is not decoded yet, has to stay silent rather
        // than claim the zero that the arrays start with is sound.
        val filled = BooleanArray(count)

        var clipStartMs = 0L
        for (clip in clips) {
            val clipEndMs = clipStartMs + clip.durationMs
            val overlapStartMs = maxOf(clipStartMs, windowStartMs)
            val overlapEndMs = minOf(clipEndMs, windowEndMs)
            if (overlapEndMs > overlapStartMs) {
                val peaks = peaksOf(clip.file)
                if (peaks != null && peaks.bucketCount > 0) {
                    val firstBucket = ((overlapStartMs - windowStartMs) * bps / 1_000L).toInt()
                    val lastBucket = ceilToBuckets(overlapEndMs - windowStartMs, bps).toInt()
                    for (bucket in firstBucket until lastBucket.coerceAtMost(count)) {
                        if (bucket < 0) continue
                        val bucketStartMs = windowStartMs + bucket * 1_000L / bps
                        val bucketEndMs = windowStartMs + (bucket + 1L) * 1_000L / bps
                        val sourceStartMs =
                            clip.startMs + (maxOf(bucketStartMs, clipStartMs) - clipStartMs)
                        val sourceEndMs =
                            clip.startMs + (minOf(bucketEndMs, clipEndMs) - clipStartMs)
                        if (sourceEndMs <= sourceStartMs) continue
                        aggregate(peaks, sourceStartMs, sourceEndMs, mins, maxs, filled, bucket)
                    }
                }
            }
            clipStartMs = clipEndMs
        }

        return PeakData(
            sampleRate = 44_100,
            channelCount = 1,
            bucketsPerSecond = bps,
            mins = mins,
            maxs = maxs,
            offsetMs = windowStartMs,
        )
    }

    /**
     * Folds the part of [peaks] between [sourceStartMs] and [sourceEndMs] into
     * the output bucket.
     *
     * Every source bucket the range touches contributes, not just the one under
     * the bucket's start: a source decoded at a thousand buckets a second holds
     * ten of them for every twenty-millisecond bucket of the overview, and
     * keeping only one is what threw the detail away.
     */
    private fun aggregate(
        peaks: PeakData,
        sourceStartMs: Long,
        sourceEndMs: Long,
        mins: ShortArray,
        maxs: ShortArray,
        filled: BooleanArray,
        bucket: Int,
    ) {
        val bps = peaks.bucketsPerSecond
        // The source buckets covering [sourceStartMs, sourceEndMs), by their own
        // time ranges rather than by an index clamped to the data.
        val first = (((sourceStartMs - peaks.offsetMs) * bps) / 1_000L).toInt()
        val last = ((((sourceEndMs - peaks.offsetMs) * bps) + 999L) / 1_000L).toInt() - 1

        var low = Int.MAX_VALUE
        var high = Int.MIN_VALUE
        var seen = false
        for (index in first..last) {
            if (index < 0 || index >= peaks.bucketCount) continue
            seen = true
            val minV = peaks.minAt(index)
            val maxV = peaks.maxAt(index)
            if (minV < low) low = minV
            if (maxV > high) high = maxV
        }
        if (!seen) {
            // The source is coarser than the output bucket — zoomed in on a
            // waveform that has not been decoded in detail yet. Take the bucket
            // this moment falls in, so the shape is blocky rather than missing.
            val index = peaks.bucketIndexAt(sourceStartMs)
            low = peaks.minAt(index)
            high = peaks.maxAt(index)
        }

        if (!filled[bucket]) {
            mins[bucket] = low.toShort()
            maxs[bucket] = high.toShort()
            filled[bucket] = true
        } else {
            if (low < mins[bucket].toInt()) mins[bucket] = low.toShort()
            if (high > maxs[bucket].toInt()) maxs[bucket] = high.toShort()
        }
    }

    /** How many buckets of [bucketsPerSecond] cover [spanMs], rounded up. */
    private fun ceilToBuckets(spanMs: Long, bucketsPerSecond: Int): Int =
        ((spanMs * bucketsPerSecond + 999L) / 1_000L).toInt()
}
