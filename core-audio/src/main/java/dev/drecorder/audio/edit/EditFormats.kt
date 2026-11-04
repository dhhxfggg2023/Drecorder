package dev.drecorder.audio.edit

/**
 * Compatibility rules for splicing sources together.
 *
 * Transmuxing reorders encoded samples; it never converts them. Two recordings can
 * therefore only be merged when their encodings agree, and when they do not the
 * user has to be told *which* ones disagree — silently re-encoding would quietly
 * cost a generation of audio quality (DESIGN §12.1).
 *
 * Pure functions, so they are unit-tested without a device.
 */
object EditFormats {

    /** The concrete property that makes two recordings impossible to splice. */
    sealed interface Mismatch {
        /** Container/codec differs, e.g. an AAC recording and a WAV one. */
        data class Codec(val base: String, val other: String) : Mismatch

        data class SampleRate(val base: Int, val other: Int) : Mismatch

        data class Channels(val base: Int, val other: Int) : Mismatch
    }

    /**
     * Which property of [other] disagrees with [base], or null when they are
     * compatible.
     *
     * It returns the *property*, not a sentence: this is a pure function in a
     * module that has no resources, and the caller needs to name the property in
     * the user's language. Returning a formatted Chinese sentence here was the
     * reason the app could not be translated.
     */
    fun mismatch(base: AudioFormatSpec, other: AudioFormatSpec): Mismatch? = when {
        base.mimeType != other.mimeType -> Mismatch.Codec(base.mimeType, other.mimeType)
        base.sampleRate != other.sampleRate -> Mismatch.SampleRate(base.sampleRate, other.sampleRate)
        base.channelCount != other.channelCount -> Mismatch.Channels(base.channelCount, other.channelCount)
        else -> null
    }

    /** True when the two can be spliced without re-encoding. */
    fun compatible(base: AudioFormatSpec, other: AudioFormatSpec): Boolean =
        mismatch(base, other) == null

    /**
     * The sources that cannot join [base], with what disagrees. The base itself is
     * never reported: it is the reference every other source is compared against.
     */
    fun incompatible(
        base: AudioFormatSpec,
        others: List<Pair<String, AudioFormatSpec>>,
    ): List<Pair<String, Mismatch>> =
        others.mapNotNull { (name, spec) ->
            mismatch(base, spec)?.let { reason -> name to reason }
        }
}