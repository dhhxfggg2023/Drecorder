package dev.drecorder.audio.edit

import kotlin.math.abs

/**
 * The waveform reduced onto the column grid of **one zoom level**.
 *
 * A waveform is not one picture: it is a family of pictures, one per zoom
 * level, and the picture is a grid of columns fixed by the span and the width
 * ([WaveformBins.stepMs]). Reducing the buckets onto that grid is the expensive
 * half of drawing — every column has to look at every bucket it covers — while
 * the drawing itself is a lookup per column.
 *
 * Doing that reduction once per column of scrolling is what made a zoomed-out
 * waveform stutter: at ten minutes across a phone a column covers a third of a
 * second, so a single column crossing re-read tens of thousands of buckets,
 * many times a second while the finger moved (「滑动性和灵动性太差」,
 * 2026-10-23). Reducing a *page* of columns — three screens' worth — makes that
 * work happen once per screen of scrolling instead, and the draw block then
 * only reads it.
 *
 * [mins] and [maxs] are the true extremes of the audio each column covers, in
 * PCM units. A column with no audio behind it — before the recording, past its
 * end, or in a stretch that was not decoded — holds [WaveformColumns.EMPTY].
 *
 * Pure and Android-free, so the maths of the waveform can be unit-tested
 * off-device.
 */
class ColumnPeaks(
    /** How much audio one column covers, in ms. */
    val columnMs: Long,
    /**
     * When the first column starts, in ms.
     *
     * A multiple of [columnMs], and *absolute* — the same grid for every page,
     * so a column always covers the same stretch of audio wherever the window
     * happens to be.
     */
    val firstColumnStartMs: Long,
    val mins: IntArray,
    val maxs: IntArray,
    /**
     * The mean amplitude of every column, in PCM units.
     *
     * Drawn as the bright core inside the peak sticks. The extremes say how
     * loud the loudest moment was; the mean says how loud the column *is*, and
     * that is the difference between a hairy wall of sticks and a picture with
     * a loudness contour in it. A silent column has a mean of zero, and so does
     * a column with no audio at all — the two are told apart by [mins].
     */
    val means: IntArray,
) {

    init {
        require(columnMs > 0L) { "columnMs must be positive ($columnMs)" }
        require(mins.size == maxs.size) {
            "mins and maxs must have the same length (${mins.size} vs ${maxs.size})"
        }
        require(means.size == mins.size) {
            "means must have the same length as the extremes (${means.size} vs ${mins.size})"
        }
    }

    /** How many columns this page covers. */
    val count: Int get() = mins.size

    /**
     * The column that holds [timeMs].
     *
     * Floor division, so the answer is an index into this page for any moment
     * inside it. A moment before or after the page gives an index outside
     * `0 until count`, which [WaveformColumns] reports as [WaveformColumns.EMPTY]
     * rather than clamping: a column past the end of the audio must draw
     * nothing, not the last column of the page over and over.
     */
    fun indexOf(timeMs: Long): Int =
        Math.floorDiv(timeMs - firstColumnStartMs, columnMs).toInt()
}

/**
 * A window of [count] columns, read out of a [ColumnPeaks] page.
 *
 * A view rather than a copy: the draw block runs on every playhead tick, and
 * copying the arrays there would be work per frame for a picture that only
 * moves sideways.
 */
class WaveformColumns internal constructor(
    val count: Int,
    private val source: ColumnPeaks,
    private val base: Int,
) {

    /** The quietest sample of column [index], or [EMPTY]. */
    fun minAt(index: Int): Int {
        val at = base + index
        return if (at in source.mins.indices) source.mins[at] else EMPTY
    }

    /** The loudest sample of column [index], or [EMPTY]. */
    fun maxAt(index: Int): Int {
        val at = base + index
        return if (at in source.maxs.indices) source.maxs[at] else EMPTY
    }

    /**
     * The mean amplitude of column [index], or 0.
     *
     * Zero for a column outside the page as well: there is nothing there to be
     * loud, and [isEmpty] is the question that tells the two zeros apart.
     */
    fun meanAt(index: Int): Int {
        val at = base + index
        return if (at in source.means.indices) source.means[at] else 0
    }

    /** Whether column [index] has any audio behind it. */
    fun isEmpty(index: Int): Boolean = minAt(index) == EMPTY

    companion object {
        /** A column with no audio behind it. */
        const val EMPTY = Int.MAX_VALUE
    }
}

/** Builds [ColumnPeaks] and reads [WaveformColumns] out of them. */
object WaveformBins {

    /** The most columns that are ever built for one screen. */
    const val MAX_COLUMNS = 4_096

    /**
     * How narrow a drawn column may be, in pixels.
     *
     * Three, because the waveform is drawn as one thin stick per column
     * (WaveformView draws matchsticks, not a filled body — 「波形不要边缘连续，
     * 不好看，细火柴棍就行」, 2026-11-04). Six-pixel columns were chosen for the
     * *filled* outline that was drawn before them: with two-pixel sticks those
     * columns left two thirds of the width empty, so a waveform read as a sparse
     * comb instead of as sound.
     *
     * Three rather than two: at two the sticks are 2.2 px apart on a phone and
     * the picture becomes a solid body again — the filled envelope the user
     * rejected. At three they are 3–4 px apart, which leaves a visible matchstick
     * texture and still fills half the width instead of the third the six-pixel
     * columns filled. Picking by eye, on rendered frames, not by arithmetic.
     *
     * The cost of the narrower column is more columns per screen, and that is
     * paid by [ColumnPeaks]: the reduction is done per page, not per frame.
     */
    const val MIN_COLUMN_PX = 3

    /**
     * Reduces [peaks] onto the column grid of [columnMs], starting at
     * [firstColumnStartMs] and covering [columns] columns.
     *
     * [firstColumnStartMs] must be a multiple of [columnMs] ([gridStart] is what
     * produces it), so that pages taken at different moments line up and a
     * column always covers the same stretch of audio.
     *
     * Every bucket that overlaps a column contributes to it, not just the one
     * under the column's start: a source decoded at a thousand buckets a second
     * holds ten for every ten-millisecond column, and keeping one of them is
     * what threw the detail away.
     */
    fun reduce(
        peaks: PeakData,
        firstColumnStartMs: Long,
        columnMs: Long,
        columns: Int,
    ): ColumnPeaks {
        val column = columnMs.coerceAtLeast(1L)
        val count = columns.coerceIn(1, MAX_COLUMNS * 3)
        val mins = IntArray(count) { WaveformColumns.EMPTY }
        val maxs = IntArray(count) { WaveformColumns.EMPTY }
        val means = IntArray(count)
        val bucketCount = peaks.bucketCount
        if (bucketCount == 0) return ColumnPeaks(column, firstColumnStartMs, mins, maxs, means)

        val bucketsPerSecond = peaks.bucketsPerSecond
        val bucketMs = (1_000L / bucketsPerSecond).coerceAtLeast(1L)
        for (index in 0 until count) {
            val columnStartMs = firstColumnStartMs + index * column
            val columnEndMs = columnStartMs + column
            // Addressed by their own time range rather than by an index clamped
            // to the data: a window that reaches past the recording has to draw
            // nothing there, not the last bucket of the file.
            val firstBucket =
                ((columnStartMs - peaks.offsetMs) * bucketsPerSecond / 1_000L).toInt()
            val lastBucket =
                ((columnEndMs - peaks.offsetMs) * bucketsPerSecond / 1_000L).toInt()
            var low = Int.MAX_VALUE
            var high = Int.MIN_VALUE
            var amplitudeSum = 0L
            var seen = 0
            for (bucket in firstBucket..lastBucket) {
                if (bucket < 0 || bucket >= bucketCount) continue
                val bucketStartMs = peaks.bucketStartMs(bucket)
                // Only the part of the bucket that really falls into this column.
                if (bucketStartMs >= columnEndMs || bucketStartMs + bucketMs <= columnStartMs) {
                    continue
                }
                val minV = peaks.minAt(bucket)
                val maxV = peaks.maxAt(bucket)
                if (minV < low) low = minV
                if (maxV > high) high = maxV
                amplitudeSum += (abs(minV) + abs(maxV)) / 2
                seen++
            }
            if (low != Int.MAX_VALUE) {
                mins[index] = low
                maxs[index] = high
                means[index] = if (seen == 0) 0 else (amplitudeSum / seen).toInt()
            }
        }
        return ColumnPeaks(column, firstColumnStartMs, mins, maxs, means)
    }

    /**
     * The [count] columns that start at [firstColumnStartMs].
     *
     * [firstColumnStartMs] must be on the reduced grid ([gridStart] of the
     * window start); a moment outside the page reads as [WaveformColumns.EMPTY].
     */
    fun columnsOf(
        reduced: ColumnPeaks,
        firstColumnStartMs: Long,
        count: Int,
    ): WaveformColumns = WaveformColumns(
        count = count.coerceIn(1, MAX_COLUMNS),
        source = reduced,
        base = reduced.indexOf(firstColumnStartMs),
    )

    /**
     * The start of the column grid at or before [startMs].
     *
     * The view bins from this moment and translates the row by the rest of a
     * column, so that two frames of a scroll inside one column draw the *same*
     * columns (moved), instead of re-binning the audio into new ones. Kotlin's
     * [mod] is floor-mod, so a window that reaches before the recording still
     * lands on the grid.
     */
    fun gridStart(startMs: Long, barMs: Long): Long =
        startMs - startMs.mod(barMs.coerceAtLeast(1L))

    /** The duration of one cached bucket, in ms. */
    fun bucketMs(peaks: PeakData): Long =
        (1_000L / peaks.bucketsPerSecond.coerceAtLeast(1)).coerceAtLeast(1L)

    /**
     * How much audio one drawn column covers, in ms.
     *
     * Zoomed out there are many buckets per column, so a column is a number of
     * whole buckets. Zoomed in the opposite happens, and drawing one column per
     * pixel is what turns every bucket into a flat block five pixels wide:
     * 「放大后看到一个个矩形」 (2026-10-23). So a column is never shorter than a
     * bucket — and never narrower than [MIN_COLUMN_PX] pixels either.
     *
     * The answer only changes when the span or the width changes, never when the
     * window scrolls, so the grid stays still while the picture moves.
     */
    fun stepMs(peaks: PeakData, spanMs: Long, widthPx: Int): Long {
        val bucket = bucketMs(peaks)
        if (widthPx <= 0 || spanMs <= 0L) return bucket
        val columns = (widthPx + MIN_COLUMN_PX - 1) / MIN_COLUMN_PX
        val perColumnMs = (spanMs + columns - 1) / columns
        val bucketsPerColumn = (perColumnMs + bucket - 1) / bucket
        return bucket * bucketsPerColumn.coerceAtLeast(1L)
    }
}
