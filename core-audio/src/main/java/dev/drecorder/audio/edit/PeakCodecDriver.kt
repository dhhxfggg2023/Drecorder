package dev.drecorder.audio.edit

import java.nio.ByteBuffer

/**
 * The codec side of the peak decoder, behind an interface.
 *
 * `MediaCodec` and `MediaExtractor` cannot be unit-tested off-device (the
 * mockable android.jar throws on every call), so the decode loop talks to
 * these interfaces instead and the JVM tests drive a fake (DESIGN §12.4).
 *
 * The adapter that really talks to Android lives in `MediaCodecPeakDriver.kt`.
 */
interface PeakCodecDriver {

    /**
     * Asks for a free input buffer.
     *
     * @param timeoutUs how long to wait for one; 0 means "do not wait"
     * @return the buffer index, or -1 when none is available
     */
    fun dequeueInputBuffer(timeoutUs: Long): Int

    /** The buffer for [index], or null when the codec has none. */
    fun inputBuffer(index: Int): ByteBuffer?

    /** Hands [size] bytes of the input buffer to the codec with timestamp [timeUs]. */
    fun queueInputBuffer(index: Int, size: Int, timeUs: Long)

    /** Marks the end of the stream on an input buffer. */
    fun queueEndOfStream(index: Int)

    /**
     * Takes the next output buffer.
     *
     * @param buffer filled in with the offset, size, flags and presentation time
     * @param timeoutUs how long to wait for one; 0 means "do not wait"
     */
    fun dequeueOutputBuffer(buffer: PeakCodecBuffer, timeoutUs: Long): PollResult

    /** The decoded PCM for [index]. */
    fun outputBuffer(index: Int): ByteBuffer?

    /** Returns an output buffer to the codec. */
    fun releaseOutputBuffer(index: Int)

    /** The format the decoder produces; only valid after [PollResult.FormatChanged]. */
    fun outputFormat(): PeakCodecFormat
}

/** What a poll of the codec produced. */
sealed interface PollResult {
    /** An output buffer is ready at [index]. */
    data class Ready(val index: Int) : PollResult

    /** The decoder changed its output format (sample rate or channel count). */
    data object FormatChanged : PollResult

    /** Nothing is ready. */
    data object None : PollResult
}

/**
 * The parts of `MediaCodec.BufferInfo` the decode loop needs.
 *
 * A plain class rather than the Android one so the loop stays testable; the
 * flag value mirrors `MediaCodec.BUFFER_FLAG_END_OF_STREAM`.
 */
class PeakCodecBuffer {
    var offset: Int = 0
    var size: Int = 0
    var flags: Int = 0

    /** The presentation time of the decoded audio, in microseconds. */
    var presentationTimeUs: Long = 0L

    /** True when this buffer carries the end of the stream. */
    val endOfStream: Boolean get() = flags and FLAG_END_OF_STREAM != 0

    companion object {
        /** Mirrors `MediaCodec.BUFFER_FLAG_END_OF_STREAM`. */
        const val FLAG_END_OF_STREAM = 0x4
    }
}

/** The audio format a decoder produces. */
data class PeakCodecFormat(val sampleRate: Int, val channelCount: Int)

/**
 * The compressed samples the decoder is fed with.
 *
 * The adapter over `MediaExtractor` lives in `MediaCodecPeakDriver.kt`.
 */
interface PeakSampleSource {

    /**
     * Reads the next sample into [buffer].
     *
     * @return the number of bytes read, or -1 when the file is exhausted
     */
    fun readSample(buffer: ByteBuffer): Int

    /** The presentation time of the sample [readSample] would return. */
    val sampleTimeUs: Long

    /** Moves to the next sample. */
    fun advance()
}
