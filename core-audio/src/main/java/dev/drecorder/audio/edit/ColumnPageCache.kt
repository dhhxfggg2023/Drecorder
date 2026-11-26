package dev.drecorder.audio.edit

/**
 * The reduced columns of the screen, kept a *page* at a time.
 *
 * Reducing buckets onto the column grid is the expensive half of drawing: every
 * column reads every bucket it covers. Doing that for the visible columns inside
 * the draw block is fine for one screen — but the visible columns change every
 * time the window moves by one column, so a scroll re-reduced the whole screen
 * one column at a time, sixty times a second. On a hundred-megabyte recording
 * zoomed out to ten minutes, where a column covers most of a second, that
 * re-read hundreds of thousands of buckets per frame (「解算卡」).
 *
 * So the reduction is done for a page of several screens of columns, and the page
 * is thrown away only when the window has scrolled off it — once per screen of
 * scrolling instead of once per column. The draw block then reads
 * already-reduced columns, which is a lookup per column.
 *
 * The page is anchored to the **recording** grid, not to the screen, so a column
 * always covers the same stretch of audio and the picture does not shimmer as it
 * moves.
 *
 * Not thread-safe on purpose: it lives in the draw path, where one frame owns it.
 */
class ColumnPageCache {

    private var peaks: PeakData? = null
    private var columnMs: Long = -1L
    private var pageStartMs: Long = Long.MIN_VALUE
    private var pageColumns: Int = 0
    private var reduced: ColumnPeaks? = null

    /** How many times the page was actually rebuilt; the cost the cache avoids. */
    var rebuilds: Int = 0
        private set

    /**
     * The [count] columns starting at [startMs], reduced if they are not already
     * on the current page.
     *
     * [startMs] must already be on the column grid ([WaveformBins.gridStart]).
     */
    fun columnsFor(
        peaks: PeakData,
        startMs: Long,
        columnMs: Long,
        count: Int,
    ): WaveformColumns {
        val needed = count.coerceIn(1, WaveformBins.MAX_COLUMNS)
        val page = reduced
        val covered = page != null &&
            peaks === this.peaks &&
            columnMs == this.columnMs &&
            startMs >= pageStartMs &&
            startMs + needed * columnMs <= pageStartMs + pageColumns * columnMs
        if (covered) return WaveformBins.columnsOf(page!!, startMs, needed)

        // One whole screen behind the window: a flick scrolls backwards as often
        // as forwards, and a page that only covered forwards would be rebuilt on
        // the first backward frame of every gesture.
        val before = needed
        val total = needed * 2 + PAGE_MARGIN_COLUMNS
        val fromMs = startMs - before * columnMs
        val built = WaveformBins.reduce(peaks, fromMs, columnMs, total)
        this.peaks = peaks
        this.columnMs = columnMs
        this.pageStartMs = fromMs
        this.pageColumns = built.count
        reduced = built
        rebuilds++
        return WaveformBins.columnsOf(built, startMs, needed)
    }

    private companion object {
        /**
         * Columns reduced beyond what the window can show in one screen.
         *
         * The page holds the forward screen, the backward screen and this margin,
         * so ordinary scrolling stays inside one page.
         */
        const val PAGE_MARGIN_COLUMNS = 64
    }
}
