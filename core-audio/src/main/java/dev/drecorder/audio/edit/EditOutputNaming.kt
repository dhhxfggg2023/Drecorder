package dev.drecorder.audio.edit

import java.io.File

/**
 * Where an exported edit is written.
 *
 * The original recording is never touched (DESIGN §12.3): an edit produces a new
 * file next to its source, and undoing an edit is deleting that file. That keeps
 * the operation trivially reversible without keeping an undo log.
 */
object EditOutputNaming {

    /**
     * The n-th derived name for [sourceName], e.g. `talk-20261020.m4a` and
     * `talk-20261020-edit-1.m4a`.
     *
     * The extension of the source is preserved so a WAV recording stays a WAV.
     */
    fun derivedName(sourceName: String, index: Int): String {
        val base = sourceName.substringBeforeLast('.')
        val extension = sourceName.substringAfterLast('.', "")
        val stem = "$base-edit-$index"
        return if (extension.isEmpty()) stem else "$stem.$extension"
    }

    /**
     * The first derived file for [source] that does not exist yet.
     *
     * Numbering starts at 1 so the first edit is visibly the first edit, and the
     * search for a free slot means an export never overwrites an earlier one.
     */
    fun outputFileFor(source: File): File {
        val directory = source.parentFile ?: File(".")
        var index = 1
        while (true) {
            val candidate = File(directory, derivedName(source.name, index))
            if (!candidate.exists()) return candidate
            index++
        }
    }

    /**
     * The path the transformer writes to: same directory, same extension, but
     * carrying a `.tmp` marker.
     *
     * The extension has to survive because the media3 muxer picks its container
     * format from the file extension — a path ending in `.tmp` would be muxed as
     * an unknown format. The `.tmp` marker in the middle of the stem keeps the
     * container guess correct while still making an unfinished export obvious on
     * disk.
     */
    fun temporaryFileFor(output: File): File {
        val name = output.name
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "")
        val temporary = if (extension.isEmpty()) "$stem.tmp" else "$stem.tmp.$extension"
        return File(output.parentFile, temporary)
    }
}
