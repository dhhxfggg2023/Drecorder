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

    /** The file name without its extension: what a row can show on one line. */
    val nameWithoutExtension: String get() = file.name.substringBeforeLast('.', file.name)

    /**
     * The name the user gave this recording, or null while the file still carries
     * the capture engine's name.
     *
     * `drecorder-20261018-101722-p01` encodes the time, not a subject: a list that
     * led with it would be unreadable, and — worse — a rename would change nothing
     * the user can see (issue #1). A typed name is the one part of this row that is
     * the user's own, so the row leads with it when it exists.
     */
    val customName: String?
        get() = nameWithoutExtension.takeUnless { it.isGeneratedRecordingName() }
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
 * Keeps the app's derived caches in step with the recordings they describe.
 *
 * The peaks caches are keyed by the recording's file name and live in the app's own
 * storage, so a recording that is deleted leaves its cache behind for ever, and a
 * recording that is renamed leaves a cache nobody will ever read — the next open
 * decodes the whole file again. core-data cannot reach the cache itself (it is in
 * core-audio, which depends on this module), so the owner of both supplies this.
 */
interface RecordingCacheHooks {

    /** [recording] is gone; drop everything derived from it. */
    fun onRemoved(recording: File)

    /** [from] is now [to]; carry anything derived from it across. */
    fun onMoved(from: File, to: File)
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
    private val cacheHooks: RecordingCacheHooks? = null,
) {

    /**
     * Lists finished recordings.
     *
     * [activeName] is the file a capture session is writing right now. The engine
     * opens its file under the final name, so without this the file being
     * recorded looks like a finished recording — and deleting it would unlink the
     * file out from under the writer while it still reports success.
     */
    fun recordings(activeName: String? = null): List<Recording> {
        val directory = directoryProvider() ?: return emptyList()
        if (!directory.isDirectory) return emptyList()

        // An export writes to `<name>.tmp.m4a` and renames only on success, so a
        // half-written file is on disk while the transformer runs. It carries a
        // recording extension (the muxer picks the container from it) and would
        // therefore be listed as a recording — a broken one, playable until it
        // ends in the middle. In-flight exports are not recordings.
        val files = directory.listFiles { file ->
            file.isFile &&
                file.name.isRecordingExtension() &&
                !file.name.isInProgressExport() &&
                file.name != activeName
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
        // The sidecars describe this file. Clearing them first meant a delete that
        // failed left the recording in place with its "silent" and "edited"
        // badges gone, which is worse than either outcome.
        val deleted = runCatching { recording.file.delete() }.getOrDefault(false)
        if (!deleted) return false
        marker.clear(recording.displayName)
        derivedMarker?.clear(recording.displayName)
        cacheHooks?.onRemoved(recording.file)
        return true
    }

    fun rename(recording: Recording, newDisplayName: String): Boolean {
        // The library only lists files that carry a recording extension, so a name
        // typed without one ("会议记录") would rename the recording out of the app's
        // sight: it would vanish from the list while still occupying the folder.
        // The original extension is kept unless the new name already has one.
        val extension = recording.file.name.substringAfterLast('.', "")
        if (extension.isEmpty() || extension.length == recording.file.name.length) return false
        val typed = newDisplayName.trim()
        if (typed.isEmpty() || typed.contains('/') || typed.contains('\\')) return false
        // ".m4a" is a valid recording extension and a useless name: the file would
        // be hidden, and the list would show an empty title. A name has to have
        // something in front of the extension.
        val stem = typed.substringBeforeLast('.', "")
        if (typed.isRecordingExtension() && stem.isBlank()) return false
        val newName = if (typed.isRecordingExtension()) typed else "${typed.trimEnd('.')}.$extension"

        val target = File(recording.file.parentFile, newName)
        if (target.exists()) return false
        val renamed = runCatching { recording.file.renameTo(target) }.getOrDefault(false)
        if (renamed) {
            if (recording.wasSilent) {
                marker.clear(recording.displayName)
                marker.write(newName, wasSilent = true)
            }
            if (recording.wasEdited) {
                derivedMarker?.clear(recording.displayName)
                derivedMarker?.markDerived(newName)
            }
            cacheHooks?.onMoved(recording.file, target)
        }
        return renamed
    }
}

/**
 * Whether this stem is one the capture engine generated:
 * `drecorder-yyyyMMdd-HHmmss`, plus the `-pNN` segment number or the `-N` /
 * `-<millis>` disambiguator `uniqueBaseName` appends when two sessions started
 * inside the same second.
 */
private val GENERATED_RECORDING_NAME = Regex("""^drecorder-\d{8}-\d{6}(-p\d{2}|-\d+)?$""")

internal fun String.isGeneratedRecordingName(): Boolean = GENERATED_RECORDING_NAME.matches(this)

/**
 * Whether this name is a recording the library can show.
 *
 * The list is [AudioImport.KNOWN]: an imported file has to be visible, so what
 * the importer writes and what the library scans cannot be two different lists.
 * Keeping them separate is what made an imported mp3 land on disk and never
 * appear in the list.
 */
internal fun String.isRecordingExtension(): Boolean {
    val extension = substringAfterLast('.', "").lowercase()
    if (extension.isEmpty()) return false
    return AudioImport.KNOWN.any { it == extension }
}

/**
 * Whether this name belongs to an export that has not finished writing.
 *
 * `EditOutputNaming.temporaryFileFor` keeps the recording extension (the muxer
 * picks the container from it) and puts a `.tmp` marker in the stem, so this is
 * the only thing that tells a half-written export from a recording.
 */
internal fun String.isInProgressExport(): Boolean = contains(".tmp.", ignoreCase = true)
