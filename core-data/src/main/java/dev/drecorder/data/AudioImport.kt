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

    /**
     * The extension given to a file whose container cannot be identified.
     *
     * MediaCodec sniffs the stream by content, not by name, so an unknown
     * container is not a reason to refuse an import: it is a reason to hand it
     * over with a name that does not 
     * claim to be something it is not.
     */
    private const val FALLBACK_EXTENSION = "m4a"

    /**
     * Every extension the library lists and the decoder can open.
     *
     * One list, because there used to be two: the importer accepted ten
     * extensions and the library scanned for four, so importing an mp3 wrote the
     * file into the recordings folder and then never showed it — the copy
     * succeeded and the recording was simply invisible
     * (「导入的音频在列表没有显示」). A file the user cannot see is worse than a
     * refused import, because nothing says anything went wrong.
     */
    val KNOWN = listOf("m4a", "mp4", "wav", "aac", "ogg", "oga", "opus", "flac", "mp3", "amr", "3gp", "mka", "webm")

    /**
     * Whether [displayName] can be kept as it is.
     *
     * An earlier version accepted only four extensions and rejected everything
     * else before a byte was copied — which is what "音频文件分享进来导入失败" was:
     * sharing an `.mp3`, an `.amr` or a file with no extension at all was refused
     * with "unsupported format", and nothing recorded why. The decoder opens what
     * the platform can decode, so the list is now a list of names that need no
     * rewriting rather than a gate.
     */
    fun accepts(displayName: String): Boolean =
        displayName.substringAfterLast('.', "").lowercase() in KNOWN

    /**
     * The name an import should be stored under.
     *
     * Keeps [displayName] when its extension is known, otherwise gives it a
     * usable one. Never returns an empty or path-bearing name.
     */
    fun importName(displayName: String?, mime: String?): String {
        val raw = displayName?.trim().orEmpty()
        // Separators are replaced, and so are the dot-segments that survive
        // replacement: "../../etc/passwd.mp3" becomes ".._.._etc_passwd.mp3",
        // which still *contains* ".." and still looks like a path to every tool
        // that inspects it later. A name is data, and data that reads like a
        // path is a hazard nobody needs.
        val safe = raw
            .replace('/', '_')
            .replace('\\', '_')
            .replace(Regex("\\.\\.+"), "_")
            .ifBlank { "imported-audio" }
        if (accepts(safe)) return safe
        val extension = extensionFor(mime) ?: FALLBACK_EXTENSION
        return safe.substringBeforeLast('.', safe) + "." + extension
    }

    /** A container extension for a MIME type the platform may report. */
    fun extensionFor(mime: String?): String? {
        val type = mime?.lowercase() ?: return null
        return when {
            type.contains("flac") -> "flac"
            type.contains("mpeg") && type.contains("audio") -> "mp3"
            type.contains("ogg") || type.contains("vorbis") || type.contains("opus") -> "ogg"
            type.contains("wav") || type.contains("x-wav") -> "wav"
            type.contains("amr") -> "amr"
            type.contains("webm") -> "webm"
            type.contains("3gpp") -> "3gp"
            type.contains("mp4") || type.contains("m4a") || type.contains("aac") -> "m4a"
            else -> null
        }
    }

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
