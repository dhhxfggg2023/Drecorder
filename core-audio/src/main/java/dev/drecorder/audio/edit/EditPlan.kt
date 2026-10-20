package dev.drecorder.audio.edit

import java.io.File

/**
 * A half-open interval `[startMs, endMs)` of a source recording that the user wants
 * to keep.
 *
 * Half-open because that is what the muxer and every audio API use: two ranges that
 * touch (`0..1000` and `1000..2000`) therefore concatenate with no duplicated sample
 * at the seam, which is exactly what [EditPlanner.normalize] relies on to merge them.
 *
 * The constructor only guarantees **ordering** ([endMs] not before [startMs]), never
 * that the range lies inside the recording. Bounds are [EditPlanner.normalize]'s job:
 * a waveform drag that overshoots the end of the file, or a stale duration after a
 * re-record, must be clamped rather than crash the editor mid-gesture. Keeping the
 * two responsibilities apart is what makes the clamping code reachable at all.
 */
data class KeepRange(val startMs: Long, val endMs: Long) {

    val durationMs: Long get() = endMs - startMs

    init {
        require(endMs >= startMs) { "endMs ($endMs) must not precede startMs ($startMs)" }
    }
}

/**
 * One recording taking part in an edit, plus the ranges of it that survive.
 *
 * [durationMs] is carried alongside the file rather than probed here so the logic in
 * [EditPlanner] stays pure: this whole package is unit-tested without a device.
 */
data class SourceSpan(
    val file: File,
    val durationMs: Long,
    val keep: List<KeepRange>,
)

/**
 * Everything needed to produce one output file.
 *
 * A single source with several [KeepRange]s is a trim; several [SourceSpan]s appended
 * in order is a merge. Both compile to the same thing — one ordered list of clips —
 * which is why the UI does not need two code paths (DESIGN §12.2).
 *
 * Text is deliberately absent: this is the engine's view. The screen owns labels.
 */
data class EditPlan(val sources: List<SourceSpan>) {

    /** Total audio the output will contain, in milliseconds. */
    val keptDurationMs: Long get() = sources.sumOf { span -> span.keep.sumOf { it.durationMs } }

    /** True when the plan would produce a zero-length file, which must never be exported. */
    val isEmpty: Boolean get() = sources.all { it.keep.isEmpty() }
}
