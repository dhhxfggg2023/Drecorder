package dev.drecorder.audio.edit

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * The audio properties that decide whether two recordings can be spliced without
 * re-encoding.
 *
 * Transmuxing rearranges whole encoded samples; it cannot convert one sample rate
 * or channel layout into another. These three values are therefore exactly the
 * contract between the sources of a merge, and the reason a merge has to refuse
 * mismatched recordings instead of quietly transcoding them (DESIGN §12.1).
 */
data class AudioFormatSpec(
    val mimeType: String,
    val sampleRate: Int,
    val channelCount: Int,
) {
    /** True for the AAC-LC that Drecorder itself records. */
    val isRecordedFormat: Boolean
        get() = mimeType == MediaFormat.MIMETYPE_AUDIO_AAC
}

/**
 * Reads the audio format out of a recording.
 *
 * `MediaExtractor` rather than `MediaMetadataRetriever` because the muxer/track
 * description is what the transformer has to agree on, and the retriever hides it.
 * Returns null when the file cannot be read or has no audio track at all.
 */
object AudioFormatProbe {

    fun probe(file: File): AudioFormatSpec? {
        if (!file.isFile) return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var found: AudioFormatSpec? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                found = AudioFormatSpec(
                    mimeType = mime,
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                )
                break
            }
            found
        } catch (failure: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }
}
