package dev.drecorder.audio.edit

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Android half of the peak decoder: `MediaExtractor` + `MediaCodec` behind
 * the interfaces the testable loop talks to (DESIGN §12.4).
 *
 * The split exists so the loop's behaviour — how often it waits, how much it
 * feeds before waiting — can be asserted on the JVM. This file is the part
 * that only a device can run.
 */
class MediaCodecPeakDriver(
    private val codec: MediaCodec,
    private val extractor: MediaExtractor,
) : PeakCodecDriver {

    private val bufferInfo = MediaCodec.BufferInfo()

    override fun dequeueInputBuffer(timeoutUs: Long): Int =
        codec.dequeueInputBuffer(timeoutUs)

    override fun inputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)

    override fun queueInputBuffer(index: Int, size: Int, timeUs: Long) {
        codec.queueInputBuffer(index, 0, size, timeUs, 0)
    }

    override fun queueEndOfStream(index: Int) {
        codec.queueInputBuffer(
            index,
            0,
            0,
            0,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
        )
    }

    override fun dequeueOutputBuffer(buffer: PeakCodecBuffer, timeoutUs: Long): PollResult {
        val index = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
        return when {
            index >= 0 -> {
                buffer.offset = bufferInfo.offset
                buffer.size = bufferInfo.size
                buffer.flags = bufferInfo.flags
                buffer.presentationTimeUs = bufferInfo.presentationTimeUs
                PollResult.Ready(index)
            }

            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> PollResult.FormatChanged

            else -> PollResult.None
        }
    }

    override fun outputBuffer(index: Int): ByteBuffer? = codec.getOutputBuffer(index)

    override fun releaseOutputBuffer(index: Int) {
        codec.releaseOutputBuffer(index, false)
    }

    override fun outputFormat(): PeakCodecFormat {
        val format = codec.outputFormat
        return PeakCodecFormat(
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0),
            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0),
        )
    }
}

/** The compressed samples of a file, read through `MediaExtractor`. */
class ExtractorSampleSource(
    private val extractor: MediaExtractor,
) : PeakSampleSource {

    override fun readSample(buffer: ByteBuffer): Int = extractor.readSampleData(buffer, 0)

    override val sampleTimeUs: Long get() = extractor.sampleTime

    override fun advance() {
        extractor.advance()
    }
}

/**
 * Decodes a recording into peak data.
 *
 * The decoder is the one part of the waveform subsystem that needs a device:
 * `MediaExtractor` + `MediaCodec` are Android APIs. Everything it feeds —
 * the bucketing, the cache, the snap-to-silence search — is plain Kotlin and
 * is unit-tested off-device.
 *
 * Decoding a 30 minute recording takes a while, so the decoder reports
 * progress and can be cancelled; a cancelled decode produces nothing rather
 * than a partial waveform.
 */
class PeakDecoder(
    private val bucketsPerSecond: Int = PeakData.DEFAULT_BUCKETS_PER_SECOND,
) {

    /** The outcome of a decode. */
    sealed interface Result {
        data class Success(val data: PeakData) : Result
        data class Failure(
            val message: String,
            val cause: Throwable? = null,
            /** Why the decode failed, so the UI can say something useful. */
            val reason: Reason = Reason.Unknown,
        ) : Result
    }

    /** Why a decode did not produce a waveform. */
    enum class Reason {
        /** The file is gone, has no audio track, or the codec threw. */
        Unknown,

        /** The user stopped it. */
        Cancelled,

        /** The decoder stopped answering. */
        Stalled,
    }

    /**
     * Decodes [file] and returns its peak data.
     *
     * @param onProgress receives a percentage in 0..100
     * @param cancelled consulted between chunks so a long decode can be stopped
     */
    fun decode(
        file: File,
        onProgress: ((Int) -> Unit)? = null,
        cancelled: AtomicBoolean = AtomicBoolean(false),
    ): Result {
        if (!file.isFile) return Result.Failure("文件已不存在")

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(file.absolutePath)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                trackIndex = index
                format = candidate
                break
            }
            if (trackIndex < 0 || format == null) {
                return Result.Failure("没有音频轨道")
            }

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return Result.Failure("音频格式未知")
            val durationUs = format.getLong(MediaFormat.KEY_DURATION, 0L)

            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val driver = MediaCodecPeakDriver(codec, extractor)
            val loop = PeakDecodeLoop(
                driver = driver,
                source = ExtractorSampleSource(extractor),
                sampleRate = sampleRate,
                channelCount = channelCount,
                bucketsPerSecond = bucketsPerSecond,
                durationUs = durationUs,
                onProgress = onProgress,
            )

            when (val outcome = loop.run(cancelled)) {
                is PeakDecodeOutcome.Completed -> {
                    onProgress?.invoke(100)
                    Result.Success(outcome.data)
                }

                is PeakDecodeOutcome.FormatMismatch -> {
                    // The container lied about the audio format; decode again
                    // with the numbers the decoder actually produced.
                    return restartWithActualFormat(
                        extractor,
                        trackIndex,
                        outcome.sampleRate,
                        outcome.channelCount,
                        onProgress,
                        cancelled,
                    )
                }

                PeakDecodeOutcome.Cancelled ->
                    Result.Failure("已取消", reason = Reason.Cancelled)

                PeakDecodeOutcome.Stalled ->
                    Result.Failure("解码器没有响应", reason = Reason.Stalled)
            }
        } catch (failure: Exception) {
            Result.Failure(failure.message ?: "解码失败", failure)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Restarts the decode with the format the decoder actually produced.
     *
     * The extractor has already consumed part of the file at this point, so the
     * decode starts over from the beginning — correctness over speed, and this
     * only happens when the container lies about its audio format.
     */
    private fun restartWithActualFormat(
        extractor: MediaExtractor,
        trackIndex: Int,
        sampleRate: Int,
        channelCount: Int,
        onProgress: ((Int) -> Unit)?,
        cancelled: AtomicBoolean,
    ): Result {
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        return decodeFresh(extractor, trackIndex, sampleRate, channelCount, onProgress, cancelled)
    }

    private fun decodeFresh(
        extractor: MediaExtractor,
        trackIndex: Int,
        sampleRate: Int,
        channelCount: Int,
        onProgress: ((Int) -> Unit)?,
        cancelled: AtomicBoolean,
    ): Result {
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: return Result.Failure("音频格式未知")
        val durationUs = format.getLong(MediaFormat.KEY_DURATION, 0L)
        val codec = MediaCodec.createDecoderByType(mime)
        return try {
            codec.configure(format, null, null, 0)
            codec.start()
            extractor.selectTrack(trackIndex)

            val driver = MediaCodecPeakDriver(codec, extractor)
            val loop = PeakDecodeLoop(
                driver = driver,
                source = ExtractorSampleSource(extractor),
                sampleRate = sampleRate,
                channelCount = channelCount,
                bucketsPerSecond = bucketsPerSecond,
                durationUs = durationUs,
                onProgress = onProgress,
            )

            when (val outcome = loop.run(cancelled)) {
                is PeakDecodeOutcome.Completed -> {
                    onProgress?.invoke(100)
                    Result.Success(outcome.data)
                }

                is PeakDecodeOutcome.FormatMismatch ->
                    Result.Failure("音频格式与文件不符")

                PeakDecodeOutcome.Cancelled ->
                    Result.Failure("已取消", reason = Reason.Cancelled)

                PeakDecodeOutcome.Stalled ->
                    Result.Failure("解码器没有响应", reason = Reason.Stalled)
            }
        } catch (failure: Exception) {
            Result.Failure(failure.message ?: "解码失败", failure)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }
}
