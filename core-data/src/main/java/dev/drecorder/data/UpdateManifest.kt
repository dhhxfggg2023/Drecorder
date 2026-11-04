package dev.drecorder.data

import java.security.MessageDigest

/**
 * The update manifest the app polls (docs/DESIGN.md §11.2).
 *
 * A static file rather than the GitHub API on purpose: the anonymous API is rate
 * limited to 60 requests/hour/IP and is often unreachable, while a static file can
 * be mirrored and can express "minimum supported version" and release notes.
 */
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val minSupportedVersionCode: Long,
    val apkUrl: String,
    val mirrors: List<String>,
    val sizeBytes: Long,
    val sha256: String,
    val signingCertSha256: String,
    val publishedAt: String,
    val releaseNotes: String,
    val force: Boolean,
)

/** Why an update is or is not offered. */
sealed interface UpdateDecision {
    data object UpToDate : UpdateDecision
    data class Available(val manifest: UpdateManifest) : UpdateDecision
    data class Invalid(val reason: String) : UpdateDecision
}

/** Outcome of verifying a downloaded APK. Never "probably fine". */
sealed interface VerificationResult {
    data object Ok : VerificationResult

    /**
     * A check did not pass.
     *
     * [reason] is the diagnostic detail (English, for the log and for anything
     * the caller has no phrase for); [kind] is what the UI turns into a sentence
     * in the user's language. A hash mismatch is a broken transfer, a
     * certificate mismatch is somebody else's app, and telling them apart is the
     * entire value of the message.
     */
    data class Failed(
        val reason: String,
        val kind: Kind = Kind.Unknown,
    ) : VerificationResult {
        enum class Kind {
            /** The bytes do not hash to the manifest value. */
            HashMismatch,

            /** Signed by a different key than the installed app, or not signed. */
            CertificateMismatch,

            /** Not an upgrade, or the package cannot be read at all. */
            NotAnUpgrade,

            Unknown,
        }
    }
}

/**
 * Decides whether [manifest] offers a newer build than [currentVersionCode].
 *
 * A manifest with a missing/blank url or a malformed hash is rejected rather than
 * trusted: it is the input to an installer, so "parse failed" must not degrade into
 * "install whatever is at that url".
 */
fun decideUpdate(currentVersionCode: Long, manifest: UpdateManifest?): UpdateDecision {
    if (manifest == null) return UpdateDecision.Invalid("manifest missing")
    if (manifest.apkUrl.isBlank()) return UpdateDecision.Invalid("apkUrl missing")
    if (!manifest.sha256.isHexOfLength(64)) return UpdateDecision.Invalid("sha256 malformed")
    if (manifest.versionName.isBlank()) return UpdateDecision.Invalid("versionName missing")

    return if (manifest.versionCode > currentVersionCode) {
        UpdateDecision.Available(manifest)
    } else {
        UpdateDecision.UpToDate
    }
}

/**
 * The three checks that must all pass before an installer is allowed to run
 * (docs/DESIGN.md §11.3). Each one closes a different hole:
 *
 * 1. [expectedSha256] vs the bytes actually on disk — catches a truncated or
 *    tampered download.
 * 2. [downloadedCertSha256] vs [installedCertSha256] — catches a *re-signed* APK,
 *    which is the attack that matters, because a different signature means a
 *    different app wearing our package name.
 * 3. [downloadedVersionCode] > [installedVersionCode] — catches a downgrade.
 *
 * Pure so it can be unit tested; the caller supplies the two certificate hashes.
 */
fun verifyDownload(
    expectedSha256: String,
    actualSha256: String,
    downloadedCertSha256: String?,
    installedCertSha256: String?,
    downloadedVersionCode: Long,
    installedVersionCode: Long,
): VerificationResult {
    if (!expectedSha256.equals(actualSha256, ignoreCase = true)) {
        return VerificationResult.Failed("sha256 mismatch", VerificationResult.Failed.Kind.HashMismatch)
    }
    if (installedCertSha256.isNullOrBlank()) {
        return VerificationResult.Failed(
            "installed signing certificate unavailable",
            VerificationResult.Failed.Kind.CertificateMismatch,
        )
    }
    if (downloadedCertSha256.isNullOrBlank()) {
        return VerificationResult.Failed(
            "downloaded APK is not signed",
            VerificationResult.Failed.Kind.CertificateMismatch,
        )
    }
    if (!downloadedCertSha256.equals(installedCertSha256, ignoreCase = true)) {
        return VerificationResult.Failed(
            "signing certificate mismatch",
            VerificationResult.Failed.Kind.CertificateMismatch,
        )
    }
    if (downloadedVersionCode <= installedVersionCode) {
        return VerificationResult.Failed("not an upgrade", VerificationResult.Failed.Kind.NotAnUpgrade)
    }
    return VerificationResult.Ok
}

/** Lowercase hex SHA-256 of [bytes]. */
fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/**
 * Lowercase hex SHA-256 of [file], hashed in 64 KB chunks.
 *
 * `sha256Hex(file.readBytes())` needed the whole APK in memory on top of the byte
 * array it produced — over 100 MB for a 53 MB build. On a device that ran out, the
 * OutOfMemoryError surfaced as "download failed", and the user was told the file
 * was broken.
 */
fun sha256Hex(file: java.io.File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}

internal fun String.isHexOfLength(length: Int): Boolean =
    this.length == length && all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
