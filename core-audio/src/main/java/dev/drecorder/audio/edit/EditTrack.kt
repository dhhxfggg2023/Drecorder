package dev.drecorder.audio.edit

import java.io.File

/**
 * One piece of one recording, somewhere on the single track.
 *
 * A clip is half open, `[startMs, endMs)`, and it names the file it came from:
 * that is the whole model. A trim is one clip made shorter, a cut is one clip
 * turned into two, a delete is a clip removed, a repeat is a clip put down
 * twice, and a merge is clips of different files standing next to each other.
 * Nothing else in the editor has to know which of those the user just did.
 *
 * This is what an editing app calls a track: an ordered list of these, played
 * end to end. The previous model — one keep-list per file — could not express
 * "this stretch, then that one again" without inventing a second file, which is
 * why a repeat used to show up as an extra track (m03086).
 */
data class EditClip(
    val file: File,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = endMs - startMs

    init {
        require(endMs >= startMs) { "endMs ($endMs) must not precede startMs ($startMs)" }
    }
}

/**
 * Operations on the single track, as pure functions.
 *
 * Every edit the editor offers is one of these, and every one of them returns a
 * new list rather than mutating: the screen keeps one list in state, and undo
 * would be a list of those lists. No Android types appear here, so all of it is
 * unit-tested without a device.
 *
 * Output time is what the user sees: 0 is the left edge of the track, and the
 * track is the clips laid end to end. Source time is the time inside one
 * recording, which is what a clip stores.
 */
object EditTrack {

    /** Length of the whole track, in ms. */
    fun totalMs(clips: List<EditClip>): Long = clips.sumOf { it.durationMs }

    /** Where the clip at [index] begins on the track, in ms. */
    fun outputStartOf(clips: List<EditClip>, index: Int): Long {
        var offset = 0L
        for (position in 0 until minOf(index, clips.size)) {
            offset += clips[position].durationMs
        }
        return offset
    }

    /**
     * The clip that is on the track at [outputMs], or null past the end.
     *
     * A time that falls exactly on a boundary belongs to the clip that starts
     * there, not to the one that just ended: the user pointing at a seam means
     * "from here", and the following material is what plays from there.
     */
    fun indexAt(clips: List<EditClip>, outputMs: Long): Int? {
        if (clips.isEmpty()) return null
        var cursor = 0L
        for (index in clips.indices) {
            val next = cursor + clips[index].durationMs
            if (outputMs < next) return index
            cursor = next
        }
        return null
    }

    /** The time inside its own recording that plays at [outputMs] of the track. */
    fun sourceTimeAt(clips: List<EditClip>, outputMs: Long): Long? {
        val index = indexAt(clips, outputMs) ?: return null
        val start = outputStartOf(clips, index)
        return clips[index].startMs + (outputMs - start)
    }

    /**
     * Splits the clip under [outputMs] in two, and returns the new track.
     *
     * This is what "add a cut here" does. A cut is a boundary, not an object:
     * it exists exactly as long as the two clips it separates, and removing it
     * is joining them back ([removeCutAt]).
     */
    fun cutAt(clips: List<EditClip>, outputMs: Long): List<EditClip> {
        val index = indexAt(clips, outputMs) ?: return clips
        val clip = clips[index]
        val start = outputStartOf(clips, index)
        val inside = outputMs - start
        // A cut on the very edge of a clip separates nothing.
        if (inside <= 0L || inside >= clip.durationMs) return clips
        val left = clip.copy(endMs = clip.startMs + inside)
        val right = clip.copy(startMs = clip.startMs + inside)
        return clips.toMutableList().apply {
            removeAt(index)
            addAll(index, listOf(left, right))
        }
    }

    /**
     * Removes the audio between [fromMs] and [toMs] of the track.
     *
     * Clips that are wholly inside the range go away; the two that straddle its
     * edges are shortened. Both ends are half-open, so removing `1000..2000`
     * from a clip that covers `0..3000` leaves `0..1000` and `2000..3000` and
     * loses exactly one second.
     */
    fun removeRange(clips: List<EditClip>, fromMs: Long, toMs: Long): List<EditClip> {
        val from = minOf(fromMs, toMs)
        val to = maxOf(fromMs, toMs)
        if (to <= from) return clips
        val result = mutableListOf<EditClip>()
        var cursor = 0L
        for (clip in clips) {
            val clipStart = cursor
            val clipEnd = cursor + clip.durationMs
            cursor = clipEnd
            // Nothing of this clip is outside the removal: it is gone whole.
            if (clipStart >= from && clipEnd <= to) continue
            if (clipEnd <= from || clipStart >= to) {
                result += clip
                continue
            }
            // The head of the clip survives.
            val headEnd = (from - clipStart).coerceIn(0L, clip.durationMs)
            if (headEnd > 0L) {
                result += clip.copy(endMs = clip.startMs + headEnd)
            }
            // The tail survives.
            val tailStart = (to - clipStart).coerceIn(0L, clip.durationMs)
            if (tailStart < clip.durationMs) {
                result += clip.copy(startMs = clip.startMs + tailStart)
            }
        }
        return result
    }

    /** Keeps only the audio between [fromMs] and [toMs]; everything else goes. */
    fun keepOnly(clips: List<EditClip>, fromMs: Long, toMs: Long): List<EditClip> {
        val from = minOf(fromMs, toMs)
        val to = maxOf(fromMs, toMs)
        if (to <= from) return emptyList()
        val inside = mutableListOf<EditClip>()
        var cursor = 0L
        for (clip in clips) {
            val clipStart = cursor
            val clipEnd = cursor + clip.durationMs
            cursor = clipEnd
            if (clipEnd <= from || clipStart >= to) continue
            val head = (from - clipStart).coerceIn(0L, clip.durationMs)
            val tail = (to - clipStart).coerceIn(0L, clip.durationMs)
            if (tail > head) {
                inside += clip.copy(
                    startMs = clip.startMs + head,
                    endMs = clip.startMs + tail,
                )
            }
        }
        return inside
    }

    /**
     * Puts a second copy of the audio between [fromMs] and [toMs] straight after
     * it, and returns the new track.
     *
     * This is "repeat", and it is the one edit the old per-file model could not
     * do: a keep list describes which parts of a file survive, in file order,
     * once each, so playing a stretch twice had to be faked by adding the whole
     * recording as a second source — which is exactly the extra track the user
     * was looking at (m03086).
     */
    fun duplicateRange(clips: List<EditClip>, fromMs: Long, toMs: Long): List<EditClip> {
        val from = minOf(fromMs, toMs)
        val to = maxOf(fromMs, toMs)
        if (to <= from) return clips
        val copy = keepOnly(clips, from, to)
        if (copy.isEmpty()) return clips
        // The copy lands immediately after the stretch it repeats, which is the
        // end of whatever the range covered once everything after it is pushed
        // down the track.
        val insertAt = clipsAfter(clips, from, to)
        return clips.toMutableList().apply { addAll(insertAt, copy) }
    }

    /** Index at which a range that ends at [toMs] ends, counted in clips. */
    private fun clipsAfter(clips: List<EditClip>, fromMs: Long, toMs: Long): Int {
        var cursor = 0L
        for (index in clips.indices) {
            cursor += clips[index].durationMs
            // The first clip that ends at or past the range is the last one the
            // range touches: the copy goes after it.
            if (cursor >= toMs) return index + 1
        }
        return clips.size
    }

    /**
     * Joins the two clips that meet at [outputMs] into one, when they are the
     * same recording and contiguous in it.
     *
     * Deleting a cut has to be able to put the audio back together, otherwise a
     * cut the user placed by accident can only be undone by leaving the editor.
     * Clips of *different* files are never joined: there is nothing to join.
     */
    fun removeCutAt(clips: List<EditClip>, outputMs: Long): List<EditClip> {
        if (clips.size < 2) return clips
        var cursor = 0L
        for (index in 0 until clips.size - 1) {
            cursor += clips[index].durationMs
            if (cursor != outputMs) continue
            val left = clips[index]
            val right = clips[index + 1]
            if (left.file != right.file || left.endMs != right.startMs) return clips
            val joined = left.copy(endMs = right.endMs)
            return clips.toMutableList().apply {
                removeAt(index + 1)
                set(index, joined)
            }
        }
        return clips
    }

    /**
     * The places on the track where one clip ends and the next begins.
     *
     * These are the cut points the user sees. They are derived, never stored: a
     * cut exists because two clips meet there, so it cannot drift away from the
     * audio it separates — which is what happened when cuts were draggable
     * marks (m01839).
     */
    fun cutPoints(clips: List<EditClip>): List<Long> {
        if (clips.size < 2) return emptyList()
        val points = mutableListOf<Long>()
        var cursor = 0L
        for (index in 0 until clips.size - 1) {
            cursor += clips[index].durationMs
            points += cursor
        }
        return points
    }

    /** Appends [added] to the end of the track. */
    fun append(clips: List<EditClip>, added: List<EditClip>): List<EditClip> =
        clips + added

    /** Inserts [added] so that it begins at [outputMs] of the track. */
    fun insertAt(clips: List<EditClip>, outputMs: Long, added: List<EditClip>): List<EditClip> {
        if (added.isEmpty()) return clips
        val index = indexAt(clips, outputMs) ?: return clips + added
        val start = outputStartOf(clips, index)
        val inside = outputMs - start
        val clip = clips[index]
        if (inside <= 0L) {
            return clips.toMutableList().apply { addAll(index, added) }
        }
        if (inside >= clip.durationMs) {
            return clips.toMutableList().apply { addAll(index + 1, added) }
        }
        val left = clip.copy(endMs = clip.startMs + inside)
        val right = clip.copy(startMs = clip.startMs + inside)
        return clips.toMutableList().apply {
            removeAt(index)
            addAll(index, listOf(left) + added + listOf(right))
        }
    }

    /** The distinct files the track draws its audio from. */
    fun files(clips: List<EditClip>): List<File> = clips.map { it.file }.distinct()

    /**
     * The track as an [EditPlan], which is what the exporter and the preview
     * player take.
     *
     * One clip becomes one source with one keep range, so the plan's source list
     * *is* the clip order — repeats included, because nothing here requires a
     * file to appear only once.
     */
    fun toPlan(clips: List<EditClip>, durationOf: (File) -> Long): EditPlan =
        EditPlan(
            clips.map { clip ->
                SourceSpan(
                    file = clip.file,
                    durationMs = durationOf(clip.file),
                    keep = listOf(KeepRange(clip.startMs, clip.endMs)),
                )
            },
        )
}
