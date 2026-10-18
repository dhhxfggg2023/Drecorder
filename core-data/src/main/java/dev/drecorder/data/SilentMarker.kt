package dev.drecorder.data

import android.content.Context
import java.io.File

/**
 * Sidecar file recording whether a capture ever produced sound.
 *
 * A valid-but-silent `.m4a` is indistinguishable from a quiet passage by looking at
 * it, and there is nowhere in the container to put "the source refused to be
 * captured". So the engine drops a marker next to the recording and the library
 * reads it back.
 *
 * Deliberately takes a directory rather than a [Context]: that keeps it usable from
 * a unit test and from the capture service, which already knows its output folder.
 */
class SilentMarker(private val directory: File) {

    constructor(context: Context) : this(context.applicationContext.filesDir)

    fun markerFor(displayName: String): File = File(
        directory,
        displayName.substringBeforeLast('.') + SUFFIX,
    )

    fun write(displayName: String, wasSilent: Boolean) {
        val marker = markerFor(displayName)
        runCatching {
            if (wasSilent) marker.writeText(FLAG) else marker.delete()
        }
    }

    fun isSilent(displayName: String): Boolean =
        runCatching { markerFor(displayName).readText() == FLAG }.getOrDefault(false)

    fun clear(displayName: String) {
        runCatching { markerFor(displayName).delete() }
    }

    private companion object {
        const val SUFFIX = ".silent"
        const val FLAG = "1"
    }
}
