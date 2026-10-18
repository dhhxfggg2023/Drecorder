package dev.drecorder

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import dev.drecorder.audio.EncoderSpec
import dev.drecorder.audio.isAvailableOnDevice
import dev.drecorder.capture.CaptureService
import dev.drecorder.capture.RecordSettings
import dev.drecorder.data.Recording
import dev.drecorder.data.RecordingLibrary
import dev.drecorder.data.SilentMarker
import dev.drecorder.ui.DrecorderTheme
import dev.drecorder.ui.home.HomeScreen
import dev.drecorder.ui.library.LibraryScreen
import dev.drecorder.ui.library.defaultOutputDirectory
import dev.drecorder.ui.record.RecordScreen
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
}

private enum class Screen { Home, Record, Library, Settings, Update }

@Composable
private fun DrecorderApp() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.Home) }
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
    val autoCheck by remember { mutableStateOf(UpdateSettings.autoCheckEnabled(context)) }
    val manifestUrl by remember { mutableStateOf(UpdateSettings.manifestUrl(context)) }
    var lastCheck by remember { mutableStateOf(UpdateSettings.lastCheckLabel(context)) }
    var canInstall by remember { mutableStateOf(false) }
    var recents by remember { mutableStateOf<List<Recording>>(emptyList()) }
    val library = remember(context) {
        RecordingLibrary(
            directoryProvider = {
                RecordSettings.outputDirectory(context)?.let(::File)
                    ?: defaultOutputDirectory(context)
            },
            marker = SilentMarker(context),
        )
    }

    // The home screen shows what was recorded last; reload every time it is shown so
    // a finished (or deleted) recording is reflected without restarting the app.
    LaunchedEffect(screen) {
        if (screen == Screen.Home) {
            recents = withContext(Dispatchers.IO) { library.recordings().take(3) }
        }
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

    // AAC support is a device property, not a setting: probe it once.
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CaptureService.start(context, result.resultCode, data, pendingScope)
            screen = Screen.Record
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
                projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            } else {
                notice = projectionDenied
            }
        } else {
            notice = permissionDenied
        }
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
            onStartCapture = { scope ->
                pendingScope = scope
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ),
                )
            },
            onOpenLibrary = { screen = Screen.Library },
            onOpenSettings = { screen = Screen.Settings },
            onOpenUpdate = { screen = Screen.Update },
            recents = recents,
        )

        Screen.Record -> RecordScreen(onDone = { screen = Screen.Home })

        Screen.Library -> LibraryScreen(onBack = { screen = Screen.Home })

        Screen.Settings -> SettingsScreen(
            settings = settings,
            aacAvailable = aacAvailable,
            outputDirectory = RecordSettings.outputDirectory(context),
            notice = notice,
            onNoticeShown = { notice = null },
            onBack = { screen = Screen.Home },
            onPickOutputDirectory = { directoryLauncher.launch(null) },
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
            onBack = { screen = Screen.Home },
            onCheck = { coordinator.check(silent = false) },
            onDownload = { coordinator.download() },
            onInstall = { coordinator.install() },
            onOpenReleases = { coordinator.openReleasesPage() },
            onOpenUnknownSources = { coordinator.openUnknownSourcesSettings() },
            onManifestUrlChange = { url ->
                UpdateSettings.storeManifestUrl(context, url)
                notice = savedNotice
            },
            onAutoCheckChange = { enabled ->
                UpdateSettings.storeAutoCheck(context, enabled)
                if (enabled) coordinator.autoCheckIfDue()
            },
        )
    }
}

private fun appVersionName(context: android.content.Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
}.getOrDefault("?")
