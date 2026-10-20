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

    /**
     * Why [other] cannot be spliced with [base], or null when they are compatible.
     *
     * The message names the concrete property, because "格式不一致" on its own
     * leaves the user with nothing to act on.
     */
    fun describeMismatch(base: AudioFormatSpec, other: AudioFormatSpec): String? = when {
        base.mimeType != other.mimeType ->
            "编码格式不同（${base.mimeType} 与 ${other.mimeType}）"

        base.sampleRate != other.sampleRate ->
            "采样率不同（${base.sampleRate} Hz 与 ${other.sampleRate} Hz）"

        base.channelCount != other.channelCount ->
            "声道数不同（${base.channelCount} 与 ${other.channelCount}）"

        else -> null
    }

    /**
     * The sources that cannot join [base], each with the reason. The base itself is
     * never reported: it is the reference every other source is compared against.
     */
    fun incompatible(
        base: AudioFormatSpec,
        others: List<Pair<String, AudioFormatSpec>>,
    ): List<Pair<String, String>> =
        others.mapNotNull { (name, spec) ->
            describeMismatch(base, spec)?.let { reason -> name to reason }
        }
}
