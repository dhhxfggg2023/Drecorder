package dev.drecorder.audio.edit

/**
 * The whole editing model, as three pure functions.
 *
 * The "keep list" representation (DESIGN §12.2) pushes every hard case — overlapping
 * selections, cuts that split a range in two, cuts off either end, undo — into this
 * file, where it is testable on the JVM. The UI only drags handles and draws the
 * result. Nothing here touches Android.
 *
 * Ranges are half-open `[start, end)` and every function returns a list that is
 * sorted by `startMs` and free of overlaps, so callers never have to re-sort and the
 * waveform can paint them in order.
 */
object EditPlanner {

    /**
     * Canonicalises a keep list against a recording of [durationMs]:
     * sorts, merges overlapping **or touching** ranges, clamps to `[0, durationMs]`,
     * and drops empty results.
     *
     * Merging touching ranges (`0..1000` + `1000..2000` -> `0..2000`) matters: it is
     * what makes a sequence of small selection gestures end up as one continuous clip
     * instead of N clips with N seams.
     *
     * Idempotent: `normalize(normalize(x)) == normalize(x)`.
     */
    fun normalize(keep: List<KeepRange>, durationMs: Long): List<KeepRange> {
        if (durationMs <= 0) return emptyList()

        val clamped = keep.mapNotNull { range ->
            val start = range.startMs.coerceIn(0, durationMs)
            val end = range.endMs.coerceIn(0, durationMs)
            // `start >= end` covers both an empty range and one that fell entirely
            // outside the recording after clamping.
            if (start >= end) null else KeepRange(start, end)
        }.sortedBy { it.startMs }

        if (clamped.isEmpty()) return emptyList()

        val merged = mutableListOf<KeepRange>()
        var current = clamped.first()
        for (next in clamped.drop(1)) {
            current = if (next.startMs <= current.endMs) {
                // Overlapping or exactly touching: extend. `maxOf` because `next` may
                // be fully contained in `current`.
                KeepRange(current.startMs, maxOf(current.endMs, next.endMs))
            } else {
                merged += current
                next
            }
        }
        merged += current
        return merged
    }

    /**
     * Removes `[fromMs, toMs)` from [keep], the core "delete this bit" operation.
     *
     * A cut in the middle of a kept range splits it into two — that split is the whole
     * reason this is a list of ranges rather than a pair of trim points. Because the
     * result is re-normalized, a cut that misses every kept range is a no-op rather
     * than an error.
     */
    fun remove(
        keep: List<KeepRange>,
        fromMs: Long,
        toMs: Long,
        durationMs: Long,
    ): List<KeepRange> {
        val start = minOf(fromMs, toMs)
        val end = maxOf(fromMs, toMs)
        if (start == end) return normalize(keep, durationMs)

        val remaining = keep.flatMap { range ->
            when {
                // Entirely before or entirely after the cut.
                end <= range.startMs || start >= range.endMs -> listOf(range)

                // The cut swallows this range.
                start <= range.startMs && end >= range.endMs -> emptyList()

                // The cut eats the head.
                start <= range.startMs -> listOf(KeepRange(end, range.endMs))

                // The cut eats the tail.
                end >= range.endMs -> listOf(KeepRange(range.startMs, start))

                // The cut goes through the middle: one range becomes two.
                else -> listOf(KeepRange(range.startMs, start), KeepRange(end, range.endMs))
            }
        }
        return normalize(remaining, durationMs)
    }

    /**
     * The inverse of [keep]: the stretches the user has cut out, for painting them
     * greyed-out on the waveform.
     *
     * The waveform's timeline always maps to the **original** file (DESIGN §12.4), so
     * the UI needs to know exactly what was removed and where it used to be.
     */
    fun complement(keep: List<KeepRange>, durationMs: Long): List<KeepRange> {
        if (durationMs <= 0) return emptyList()

        val kept = normalize(keep, durationMs)
        if (kept.isEmpty()) return listOf(KeepRange(0, durationMs))

        val gaps = mutableListOf<KeepRange>()
        var cursor = 0L
        for (range in kept) {
            if (range.startMs > cursor) gaps += KeepRange(cursor, range.startMs)
            cursor = range.endMs
        }
        if (cursor < durationMs) gaps += KeepRange(cursor, durationMs)
        return gaps
    }

    /** The whole recording, i.e. a freshly opened editor. */
    fun fullLength(durationMs: Long): List<KeepRange> =
        if (durationMs <= 0) emptyList() else listOf(KeepRange(0, durationMs))
}
