package dev.drecorder.waveforms

import android.content.Context
import dev.drecorder.audio.edit.PeakDecoder
import dev.drecorder.audio.edit.PeaksCache
import dev.drecorder.capture.RecordSettings
import dev.drecorder.data.DerivedMarker
import dev.drecorder.data.RecordingLibrary
import dev.drecorder.data.SilentMarker
import dev.drecorder.ui.library.defaultOutputDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Computes the cached waveform of every recording that has none, quietly, in
 * the background.
 *
 * Recordings made by this app already carry their waveform — the capture
 * engine buckets the PCM while it writes the audio (DESIGN §12.4). What has
 * always been slow is a recording that arrived from outside: an import, an
 * older build whose cache was invalidated, or an export made before the editor
 * started writing the derived cache. Those are decoded in full when the editor
 * opens them, which is the "波形刚进去没加载完" the user saw.
 *
 * One decode at a time on Dispatchers.Default, so it never competes with the
 * UI or a recording session. It is a process-wide singleton with no lifecycle:
 * a decode killed with the process is simply missing a cache again, and the
 * next cold start picks it up. Only a rebuildable cache is written — never a
 * source of truth.
 */
object WaveformPrecompute {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = LinkedHashSet<File>()
    private val mutex = Mutex()
    private var worker: Job? = null
    private var currentCancel: AtomicBoolean? = null
    private var appContext: Context? = null

    /** True while a capture session runs; the decode yields so recording wins. */
    @Volatile
    private var captureActive = false

    /** Called by the capture session when it starts and stops. */
    fun setCaptureActive(active: Boolean) {
        captureActive = active
        if (active) {
            // The decoder polls this between chunks, so the in-flight decode
            // stops at its next boundary instead of fighting the recorder for
            // a MediaCodec slot.
            currentCancel?.set(true)
            worker?.cancel()
            worker = null
        } else {
            // A recording finished: whatever was queued while it ran (or was
            // put back after the interrupted decode) has to resume now, rather
            // than waiting for the next cold start.
            appContext?.let { startWorker(it) }
        }
    }

    /**
     * Queues the recordings in the library that have no usable cache.
     *
     * Safe on every cold start and after every import: the set de-dupes, and an
     * existing cache is never decoded again.
     */
    suspend fun enqueueMissing(context: Context) {
        val app = context.applicationContext
        appContext = app
        val directory = withContext(Dispatchers.IO) {
            RecordSettings.outputDirectory(app)?.let(::File) ?: defaultOutputDirectory(app)
        } ?: return
        val library = RecordingLibrary(
            directoryProvider = { directory },
            marker = SilentMarker(app),
            derivedMarker = DerivedMarker(app),
            cacheHooks = PeaksCacheHooks(app.filesDir),
        )
        val needing = withContext(Dispatchers.IO) {
            library.recordings().map { it.file }.filter { file ->
                PeaksCache.read(PeaksCache.fileFor(file, app.filesDir), file) == null
            }
        }
        if (needing.isEmpty()) return
        mutex.withLock { pending.addAll(needing) }
        startWorker(app)
    }

    /** Queues one file immediately — used right after an import or an export. */
    suspend fun enqueue(context: Context, file: File) {
        val app = context.applicationContext
        appContext = app
        mutex.withLock { pending.add(file) }
        startWorker(app)
    }

    private fun startWorker(app: Context) {
        if (captureActive) return
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                if (captureActive) break
                val next = mutex.withLock {
                    val iterator = pending.iterator()
                    if (iterator.hasNext()) {
                        val target = iterator.next()
                        iterator.remove()
                        target
                    } else {
                        null
                    }
                } ?: break
                // A file with a recording extension but unreadable audio
                // must not end the loop: every other queued file still needs
                // its cache. The failure is disposable — the editor falls
                // back to progressive decoding for this one file.
                runCatching { decodeOne(app, next) }
                    .onFailure { currentCancel = null }
                // Yield between files: a long queue must never own the CPU.
                delay(150L)
            }
        }
    }

    private suspend fun decodeOne(app: Context, file: File) {
        if (!file.isFile) return
        val cancelled = AtomicBoolean(false)
        currentCancel = cancelled
        val result = withContext(Dispatchers.IO) {
            PeakDecoder().decode(file = file, cancelled = cancelled)
        }
        currentCancel = null
        if (result is PeakDecoder.Result.Success && !cancelled.get()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    PeaksCache.write(PeaksCache.fileFor(file, app.filesDir), result.data, file)
                }
            }
        } else if (cancelled.get()) {
            // Put it back: a recording that interrupted this decode should not
            // lose its place in the queue.
            mutex.withLock { pending.add(file) }
        }
    }
}
