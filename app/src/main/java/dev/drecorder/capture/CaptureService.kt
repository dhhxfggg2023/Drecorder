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
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import dev.drecorder.MainActivity
import dev.drecorder.R
import dev.drecorder.audio.AudioFormatSpec
import dev.drecorder.audio.CaptureScope
import dev.drecorder.audio.ProjectionCaptureEngine
import dev.drecorder.audio.StopReason
import dev.drecorder.data.SilentMarker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
        engine?.stop(StopReason.UserStop)
        engine = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startCapture(intent: Intent) {
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

        if (resultData == null) {
            CaptureSession.fail(getString(R.string.error_missing_projection))
            shutdown()
            return
        }

        val projection = getSystemService(MediaProjectionManager::class.java)
            ?.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            CaptureSession.fail(getString(R.string.error_projection_null))
            shutdown()
            return
        }

        val scope = scopeFrom(intent)
        val preferences = RecordSettings.load(this)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val capture = ProjectionCaptureEngine(
            scope = scope,
            format = AudioFormatSpec(
                sampleRate = preferences.sampleRate,
                channelCount = preferences.channelCount,
                bitRate = preferences.bitRate,
            ),
            outputDirectory = outputDirectory(),
            encoder = preferences.encoder,
            baseName = "drecorder-$stamp",
            segmentDurationMs = preferences.segmentDurationMs,
        )
        capture.attachProjection(projection)

        val started = capture.start()
        if (started.isFailure) {
            CaptureSession.fail(started.exceptionOrNull()?.message ?: "capture failed to start")
            shutdown()
            return
        }

        engine = capture
        serviceScope.launch { capture.state.collect(CaptureSession::onState) }
        serviceScope.launch { capture.level.collect(CaptureSession::onLevel) }
        serviceScope.launch { capture.targetSilent.collect(CaptureSession::onTargetSilent) }
    }

    private fun finishSession(reason: StopReason) {
        val handle = engine?.stop(reason)
        engine = null
        if (handle != null) {
            // A blocked source yields a valid but silent file; the sidecar marker is
            // the only thing that lets the library say so (core-data SilentMarker).
            // Every slice of a segmented session gets one.
            val marker = SilentMarker(this)
            handle.files.forEach { file -> marker.write(file.name, wasSilent = !handle.everHadSignal) }
        }
        CaptureSession.onFinished(handle)
        shutdown()
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
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stopCapture)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_START = "dev.drecorder.action.START"
        private const val ACTION_STOP = "dev.drecorder.action.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_SCOPE_KIND = "scopeKind"
        private const val EXTRA_UIDS = "uids"
        private const val SCOPE_APPS = "apps"

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
