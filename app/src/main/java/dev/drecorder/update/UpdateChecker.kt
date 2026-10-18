package dev.drecorder.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.drecorder.data.UpdateDecision
import dev.drecorder.data.UpdateManifest
import dev.drecorder.data.VerificationResult
import dev.drecorder.data.decideUpdate
import dev.drecorder.data.parseUpdateManifest
import dev.drecorder.data.sha256Hex
import dev.drecorder.data.verifyDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the update manifest, verifies a downloaded APK, and reports the running
 * build's identity.
 *
 * The download itself is delegated to `DownloadManager` (see [UpdateInstaller]); this
 * class owns only the parts that must be exact: reading our own version/signature and
 * deciding whether an installer may run.
 */
class UpdateChecker(context: Context) {

    private val appContext = context.applicationContext

    /** Current `versionCode` / `versionName`, or null when the package is unreadable. */
    fun installedVersion(): Pair<Long, String>? {
        val info = packageInfo(appContext.packageName) ?: return null
        return info.longVersionCode to (info.versionName ?: "")
    }

    /** SHA-256 of the certificate that signed the installed app, or null. */
    fun installedCertSha256(): String? = signingCertSha256(packageInfo(appContext.packageName))

    /**
     * SHA-256 of the certificate that signed an APK on disk.
     *
     * Uses the archive-info API so no install is required to inspect it.
     */
    fun apkCertSha256(apk: File): String? {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val info: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        } ?: return null
        return signingCertSha256(info)
    }

    fun apkVersionCode(apk: File): Long? {
        val flags = 0
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        } ?: return null
        return info.longVersionCode
    }

    suspend fun fetchManifest(url: String): Result<UpdateManifest> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            try {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parseUpdateManifest(body) ?: error("malformed manifest")
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }

    /** Decides whether [manifest] is a newer build than what is installed. */
    fun decide(manifest: UpdateManifest?): UpdateDecision {
        val (code, _) = installedVersion() ?: return UpdateDecision.Invalid("cannot read installed version")
        return decideUpdate(code, manifest)
    }

    /**
     * The full three-check gate from docs/DESIGN.md §11.3.
     *
     * Certificate comparison is what makes this meaningful: a matching hash only
     * proves the bytes are the ones we downloaded, not that they are signed by us.
     */
    suspend fun verifyDownloadedApk(apk: File, manifest: UpdateManifest): VerificationResult =
        withContext(Dispatchers.IO) {
            runCatching { apk.readBytes() }.fold(
                onSuccess = { bytes ->
                    val actual = sha256Hex(bytes)
                    verifyDownload(
                        expectedSha256 = manifest.sha256,
                        actualSha256 = actual,
                        downloadedCertSha256 = apkCertSha256(apk),
                        installedCertSha256 = installedCertSha256(),
                        downloadedVersionCode = apkVersionCode(apk) ?: 0L,
                        installedVersionCode = installedVersion()?.first ?: 0L,
                    )
                },
                onFailure = { VerificationResult.Failed(it.message ?: "cannot read download") },
            )
        }

    private fun packageInfo(packageName: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }.getOrNull()

    private fun signingCertSha256(info: PackageInfo?): String? {
        val signatures = info?.signingInfo?.apkContentsSigners ?: return null
        val signer = signatures.firstOrNull() ?: return null
        return sha256Hex(signer.toByteArray())
    }
}
