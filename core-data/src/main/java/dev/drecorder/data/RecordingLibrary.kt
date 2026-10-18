package dev.drecorder.data

import java.io.File

/**
 * One finished recording on disk.
 *
 * `wasSilent` is the field the user most needs: a source that refuses to be
 * captured produces a perfectly valid, perfectly empty file.
 */
data class Recording(
    val file: File,
    val durationMs: Long,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val wasSilent: Boolean,
) {
    val displayName: String get() = file.name
}

/**
 * Orders recordings newest-first.
 *
 * Pure on purpose: this is the part of the library screen worth a unit test, and
 * it does not need a device.
 */
fun List<Recording>.newestFirst(): List<Recording> =
    sortedWith(compareByDescending<Recording> { it.modifiedAtMillis }.thenByDescending { it.displayName })

/** Human-readable size, e.g. `11.5 MB`. */
fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (value >= 100) {
        "${value.toInt()} ${units[unitIndex]}"
    } else {
        String.format(java.util.Locale.US, "%.1f %s", value, units[unitIndex])
    }
}

/** `mm:ss`, or `hh:mm:ss` past an hour. Negative and unknown durations read as `00:00`. */
fun formatDuration(millis: Long): String {
    val safe = if (millis < 0) 0 else millis
    val totalSeconds = safe / 1_000
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/**
 * Lists recordings from the capture output directory.
 *
 * The writers need a real filesystem path (`MediaMuxer` takes a path or a file
 * descriptor, and the WAV header is patched by seeking), so the library scans the
 * same directory the engine wrote to rather than querying MediaStore.
 */
class RecordingLibrary(
    private val directoryProvider: () -> File?,
    private val marker: SilentMarker,
) {

    fun recordings(): List<Recording> {
        val directory = directoryProvider() ?: return emptyList()
        if (!directory.isDirectory) return emptyList()

        val files = directory.listFiles { file -> file.isFile && file.name.isRecordingExtension() }
            ?: return emptyList()

        return files.map { file ->
            Recording(
                file = file,
                durationMs = DurationProbe.readDurationMs(file),
                sizeBytes = file.length(),
                modifiedAtMillis = file.lastModified(),
                wasSilent = marker.isSilent(file.name),
            )
        }.newestFirst()
    }

    fun delete(recording: Recording): Boolean {
        marker.clear(recording.displayName)
        return runCatching { recording.file.delete() }.getOrDefault(false)
    }

    fun rename(recording: Recording, newDisplayName: String): Boolean {
        val target = File(recording.file.parentFile, newDisplayName)
        if (target.exists()) return false
        val renamed = runCatching { recording.file.renameTo(target) }.getOrDefault(false)
        if (renamed && recording.wasSilent) {
            marker.clear(recording.displayName)
            marker.write(newDisplayName, wasSilent = true)
        }
        return renamed
    }
}

internal fun String.isRecordingExtension(): Boolean =
    endsWith(".m4a", ignoreCase = true) ||
        endsWith(".wav", ignoreCase = true) ||
        endsWith(".aac", ignoreCase = true) ||
        endsWith(".ogg", ignoreCase = true)
