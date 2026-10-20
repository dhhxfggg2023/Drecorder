package dev.drecorder

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.drecorder.audio.CaptureScope
import dev.drecorder.audio.CaptureState
import dev.drecorder.audio.EncoderSpec
import dev.drecorder.audio.isAvailableOnDevice
import dev.drecorder.capture.AppVisibility
import dev.drecorder.capture.CaptureOverlay
import dev.drecorder.capture.CaptureService
import dev.drecorder.capture.CaptureSession
import dev.drecorder.capture.RecordSettings
import dev.drecorder.data.Recording
import dev.drecorder.data.RecordingLibrary
import dev.drecorder.data.SilentMarker
import dev.drecorder.ui.DrecorderTheme
import dev.drecorder.ui.editor.EditorScreen
import dev.drecorder.ui.home.HomeScreen
import dev.drecorder.ui.library.LibraryScreen
import dev.drecorder.ui.library.defaultOutputDirectory
import dev.drecorder.ui.settings.SettingsScreen
import dev.drecorder.ui.update.UpdateScreen
import dev.drecorder.ui.update.UpdateUiState
import dev.drecorder.update.UpdateCoordinator
import dev.drecorder.update.UpdateSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DrecorderTheme {
                DrecorderApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.visible = true
    }

    override fun onStop() {
        // The overlay only steps aside while one of our screens is visible.
        AppVisibility.visible = false
        super.onStop()
    }
}

private enum class Screen { Home, Library, Settings, Update, Editor }

@Composable
private fun DrecorderApp() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.Home) }
    val captureState by CaptureSession.state.collectAsState()

    // System back (button or gesture) must walk the in-app stack instead of finishing
    // the activity: this is one activity with five screens, so there is no framework
    // back stack to pop.
    BackHandler(enabled = screen != Screen.Home) {
        screen = if (screen == Screen.Editor) Screen.Library else Screen.Home
    }

    var pendingScope by remember { mutableStateOf<CaptureScope>(CaptureScope.AllMedia) }
    var notice by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(RecordSettings.load(context)) }
    var aacAvailable by remember { mutableStateOf(true) }
    // Hoisted: rememberCoroutineScope() is itself a composable call and cannot be
    // invoked from inside another remember { } lambda.
    val appScope = rememberCoroutineScope()
    val coordinator = remember(context, appScope) { UpdateCoordinator(context, appScope) }
    val updateState by coordinator.state.collectAsState()
    val updateNotice by coordinator.notice.collectAsState()
    // These two back the update screen's controls; they are `var` so a tap actually
    // re-renders them (a `val` here made the auto-check switch look dead).
    var autoCheck by remember { mutableStateOf(UpdateSettings.autoCheckEnabled(context)) }
    var manifestUrl by remember { mutableStateOf(UpdateSettings.manifestUrl(context)) }
    var lastCheck by remember { mutableStateOf(UpdateSettings.lastCheckLabel(context)) }
    var canInstall by remember { mutableStateOf(false) }
    // The scope the user asked to record, held while the overlay permission is missing.
    var pendingStartScope by remember { mutableStateOf<CaptureScope?>(null) }
    var overlayPrompt by remember { mutableStateOf(false) }
    var recordingCount by remember { mutableStateOf(0) }
    // The recordings the editor is working on. One entry means a trim, several
    // mean a merge — the editor does not need a separate mode for it.
    var editingTargets by remember { mutableStateOf<List<Recording>>(emptyList()) }
    val library = remember(context) {
        RecordingLibrary(
            directoryProvider = {
                RecordSettings.outputDirectory(context)?.let(::File)
                    ?: defaultOutputDirectory(context)
            },
            marker = SilentMarker(context),
        )
    }

    val savedToast = stringResource(R.string.home_saved_toast)

    // The home screen only needs the number of files, not the rows themselves.
    LaunchedEffect(screen, captureState) {
        if (screen == Screen.Home) {
            recordingCount = withContext(Dispatchers.IO) { library.recordings().size }
        }
    }

    // A finished session has to say so somewhere now that there is no record screen.
    LaunchedEffect(captureState) {
        if (captureState is CaptureState.Finished) notice = savedToast
    }

    // A cold-start check, rate-limited to once a day by UpdateSettings.shouldAutoCheck.
    LaunchedEffect(Unit) {
        aacAvailable = EncoderSpec.Aac(settings.bitRate).isAvailableOnDevice()
        coordinator.autoCheckIfDue()
    }

    LaunchedEffect(updateState) {
        if (updateState !is UpdateUiState.Checking && updateState !is UpdateUiState.Downloading) {
            lastCheck = UpdateSettings.lastCheckLabel(context)
        }
        canInstall = coordinator.canInstallPackages()
    }

    LaunchedEffect(updateNotice) {
        updateNotice?.let { notice = it; coordinator.consumeNotice() }
    }

    val permissionDenied = stringResource(R.string.notice_permission_denied)
    val projectionDenied = stringResource(R.string.notice_projection_denied)
    val dirUnwritable = stringResource(R.string.settings_dir_unwritable)
    val savedNotice = stringResource(R.string.settings_saved)
    val installPermissionMissing = stringResource(R.string.update_permission_missing)
    val editBusyNotice = stringResource(R.string.library_edit_busy)

    // AAC support is a device property, not a setting: probe it once.
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CaptureService.start(context, result.resultCode, data, pendingScope)
        } else {
            notice = projectionDenied
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
            if (projectionManager != null) {
                // Android 14+ offers a "single app / entire screen" chooser and defaults
                // to the single-app entry, which is the wrong default for a recorder:
                // the user asked for everything the device plays. Asking for the default
                // display explicitly restores the plain "start recording?" dialog. The
                // capture itself is filtered by uid, not by this token's scope, so this
                // does not change which audio a "only these apps" session receives.
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    projectionManager.createScreenCaptureIntent(
                        MediaProjectionConfig.createConfigForDefaultDisplay(),
                    )
                } else {
                    projectionManager.createScreenCaptureIntent()
                }
                projectionLauncher.launch(intent)
            } else {
                notice = projectionDenied
            }
        } else {
            notice = permissionDenied
        }
    }

    fun beginCapture(scope: CaptureScope) {
        pendingScope = scope
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
    }

    // Coming back from the overlay-permission page continues the recording the user
    // asked for, instead of dropping them on the home screen wondering why nothing
    // started.
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val scope = pendingStartScope
        pendingStartScope = null
        if (scope != null) beginCapture(scope)
    }

    val directoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            val directory = DocumentTree.uriToDirectory(context, uri)
            // The writers take a real filesystem path, so only a mapping onto actual
            // storage is usable; a cloud provider's tree is refused rather than
            // silently recording nowhere.
            if (directory != null && directory.canWrite()) {
                RecordSettings.storeOutputDirectory(context, directory.absolutePath)
                notice = savedNotice
            } else {
                notice = dirUnwritable
            }
        }
    }

    when (screen) {
        Screen.Home -> HomeScreen(
            notice = notice,
            onNoticeShown = { notice = null },
            onStartCapture = {
                val scope = settings.captureScope()
                if (CaptureOverlay.canShow(context)) {
                    beginCapture(scope)
                } else {
                    // The overlay is the only way back into a background recording, so
                    // ask once instead of silently recording without a stop control.
                    pendingStartScope = scope
                    overlayPrompt = true
                }
            },
            onStopCapture = { CaptureService.stop(context) },
            onOpenLibrary = { screen = Screen.Library },
            onOpenSettings = { screen = Screen.Settings },
            appsOnly = settings.appsOnly,
            appCount = settings.appUids.size,
            recordingSinceMillis = (captureState as? CaptureState.Recording)?.startedAtMillis,
            recordingCount = recordingCount,
        )

        Screen.Library -> LibraryScreen(
            onBack = { screen = Screen.Home },
            onEdit = { targets ->
                // Editing while a capture is running would have two decoders and a
                // muxer competing for the same audio stack; the library disables the
                // entry point instead of failing here.
                if (captureState is CaptureState.Recording) {
                    notice = editBusyNotice
                } else {
                    editingTargets = targets
                    screen = Screen.Editor
                }
            },
            editingDisabled = captureState is CaptureState.Recording,
        )

        Screen.Editor -> EditorScreen(
            recordings = editingTargets,
            onBack = { screen = Screen.Library },
        )

        Screen.Settings -> SettingsScreen(
            settings = settings,
            aacAvailable = aacAvailable,
            outputDirectory = RecordSettings.outputDirectory(context),
            notice = notice,
            onNoticeShown = { notice = null },
            onBack = { screen = Screen.Home },
            onPickOutputDirectory = { directoryLauncher.launch(null) },
            onOpenUpdate = { screen = Screen.Update },
            onRangeChange = { appsOnly, uids ->
                RecordSettings.storeRange(context, appsOnly, uids)
                settings = RecordSettings.load(context)
            },
            appCount = settings.appUids.size,
            onSegmentChange = { minutes ->
                RecordSettings.storeSegmentMinutes(context, minutes)
                settings = RecordSettings.load(context)
            },
            onChange = { sampleRate, channelCount, bitRate, format ->
                RecordSettings.store(context, sampleRate, channelCount, bitRate, format)
                settings = RecordSettings.load(context)
            },
        )

        Screen.Update -> UpdateScreen(
            currentVersionName = appVersionName(context),
            state = updateState,
            manifestUrl = manifestUrl,
            autoCheck = autoCheck,
            lastCheckLabel = lastCheck,
            canInstallPackages = canInstall,
            notice = notice,
            onNoticeShown = { notice = null },
            onBack = { screen = Screen.Settings },
            onCheck = { coordinator.check(silent = false) },
            onDownload = { coordinator.download() },
            onInstall = { coordinator.install() },
            onOpenReleases = { coordinator.openReleasesPage() },
            onOpenUnknownSources = { coordinator.openUnknownSourcesSettings() },
            onManifestUrlChange = { url ->
                manifestUrl = url
                UpdateSettings.storeManifestUrl(context, url)
                notice = savedNotice
            },
            onAutoCheckChange = { enabled ->
                autoCheck = enabled
                UpdateSettings.storeAutoCheck(context, enabled)
                if (enabled) coordinator.autoCheckIfDue()
            },
        )
    }

    if (overlayPrompt) {
        AlertDialog(
            onDismissRequest = { overlayPrompt = false },
            title = { Text(stringResource(R.string.overlay_permission_title)) },
            text = { Text(stringResource(R.string.overlay_permission_body)) },
            confirmButton = {
                TextButton(onClick = {
                    overlayPrompt = false
                    overlayPermissionLauncher.launch(CaptureOverlay.permissionIntent(context))
                }) { Text(stringResource(R.string.overlay_permission_open)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    overlayPrompt = false
                    val scope = pendingStartScope
                    pendingStartScope = null
                    scope?.let(::beginCapture)
                }) { Text(stringResource(R.string.overlay_permission_skip)) }
            },
        )
    }
}

private fun appVersionName(context: android.content.Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
}.getOrDefault("?")
