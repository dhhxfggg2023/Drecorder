package dev.drecorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.drecorder.audio.CaptureScope
import dev.drecorder.audio.CaptureState
import dev.drecorder.audio.EncoderSpec
import dev.drecorder.audio.isAvailableOnDevice
import dev.drecorder.capture.AppVisibility
import dev.drecorder.capture.CaptureOverlay
import dev.drecorder.capture.CaptureService
import dev.drecorder.capture.CaptureSession
import dev.drecorder.capture.RecordSettings
import dev.drecorder.data.DerivedMarker
import dev.drecorder.data.Recording
import dev.drecorder.data.RecordingLibrary
import dev.drecorder.data.SilentMarker
import dev.drecorder.data.UpdateSafety
import dev.drecorder.ui.DrecorderNavigationBar
import dev.drecorder.ui.DrecorderTheme
import dev.drecorder.ui.NavPage
import dev.drecorder.ui.UiPreferences
import dev.drecorder.ui.editor.EditorScreen
import dev.drecorder.ui.home.HomeScreen
import dev.drecorder.ui.library.LibraryScreen
import dev.drecorder.ui.library.defaultOutputDirectory
import dev.drecorder.ui.settings.SettingsScreen
import dev.drecorder.ui.update.UpdateScreen
import dev.drecorder.waveforms.WaveformPrecompute
import kotlinx.coroutines.flow.StateFlow
import dev.drecorder.waveforms.AudioInbox
import dev.drecorder.ui.update.UpdateUiState
import dev.drecorder.update.UpdateCoordinator
import dev.drecorder.update.UpdateSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    /**
     * An audio file shared to the app from outside.
     *
     * A MutableStateFlow rather than a one-shot field: the activity may be
     * created for the share, or already on top and receive [onNewIntent], and
     * the Compose tree is not up yet in either case. The screen collects it,
     * imports the file, and clears it.
     */
    private val sharedAudio = kotlinx.coroutines.flow.MutableStateFlow<android.net.Uri?>(null)

    /**
     * Applies the chosen language before any resource is resolved.
     *
     * The in-app language switch has to survive a process restart, so it cannot be
     * a Compose-only concern: the base context is what every `getString`,
     * `stringResource` and notification builder reads from. Wrapping it here means
     * the setting reaches the notification text of a capture that was started by
     * the service as well.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withChosenLocale())
    }

    /**
     * Rebuilds the activity after the language or theme changed.
     *
     * There is no way to swap a resolved locale or colour scheme underneath a
     * running composition without lying to it, and a recreate is what the system
     * does itself for a configuration change — the saved state keeps the user on
     * the same screen.
     */
    private fun restartUi() {
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeSharedAudio(intent)
        enableEdgeToEdge()
        setContent {
            DrecorderTheme(darkTheme = UiPreferences.theme(this).resolvedDark()) {
                DrecorderApp(onRestartUi = ::restartUi, sharedAudio = sharedAudio)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeSharedAudio(intent)
        setIntent(intent)
    }

    private fun consumeSharedAudio(intent: Intent?) {
        val uri = AudioInbox.uriFrom(intent) ?: return
        sharedAudio.value = uri
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
private fun DrecorderApp(
    onRestartUi: () -> Unit = {},
    sharedAudio: kotlinx.coroutines.flow.MutableStateFlow<android.net.Uri?>? = null,
) {
    val context = LocalContext.current
    // Which screen is showing survives a rotation. With `remember` the app fell
    // back to the home screen on every configuration change, which looked like
    // the app had thrown the user out of their work.
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    val captureState by CaptureSession.state.collectAsState()
    val targetSilent by CaptureSession.targetSilent.collectAsState()
    // The live input level: the only thing on the home screen that moves while
    // recording, and the only proof that audio is actually arriving.
    val captureLevel by CaptureSession.level.collectAsState()
    // Bumped on every entry into the library, so the screen re-reads the folder
    // instead of showing the list as it was before an export.
    var libraryEntry by remember { mutableIntStateOf(0) }

    // System back (button or gesture) must walk the in-app stack instead of finishing
    // the activity: this is one activity with five screens, so there is no framework
    // back stack to pop.
    BackHandler(enabled = screen != Screen.Home) {
        // Mirrors every screen's own back arrow. Updates used to send the system
        // back key to Home while its on-screen arrow went to Settings, so the
        // same gesture had two different destinations depending on how it was
        // made.
        screen = when (screen) {
            Screen.Editor -> {
                libraryEntry++
                Screen.Library
            }

            Screen.Update -> Screen.Settings
            else -> Screen.Home
        }
    }

    // The scope the user asked to record, held while the projection dialog or the
    // overlay permission check is in flight. Saved, because that dialog can outlive
    // the activity: a `remember` here came back as AllMedia, so a user who chose
    // 「只录这些应用」 after a rotation silently got a full-device capture.
    var pendingScope by rememberSaveable(stateSaver = CaptureScopeSaver) {
        mutableStateOf<CaptureScope>(CaptureScope.AllMedia)
    }
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
    var recordingCount by remember { mutableIntStateOf(0) }
    // The recordings the editor is working on. One entry means a trim, several
    // mean a merge — the editor does not need a separate mode for it. The paths
    // are what survives a configuration change; the rows are re-read below, since
    // a rotation used to drop both the editor screen and the splice built in it.
    var editingPaths by rememberSaveable { mutableStateOf<List<String>>(arrayListOf()) }
    var editingTargets by remember { mutableStateOf<List<Recording>>(emptyList()) }
    val library = remember(context) {
        RecordingLibrary(
            directoryProvider = {
                RecordSettings.outputDirectory(context)?.let(::File)
                    ?: defaultOutputDirectory(context)
            },
            marker = SilentMarker(context),
            // Without this a recording produced by the editor is listed here as
            // an original: the badge, and any later "was this edited?" question,
            // needs the same markers the library screen reads.
            derivedMarker = DerivedMarker(context),
        )
    }

    val savedToast = stringResource(R.string.home_saved_toast)

    // Restores the editor's targets after a rotation, from the saved paths.
    LaunchedEffect(editingPaths) {
        if (editingTargets.map { it.file.path } == editingPaths) return@LaunchedEffect
        if (editingPaths.isEmpty()) {
            editingTargets = emptyList()
            return@LaunchedEffect
        }
        val byPath = withContext(Dispatchers.IO) {
            library.recordings().associateBy { it.file.path }
        }
        editingTargets = editingPaths.mapNotNull { byPath[it] }
    }

    // The home screen only needs the number of files, not the rows themselves.
    LaunchedEffect(screen, captureState) {
        if (screen == Screen.Home) {
            recordingCount = withContext(Dispatchers.IO) { library.recordings().size }
        }
    }

    // A finished session has to say so somewhere now that there is no record screen.
    // A failed one has to say so too: the engine used to fail silently, and this
    // screen only ever announced success, so a capture that never started looked
    // exactly like one that started and stopped.
    val captureFailedPrefix = stringResource(R.string.record_failed)
    LaunchedEffect(captureState) {
        when (val state = captureState) {
            is CaptureState.Finished -> notice = savedToast
            is CaptureState.Failed -> notice = captureFailedPrefix + "：" + state.message
            else -> Unit
        }
    }

    // A cold-start check, rate-limited to once a day by UpdateSettings.shouldAutoCheck.
    LaunchedEffect(Unit) {
        aacAvailable = EncoderSpec.Aac(settings.bitRate).isAvailableOnDevice()
        coordinator.autoCheckIfDue()
        // Quietly fill in any missing waveform caches — older recordings,
        // imported files, exports made by previous builds. One low-priority
        // decode at a time, never blocking the screen.
        launch { WaveformPrecompute.enqueueMissing(context) }
    }

    LaunchedEffect(updateState) {
        if (updateState !is UpdateUiState.Checking && updateState !is UpdateUiState.Downloading) {
            lastCheck = UpdateSettings.lastCheckLabel(context)
        }
        canInstall = coordinator.canInstallPackages()
    }

    // The "install unknown apps" grant is given in the system settings, so the
    // app has to notice the change when it comes back — the update state does
    // not change while the user is away, and reading the flag only on a state
    // change left the screen showing "grant permission" forever. That is what
    // made the first update look stuck: the user had to download a second time
    // for the state to change and the flag to be re-read.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canInstall = coordinator.canInstallPackages()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

    // Import: a file picked inside the library, or shared to the app from
    // another app. Both go through the same copy-into-library path, and both
    // queue their waveform immediately so the first open is not a wait.
    val importNotice = stringResource(R.string.import_done)
    val importFailed = stringResource(R.string.import_failed)
    val appScopeIO = rememberCoroutineScope()
    suspend fun importAudio(uri: android.net.Uri, name: String?) {
        val result = withContext(Dispatchers.IO) {
            AudioInbox.import(context, uri, name)
        }
        notice = when (result) {
            is AudioInbox.Result.Imported -> importNotice + " " + result.name
            is AudioInbox.Result.Rejected -> importFailed + "：" + result.reason
        }
        libraryEntry++
    }

    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            appScopeIO.launch { importAudio(uri, null) }
        }
    }

    // A share that arrived while the app was not running (or was already open).
    sharedAudio?.let { flow ->
        val shared by flow.collectAsState()
        LaunchedEffect(shared) {
            val uri = shared ?: return@LaunchedEffect
            importAudio(uri, null)
            flow.value = null
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

    // The file a running session is writing, by name. The library must not offer
    // it: deleting it would unlink the file the encoder still holds open.
    val activeCaptureName = (captureState as? CaptureState.Recording)
        ?.outputPath
        ?.let { path -> File(path).name }

    // One transition for every change of page. A move between the two top-level
    // pages is a scale-and-fade — the page you asked for grows into place, which
    // is what makes it read as "this one is now the main one". A move deeper
    // (library, editor, update) slides, because those are places you go and come
    // back from, and a slide says that.
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val bothTopLevel = topLevel(initialState) && topLevel(targetState)
            if (bothTopLevel) {
                (fadeIn(animationSpec = tween(220)) +
                    scaleIn(initialScale = 0.94f, animationSpec = tween(260))) togetherWith
                    (fadeOut(animationSpec = tween(180)) +
                        scaleOut(targetScale = 0.98f, animationSpec = tween(260)))
            } else {
                val deeper = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(animationSpec = tween(280)) { full ->
                    if (deeper) full else -full / 4
                } + fadeIn(animationSpec = tween(220))) togetherWith
                    (slideOutHorizontally(animationSpec = tween(280)) { full ->
                        if (deeper) -full / 4 else full
                    } + fadeOut(animationSpec = tween(180)))
            }
        },
        label = "page",
    ) { currentScreen ->
        when (currentScreen) {
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
            onOpenLibrary = {
                libraryEntry++
                screen = Screen.Library
            },
            onOpenSettings = { screen = Screen.Settings },
            appsOnly = settings.appsOnly,
            appCount = settings.appUids.size,
            recordingSinceMillis = (captureState as? CaptureState.Recording)?.startedAtMillis,
            recordingCount = recordingCount,
            targetSilent = targetSilent && captureState is CaptureState.Recording,
            level = captureLevel,
            bottomBar = {
                DrecorderNavigationBar(NavPage.Home) { page ->
                    screen = if (page == NavPage.Settings) Screen.Settings else Screen.Home
                }
            },
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
                    editingPaths = ArrayList(targets.map { it.file.path })
                    screen = Screen.Editor
                }
            },
            editingDisabled = captureState is CaptureState.Recording,
            activeRecordingName = activeCaptureName,
            screenKey = libraryEntry,
            onImport = { documentLauncher.launch(arrayOf("audio/*")) },
        )

        Screen.Editor -> EditorScreen(
            recordings = editingTargets,
            onBack = {
                libraryEntry++
                screen = Screen.Library
            },
            // Without this the library kept showing the pre-export list: the file
            // was written and the badge marked, but the screen the user returned
            // to never re-read the directory.
            onExported = { recordingCount = recordingCount + 1 },
            // The editor reads the library itself when the user asks to splice
            // another recording in, so the list is never stale and the user
            // does not have to go back to the library to build a merge.
            loadLibrary = { library.recordings(activeCaptureName) },
        )

        Screen.Settings -> SettingsScreen(
            settings = settings,
            language = UiPreferences.language(context),
            theme = UiPreferences.theme(context),
            onLanguageChange = { tag ->
                UiPreferences.storeLanguage(context, tag)
                onRestartUi()
            },
            onThemeChange = { chosen ->
                UiPreferences.storeTheme(context, chosen)
                onRestartUi()
            },
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
            bottomBar = {
                DrecorderNavigationBar(NavPage.Settings) { page ->
                    screen = if (page == NavPage.Settings) Screen.Settings else Screen.Home
                }
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
            dataSafety = coordinator.offeredManifest()
                ?.let { coordinator.dataSafety(it) }
                ?: UpdateSafety.Unknown,
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

/**
 * Writes [CaptureScope] to a saved instance state as `all` or `apps:1,2`.
 *
 * The scope cannot be saved as an object (it holds a list of uids), and losing it
 * across a configuration change meant a user who chose 「只录这些应用」 got a
 * full-device capture when the projection dialog outlived the activity.
 */
private val CaptureScopeSaver: Saver<CaptureScope, String> = Saver(
    save = { scope ->
        when (scope) {
            is CaptureScope.AllMedia -> "all"
            is CaptureScope.OnlyApps -> "apps:" + scope.uids.joinToString(",")
        }
    },
    restore = { saved ->
        val uids = saved
            .removePrefix("apps:")
            .split(',')
            .mapNotNull { it.toIntOrNull() }
        if (saved == "all" || uids.isEmpty()) CaptureScope.AllMedia else CaptureScope.OnlyApps(uids)
    },
)

/**
 * The base context with the user's language applied.
 *
 * Works on every API level the app supports (29+): [LocaleManager] only exists on
 * 33+, and the older per-context override is still the way to do it before that.
 * The locale is set on the *context*, not on the process, so the system language
 * of other apps and of the notification shade is untouched.
 */
private fun Context.withChosenLocale(): Context {
    val tag = UiPreferences.language(this)
    if (tag.isEmpty()) return this
    val locale = Locale.forLanguageTag(tag)
    Locale.setDefault(locale)
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return createConfigurationContext(configuration)
}

/**
 * Whether the resolved theme is dark.
 *
 * [UiPreferences.Theme.System] defers to the device; the two explicit values
 * override it, which is the whole point of the setting on a phone that follows
 * sunset while the user wants the app dark all day.
 */
@Composable
private fun UiPreferences.Theme.resolvedDark(): Boolean = when (this) {
    UiPreferences.Theme.Dark -> true
    UiPreferences.Theme.Light -> false
    UiPreferences.Theme.System -> isSystemInDarkTheme()
}

/** The two pages the bottom bar offers; everything else is opened from one of them. */
private fun topLevel(screen: Screen): Boolean =
    screen == Screen.Home || screen == Screen.Settings

private fun appVersionName(context: android.content.Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
}.getOrDefault("?")