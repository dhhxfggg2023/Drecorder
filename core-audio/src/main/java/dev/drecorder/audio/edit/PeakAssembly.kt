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

    private val capacity =
        ((totalMs.coerceAtLeast(0L) * bucketsPerSecond) / 1_000L).toInt().coerceAtLeast(1)
    private val mins = ShortArray(capacity)
    private val maxs = ShortArray(capacity)
    private val filled = BooleanArray(capacity)

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
            if (target < 0 || target >= capacity) continue
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

    /**
     * The waveform of the whole recording, as far as it is known.
     *
     * The buckets that are not known yet are left out, so the waveform covers
     * only the part that has been decoded. The renderer draws nothing beyond
     * it — which is what the user should see while the rest is still being
     * decoded.
     */
    fun snapshot(): PeakData = PeakData(
        sampleRate = sampleRate.coerceAtLeast(1),
        channelCount = channelCount.coerceAtLeast(1),
        bucketsPerSecond = bucketsPerSecond,
        mins = mins.copyOf(count),
        maxs = maxs.copyOf(count),
        offsetMs = 0L,
    )
}
