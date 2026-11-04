package dev.drecorder.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * PCM16 -> AAC-LC -> `.m4a`. Wraps one [MediaCodec] encoder and one [MediaMuxer];
 * both are created up front so a failure surfaces before any audio is recorded.
 */
class AacM4aWriter(
    outputFile: File,
    private val sampleRate: Int,
    private val channelCount: Int,
    bitRate: Int = 192_000,
) : AudioWriter {

    private companion object {
        const val TIMEOUT_US = 10_000L
        /**
         * How many rounds the final flush gets.
         *
         * Each round waits [TIMEOUT_US], so this is up to four seconds of a stuck
         * encoder before the tail is given up on. It is deliberately generous:
         * cutting the flush short would end the file before the end of the audio
         * it was fed, which is exactly the silent truncation this writer exists
         * to avoid. Callers therefore close the writer off the main thread
         * (CaptureService stops the session on Dispatchers.IO).
         */
        const val MAX_DRAIN_ROUNDS = 400

        /**
         * How many times the writer waits for the encoder before giving up.
         *
         * Each round waits [TIMEOUT_US] for a free input buffer, so this is roughly
         * 20 seconds of a completely stuck encoder before the writer gives up. A
         * healthy encoder frees a buffer after one drain.
         */
        const val MAX_INPUT_STALLS = 2_000
    }

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()

    private val bytesPerSecond = sampleRate * channelCount * 2
    private var trackIndex = -1
    private var muxerStarted = false
    private var pcmBytesWritten = 0L
    private var closed = false

    init {
        // The encoder and the muxer are opened as a pair, and a failure in either
        // one releases the other. Built as two property initialisers, a muxer that
        // could not be created left a started hardware encoder behind — which every
        // later session sees as "another app is recording" — and a configure failure
        // left both open plus an .m4a with no moov atom on disk.
        val openedCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val openedMuxer = try {
            MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (failure: Throwable) {
            runCatching { openedCodec.release() }
            throw failure
        }
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        try {
            openedCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            openedCodec.start()
        } catch (failure: Throwable) {
            runCatching { openedCodec.release() }
            runCatching { openedMuxer.release() }
            runCatching { outputFile.delete() }
            throw failure
        }
        codec = openedCodec
        muxer = openedMuxer
    }

    /** Feeds [size] bytes of interleaved PCM16 from [pcm]. */
    override fun encode(pcm: ByteArray, size: Int) {
        check(!closed) { "writer already closed" }
        var offset = 0
        while (offset < size) {
            val inputIndex = acquireInputBuffer()
            if (inputIndex < 0) {
                // The encoder never accepted input again. Throwing is deliberate:
                // silently dropping the rest of this chunk would leave a recording
                // that is shorter than the session that produced it, with nothing
                // in the file or the UI to say so.
                throw IllegalStateException("AAC encoder stopped accepting input")
            }

            val input = codec.getInputBuffer(inputIndex)
                ?: throw IllegalStateException("AAC encoder returned no input buffer")
            input.clear()
            val chunk = minOf(input.capacity(), size - offset)
            input.put(pcm, offset, chunk)
            codec.queueInputBuffer(inputIndex, 0, chunk, presentationTimeUs(), 0)

            pcmBytesWritten += chunk
            offset += chunk
            drain()
        }
    }

    /**
     * Waits for a free input buffer, freeing output buffers while it waits.
     *
     * The encoder refuses input while its output queue is full — that is normal
     * whenever the encoder is busier than the capture loop. The PCM that cannot be
     * handed over must not be lost, so the writer drains the encoder instead of
     * returning "no buffer" to the caller.
     */
    private fun acquireInputBuffer(): Int {
        var stalls = 0
        while (stalls < MAX_INPUT_STALLS) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) return index
            stalls++
            drainOutput(TIMEOUT_US)
        }
        return -1
    }

    /** Flushes the encoder and finishes the container. Returns the audio duration in ms. */
    override fun close(): Long {
        if (closed) return pcmBytesWritten * 1_000L / bytesPerSecond
        closed = true

        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex >= 0) {
            codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                presentationTimeUs(),
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
        }
        drainUntilEndOfStream()

        // try/finally: this is the only place the hardware encoder and the muxer
        // are released, and a throw from the drain or from writeSampleData would
        // otherwise leak the encoder for the life of the process and leave the
        // .m4a without its index.
        try {
            runCatching { codec.stop() }
            codec.release()
        } finally {
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }

        return pcmBytesWritten * 1_000L / bytesPerSecond
    }

    private fun presentationTimeUs(): Long = pcmBytesWritten * 1_000_000L / bytesPerSecond

    private fun drain() {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            if (!handleOutput(outputIndex)) return
        }
    }

    /**
     * Frees encoder output while the writer waits for room on the input side.
     *
     * The encoder cannot take new input while its output queue is full, so the
     * output has to be handed to the muxer to unblock it. This is the counterpart
     * of [acquireInputBuffer]: without it a busy encoder makes the writer drop
     * audio, and without that the recording is quietly shorter than the session.
     */
    private fun drainOutput(timeoutUs: Long) {
        val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
        if (outputIndex >= 0 || outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            handleOutput(outputIndex)
        }
    }

    private fun drainUntilEndOfStream() {
        var rounds = 0
        while (rounds++ < MAX_DRAIN_ROUNDS) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // The encoder is still working on what it was given. Giving up
                // early here would cut the tail of the recording off, so the loop
                // keeps waiting until the end-of-stream marker arrives.
                continue
            }
            if (!handleOutput(outputIndex)) return
        }
    }

    /** Returns true when draining should continue. */
    private fun handleOutput(outputIndex: Int): Boolean {
        when {
            outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return false

            outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                if (!muxerStarted) {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                return true
            }

            outputIndex >= 0 -> {
                val output = codec.getOutputBuffer(outputIndex)
                if (output != null) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, output, bufferInfo)
                    }
                }
                val endOfStream = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(outputIndex, false)
                return !endOfStream
            }

            else -> return false
        }
    }
}
