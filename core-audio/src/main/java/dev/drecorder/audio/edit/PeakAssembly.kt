package dev.drecorder.audio.edit

/**
 * Collects the waveform of a whole recording out of the pieces it was decoded in.
 *
 * A long recording cannot be decoded in one go: the user would stare at a
 * spinner for minutes before the first waveform appeared. It is therefore
 * decoded in **segments**, and this class puts the segments back together into
 * one waveform of the whole recording (DESIGN §12.4).
 *
 * The point is not only speed. A segmented decode can *show* what it has
 * already decoded, so the overview fills in while the rest is still being
 * worked on, and the user can start cutting before the decode is over.
 *
 * The buckets of a segment are addressed by their own time, so a segment that
 * starts at 90 s of the recording lands on the right place in the whole
 * waveform without the caller having to know anything about it.
 *
 * Pure and Android-free, so the assembly can be unit-tested off-device.
 */
class PeakAssembly(
    /** The resolution the whole waveform is assembled at. */
    val bucketsPerSecond: Int,
    /** How long the recording is; the waveform covers it entirely. */
    totalMs: Long,
) {

    init {
        require(bucketsPerSecond > 0) { "bucketsPerSecond must be positive" }
    }

    /**
     * How many buckets the file is expected to need.
     *
     * Only a first guess: a container that under-reports its duration would
     * otherwise silently truncate the waveform. The arrays grow on demand.
     */
    private val expected = ((totalMs.coerceAtLeast(0L) * bucketsPerSecond) / 1_000L).toInt().coerceAtLeast(1)

    /**
     * The furthest bucket the waveform may ever reach.
     *
     * The declared duration, with a little slack for a container that
     * under-reports it by a fraction of a second. Growth beyond this would let a
     * mis-decoded segment invent audio past the end of the file, which is worse
     * than leaving it out.
     */
    private val limit = expected + expected / 100 + bucketsPerSecond

    private var capacity = expected
    private var mins = ShortArray(expected)
    private var maxs = ShortArray(expected)
    private var filled = BooleanArray(expected)

    /** How many buckets of the whole waveform are known. */
    var count: Int = 0
        private set

    /** The format of the recording the segments came from. */
    var sampleRate: Int = 0
        private set

    /** The channel count of the recording the segments came from. */
    var channelCount: Int = 0
        private set

    /**
     * Adds one decoded segment.
     *
     * A segment that was decoded at another resolution is ignored: mixing
     * resolutions would make the waveform a lie.
     *
     * @return how many buckets of the whole waveform are known afterwards
     */
    fun add(segment: PeakData): Int {
        if (segment.bucketsPerSecond != bucketsPerSecond) return count
        if (sampleRate == 0) {
            sampleRate = segment.sampleRate
            channelCount = segment.channelCount
        }
        for (index in 0 until segment.bucketCount) {
            val timeMs = segment.bucketStartMs(index)
            if (timeMs < 0L) continue
            val target = ((timeMs * bucketsPerSecond) / 1_000L).toInt()
            // A segment that starts beyond the recording is a decoding fault, not
            // audio: it is dropped rather than allowed to stretch the waveform
            // past the length of the file.
            if (target < 0 || target >= limit) continue
            if (target >= capacity) growTo(target + 1)
            val minV = segment.minAt(index)
            val maxV = segment.maxAt(index)
            if (!filled[target]) {
                mins[target] = minV.toShort()
                maxs[target] = maxV.toShort()
                filled[target] = true
            } else {
                if (minV < mins[target]) mins[target] = minV.toShort()
                if (maxV > maxs[target]) maxs[target] = maxV.toShort()
            }
            if (target + 1 > count) count = target + 1
        }
        return count
    }

    /** Doubles the arrays until they can hold [needed] buckets. */
    private fun growTo(needed: Int) {
        var size = capacity
        while (size < needed) size = size shl 1
        mins = mins.copyOf(size)
        maxs = maxs.copyOf(size)
        filled = filled.copyOf(size)
        capacity = size
    }

    /**
     * The waveform of the whole recording, as far as it is known.
     *
     * The buckets that are not known yet are left out, so the waveform covers
     * only the part that has been decoded. The renderer draws nothing beyond
     * it — which is what the user should see while the rest is still being
     * decoded.
     *
     * **No copy.** [mins] and [maxs] are the assembly's own arrays, truncated to
     * [count] only when that already is their length. Copying them here was the
     * single most expensive thing in the decode of a long recording: a
     * two-hour file is sixty segments, and each segment copied everything
     * decoded so far — about 84 MB of copying and sixty arrays big enough to go
     * straight into the large-object heap, which is what makes the waveform crawl
     * in while the codec is already ahead (「解算卡」).
     *
     * The arrays only ever grow by doubling, and a grown array is written to
     * behind the caller's back, so the snapshot returned to a caller is stable
     * only until the next [add]. That is exactly how the caller uses it: every
     * segment is handed straight to the UI, which copies what it needs.
     */
    fun snapshot(): PeakData {
        val exact = count == mins.size && count == maxs.size
        return PeakData(
            sampleRate = sampleRate.coerceAtLeast(1),
            channelCount = channelCount.coerceAtLeast(1),
            bucketsPerSecond = bucketsPerSecond,
            mins = if (exact) mins else mins.copyOf(count),
            maxs = if (exact) maxs else maxs.copyOf(count),
            offsetMs = 0L,
        )
    }
}
