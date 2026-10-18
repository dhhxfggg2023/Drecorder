package dev.drecorder.audio

import java.io.File

/** Why a capture session ended. */
enum class StopReason {
    /** The user pressed stop. */
    UserStop,

    /** The system revoked the projection token (quick-settings chip, lock screen, another session). */
    ProjectionRevoked,

    /** Capture could not even start. */
    Error,
}

/** Audio format requested from the playback-capture stream. */
data class AudioFormatSpec(
    val sampleRate: Int = 48_000,
    val channelCount: Int = 2,
    val bitRate: Int = 192_000,
) {
    init {
        require(sampleRate == 44_100 || sampleRate == 48_000) {
            "playback capture is only reliable at 44100/48000 Hz, got $sampleRate"
        }
        require(channelCount == 1 || channelCount == 2) { "channelCount must be 1 or 2" }
    }
}

sealed interface CaptureState {
    data object Idle : CaptureState
    data object Preparing : CaptureState
    data class Recording(val outputPath: String, val startedAtMillis: Long) : CaptureState

    /**
     * [everHadSignal] travels with the state so the UI can tell "saved" from
     * "saved, but the stream was silent the whole time" without waiting for the
     * separate [RecordingHandle] to arrive.
     */
    data class Finished(
        val outputPath: String,
        val durationMs: Long,
        val reason: StopReason,
        val everHadSignal: Boolean,
        /** How many files this session produced; more than 1 when it was segmented. */
        val segmentCount: Int = 1,
    ) : CaptureState

    data class Failed(val message: String) : CaptureState
}

/**
 * Result of a finished session.
 *
 * [everHadSignal] is false when every analysis window stayed under the silence
 * threshold: the file exists but contains nothing, and the UI must say so rather
 * than present it as a successful recording.
 */
data class RecordingHandle(
    /**
     * Every file this session wrote, oldest first. A session longer than the
     * configured slice length produces several; a normal one has exactly one.
     */
    val files: List<File>,
    val durationMs: Long,
    val stopReason: StopReason,
    val peakDbfs: Float,
    val everHadSignal: Boolean,
) {
    /** The most recent file, for callers that need to name a single one. */
    val file: File get() = files.last()
}
