package dev.drecorder.audio.edit

/**
 * One item of the preview playlist: a clip, and the moment it starts on the
 * track.
 *
 * The preview is a playlist of clipped media items, not a
 * `androidx.media3.transformer.Composition`: a composition is the *export*'s
 * shape (it has to be muxed into one file), and playing one meant playing it
 * through a player built for that other job — the one that answered "试听失败"
 * with silence for three releases (m00678). A playlist of the same clips is
 * what every ordinary player understands: the same `ExoPlayer` that plays a
 * recording in the library plays the edit, so an edit that plays in the
 * library plays here too.
 *
 * Pure and Android-free, so the mapping from track time to playlist position
 * can be unit-tested off-device.
 */
data class PreviewItem(
    val clip: EditClip,
    val outputStartMs: Long,
) {
    /** How long this item lasts on the track, in ms. */
    val durationMs: Long get() = clip.durationMs

    /** The moment this item ends on the track, in ms. */
    val outputEndMs: Long get() = outputStartMs + clip.durationMs
}

/**
 * Where a moment of the track falls in the preview playlist.
 *
 * @param index the playlist item to play
 * @param offsetMs how far inside that item's own clip the moment is
 */
data class PreviewSeek(val index: Int, val offsetMs: Long)

/** Lays the track's clips out as a playlist, in play order. */
object EditPreview {

    /** One item per clip, each carrying where it starts on the track. */
    fun build(clips: List<EditClip>): List<PreviewItem> {
        var cursor = 0L
        return clips.map { clip ->
            val item = PreviewItem(clip = clip, outputStartMs = cursor)
            cursor += clip.durationMs
            item
        }
    }

    /** Total length of a playlist, in ms. */
    fun totalMs(items: List<PreviewItem>): Long = items.sumOf { it.durationMs }

    /**
     * The playlist position that plays [outputMs] of the track.
     *
     * A moment that falls exactly on a seam belongs to the clip that starts
     * there — pointing at a seam means "play from here", and the material after
     * the seam is what plays from there ([EditTrack.indexAt] says the same
     * thing, and the two have to agree or a tap on a seam would seek into the
     * silence at the end of the previous clip).
     *
     * A moment past the end lands on the last item's end: a legal position that
     * plays nothing, rather than an error.
     */
    fun locate(items: List<PreviewItem>, outputMs: Long): PreviewSeek? {
        if (items.isEmpty()) return null
        if (outputMs <= 0L) return PreviewSeek(index = 0, offsetMs = 0L)
        for ((index, item) in items.withIndex()) {
            if (outputMs < item.outputEndMs) {
                return PreviewSeek(
                    index = index,
                    offsetMs = (outputMs - item.outputStartMs).coerceAtLeast(0L),
                )
            }
        }
        val last = items.last()
        return PreviewSeek(index = items.lastIndex, offsetMs = last.durationMs)
    }

    /** The track moment of a playlist position, or null when the item is gone. */
    fun outputMsAt(items: List<PreviewItem>, index: Int, offsetMs: Long): Long? {
        val item = items.getOrNull(index) ?: return null
        return item.outputStartMs + offsetMs.coerceIn(0L, item.durationMs)
    }
}
