package dev.drecorder.audio.edit

import kotlin.math.abs

/**
 * A stretch of the recording between two markers.
 *
 * @param startMs the first moment of the segment
 * @param endMs the moment after the last one
 */
data class EditSegment(val startMs: Long, val endMs: Long) {

    /** How long the segment is. */
    val durationMs: Long get() = endMs - startMs
}

/**
 * The state of one recording's cuts: what is kept, and where the markers are.
 *
 * The two travel together because a marker is a **cut point**: the stretch
 * between two markers is a segment that can be taken out, and taking it out
 * removes the markers that bounded it (DESIGN §12.4).
 */
data class EditCuts(
    val keep: List<KeepRange>,
    val markers: List<Long>,
)

/**
 * The markers on a recording's timeline.
 *
 * This is the video-editor model the user asked for: a marker is at once a
 * bookmark you can jump to and a cut point you can cut on. The recording is
 * divided into segments by its markers, and a segment is what gets deleted —
 * after which the rest of the recording joins up seamlessly, because the
 * exporter concatenates whatever the keep list still holds (DESIGN §12.1).
 *
 * The markers are kept apart from the keep list on purpose: the keep list is
 * what the exporter consumes and it must stay a plain list of ranges, while
 * the markers are the *user's* view of the same edit.
 *
 * Pure and Android-free, so the arithmetic can be unit-tested off-device.
 */
object EditMarkers {

    /**
     * The marker within reach of a touch, or null.
     *
     * The reach is given in milliseconds rather than pixels, because the
     * caller knows how much of the recording is on screen: a marker has to be
     * as easy to hit when the whole recording is on screen as when two
     * seconds of it are. The caller turns pixels into milliseconds with
     * [EditFollow.msPerPixel].
     *
     * The nearest one wins, so a touch between two markers grabs the one the
     * user was aiming at rather than the first one that happens to be close
     * enough.
     */
    fun hit(markers: List<Long>, timeMs: Long, radiusMs: Long): Long? {
        if (radiusMs <= 0L) return null
        var best: Long? = null
        var bestDistance = Long.MAX_VALUE
        for (marker in markers) {
            val distance = abs(marker - timeMs)
            if (distance <= radiusMs && distance < bestDistance) {
                bestDistance = distance
                best = marker
            }
        }
        return best
    }

    /**
     * [markers] with one added at [atMs].
     *
     * The result is sorted and free of duplicates: a marker dropped on an
     * existing one changes nothing, because two markers at the same moment
     * would make a segment of zero length.
     */
    fun insert(markers: List<Long>, atMs: Long, totalMs: Long): List<Long> {
        val clamped = atMs.coerceIn(0L, totalMs.coerceAtLeast(0L))
        if (markers.contains(clamped)) return markers
        return (markers + clamped).sorted()
    }

    /** [markers] without the one exactly at [atMs]. */
    fun removeAt(markers: List<Long>, atMs: Long): List<Long> =
        markers.filter { it != atMs }

    /**
     * [markers] with the one at [fromMs] moved to [toMs].
     *
     * The result stays sorted and free of duplicates, and the marker stays
     * inside the recording: a marker that could be dragged past the end of
     * the file would bound a segment that does not exist.
     */
    fun move(markers: List<Long>, fromMs: Long, toMs: Long, totalMs: Long): List<Long> {
        val total = totalMs.coerceAtLeast(0L)
        val target = toMs.coerceIn(0L, total)
        if (target == fromMs) return markers
        val rest = markers.filter { it != fromMs }
        if (rest.contains(target)) return markers
        return (rest + target).sorted()
    }

    /**
     * The segment that [timeMs] falls in.
     *
     * A segment runs from the marker before it to the marker after it. Before
     * the first marker it runs from the beginning of the recording, and after
     * the last one to the end — so there is always exactly one segment, and
     * "delete the stretch between the two markers" is always available.
     */
    fun segmentContaining(markers: List<Long>, timeMs: Long, totalMs: Long): EditSegment {
        val total = totalMs.coerceAtLeast(0L)
        val moment = timeMs.coerceIn(0L, total)
        val start = markers.lastOrNull { it <= moment } ?: 0L
        val end = markers.firstOrNull { it > moment } ?: total
        return EditSegment(start, maxOf(start, end))
    }

    /** The first marker strictly after [atMs], or null. */
    fun next(markers: List<Long>, atMs: Long): Long? =
        markers.firstOrNull { it > atMs }

    /** The last marker strictly before [atMs], or null. */
    fun previous(markers: List<Long>, atMs: Long): Long? =
        markers.lastOrNull { it < atMs }

    /**
     * The markers that bound the segment containing [timeMs].
     *
     * Either of them is null when the segment runs to the beginning or the end
     * of the recording, where there is no marker.
     */
    fun bounds(markers: List<Long>, timeMs: Long): Pair<Long?, Long?> {
        val start = markers.lastOrNull { it <= timeMs }
        val end = markers.firstOrNull { it > timeMs }
        return start to end
    }

    /**
     * Takes [segment] out of the recording.
     *
     * The audio between the two markers is removed from the keep list, and the
     * markers that lay strictly inside it go with it: a marker that pointed
     * into audio that no longer exists would bound a segment that is not
     * there. The two markers that *bound* the segment stay, because they are
     * where the cut was made — they are the cut points (DESIGN §12.4).
     *
     * What is left of the recording joins up seamlessly, because the exporter
     * concatenates the remaining ranges (DESIGN §12.1).
     */
    fun deleteSegment(
        keep: List<KeepRange>,
        markers: List<Long>,
        segment: EditSegment,
        durationMs: Long,
    ): EditCuts {
        val newKeep = EditPlanner.remove(
            keep = keep,
            fromMs = segment.startMs,
            toMs = segment.endMs,
            durationMs = durationMs,
        )
        val newMarkers = markers.filter { it <= segment.startMs || it >= segment.endMs }
        return EditCuts(keep = newKeep, markers = newMarkers)
    }

    /**
     * Whether [segment] still holds any audio.
     *
     * A segment whose audio was deleted is still bounded by its two markers —
     * they are the cut points — but there is nothing left to cut again, so
     * the action has to be refused rather than silently do nothing.
     */
    fun segmentHasAudio(keep: List<KeepRange>, segment: EditSegment): Boolean =
        keep.any { it.startMs < segment.endMs && segment.startMs < it.endMs }
}
