package dev.drecorder.audio.edit

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes a recording into PCM so that [PeakBins] can summarise it.
 *
 * The decoder is the one part of the waveform subsystem that needs a device:
 * `MediaExtractor` + `MediaCodec` are Android APIs. Everything it feeds —
 * the bucketing, the cache, the snap-to-silence search — is plain Kotlin and
 * is unit-tested off-device.
 *
 * Decoding a 30 minute recording to PCM takes a while, so the decoder reports
 * progress and can be cancelled; a cancelled decode produces nothing rather
 * than a partial waveform.
 */
class PeakDecoder(
    private val bucketsPerSecond: Int = PeakData.DEFAULT_BUCKETS_PER_SECOND,
) {

    /** The outcome of a decode. */
    sealed interface Result {
        data class Success(val data: PeakData) : Result
        data class Failure(val message: String, val cause: Throwable? = null) : Result
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

            val bins = PeakBins(sampleRate, channelCount, bucketsPerSecond)
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false
            val pcm = ShortArray(PCM_CHUNK_FRAMES * channelCount)

            while (!sawOutputEnd) {
                if (cancelled.get()) {
                    return Result.Failure("已取消")
                }

                if (!sawInputEnd) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = if (inputBuffer != null) {
                            extractor.readSampleData(inputBuffer, 0)
                        } else {
                            -1
                        }
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                            reportProgress(onProgress, durationUs, extractor.sampleTime)
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            readInto(bins, outputBuffer, bufferInfo, channelCount, pcm)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEnd = true
                        }
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The decoder may report the real sample rate here.
                        val actual = codec.outputFormat
                        val actualRate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        val actualChannels =
                            actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                        if (actualRate != sampleRate || actualChannels != channelCount) {
                            // The bins were created for the container's numbers; a
                            // mismatch would silently mis-scale the timeline, so
                            // restart with the real ones.
                            return restartWithActualFormat(
                                extractor,
                                trackIndex,
                                actualRate,
                                actualChannels,
                                onProgress,
                                cancelled,
                            )
                        }
                    }
                }
            }

            Result.Success(bins.finish())
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

            val bins = PeakBins(sampleRate, channelCount, bucketsPerSecond)
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEnd = false
            var sawOutputEnd = false
            val pcm = ShortArray(PCM_CHUNK_FRAMES * channelCount)

            while (!sawOutputEnd) {
                if (cancelled.get()) return Result.Failure("已取消")

                if (!sawInputEnd) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = if (inputBuffer != null) {
                            extractor.readSampleData(inputBuffer, 0)
                        } else {
                            -1
                        }
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                            reportProgress(onProgress, durationUs, extractor.sampleTime)
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        readInto(bins, outputBuffer, bufferInfo, channelCount, pcm)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEnd = true
                    }
                }
            }
            Result.Success(bins.finish())
        } catch (failure: Exception) {
            Result.Failure(failure.message ?: "解码失败", failure)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /**
     * Copies one decoded buffer into the bins.
     *
     * The decoder hands back raw PCM16 in native byte order, which on every
     * Android device is little endian — reading it as big endian would turn a
     * quiet recording into a loud one and vice versa, so the bytes are
     * converted by hand rather than through a byte-order-sensitive overload
     * that Android's `ByteBuffer` does not have.
     */
    private fun readInto(
        bins: PeakBins,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channelCount: Int,
        pcm: ShortArray,
    ) {
        val end = (info.offset + info.size).coerceAtMost(buffer.capacity())
        if (end <= info.offset) return
        buffer.position(info.offset)
        buffer.limit(end)

        val bytes = ByteArray(end - info.offset)
        buffer.get(bytes)

        var total = 0
        var index = 0
        while (index + 1 < bytes.size) {
            val low = bytes[index].toInt() and 0xFF
            val high = bytes[index + 1].toInt()
            pcm[total] = ((high shl 8) or low).toShort()
            total++
            index += 2
            if (total == pcm.size) {
                bins.addPcm16(pcm, total)
                total = 0
            }
        }
        if (total > 0) {
            bins.addPcm16(pcm, total)
        }
    }

    private fun reportProgress(onProgress: ((Int) -> Unit)?, durationUs: Long, sampleTimeUs: Long) {
        if (onProgress == null) return
        if (durationUs <= 0) return
        val percent = (sampleTimeUs * 100L / durationUs).toInt().coerceIn(0, 99)
        onProgress(percent)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val PCM_CHUNK_FRAMES = 4_096
    }
}
