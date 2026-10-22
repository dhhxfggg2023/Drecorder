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
 * Building it is cheap — one lookup per bucket — because each recording's
 * waveform is already decoded. It is rebuilt whenever the clips change, which is
 * once per edit rather than once per frame.
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
     */
    fun build(
        clips: List<EditClip>,
        peaksOf: (File) -> PeakData?,
        bucketsPerSecond: Int = BUCKETS_PER_SECOND,
    ): PeakData? {
        if (clips.isEmpty()) return null
        val totalMs = EditTrack.totalMs(clips)
        if (totalMs <= 0L) return null
        val count = ((totalMs * bucketsPerSecond + 999L) / 1_000L).toInt().coerceAtLeast(1)
        val mins = ShortArray(count)
        val maxs = ShortArray(count)

        var clipStartMs = 0L
        for (clip in clips) {
            val clipEndMs = clipStartMs + clip.durationMs
            val firstBucket = (clipStartMs * bucketsPerSecond / 1_000L).toInt()
            val lastBucket = ((clipEndMs * bucketsPerSecond + 999L) / 1_000L)
                .toInt()
                .coerceAtMost(count)
            val peaks = peaksOf(clip.file)
            for (bucket in firstBucket until lastBucket) {
                if (peaks == null || peaks.bucketCount == 0) continue
                val outputMs = bucket.toLong() * 1_000L / bucketsPerSecond
                val sourceMs = clip.startMs + (outputMs - clipStartMs)
                val index = peaks.bucketIndexAt(sourceMs)
                mins[bucket] = peaks.mins[index]
                maxs[bucket] = peaks.maxs[index]
            }
            clipStartMs = clipEndMs
        }

        return PeakData(
            sampleRate = 44_100,
            channelCount = 1,
            bucketsPerSecond = bucketsPerSecond,
            mins = mins,
            maxs = maxs,
            offsetMs = 0L,
        )
    }
}
