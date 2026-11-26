package dev.drecorder.audio.edit

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Reads and writes the on-disk peak cache.
 *
 * The cache lives in the app's own storage and **not** next to the recording:
 * the library scans that directory and would otherwise offer the cache file to
 * the user as a recording (DESIGN §12.4).
 *
 * The format is deliberately trivial — a fixed header followed by two `short`
 * arrays — because it is a disposable cache: if the format ever changes, the
 * version byte makes every old file simply ignored rather than misread.
 */
object PeaksCache {

    private const val MAGIC = 0x4452504B // "DRPK"

    /**
     * The overview cache — the one written while a recording is being made —
     * has been bucketed the same way since version 2, and that bucketing was
     * never wrong: the samples arrive in order from the very first frame, so
     * there is no decoder offset to fold in. Keeping this version at 2 is what
     * lets recordings made by older builds open instantly instead of being
     * re-decoded from scratch (「加载从左到右，不要取间隔加载」, 2026-11-04 — the
     * wholesale version bump made every old recording crawl back in).
     */
    /**
     * Version 3 retires every overview cache written before the window-offset fix.
     *
     * The bug it retires was not in the cache: it was in the bucketing that wrote
     * it. A long recording was decoded in two-minute segments, and each segment
     * was indexed by the file's own frame number while claiming to start at the
     * window — so every second segment landed at twice its position, on top of a
     * later one, and the stretch it belonged to stayed flat. Caches written by
     * that code hold the damage, and no reader can tell a legitimately quiet
     * stretch from a lost one, so the version byte is what makes them rebuild
     * (「每次开启 app 都要自己核对缓存对不对，不对就清除重建」).
     */
    private const val VERSION = 3

    /**
     * The **window** cache version is separate, because that is where the
     * bucketing changed: version 3 buckets window samples by the decoder's
     * presentation time instead of by the order they arrived. Every window
     * decoded before this change carries a decoder-dependent horizontal offset
     * — up to a frame plus an AAC priming delay — and adjacent windows
     * disagreed with each other, so a zoomed-in waveform jumped sideways
     * whenever the display moved from one cached window to the next. The
     * caches are disposable; the version byte is what retires them.
     */
    private const val WINDOW_VERSION = 3

    /** Magic + version + four ints + offsetUs + source size + source mtime. */
    private const val HEADER_BYTES = 48L

    /** Two `short` per bucket. */
    private const val BYTES_PER_BUCKET = 4L

    /**
     * The cache file for [recording].
     *
     * The name is derived from the recording's own name, so two recordings
     * with the same name in different folders would share a cache — the
     * modification time and size in the header catch that case.
     */
    fun fileFor(recording: File, cacheDirectory: File): File =
        File(cacheDirectory, "${recording.name}.peaks")

    /**
     * The cache directory for **window** waveforms.
     *
     * A window waveform is the fine one decoded for the stretch that is on
     * screen. It used to be decoded again on every visit — every pinch, every
     * scroll back and every reopening of the editor started from the audio file
     * again, which is what the user meant by "it loads the waveform every
     * time". The files live in a subdirectory of their own so that pruning them
     * can never delete a whole-recording cache.
     */
    fun windowDirectory(cacheDirectory: File): File = File(cacheDirectory, "lod")

    /**
     * The cache file for one window of [recording].
     *
     * Every part of the name is one of the things that make the waveform
     * different: a window at another offset, another width or another
     * resolution is another picture of the audio.
     */
    fun windowFileFor(
        recording: File,
        cacheDirectory: File,
        bucketsPerSecond: Int,
        startMs: Long,
        spanMs: Long,
    ): File = File(
        windowDirectory(cacheDirectory),
        "${recording.name}.$bucketsPerSecond.$startMs.$spanMs.peaks",
    )

    /**
     * Deletes every cache derived from [recording]: the overview and every window.
     *
     * Without this a deleted recording leaves its cache behind for ever — the app
     * has no other bookkeeping to notice it is no longer referenced.
     */
    fun drop(recording: File, cacheDirectory: File) {
        runCatching { fileFor(recording, cacheDirectory).delete() }
        val prefix = recording.name + "."
        windowDirectory(cacheDirectory).listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) }
            ?.forEach { runCatching { it.delete() } }
    }

    /**
     * Carries the caches of [from] over to [to] after a rename.
     *
     * The cache names are derived from the file name, so without this a renamed
     * recording loses its waveform and the editor decodes the whole file again on
     * the next open — the longest recordings, the ones where it hurts most.
     */
    fun rename(from: File, to: File, cacheDirectory: File) {
        val overview = fileFor(from, cacheDirectory)
        if (overview.isFile) {
            runCatching { overview.renameTo(fileFor(to, cacheDirectory)) }
        }
        val fromPrefix = from.name + "."
        val toPrefix = to.name + "."
        windowDirectory(cacheDirectory).listFiles()
            ?.filter { it.isFile && it.name.startsWith(fromPrefix) }
            ?.forEach { window ->
                val renamed = File(window.parentFile, toPrefix + window.name.removePrefix(fromPrefix))
                runCatching { window.renameTo(renamed) }
            }
    }

    /**
     * Keeps only the [keep] newest window caches.
     *
     * A zoom level and a scroll position are cheap to recreate but they are not
     * free, and they multiply: without this the directory grows with every
     * gesture for as long as the app is installed.
     */
    fun pruneWindows(cacheDirectory: File, keep: Int = 64) {
        val directory = windowDirectory(cacheDirectory)
        val files = directory.listFiles() ?: return
        val stale = files
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }
            .drop(keep)
        for (file in stale) {
            runCatching { file.delete() }
        }
    }

    /**
     * Writes [data] to [file].
     *
     * The write goes to a temporary file that is renamed into place, so a
     * process killed mid-write cannot leave a half-written cache that would
     * be read back as valid.
     */
    /** Writes a whole-recording overview cache. */
    @Throws(IOException::class)
    fun write(file: File, data: PeakData, source: File) {
        writeVersioned(file, data, source, VERSION)
    }

    /** Writes a window cache, stamped with the window bucketing's version. */
    @Throws(IOException::class)
    fun writeWindow(file: File, data: PeakData, source: File) {
        writeVersioned(file, data, source, WINDOW_VERSION)
    }

    @Throws(IOException::class)
    private fun writeVersioned(file: File, data: PeakData, source: File, version: Int) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.parentFile?.mkdirs()
        DataOutputStream(temporary.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(version)
            out.writeInt(data.sampleRate)
            out.writeInt(data.channelCount)
            out.writeInt(data.bucketsPerSecond)
            out.writeInt(data.bucketCount)
            out.writeLong(data.offsetMs)
            out.writeLong(source.length())
            out.writeLong(source.lastModified())
            for (index in 0 until data.bucketCount) {
                out.writeShort(data.minAt(index))
            }
            for (index in 0 until data.bucketCount) {
                out.writeShort(data.maxAt(index))
            }
        }
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    /**
     * Reads the cache in [file], but only if it still describes [source].
     *
     * Returns `null` for a missing, unreadable, malformed or stale cache — in
     * every one of those cases the caller should decode the recording again.
     */
    /** Reads a whole-recording overview cache. */
    fun read(file: File, source: File): PeakFile? = readVersioned(file, source, VERSION)

    /**
     * Whether [file] describes [source] well enough to draw from it.
     *
     * Null when the cache is good; a short reason when it is not, so a caller can
     * say which of the ways it failed rather than "the waveform is wrong".
     */
    fun validate(file: File, source: File): String? = diagnoseMiss(file, source, VERSION)

    /**
     * How much of [file]'s waveform is a flat line.
     *
     * A waveform of a recording that was bucketed correctly is almost never
     * mostly zero: even a silent room has a noise floor, and this app captures
     * playback rather than a muted microphone. A cache that is more than [limit]
     * silent is treated as damaged and rebuilt, because the alternative — drawing
     * it — shows the user a recording with gaps that are not in the audio.
     *
     * The threshold is deliberately generous: a real recording can be mostly
     * quiet, and re-decoding a long file is expensive enough that the check has
     * to be about *broken*, not about *quiet*.
     */
    fun silentFraction(cached: PeakFile, limit: Float = 0.55f): Boolean {
        val data = cached.data
        if (data.bucketCount == 0) return true
        var silent = 0
        for (index in 0 until data.bucketCount) {
            if (data.peakAt(index) == 0) silent++
        }
        return silent.toFloat() / data.bucketCount > limit
    }

    /** Reads a window cache, accepting only the window bucketing's version. */
    fun readWindow(file: File, source: File): PeakFile? = readVersioned(file, source, WINDOW_VERSION)

    private fun readVersioned(file: File, source: File, expectedVersion: Int): PeakFile? {
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return null
                if (input.readInt() != expectedVersion) return null
                val sampleRate = input.readInt()
                val channelCount = input.readInt()
                val bucketsPerSecond = input.readInt()
                val bucketCount = input.readInt()
                val offsetMs = input.readLong()
                val sourceSize = input.readLong()
                val sourceModified = input.readLong()
                if (sampleRate <= 0 || channelCount <= 0 || bucketsPerSecond <= 0 || bucketCount < 0) {
                    return null
                }
                // The bucket count is a number from a file that may have been
                // truncated or corrupted: without this ceiling it would be an
                // allocation request, and an OutOfMemoryError is not caught by
                // the IOException handler below — it kills the editor.
                val available = (file.length() - HEADER_BYTES).coerceAtLeast(0L)
                if (bucketCount.toLong() * BYTES_PER_BUCKET > available) return null
                val mins = ShortArray(bucketCount)
                val maxs = ShortArray(bucketCount)
                for (index in 0 until bucketCount) mins[index] = input.readShort()
                for (index in 0 until bucketCount) maxs[index] = input.readShort()

                // A cache that no longer matches its recording is worse than no
                // cache at all: the waveform would show the wrong audio.
                if (sourceSize != source.length() || sourceModified != source.lastModified()) {
                    return null
                }
                PeakFile(
                    data = PeakData(
                        sampleRate = sampleRate,
                        channelCount = channelCount,
                        bucketsPerSecond = bucketsPerSecond,
                        mins = mins,
                        maxs = maxs,
                        offsetMs = offsetMs,
                    ),
                    sourceSizeBytes = sourceSize,
                    sourceModifiedAtMillis = sourceModified,
                )
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            // A truncated file can make readShort throw; treat it as a miss.
            null
        }
    }

    /**
     * A one-line account of why a cache does or does not describe [source].
     *
     * Read-only and cheap: it opens nothing that [readVersioned] will not open
     * anyway. Returns null when the cache is usable, so a hit logs nothing.
     */
    fun diagnoseMiss(file: File, source: File, expectedVersion: Int = VERSION): String? {
        if (!file.isFile) return "no cache file (" + file.name + ")"
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return "bad magic"
                val version = input.readInt()
                input.readInt()
                input.readInt()
                input.readInt()
                input.readInt()
                input.readLong()
                val sourceSize = input.readLong()
                val sourceModified = input.readLong()
                if (version != expectedVersion) return "version " + version + " != " + expectedVersion
                if (sourceSize != source.length()) {
                    return "size " + sourceSize + " != " + source.length()
                }
                if (sourceModified != source.lastModified()) {
                    return "mtime " + sourceModified + " != " + source.lastModified()
                }
                null
            }
        } catch (error: Exception) {
            "unreadable: " + error.javaClass.simpleName
        }
    }
}
