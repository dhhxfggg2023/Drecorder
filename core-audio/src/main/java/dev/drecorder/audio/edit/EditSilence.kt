package dev.drecorder.audio.edit

/**
 * Deciding whether an edited recording contains any sound at all.
 *
 * The library marks a recording as "全程没有声音" with a sidecar file, and that
 * sidecar is written by the capture engine. An edited file is a *new* recording,
 * so it needs its own verdict — and it must not inherit the source's: cutting
 * away the only spoken minute of a recording leaves a file that is genuinely
 * silent even though the source was not (DESIGN §12.3).
 *
 * The verdict is derived from the peaks that are already in memory for the
 * waveform, so it costs nothing extra: a kept range is silent when none of its
 * buckets carries a non-zero sample.
 *
 * Pure functions, so they are unit-tested without a device.
 */
object EditSilence {

    /**
     * True when [range] of [peaks] carries no sound.
     *
     * A missing waveform (`peaks == null`) is **not** treated as silence: the
     * honest answer is "unknown", and claiming silence would label a perfectly
     * audible file as broken.
     */
    fun rangeIsSilent(range: KeepRange, peaks: PeakData?): Boolean {
        if (peaks == null || peaks.bucketCount == 0) return false
        if (range.durationMs <= 0) return true

        val first = peaks.bucketIndexAt(range.startMs)
        // The range is half-open, so the last moment inside it is one millisecond
        // before its end.
        val last = peaks.bucketIndexAt(range.endMs - 1)
        for (index in first..last) {
            if (peaks.peakAt(index) > 0) return false
        }
        return true
    }

    /**
     * True when everything the plan keeps is silent.
     *
     * @param sources each source's kept ranges together with its waveform
     */
    fun keptAudioIsSilent(sources: List<Pair<List<KeepRange>, PeakData?>>): Boolean {
        var keptAnything = false
        for ((ranges, peaks) in sources) {
            for (range in ranges) {
                keptAnything = true
                if (!rangeIsSilent(range, peaks)) return false
            }
        }
        // Nothing kept at all is not "a silent recording" — it is an empty file,
        // which the exporter refuses to produce in the first place.
        return keptAnything
    }
}
