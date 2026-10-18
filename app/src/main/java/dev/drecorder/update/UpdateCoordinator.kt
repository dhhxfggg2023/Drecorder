package dev.drecorder.update

import android.content.Context
import dev.drecorder.data.UpdateDecision
import dev.drecorder.data.UpdateManifest
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
            UpdateSettings.storeLastCheckAt(context, System.currentTimeMillis())

            fetched.fold(
                onSuccess = { parsed ->
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

    /** Downloads, verifies, and only then offers the installer. */
    fun download() {
        val target = manifest ?: return
        if (_state.value is UpdateUiState.Downloading) return

        scope.launch {
            _state.value = UpdateUiState.Downloading(0)

            val candidates = installer.allCandidates(target)
            var lastError: String? = null

            for (candidate in candidates) {
                val attempt = runCatching {
                    val id = installer.enqueue(target, candidate)
                    val file = installer.await(id)
                    when (val verification = checker.verifyDownloadedApk(file, target)) {
                        VerificationResult.Ok -> file
                        is VerificationResult.Failed -> {
                            // A file that fails verification must not survive: leaving it
                            // on disk invites a later "install anyway" tap.
                            runCatching { file.delete() }
                            error(verification.reason)
                        }
                    }
                }

                attempt.fold(
                    onSuccess = { file ->
                        _state.value = UpdateUiState.ReadyToInstall(target.versionName)
                        return@launch
                    },
                    onFailure = { error ->
                        lastError = error.message
                    },
                )
            }

            _state.value = UpdateUiState.Failed(lastError ?: "download failed")
        }
    }

    /** Launches the installer for the already-verified APK. */
    fun install() {
        val target = manifest ?: return
        val apk = installer.fileFor(target)
        if (!apk.isFile) {
            _state.value = UpdateUiState.Failed("verified APK missing")
            return
        }
        if (!installer.install(apk, target)) {
            _notice.value = null
            // The only realistic reason here is the "install unknown apps" toggle.
            _state.value = UpdateUiState.ReadyToInstall(target.versionName)
        }
    }

    fun canInstallPackages(): Boolean = installer.canInstallPackages()

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
