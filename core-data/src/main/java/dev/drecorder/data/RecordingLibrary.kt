package dev.drecorder.data

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One finished recording on disk.
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
    val wasEdited: Boolean = false,
) {
    val displayName: String get() = file.name
}

/** Which day a recording belongs to, expressed without user-facing text. */
sealed interface RecordingDay {
    data object Today : RecordingDay
    data object Yesterday : RecordingDay
    data class SameYear(val month: Int, val day: Int) : RecordingDay
    data class Older(val year: Int, val month: Int, val day: Int) : RecordingDay
}

/**
 * Day of [modifiedAtMillis] relative to [nowMillis], so the UI can say "今天 10:17"
 * instead of `drecorder-20261018-101722-p01.m4a` (the file name is an implementation
 * detail once the library knows the truth).
 */
fun Recording.dayLabel(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): RecordingDay {
    val day = Instant.ofEpochMilli(modifiedAtMillis).atZone(zone).toLocalDate()
    val today: LocalDate = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    return when {
        day == today -> RecordingDay.Today
        day == today.minusDays(1) -> RecordingDay.Yesterday
        day.year == today.year -> RecordingDay.SameYear(day.monthValue, day.dayOfMonth)
        else -> RecordingDay.Older(day.year, day.monthValue, day.dayOfMonth)
    }
}

/** `HH:mm` in the given zone. */
fun Recording.clockLabel(zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(modifiedAtMillis)
        .atZone(zone)
        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))

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
    private val derivedMarker: DerivedMarker? = null,
) {

    fun recordings(): List<Recording> {
        val directory = directoryProvider() ?: return emptyList()
        if (!directory.isDirectory) return emptyList()

        // An export writes to `<name>.tmp.m4a` and renames only on success, so a
        // half-written file is on disk while the transformer runs. It carries a
        // recording extension (the muxer picks the container from it) and would
        // therefore be listed as a recording — a broken one, playable until it
        // ends in the middle. In-flight exports are not recordings.
        val files = directory.listFiles { file ->
            file.isFile && file.name.isRecordingExtension() && !file.name.isInProgressExport()
        } ?: return emptyList()

        return files.map { file ->
            Recording(
                file = file,
                durationMs = DurationProbe.readDurationMs(file),
                sizeBytes = file.length(),
                modifiedAtMillis = file.lastModified(),
                wasSilent = marker.isSilent(file.name),
                wasEdited = derivedMarker?.isDerived(file.name) == true,
            )
        }.newestFirst()
    }

    fun delete(recording: Recording): Boolean {
        marker.clear(recording.displayName)
        derivedMarker?.clear(recording.displayName)
        return runCatching { recording.file.delete() }.getOrDefault(false)
    }

    fun rename(recording: Recording, newDisplayName: String): Boolean {
        val target = File(recording.file.parentFile, newDisplayName)
        if (target.exists()) return false
        val renamed = runCatching { recording.file.renameTo(target) }.getOrDefault(false)
        if (renamed) {
            if (recording.wasSilent) {
                marker.clear(recording.displayName)
                marker.write(newDisplayName, wasSilent = true)
            }
            if (recording.wasEdited) {
                derivedMarker?.clear(recording.displayName)
                derivedMarker?.markDerived(newDisplayName)
            }
        }
        return renamed
    }
}

internal fun String.isRecordingExtension(): Boolean =
    endsWith(".m4a", ignoreCase = true) ||
        endsWith(".wav", ignoreCase = true) ||
        endsWith(".aac", ignoreCase = true) ||
        endsWith(".ogg", ignoreCase = true)

/**
 * Whether this name belongs to an export that has not finished writing.
 *
 * `EditOutputNaming.temporaryFileFor` keeps the recording extension (the muxer
 * picks the container from it) and puts a `.tmp` marker in the stem, so this is
 * the only thing that tells a half-written export from a recording.
 */
internal fun String.isInProgressExport(): Boolean = contains(".tmp.", ignoreCase = true)
