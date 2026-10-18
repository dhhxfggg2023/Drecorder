package dev.drecorder.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * PCM16 -> `.wav`, used when the AAC encoder is unavailable or when the user asks
 * for a lossless master.
 *
 * The RIFF size fields are unknown while recording, so they are written as
 * placeholders and patched on [close].
 */
class WavWriter(
    private val outputFile: File,
    private val sampleRate: Int,
    private val channelCount: Int,
) : AudioWriter {

    private companion object {
        const val HEADER_BYTES = 44
        const val BYTES_PER_SAMPLE = 2
    }

    private val file = RandomAccessFile(outputFile, "rw")
    private val dataBytesPerSecond = sampleRate.toLong() * channelCount * BYTES_PER_SAMPLE
    private var dataBytes = 0L
    private var closed = false

    init {
        file.setLength(0)
        file.write(ByteArray(HEADER_BYTES))
        writeHeader(dataLength = 0, riffSize = 0)
    }

    override fun encode(pcm: ByteArray, size: Int) {
        check(!closed) { "writer already closed" }
        if (size <= 0) return
        file.seek(file.length())
        file.write(pcm, 0, size)
        dataBytes += size
    }

    /** Flushes the header. Returns the audio duration in ms. */
    override fun close(): Long {
        if (closed) return durationMs()
        closed = true
        writeHeader(dataLength = dataBytes, riffSize = dataBytes + HEADER_BYTES - 8)
        file.fd.sync()
        file.close()
        return durationMs()
    }

    fun durationMs(): Long = dataBytes * 1_000L / dataBytesPerSecond

    private fun writeHeader(dataLength: Long, riffSize: Long) {
        file.seek(0)
        file.writeBytes("RIFF")
        file.write(intLe(riffSize))
        file.writeBytes("WAVE")
        file.writeBytes("fmt ")
        file.write(intLe(16L))                       // PCM chunk size
        file.write(shortLe(1))                      // audio format 1 = PCM
        file.write(shortLe(channelCount))
        file.write(intLe(sampleRate.toLong()))
        file.write(intLe(dataBytesPerSecond))       // byte rate
        file.write(shortLe(channelCount * BYTES_PER_SAMPLE)) // block align
        file.write(shortLe(BYTES_PER_SAMPLE * 8))   // bits per sample
        file.writeBytes("data")
        file.write(intLe(dataLength))
    }

    private fun intLe(value: Long) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun shortLe(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )
}
