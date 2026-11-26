package dev.drecorder.data

import java.io.File

/**
 * The update folder: the one place installers are written to and deleted from.
 *
 * It lives in the app's own external storage, which the system counts as the
 * app's data — so whatever accumulates there is what the user reads as "this app
 * takes gigabytes". Nothing ever removed an installer except the one about to be
 * overwritten, so the folder kept one copy per version ever downloaded, forever.
 *
 * Four rules, and why each exists:
 *
 * 1. A new download starts from nothing. [begin] deletes everything first,
 *    including the partial file an interrupted download left. A half-written APK
 *    is never a resume candidate — its checksum cannot match — so resuming would
 *    only waste work and then fail. Restarting is also what guarantees the bytes
 *    that get verified are the bytes written in one uninterrupted pass.
 * 2. Only one installer is ever kept. Every entry point sweeps the folder down
 *    to at most one file.
 * 3. A finished install removes the file. Once the system installer has the
 *    package, the copy on disk is dead weight whether the user completes the
 *    install or cancels it.
 * 4. A file that failed verification is removed, because leaving it invites a
 *    later "install anyway" that the checks exist to prevent.
 *
 * Every deletion is best-effort: on Linux a file another process holds open
 * cannot be removed, and a cleanup that threw would turn a successful update into
 * a failed one. A survivor is retried by the next sweep.
 *
 * Pure java.io.File and no Android API, so the policy is unit-tested off-device.
 * The caller supplies the directory, because only the app knows where its external
 * files live.
 */
class UpdateFiles(private val directory: File) {

    /** The folder, created if it is not there yet. */
    fun ensureDirectory(): File = directory.apply { mkdirs() }

    /**
     * The file a download of [versionName] is written to.
     *
     * The name is built from the version rather than from the manifest URL, so a
     * mirror cannot decide what the local file is called.
     */
    fun fileFor(versionName: String): File =
        File(ensureDirectory(), "drecorder-" + safeVersionName(versionName) + ".apk")

    /**
     * Prepares the folder for a download of [versionName] and returns its target.
     *
     * Everything else goes first: older installers, installers of versions the
     * manifest no longer offers, and partial files from downloads that a reboot,
     * a process death or a user walking away interrupted. The download that
     * follows therefore starts at byte zero, and a download that fails leaves
     * nothing the next run could mistake for a finished file.
     */
    fun begin(versionName: String): File {
        val target = fileFor(versionName)
        // The target goes too, and it goes first: it is where an *interrupted*
        // download of this same version left half an APK, and a sweep that kept
        // the target by name would keep exactly that file. DownloadManager would
        // then write over the front of a longer file and leave stale bytes from
        // the old attempt inside the APK — a package whose hash cannot match and
        // whose contents nobody can predict.
        runCatching { target.delete() }
        sweep(keep = null)
        return target
    }

    /**
     * Deletes every leftover in the folder.
     *
     * Called at startup, not only before a download: the users who already have
     * the problem updated through many versions, and the space should come back
     * without them having to start another update first.
     */
    fun sweepLeftovers() = sweep(keep = null)

    /** Removes [file] after it failed verification. */
    fun discard(file: File) {
        runCatching { file.delete() }
    }

    /**
     * Removes [apk] once the system installer has been given it.
     *
     * Both outcomes — installed, or the user cancelled — leave the file useless.
     */
    fun afterInstallStarted(apk: File) {
        runCatching { apk.delete() }
        sweep(keep = null)
    }

    /** Every file currently in the folder, for diagnostics. */
    fun contents(): List<File> =
        directory.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    /** Deletes every file in the folder except [keep]. */
    private fun sweep(keep: File?) {
        val entries = ensureDirectory().listFiles() ?: return
        for (entry in entries) {
            if (!entry.isFile) continue
            if (keep != null && entry.name == keep.name) continue
            runCatching { entry.delete() }
        }
    }

    /**
     * The version name reduced to something that cannot leave the folder.
     *
     * The manifest is fetched over the network, so a version name is remote input
     * that used to become a path segment verbatim: a value such as ../../../x
     * wrote outside the directory. Nothing but letters, digits, dots, dashes and
     * underscores survives, and never more than 64 characters.
     */
    fun safeVersionName(versionName: String): String =
        versionName.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
            .take(64)
            // Leading and trailing dots go: a name made only of dots would be a
            // ".."-relative path the moment it were ever joined differently, and
            // a trailing dot is invisible and confuses every file manager.
            .trimStart('.')
            .trimEnd('.')
            .ifBlank { "update" }
}
