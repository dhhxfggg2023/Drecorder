package dev.drecorder.waveforms

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.drecorder.capture.RecordSettings
import dev.drecorder.data.AudioImport
import dev.drecorder.ui.library.defaultOutputDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies a shared/picked audio file into the recording library.
 *
 * The copy is deliberate: the library scans a real directory and every
 * consumer (player, peak decoder, editor, exporter) takes a [File]. A
 * `content://` URI would only work for the one screen that received it, and
 * would vanish when the granting app revokes permission. The user's original
 * file is never modified.
 */
object AudioInbox {

    /** The outcome the UI reports. */
    sealed interface Result {
        data class Imported(val name: String) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * Imports the audio carried by [uri].
     *
     * @param displayName a name to keep, when the URI has none
     */
    suspend fun import(context: Context, uri: Uri, displayName: String?): Result {
        val app = context.applicationContext
        val name = displayName ?: uri.lastPathSegment ?: return Result.Rejected("no name")
        if (!AudioImport.accepts(name)) {
            return Result.Rejected("unsupported format")
        }
        val directory = withContext(Dispatchers.IO) {
            RecordSettings.outputDirectory(app)?.let(::File) ?: defaultOutputDirectory(app)
        } ?: return Result.Rejected("no output directory")
        val staged = withContext(Dispatchers.IO) {
            val temporary = File(app.cacheDir, "import-" + System.currentTimeMillis())
            try {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext null
                AudioImport.copyInto(temporary, directory, name)
            } finally {
                runCatching { temporary.delete() }
            }
        } ?: return Result.Rejected("copy failed")
        // The import is the file the user will open next: decode its waveform
        // now, in the background, rather than making them wait for it.
        WaveformPrecompute.enqueue(app, staged)
        return Result.Imported(staged.name)
    }

    /**
     * The URI of a share intent, if it is an audio file.
     *
     * Handles both ACTION_SEND (EXTRA_STREAM) and ACTION_VIEW / open-document
     * results (data). ClipData is honoured for the rare multi-share case by
     * taking the first item, which is all the library can show per tap.
     */
    fun uriFrom(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    ?: intent.clipData?.getItemAt(0)?.uri
            }
            else -> intent.data
        }
    }
}
