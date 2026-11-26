package dev.drecorder.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.content.res.Configuration
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import dev.drecorder.MainActivity
import dev.drecorder.R
import dev.drecorder.audio.CaptureFormat
import dev.drecorder.audio.CaptureScope
import dev.drecorder.audio.CaptureState
import dev.drecorder.audio.ProjectionCaptureEngine
import dev.drecorder.audio.RecordingHandle
import dev.drecorder.audio.StopReason
import dev.drecorder.audio.edit.PeaksCache
import dev.drecorder.data.SilentMarker
import dev.drecorder.ui.UiPreferences
import dev.drecorder.waveforms.WaveformPrecompute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The foreground service that owns capture.
 *
 * Order matters: `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)`
 * must happen before `getMediaProjection()`, otherwise Android 14+ throws
 * SecurityException (docs/DESIGN.md §2.4).
 */
class CaptureService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: ProjectionCaptureEngine? = null
    private var overlay: CaptureOverlay? = null

    /** True once the finished session was persisted; finishing twice would duplicate it. */
    private var completed = false

    /** True between accepting a start request and the engine reporting it is up. */
    private var startInFlight = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> {
                finishSession(StopReason.UserStop)
                return START_NOT_STICKY
            }

            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        WaveformPrecompute.setCaptureActive(false)
        overlay?.hide()
        overlay = null
        val stopping = engine
        engine = null
        serviceScope.cancel()
        if (stopping != null) {
            // The process may be going away, but the container still has to be
            // closed or the file is unplayable. Off the main thread: the close
            // joins the capture worker and flushes the encoder.
            Thread({
                val handle = runCatching { stopping.stop(StopReason.UserStop) }.getOrNull()
                if (handle != null) persist(handle)
            }, "drecorder-teardown").start()
        }
        super.onDestroy()
    }

    private fun startCapture(intent: Intent) {
        if (engine != null || startInFlight) {
            // A second start (double tap, re-sent intent) would overwrite the live
            // engine: its AudioRecord, encoder and muxer would never be closed and
            // its file never finalised.
            Log.w(TAG, "ignoring a start while a session is already running")
            return
        }
        startInFlight = true
        completed = false
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)

        // Foreground first: the projection token may only be taken afterwards.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        CaptureSession.resetForNewSession()
        WaveformPrecompute.setCaptureActive(true)

        if (resultData == null) {
            CaptureSession.fail(getString(R.string.error_missing_projection))
            startInFlight = false
            shutdown()
            return
        }

        val captureScope = scopeFrom(intent)
        val preferences = RecordSettings.load(this)
        val directory = outputDirectory()
        val baseName = uniqueBaseName(directory)

        // Starting a session used to run on the main thread, and it is the most
        // expensive thing the app does: it probes for a hardware AAC encoder by
        // instantiating one, builds an AudioRecord, and opens the muxer. All three
        // are tens to hundreds of milliseconds of driver work — a visible freeze
        // when the user taps record, an ANR when the device is busy.
        serviceScope.launch {
            val attempt = withContext(Dispatchers.IO) {
                runCatching {
                    val projection = getSystemService(MediaProjectionManager::class.java)
                        ?.getMediaProjection(resultCode, resultData)
                        ?: error(getString(R.string.error_projection_null))
                    val capture = ProjectionCaptureEngine(
                        scope = captureScope,
                        format = CaptureFormat(
                            sampleRate = preferences.sampleRate,
                            channelCount = preferences.channelCount,
                            bitRate = preferences.bitRate,
                        ),
                        outputDirectory = directory,
                        encoder = preferences.encoder,
                        baseName = baseName,
                        segmentDurationMs = preferences.segmentDurationMs,
                    )
                    capture.attachProjection(projection)
                    capture.start().getOrThrow()
                    capture
                }
            }
            startInFlight = false
            attempt.fold(
                onSuccess = { capture ->
                    attachSession(capture)
                    Log.i(TAG, "capture started scope=" + captureScope)
                },
                onFailure = { failure ->
                    Log.w(TAG, "capture failed to start", failure)
                    CaptureSession.fail(failure.message ?: "capture failed to start")
                    shutdown()
                },
            )
        }
    }

    /** Publishes a running engine to the UI and shows the floating control. */
    private fun attachSession(capture: ProjectionCaptureEngine) {
        engine = capture
        serviceScope.launch {
            capture.state.collect { state ->
                CaptureSession.onState(state)
                // Every way a session can end lands here — including the
                // projection being revoked, which ends it inside the engine
                // rather than in this class. Without this the notification and
                // the overlay kept running over a session that had stopped.
                if (state is CaptureState.Finished) completeSession(state.handle)
            }
        }
        serviceScope.launch { capture.level.collect(CaptureSession::onLevel) }
        serviceScope.launch { capture.targetSilent.collect(CaptureSession::onTargetSilent) }

        // A recording of a backgrounded phone needs a way back and a way to stop; the
        // notification is not enough (it can be swiped away or hidden by the system).
        if (CaptureOverlay.canShow(this)) {
            overlay = CaptureOverlay(
                context = this,
                onStop = { stop(this) },
                onOpen = { openApp() },
            ).also { it.show(System.currentTimeMillis()) }
        }
    }

    /**
     * A file name no existing recording shares.
     *
     * The stamp only has second resolution, and a second session can begin inside
     * the same second (stop, then start again; or a restart after a revocation).
     * The muxer opens the name it is given, so the earlier recording would be
     * truncated in place.
     */
    private fun uniqueBaseName(directory: File): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var baseName = "drecorder-" + stamp
        var attempt = 2
        while (attempt <= 999) {
            val clash = directory.listFiles { file -> file.name.startsWith(baseName) }
            if (clash.isNullOrEmpty()) return baseName
            baseName = "drecorder-" + stamp + "-" + attempt
            attempt++
        }
        return "drecorder-" + stamp + "-" + System.currentTimeMillis()
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_OPEN_RECORD, true),
        )
    }

    private fun finishSession(reason: StopReason) {
        overlay?.hide()
        overlay = null
        val stopping = engine
        engine = null
        if (stopping == null) {
            completeSession(null)
            return
        }
        // The stop joins the capture worker and flushes the encoder (up to a few
        // seconds). On the main thread that freezes the notification and the
        // overlay, and an ANR follows. The state collector above finishes the
        // session when the engine reports it.
        serviceScope.launch(Dispatchers.IO) { stopping.stop(reason) }
    }

    /**
     * Runs once per session, whichever way it ended: writes the sidecar markers
     * and the cached waveform, hands the handle to the UI and closes the service.
     */
    private fun completeSession(handle: RecordingHandle?) {
        if (completed) return
        completed = true
        WaveformPrecompute.setCaptureActive(false)
        CaptureSession.onFinished(handle)
        if (handle == null) {
            shutdown()
            return
        }
        // Two files per recording (a silence marker and a cached waveform) is a
        // handful of milliseconds on a good day, but this runs on the main thread
        // that also drives the notification and the overlay, and a segmented
        // session writes them for every slice. Off the main thread.
        //
        // Shutting down *after* the write, and on a scope the service cannot
        // cancel: stopSelf() schedules onDestroy(), which cancels [serviceScope].
        // Launched first and cancelled a moment later, the marker of a silent
        // recording and the cached waveform were simply lost — intermittently,
        // which is the worst way for a bug to present.
        serviceScope.launch(NonCancellable + Dispatchers.IO) {
            runCatching { persist(handle) }
            shutdown()
        }
    }

    private fun persist(handle: RecordingHandle) {
        Log.i(
            TAG,
            "capture finished reason=${handle.stopReason} files=${handle.files.size} " +
                "ms=${handle.durationMs} peakDbfs=${handle.peakDbfs} signal=${handle.everHadSignal}",
        )
        // A blocked source yields a valid but silent file; the sidecar marker is
        // the only thing that lets the library say so (core-data SilentMarker).
        // Every slice of a segmented session gets one.
        val marker = SilentMarker(this)
        handle.files.forEach { file -> marker.write(file.name, wasSilent = !handle.everHadSignal) }

        // The waveform was computed while the audio was captured, so it is
        // written to the cache now. The editor then opens instantly instead
        // of decoding the whole recording again (DESIGN §12.4).
        writePeaks(handle)
    }

    /** Writes the cached waveform of every file the session produced. */
    private fun writePeaks(handle: RecordingHandle) {
        handle.peaks.forEachIndexed { index, peaks ->
            val file = handle.files.getOrNull(index) ?: return@forEachIndexed
            val target = PeaksCache.fileFor(file, filesDir)
            runCatching { PeaksCache.write(target, peaks, file) }
        }
    }

    private fun shutdown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scopeFrom(intent: Intent): CaptureScope =
        if (intent.getStringExtra(EXTRA_SCOPE_KIND) == SCOPE_APPS) {
            CaptureScope.OnlyApps(intent.getIntArrayExtra(EXTRA_UIDS)?.toList().orEmpty())
        } else {
            CaptureScope.AllMedia
        }

    private fun outputDirectory(): File {
        val preferred = RecordSettings.outputDirectory(this)
        if (preferred != null) {
            val directory = File(preferred)
            if (directory.isDirectory || directory.mkdirs()) return directory
        }
        return getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
    }

    /**
     * A context that speaks the language chosen in the app.
     *
     * The service is not MainActivity and never sees the base context that the
     * language switch installs, so the notification and the floating control
     * would otherwise stay in the device language while the app was in another.
     */
    private fun localised(): Context {
        val tag = UiPreferences.language(this)
        if (tag.isEmpty()) return this
        val configuration = Configuration(resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        return createConfigurationContext(configuration)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val text = localised()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopCapture = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(text.getString(R.string.notification_title))
            .setContentText(text.getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .addAction(0, text.getString(R.string.notification_stop), stopCapture)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "DrecorderService"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_START = "dev.drecorder.action.START"
        private const val ACTION_STOP = "dev.drecorder.action.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_SCOPE_KIND = "scopeKind"
        private const val EXTRA_UIDS = "uids"
        private const val SCOPE_APPS = "apps"

        /** Set on the MainActivity intent when the overlay asks to reopen the recording screen. */
        const val EXTRA_OPEN_RECORD = "dev.drecorder.extra.OPEN_RECORD"

        fun start(context: Context, resultCode: Int, resultData: Intent, scope: CaptureScope) {
            val intent = Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            when (scope) {
                is CaptureScope.AllMedia -> intent.putExtra(EXTRA_SCOPE_KIND, "all")
                is CaptureScope.OnlyApps -> intent
                    .putExtra(EXTRA_SCOPE_KIND, SCOPE_APPS)
                    .putExtra(EXTRA_UIDS, scope.distinctUids.toIntArray())
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}