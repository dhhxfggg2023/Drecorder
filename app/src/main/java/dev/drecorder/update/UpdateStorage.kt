package dev.drecorder.update

import android.content.Context
import android.os.Environment
import dev.drecorder.data.UpdateFiles
import java.io.File

/**
 * Where the update flow keeps its installers, on this app's own storage.
 *
 * The policy — what to delete and when — lives in [UpdateFiles], which is pure
 * java.io.File and unit-tested. This class answers the one question the policy
 * cannot: which directory is the app's external Downloads folder.
 */
class UpdateStorage(context: Context) {

    private val appContext = context.applicationContext

    private val files: UpdateFiles by lazy {
        val root = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: appContext.filesDir
        UpdateFiles(File(root, DIRECTORY_NAME))
    }

    fun directory(): File = files.ensureDirectory()

    fun fileFor(versionName: String): File = files.fileFor(versionName)

    fun begin(versionName: String): File = files.begin(versionName)

    fun sweepLeftovers() = files.sweepLeftovers()

    fun discard(file: File) = files.discard(file)

    fun afterInstallStarted(apk: File) = files.afterInstallStarted(apk)

    private companion object {
        /** The subfolder of Downloads the update flow owns; nothing else writes here. */
        const val DIRECTORY_NAME = "updates"
    }
}
