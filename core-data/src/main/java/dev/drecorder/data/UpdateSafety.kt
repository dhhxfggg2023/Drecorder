package dev.drecorder.data

/**
 * What an in-app update will do to the data that is already on the device.
 *
 * This exists because "update" and "reinstall" look identical in the UI and are
 * not identical at all: an APK installed over the app with the *same signing key*
 * keeps every recording and every setting, while the OS refuses that same APK if
 * the key changed — and the only way left is uninstalling first, which takes
 * `Android/data/dev.drecorder` (the default recordings folder) with it.
 *
 * So the question "will this update keep my recordings?" is answered before the
 * download starts, by comparing the certificate the installed build was signed
 * with to the one the manifest announces. Pure, so it is unit-tested.
 */
sealed interface UpdateSafety {

    /**
     * The new build can be installed over this one.
     *
     * Recordings, the library sidecars (silent/edited markers), the cached
     * waveforms and every setting stay exactly where they are: the installer
     * replaces the APK and leaves the application data directory untouched.
     */
    data object PreservesData : UpdateSafety

    /**
     * The manifest announces a build signed with another key.
     *
     * Installing it over this one is impossible — the system rejects the package.
     * The user must be told *before* they start, and told what uninstalling costs,
     * because the default recordings folder is inside the app and goes with it.
     */
    data class RequiresUninstall(
        val installedCertSha256: String,
        val manifestCertSha256: String,
    ) : UpdateSafety

    /**
     * The manifest does not say which key signs the build.
     *
     * Older manifests predate the field. The download is still verified against
     * the *installed* certificate before anything is installed, so this is not a
     * risk — but it cannot warn about an in-place upgrade either.
     */
    data object Unknown : UpdateSafety
}

/**
 * Whether installing [manifestCertSha256] over a build signed with
 * [installedCertSha256] can keep the existing data.
 *
 * Both null/blank means the answer is unknown rather than "fine": guessing
 * "fine" here would produce exactly the uninstall the user did not ask for.
 */
fun updateSafety(installedCertSha256: String?, manifestCertSha256: String?): UpdateSafety {
    if (installedCertSha256.isNullOrBlank() || manifestCertSha256.isNullOrBlank()) {
        return UpdateSafety.Unknown
    }
    return if (installedCertSha256.equals(manifestCertSha256, ignoreCase = true)) {
        UpdateSafety.PreservesData
    } else {
        UpdateSafety.RequiresUninstall(installedCertSha256, manifestCertSha256)
    }
}
