package dev.drecorder.audio.edit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The shape of one recording: for every time bucket, the smallest and the
 * largest sample value that occurred inside it.
 *
 * Two numbers per bucket are enough to draw a waveform and enough to find the
 * quietest moment of a recording — which is what the editor snaps cuts to
 * (DESIGN §12.4). Storing the extremes rather than an average keeps a short
 * transient visible instead of averaging it away, and it keeps a bucket that
 * contains one loud sample from looking silent.
 *
 * Plain data with no Android dependency, so the whole of the waveform maths is
 * unit-testable off-device.
 *
 * @param sampleRate the rate the source was decoded at
 * @param channelCount the channel count of the source
 * @param bucketsPerSecond how many buckets cover one second of audio
 * @param mins the smallest sample value of each bucket, in PCM units
 * @param maxs the largest sample value of each bucket, in PCM units
 */
class PeakData(
    val sampleRate: Int,
    val channelCount: Int,
    val bucketsPerSecond: Int,
    val mins: ShortArray,
    val maxs: ShortArray,
    /**
     * The time the first bucket starts at, in ms.
     *
     * Zero for a waveform of a whole recording. A waveform decoded for a
     * *window* of a recording carries the window's start, so that the same
     * time axis applies to both and a window can be drawn without knowing
     * where it came from (DESIGN §12.4).
     */
    val offsetMs: Long = 0L,
) {

    init {
        require(mins.size == maxs.size) {
            "mins and maxs must have the same length (${mins.size} vs ${maxs.size})"
        }
        require(bucketsPerSecond > 0) { "bucketsPerSecond must be positive" }
    }

    /** Number of buckets; each covers `1 / bucketsPerSecond` of a second. */
    val bucketCount: Int get() = mins.size

    /** Audio time the buckets cover. */
    val durationMs: Long get() = bucketCount.toLong() * 1_000L / bucketsPerSecond

    /** The time the last bucket ends at, in ms. */
    val endMs: Long get() = offsetMs + durationMs

    /** The smallest sample value of the bucket at [index]. */
    fun minAt(index: Int): Int = mins[index].toInt()

    /** The largest sample value of the bucket at [index]. */
    fun maxAt(index: Int): Int = maxs[index].toInt()

    /**
     * How far the bucket at [index] reaches from silence, in PCM units
     * (0 = digital silence, 32768 = full scale).
     *
     * This is the *loudest* of the two extremes on purpose: a bucket that holds
     * one loud sample is not quiet, and the snap-to-silence search must not
     * mistake its zero crossing for a resting place.
     */
    fun peakAt(index: Int): Int = max(abs(minAt(index)), abs(maxAt(index)))

    /** The height of the bucket at [index] as a 0..1 fraction of full scale. */
    fun amplitudeAt(index: Int): Float = peakAt(index) / FULL_SCALE

    /** The time the bucket at [index] starts at, in ms. */
    fun bucketStartMs(index: Int): Long =
        offsetMs + index.toLong() * 1_000L / bucketsPerSecond

    /** The index of the bucket that contains [timeMs], clamped to the data. */
    fun bucketIndexAt(timeMs: Long): Int {
        if (bucketCount == 0) return 0
        val index = ((timeMs - offsetMs) * bucketsPerSecond / 1_000L).toInt()
        return index.coerceIn(0, bucketCount - 1)
    }

    /**
     * The start time of the quietest bucket within [radiusMs] of [timeMs].
     *
     * This is what makes a cut land in a pause instead of in the middle of a
     * word (DESIGN §12.4). The search looks at the peak amplitude of each
     * candidate bucket and takes the smallest one; a tie is broken towards
     * [timeMs] so the snap never moves the cut further than it has to.
     *
     * Returns [timeMs] unchanged when the data has no bucket in range.
     */
    fun quietestNear(timeMs: Long, radiusMs: Long): Long {
        if (bucketCount == 0) return timeMs
        val first = bucketIndexAt(timeMs - radiusMs)
        val last = bucketIndexAt(timeMs + radiusMs)

        var bestIndex = -1
        var bestPeak = Int.MAX_VALUE
        var bestDistance = Long.MAX_VALUE
        for (index in first..last) {
            val peak = peakAt(index)
            val distance = abs(bucketStartMs(index) - timeMs)
            if (peak < bestPeak || (peak == bestPeak && distance < bestDistance)) {
                bestPeak = peak
                bestDistance = distance
                bestIndex = index
            }
        }
        return if (bestIndex < 0) timeMs else bucketStartMs(bestIndex)
    }

    /** The quietest bucket in the whole recording, as a start time in ms. */
    fun quietestOverall(): Long {
        if (bucketCount == 0) return 0
        var bestIndex = 0
        var bestPeak = peakAt(0)
        for (index in 1 until bucketCount) {
            val peak = peakAt(index)
            if (peak < bestPeak) {
                bestPeak = peak
                bestIndex = index
            }
        }
        return bucketStartMs(bestIndex)
    }

    companion object {
        /** PCM16 full scale. */
        const val FULL_SCALE = 32_768f

        /** Buckets per second used for the on-disk cache. */
        const val DEFAULT_BUCKETS_PER_SECOND = 100
    }
}

/**
 * Builds a [PeakData] from raw PCM.
 *
 * The bucketing is the only fiddly part of the waveform subsystem, so it lives
 * here in a class with no Android dependency: the maths is unit-tested, and
 * only the decoding around it needs a device.
 *
 * A bucket covers `sampleRate / bucketsPerSecond` frames, which is not always
 * a whole number (44 100 Hz at 100 buckets/s is 441 frames, but 11 025 Hz would
 * be 110.25). The boundaries are therefore computed from the absolute frame
 * index rather than by counting frames per bucket, so no sample is lost or
 * double-counted at a fractional boundary.
 */
class PeakBins(
    val sampleRate: Int,
    val channelCount: Int,
    val bucketsPerSecond: Int,
    /**
     * The time the first bucket starts at, in ms.
     *
     * Zero for a whole recording. A waveform built for a *window* of a
     * recording carries the window's start, so it can be drawn on the same
     * time axis as everything else (DESIGN §12.4).
     */
    val offsetMs: Long = 0L,
) {

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channelCount > 0) { "channelCount must be positive" }
        require(bucketsPerSecond > 0) { "bucketsPerSecond must be positive" }
    }

    private var mins = ShortArray(INITIAL_CAPACITY)
    private var maxs = ShortArray(INITIAL_CAPACITY)
    private var count = 0
    private var frameCursor = 0L
    private var currentMin = 0
    private var currentMax = 0
    private var currentHasSample = false

    /** Buckets completed so far. */
    val bucketCount: Int get() = count

    /**
     * Feeds interleaved PCM16 samples.
     *
     * @param samples the interleaved samples
     * @param sampleCount how many samples of [samples] to read; values beyond
     *   the array are ignored
     */
    fun addPcm16(samples: ShortArray, sampleCount: Int) {
        if (sampleCount <= 0) return
        val usable = min(sampleCount, samples.size)
        var sampleIndex = 0
        while (sampleIndex < usable) {
            val framesAvailable = (usable - sampleIndex) / channelCount
            if (framesAvailable <= 0) break

            val bucket = bucketOf(frameCursor)
            val bucketEnd = bucketEndFrame(bucket)
            val framesToTake = min(framesAvailable.toLong(), bucketEnd - frameCursor).toInt()

            var offset = sampleIndex
            repeat(framesToTake) {
                var low = Int.MAX_VALUE
                var high = Int.MIN_VALUE
                for (channel in 0 until channelCount) {
                    val value = samples[offset + channel].toInt()
                    if (value < low) low = value
                    if (value > high) high = value
                }
                if (!currentHasSample || low < currentMin) currentMin = low
                if (!currentHasSample || high > currentMax) currentMax = high
                currentHasSample = true
                offset += channelCount
            }

            sampleIndex += framesToTake * channelCount
            frameCursor += framesToTake

            if (frameCursor >= bucketEnd) {
                emit()
            }
        }
    }

    /**
     * Closes the last bucket and returns the collected data.
     *
     * The final bucket of a recording is normally short — the audio ends in
     * the middle of a bucket — and it still carries sound, so it is emitted
     * rather than dropped.
     */
    fun finish(): PeakData {
        if (currentHasSample) emit()
        return PeakData(
            sampleRate = sampleRate,
            channelCount = channelCount,
            bucketsPerSecond = bucketsPerSecond,
            mins = mins.copyOf(count),
            maxs = maxs.copyOf(count),
            offsetMs = offsetMs,
        )
    }

    private fun emit() {
        ensureCapacity(count + 1)
        mins[count] = currentMin.toShort()
        maxs[count] = currentMax.toShort()
        count++
        currentMin = 0
        currentMax = 0
        currentHasSample = false
    }

    private fun ensureCapacity(required: Int) {
        if (required <= mins.size) return
        var size = if (mins.isEmpty()) INITIAL_CAPACITY else mins.size
        while (size < required) size *= 2
        mins = mins.copyOf(size)
        maxs = maxs.copyOf(size)
    }

    /** The bucket a frame belongs to. */
    private fun bucketOf(frame: Long): Long = frame * bucketsPerSecond / sampleRate

    /** The first frame index that belongs to the bucket after [bucket]. */
    private fun bucketEndFrame(bucket: Long): Long =
        ((bucket + 1) * sampleRate + bucketsPerSecond - 1) / bucketsPerSecond

    private companion object {
        const val INITIAL_CAPACITY = 1_024
    }
}

/**
 * The on-disk form of a [PeakData].
 *
 * The header carries the size and modification time of the source recording so
 * a stale cache can be detected: re-recording a file with the same name must
 * not show the old waveform.
 */
class PeakFile(
    val data: PeakData,
    val sourceSizeBytes: Long,
    val sourceModifiedAtMillis: Long,
)
