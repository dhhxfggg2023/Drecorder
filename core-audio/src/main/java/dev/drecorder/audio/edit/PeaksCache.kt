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
    private const val VERSION = 2

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
    @Throws(IOException::class)
    fun write(file: File, data: PeakData, source: File) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.parentFile?.mkdirs()
        DataOutputStream(temporary.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
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
    fun read(file: File, source: File): PeakFile? {
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return null
                if (input.readInt() != VERSION) return null
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
}
