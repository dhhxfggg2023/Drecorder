package dev.drecorder.waveforms

import dev.drecorder.audio.edit.PeaksCache
import dev.drecorder.data.RecordingCacheHooks
import java.io.File

/**
 * Points [RecordingCacheHooks][dev.drecorder.data.RecordingCacheHooks] at the peaks
 * cache in the app's own storage.
 *
 * The recordings and the caches have different owners (core-data finds the files,
 * core-audio parses them), and only this module sees both, so the two lines of glue
 * live here rather than in either of them.
 */
class PeaksCacheHooks(
    private val cacheDirectory: File,
) : RecordingCacheHooks {

    override fun onRemoved(recording: File) {
        PeaksCache.drop(recording, cacheDirectory)
    }

    override fun onMoved(from: File, to: File) {
        PeaksCache.rename(from, to, cacheDirectory)
    }
}
