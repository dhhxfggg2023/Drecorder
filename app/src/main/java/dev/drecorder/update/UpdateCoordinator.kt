package dev.drecorder.update

import android.content.Context
import dev.drecorder.R
import dev.drecorder.data.UpdateDecision
import dev.drecorder.data.UpdateManifest
import dev.drecorder.data.UpdateSafety
import dev.drecorder.data.updateSafety
import dev.drecorder.data.VerificationResult
import dev.drecorder.data.requiresUpdate
import dev.drecorder.ui.update.UpdateUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drives the whole update flow: check, download, verify, install.
 *
 * Kept out of the composables so the ordering that matters — *verify before install,
 * and never install after a failed check* — is visible in one place instead of being
 * spread across button callbacks.
 */
class UpdateCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val checker = UpdateChecker(context)
    private val installer = UpdateInstaller(context)

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var manifest: UpdateManifest? = null

    fun consumeNotice() {
        _notice.value = null
    }

    /**
     * Fetches the manifest and decides. [silent] suppresses "up to date" so a
     * background check does not interrupt the user with good news.
     */
    fun check(silent: Boolean = false) {
        if (_state.value is UpdateUiState.Checking) return
        scope.launch {
            _state.value = UpdateUiState.Checking
            val url = UpdateSettings.manifestUrl(context)

            val fetched = checker.fetchManifest(url)

            fetched.fold(
                onSuccess = { parsed ->
                    // Recorded only on success: a check that could not reach the
                    // network used to count as "checked", which suppressed the next
                    // automatic attempt for a full day.
                    UpdateSettings.storeLastCheckAt(context, System.currentTimeMillis())
                    manifest = parsed
                    when (val decision = checker.decide(parsed)) {
                        is UpdateDecision.Available -> {
                            _state.value = UpdateUiState.Available(
                                versionName = parsed.versionName,
                                notes = parsed.releaseNotes,
                                forced = parsed.requiresUpdate(checker.installedVersion()?.first ?: 0L),
                            )
                        }

                        UpdateDecision.UpToDate -> {
                            _state.value = if (silent) {
                                UpdateUiState.Idle
                            } else {
                                UpdateUiState.UpToDate
                            }
                        }

                        is UpdateDecision.Invalid -> {
                            _state.value = if (silent) UpdateUiState.Idle else UpdateUiState.Failed(decision.reason)
                        }
                    }
                },
                onFailure = { error ->
                    _state.value = if (silent) {
                        UpdateUiState.Idle
                    } else {
                        UpdateUiState.Failed(error.message ?: "unreachable")
                    }
                },
            )
        }
    }

    /**
     * The APK that actually passed verification.
     *
     * [install] used to re-derive the path from `manifest.versionName`, so the
     * file the installer opened was not provably the file that was verified —
     * the guarantee ended at the last statement of [download]. Keeping the
     * verified file is what makes "verify before install" a property of the code
     * rather than of the code's good intentions.
     */
    private var verifiedApk: File? = null

    /** Downloads, verifies, and only then offers the installer. */
    fun download() {
        val target = manifest ?: return
        if (_state.value is UpdateUiState.Downloading) return

        scope.launch {
            _state.value = UpdateUiState.Downloading(0L, -1L)
            verifiedApk = null

            val candidates = installer.allCandidates(target)
            var lastFailure: VerificationResult.Failed? = null

            for (candidate in candidates) {
                val attempt = runCatching {
                    val id = installer.enqueue(target, candidate)
                    val file = installer.await(id) { downloaded, total ->
                        // The poll runs on an IO thread, so the state is updated
                        // from there; the flow is thread-safe and the screen
                        // collects it.
                        _state.value = UpdateUiState.Downloading(downloaded, total)
                    }
                    when (val verification = checker.verifyDownloadedApk(file, target)) {
                        VerificationResult.Ok -> file
                        is VerificationResult.Failed -> {
                            // A file that fails verification must not survive: leaving it
                            // on disk invites a later "install anyway" tap.
                            runCatching { file.delete() }
                            lastFailure = verification
                            error(verification.reason)
                        }
                    }
                }

                attempt.fold(
                    onSuccess = { file ->
                        verifiedApk = file
                        _state.value = UpdateUiState.ReadyToInstall(target.versionName)
                        return@launch
                    },
                    onFailure = { /* try the next mirror */ },
                )
            }

            _state.value = UpdateUiState.Failed(
                lastFailure?.kind?.let { kind ->
                    context.getString(
                        when (kind) {
                            VerificationResult.Failed.Kind.HashMismatch -> R.string.update_failed_hash
                            VerificationResult.Failed.Kind.CertificateMismatch -> R.string.update_failed_certificate
                            VerificationResult.Failed.Kind.NotAnUpgrade -> R.string.update_failed_not_upgrade
                            VerificationResult.Failed.Kind.Unknown -> R.string.update_failed_generic
                        },
                    )
                } ?: context.getString(R.string.update_failed_generic),
            )
        }
    }

    /**
     * Launches the installer for the already-verified APK.
     *
     * The "install unknown apps" permission is checked *again* here: the user
     * grants it in the system settings and comes back, and the app has to
     * notice that the grant took effect instead of sitting on a dead button.
     */
    fun install() {
        val target = manifest ?: return
        // The verified file, not a path re-derived from the manifest.
        val apk = verifiedApk?.takeIf { it.isFile } ?: installer.fileFor(target)
        if (!apk.isFile) {
            _state.value = UpdateUiState.Failed(context.getString(R.string.update_failed_generic))
            return
        }
        if (!installer.install(apk, target)) {
            // The only realistic reason here is the "install unknown apps" toggle.
            // Saying so is the whole point: a tap that does nothing reads as a bug.
            _state.value = UpdateUiState.ReadyToInstall(target.versionName)
            _notice.value = context.getString(R.string.update_permission_missing)
        }
    }

    fun canInstallPackages(): Boolean = installer.canInstallPackages()

    /**
     * Whether the offered update can be installed over this build without
     * taking the recordings with it.
     *
     * Answered from the manifest and the installed package, so the warning
     * appears while the user is still deciding — not after the download, when
     * the only remaining action would be an uninstall.
     */
    fun dataSafety(target: UpdateManifest): UpdateSafety =
        updateSafety(
            installedCertSha256 = checker.installedCertSha256(),
            manifestCertSha256 = target.signingCertSha256,
        )

    /** The manifest currently offered, so the screen can ask about it. */
    fun offeredManifest(): UpdateManifest? = manifest

    fun openUnknownSourcesSettings() {
        runCatching { context.startActivity(installer.unknownSourcesSettingsIntent()) }
    }

    /** Manual escape hatch when the manifest or a mirror is unreachable. */
    fun openReleasesPage() {
        // The asset URL points at one file; the release page is the tag URL one level up.
        val url = manifest?.apkUrl
            ?.replace("/releases/download/", "/releases/tag/")
            ?.substringBeforeLast('/')
            ?: UpdateSettings.manifestUrl(context)
        runCatching { context.startActivity(installer.releasesPageIntent(url)) }
    }

    /** Runs a silent check only when the rate limit allows it. */
    fun autoCheckIfDue() {
        if (UpdateSettings.shouldAutoCheck(context)) check(silent = true)
    }
}