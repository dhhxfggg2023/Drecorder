package dev.drecorder.audio

import java.io.File

/** How the captured PCM is stored. */
sealed interface EncoderSpec {

    /** AAC-LC in an `.m4a` container; [bitRate] in bits per second. */
    data class Aac(val bitRate: Int) : EncoderSpec

    /** Uncompressed PCM16 in a `.wav` container. */
    data object Wav : EncoderSpec

    val fileExtension: String
        get() = when (this) {
            is Aac -> "m4a"
            Wav -> "wav"
        }

    fun outputFileFor(directory: File, baseName: String): File =
        File(directory, "$baseName.${fileExtension}")

    /**
     * Name for one slice of a segmented session, e.g. `drecorder-20261018-100532-p02.m4a`.
     * Numbered from 1 so the parts sort in playback order in any file browser.
     */
    fun outputFileForSegment(directory: File, baseName: String, segmentNumber: Int): File =
        File(
            directory,
            String.format(
                java.util.Locale.US,
                "%s-p%02d.%s",
                baseName,
                segmentNumber,
                fileExtension,
            ),
        )

    /**
     * Throwaway path used only to probe whether this device can provide the encoder.
     * AAC writers create the file in their constructor, so the probe runs against a
     * temp file that is deleted immediately.
     */
    fun temporaryFileFor(): File =
        File.createTempFile("drecorder-probe-", ".$fileExtension")
}

/**
 * Writer contract shared by [AacM4aWriter] and [WavWriter] so the engine can fall
 * back to a lossless master when no AAC encoder exists on the device.
 */
interface AudioWriter {
    fun encode(pcm: ByteArray, size: Int)

    /** Returns the audio duration in milliseconds. */
    fun close(): Long
}

/**
 * Builds the writer for a given encoder choice, or null when the device cannot
 * provide it (no AAC encoder at all) so the caller can fall back to WAV.
 *
 * The subclasses are named `EncoderSpec.Aac` / `EncoderSpec.Wav`: an extension
 * receiver's nested classifiers are not in lexical scope, so the bare names would
 * not resolve.
 */
fun EncoderSpec.createWriter(
    outputFile: File,
    sampleRate: Int,
    channelCount: Int,
): AudioWriter? = runCatching<AudioWriter> {
    // An explicit block per branch, because a `when` expression over two unrelated
    // concrete writer types infers their common supertype `Any` and then fails to
    // unify with the declared return type.
    when (this) {
        is EncoderSpec.Aac -> newAacWriter(
            outputFile = outputFile,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitRate = bitRate,
        )

        EncoderSpec.Wav -> newWavWriter(outputFile, sampleRate, channelCount)
    }
}.getOrNull()

private fun newAacWriter(
    outputFile: File,
    sampleRate: Int,
    channelCount: Int,
    bitRate: Int,
): AudioWriter = AacM4aWriter(outputFile, sampleRate, channelCount, bitRate)

private fun newWavWriter(
    outputFile: File,
    sampleRate: Int,
    channelCount: Int,
): AudioWriter = WavWriter(outputFile, sampleRate, channelCount)

/**
 * True when this device can actually instantiate this encoder.
 *
 * Aac writers grab a hardware `MediaCodec` in their constructor, so the probe has to
 * create one, close it, and delete the throwaway file.
 */
fun EncoderSpec.isAvailableOnDevice(): Boolean {
    val probe = temporaryFileFor()
    val writer = createWriter(probe, 48_000, 2)
    return try {
        writer != null
    } finally {
        runCatching { writer?.close() }
        probe.delete()
    }
}

/** File extension and settings the UI should offer, given what the device supports. */
data class EncoderAvailability(
    val aacBitRates: List<Int>,
    val wav: Boolean,
) {
    companion object {
        val DEFAULT_AAC_BIT_RATES = listOf(128_000, 192_000, 256_000, 320_000)
    }
}
