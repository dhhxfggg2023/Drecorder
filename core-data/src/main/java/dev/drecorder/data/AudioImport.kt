package dev.drecorder.data

import java.io.File

/**
 * Brings an audio file the user picked into the recording library.
 *
 * The library scans a real directory: every reader — the player, the peak
 * decoder, the editor, the exporter — takes a [File] path, and a `content://`
 * URI would only work for the one screen that opened it. So an import is a
 * copy into that directory, not a link to the original. The user's original
 * file is never touched.
 *
 * Pure Kotlin (no Android), so name collisions and extension rules are
 * unit-testable off-device.
 */
object AudioImport {

    /** The formats the library already reads and the decoder can open. */
    private val ACCEPTED = listOf("m4a", "wav", "aac", "ogg")

    fun accepts(displayName: String): Boolean =
        displayName.substringAfterLast('.', "").lowercase() in ACCEPTED

    /**
     * A destination name that does not overwrite anything already in the
     * library. A second import of the same file keeps both.
     */
    fun destinationName(displayName: String, existing: Set<String>): String {
        // A display name is data, not a path. Strip separators so a provider
        // that returns "Music/song.m4a" cannot create a subdirectory.
        val safe = displayName.replace('/', '_').replace('\\', '_')
        val stem = safe.substringBeforeLast('.', safe)
        val extension = safe.substringAfterLast('.', "m4a")
        var candidate = safe
        var attempt = 2
        while (candidate in existing) {
            candidate = "$stem-import-$attempt.$extension"
            attempt++
        }
        return candidate
    }

    /**
     * Copies [source] into [directory] under [displayName].
     *
     * @return the new file, or null when the copy failed or the directory is
     *   not writable. The caller decides what to tell the user.
     */
    fun copyInto(source: File, directory: File, displayName: String): File? {
        if (!source.isFile) return null
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val existing = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
        val name = destinationName(displayName, existing)
        val target = File(directory, name)
        return try {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() == source.length()) target else { target.delete(); null }
        } catch (failure: Exception) {
            runCatching { target.delete() }
            null
        }
    }
}
