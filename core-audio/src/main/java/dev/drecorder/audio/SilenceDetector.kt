package dev.drecorder.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Watches a playback-capture stream for digital silence.
 *
 * A source app that opts out of capture (`ALLOW_CAPTURE_BY_NONE`) or a
 * MediaProjection session that the system terminated both hand us a stream of
 * zeros with **no error at all**. Without this detector the recorder happily
 * produces a long, silent file and the user only finds out afterwards.
 *
 * Pure Kotlin on purpose: it is the one part of the capture path that can be
 * unit-tested off-device.
 *
 * @param silenceTimeoutMs how much *continuous* silence is reported as "blocked"
 * @param thresholdDbfs    windows quieter than this count as silence
 * @param windowMs         analysis window; silence is accounted in whole windows
 */
class SilenceDetector(
    val silenceTimeoutMs: Long = 8_000,
    val thresholdDbfs: Float = -60f,
    val windowMs: Long = 500,
) {

    companion object {
        /** Reported level for a window that contains no signal at all. */
        const val FLOOR_DBFS: Float = -120f

        private const val FULL_SCALE = 32_768.0
    }

    private var windowFrames = 0L
    private var windowSquareSum = 0.0
    private var elapsedFrames = 0L
    private var silentRunFrames = 0L
    private var windowDbfs = FLOOR_DBFS
    private var peak = FLOOR_DBFS
    private var lastSampleRate = 48_000

    fun reset() {
        windowFrames = 0L
        windowSquareSum = 0.0
        elapsedFrames = 0L
        silentRunFrames = 0L
        windowDbfs = FLOOR_DBFS
        peak = FLOOR_DBFS
    }

    /**
     * Feeds interleaved PCM16 samples. [count] is the number of samples to read,
     * [channels] the number of interleaved channels they carry.
     */
    fun addSamples(samples: ShortArray, count: Int, sampleRate: Int, channels: Int = 1) {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels > 0) { "channels must be positive" }

        val available = if (count < samples.size) count else samples.size
        val frames = available / channels
        if (frames <= 0) return

        for (frame in 0 until frames) {
            val base = frame * channels
            var sum = 0.0
            for (channel in 0 until channels) {
                sum += samples[base + channel].toDouble()
            }
            val value = sum / channels
            windowSquareSum += value * value
        }

        windowFrames += frames
        elapsedFrames += frames
        lastSampleRate = sampleRate

        val framesPerWindow = max(1L, sampleRate.toLong() * windowMs / 1_000L)
        if (windowFrames >= framesPerWindow) {
            val rms = sqrt(windowSquareSum / windowFrames)
            windowDbfs = if (rms <= 0.0) {
                FLOOR_DBFS
            } else {
                max(FLOOR_DBFS, (20.0 * log10(rms / FULL_SCALE)).toFloat())
            }
            peak = max(peak, windowDbfs)
            silentRunFrames = if (windowDbfs < thresholdDbfs) silentRunFrames + windowFrames else 0L
            windowFrames = 0L
            windowSquareSum = 0.0
        }
    }

    /** Level of the most recently completed window. */
    fun currentDbfs(): Float = windowDbfs

    /** Loudest window seen since the last [reset]; [FLOOR_DBFS] means "never any sound". */
    fun peakDbfs(): Float = peak

    /** True while the current continuous silent run has lasted at least [silenceTimeoutMs]. */
    fun isSilent(): Boolean = silentRunFrames * 1_000L / lastSampleRate >= silenceTimeoutMs

    /** Audio time fed so far. */
    fun elapsedMs(): Long = elapsedFrames * 1_000L / lastSampleRate
}
