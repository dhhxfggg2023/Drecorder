package dev.drecorder.data

import android.content.Context
import java.io.File

/**
 * Sidecar file recording that a recording was produced by the editor.
 *
 * The library shows a "已编辑" badge on derived files so the user can tell an
 * edited recording from an original one at a glance (DESIGN §12.3). The
 * information is kept beside the app's own storage, in the same way and for the
 * same reason as [SilentMarker]: the recordings themselves may live in a folder
 * the user picked, and nothing may be written there that is not a recording.
 *
 * Deliberately takes a directory rather than a [Context] so it stays usable from
 * a unit test.
 */
class DerivedMarker(private val directory: File) {

    constructor(context: Context) : this(context.applicationContext.filesDir)

    fun markerFor(displayName: String): File = File(
        directory,
        displayName.substringBeforeLast('.') + SUFFIX,
    )

    fun markDerived(displayName: String) {
        val marker = markerFor(displayName)
        runCatching { marker.writeText(FLAG) }
    }

    fun isDerived(displayName: String): Boolean =
        runCatching { markerFor(displayName).readText() == FLAG }.getOrDefault(false)

    fun clear(displayName: String) {
        runCatching { markerFor(displayName).delete() }
    }

    private companion object {
        const val SUFFIX = ".derived"
        const val FLAG = "1"
    }
}
