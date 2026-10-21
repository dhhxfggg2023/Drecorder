package dev.drecorder.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.drecorder.data.UpdateManifest
import dev.drecorder.data.downloadCandidates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads the update APK and hands it to the system installer.
 *
 * Uses [DownloadManager] rather than a hand-rolled HTTP loop: it survives process
 * death, resumes a partial download, shows progress in the notification shade, and
 * respects the platform's power policy (docs/DESIGN.md §11.3).
 *
 * The download lands in app-specific external storage, so it needs no permission and
 * is only ever exposed to the installer through [FileProvider].
 */
class UpdateInstaller(private val context: Context) {

    private val appContext = context.applicationContext

    fun downloadDirectory(): File =
        File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir, "updates")
            .apply { mkdirs() }

    fun fileFor(manifest: UpdateManifest): File =
        File(downloadDirectory(), "drecorder-${manifest.versionName}.apk")

    /**
     * Enqueues the download and calls [onComplete] with the local file once the
     * system reports success. Returns the [DownloadManager] id.
     *
     * Mirrors are tried in order by enqueueing only the first candidate: a real
     * fallback loop is the caller's job (see [downloadWithFallback]).
     */
    fun enqueue(manifest: UpdateManifest, url: String): Long {
        val target = fileFor(manifest)
        if (target.exists()) target.delete()

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("Drecorder ${manifest.versionName}")
            setDescription(manifest.releaseNotes.take(120))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, "updates/${target.name}")
            setMimeType("application/vnd.android.package-archive")
        }

        val manager = appContext.getSystemService(DownloadManager::class.java)
        return manager.enqueue(request)
    }

    /**
     * Polls until the download finishes, returning the local file.
     *
     * Polling rather than a `BroadcastReceiver` + `suspendCancellableCoroutine`: the
     * completion broadcast is also delivered to the system UI, so observing it buys
     * nothing here, while a poll loop is cancellation-safe, needs no receiver
     * registration, and still returns immediately on a four-second cadence.
     *
     * [onProgress] receives the bytes downloaded so far and the total (which is
     * negative while the size is still unknown). The caller needs it: without a
     * progress callback the screen can only show a spinner, and a 50 MB download
     * that shows nothing at all reads as "hung".
     *
     * Throws on download failure; callers wrap this in `runCatching`.
     */
    suspend fun await(
        downloadId: Long,
        pollIntervalMs: Long = 1_000L,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val manager = appContext.getSystemService(DownloadManager::class.java)
        while (true) {
            val row = manager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    DownloadRow(
                        status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS),
                        ),
                        downloaded = cursor.getLong(
                            cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR,
                            ),
                        ),
                        total = cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                        ),
                    )
                }
            }

            val current = row
            if (current == null) error("download failed")

            onProgress?.invoke(current.downloaded, current.total)

            when (current.status) {
                DownloadManager.STATUS_SUCCESSFUL -> return@withContext localFileFor(downloadId)
                DownloadManager.STATUS_FAILED -> error("download failed")
                else -> delay(pollIntervalMs)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /** One row of a download query. */
    private data class DownloadRow(
        val status: Int,
        val downloaded: Long,
        val total: Long,
    )

    /** Progress of a download in flight, or null when the query fails. */
    fun progress(downloadId: Long): Pair<Long, Long>? {
        val manager = appContext.getSystemService(DownloadManager::class.java)
        val query = DownloadManager.Query().setFilterById(downloadId)
        return manager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            done to total
        }
    }

    private fun localFileFor(downloadId: Long): File {
        val manager = appContext.getSystemService(DownloadManager::class.java)
        val query = DownloadManager.Query().setFilterById(downloadId)
        val name = manager.query(query)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    ?.substringAfterLast('/')
            } else {
                null
            }
        }
        return name?.let { File(downloadDirectory(), it) } ?: downloadDirectory()
    }

    /**
     * Launches the system installer for an APK that already passed verification.
     *
     * Returns false when the user has not granted "install unknown apps" yet; the UI
     * is expected to send them to that setting and retry.
     */
    fun install(apk: File, manifest: UpdateManifest): Boolean {
        if (!appContext.packageManager.canRequestPackageInstalls()) return false

        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { appContext.startActivity(intent) }.isSuccess
    }

    /** Sends the user to the per-app "install unknown apps" screen. */
    fun unknownSourcesSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun canInstallPackages(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    /** Opens the releases page as a manual fallback when downloading or mirrors fail. */
    fun releasesPageIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun allCandidates(manifest: UpdateManifest): List<String> = manifest.downloadCandidates()
}
