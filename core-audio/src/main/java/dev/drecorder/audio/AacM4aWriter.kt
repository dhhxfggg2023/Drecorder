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
        const val MAX_DRAIN_ROUNDS = 400
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer: MediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val bufferInfo = MediaCodec.BufferInfo()

    private val bytesPerSecond = sampleRate * channelCount * 2
    private var trackIndex = -1
    private var muxerStarted = false
    private var pcmBytesWritten = 0L
    private var closed = false

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Feeds [size] bytes of interleaved PCM16 from [pcm]. */
    override fun encode(pcm: ByteArray, size: Int) {
        check(!closed) { "writer already closed" }
        var offset = 0
        while (offset < size) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) break

            val input = codec.getInputBuffer(inputIndex) ?: break
            input.clear()
            val chunk = minOf(input.capacity(), size - offset)
            input.put(pcm, offset, chunk)
            codec.queueInputBuffer(inputIndex, 0, chunk, presentationTimeUs(), 0)

            pcmBytesWritten += chunk
            offset += chunk
            drain()
        }
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

        runCatching { codec.stop() }
        codec.release()
        if (muxerStarted) {
            runCatching { muxer.stop() }
        }
        muxer.release()

        return pcmBytesWritten * 1_000L / bytesPerSecond
    }

    private fun presentationTimeUs(): Long = pcmBytesWritten * 1_000_000L / bytesPerSecond

    private fun drain() {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            if (!handleOutput(outputIndex)) return
        }
    }

    private fun drainUntilEndOfStream() {
        var rounds = 0
        while (rounds++ < MAX_DRAIN_ROUNDS) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (rounds > 4) return
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
