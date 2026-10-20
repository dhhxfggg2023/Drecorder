package dev.drecorder.audio.edit

/**
 * Mapping between the exported timeline and the original recordings.
 *
 * The editor draws every recording on its **own** timeline, but the preview and
 * the export play one continuous stream. Knowing where a source's kept audio
 * starts in that stream is what lets the playhead sit on the right recording
 * while the merged result plays (DESIGN §12.4).
 *
 * Pure functions over an [EditPlan], so they are unit-tested without a device.
 */
object EditTimeline {

    /**
     * The position in the exported stream at which [sourceIndex]'s kept audio
     * begins.
     *
     * Zero for the first source; for later sources it is the total length of
     * everything that was kept before it.
     */
    fun sourceStartMs(plan: EditPlan, sourceIndex: Int): Long {
        var offset = 0L
        val limit = minOf(sourceIndex, plan.sources.size)
        for (index in 0 until limit) {
            offset += plan.sources[index].keep.sumOf { it.durationMs }
        }
        return offset
    }

    /**
     * The position in the exported stream of a point in [sourceIndex]'s original
     * timeline, or null when that point was cut away.
     */
    fun outputMsForSourceTime(plan: EditPlan, sourceIndex: Int, sourceTimeMs: Long): Long? {
        val source = plan.sources.getOrNull(sourceIndex) ?: return null
        var cursor = sourceStartMs(plan, sourceIndex)
        for (range in source.keep) {
            if (sourceTimeMs >= range.startMs && sourceTimeMs < range.endMs) {
                return cursor + (sourceTimeMs - range.startMs)
            }
            cursor += range.durationMs
        }
        return null
    }

    /**
     * The point in [sourceIndex]'s original timeline that plays at [outputMs] of
     * the exported stream, or null when that position belongs to another source.
     */
    fun sourceTimeForOutputMs(plan: EditPlan, sourceIndex: Int, outputMs: Long): Long? {
        val source = plan.sources.getOrNull(sourceIndex) ?: return null
        val local = outputMs - sourceStartMs(plan, sourceIndex)
        if (local < 0) return null
        var cursor = 0L
        for (range in source.keep) {
            if (local < cursor + range.durationMs) {
                return range.startMs + (local - cursor)
            }
            cursor += range.durationMs
        }
        return null
    }
}
