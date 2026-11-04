package dev.drecorder.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.drecorder.audio.edit.PeakBins
import dev.drecorder.audio.edit.PeakData
import dev.drecorder.audio.edit.PeakSplitter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records what other apps play, without recording the screen.
 *
 * The MediaProjection token is only ever used to build an
 * [AudioPlaybackCaptureConfiguration]; `createVirtualDisplay()` is deliberately
 * never called, so no frames are captured (docs/DESIGN.md §3.1).
 *
 * The caller owns the token and must hand it over with [attachProjection] before
 * [start]; on Android 14+ that means the mediaProjection foreground service has
 * to be running already.
 */
class ProjectionCaptureEngine(
    private val scope: CaptureScope,
    private val format: CaptureFormat,
    private val outputDirectory: File,
    encoder: EncoderSpec = EncoderSpec.Aac(format.bitRate),
    private val baseName: String,
    private val silenceTimeoutMs: Long = 8_000,
    /** Slice length; 0 writes one file for the whole session. */
    private val segmentDurationMs: Long = 0L,
) {

    /**
     * Resolved up front so the written files match the announced encoder: if no
     * AAC encoder exists on this device the session silently becomes WAV, with the
     * `.wav` extension, rather than failing or writing a broken `.m4a`.
     */
    private val resolvedEncoder: EncoderSpec =
        if (encoder.isAvailableOnDevice()) encoder else EncoderSpec.Wav

    /**
     * Every file this session writes, oldest first; more than one when segmented.
     *
     * Written by the capture thread (a segment rotation opens the next file) and
     * read by whichever thread stops the session, so it is synchronized rather
     * than plain: an unsynchronized [MutableList] shared that way is a data race,
     * and reading it while the capture thread appends can throw from [first].
     */
    private val files: MutableList<File> = Collections.synchronizedList(mutableListOf())

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    /** 0f..1f meter value derived from the current analysis window. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** True once the captured stream has been silent for [silenceTimeoutMs]. */
    private val _targetSilent = MutableStateFlow(false)
    val targetSilent: StateFlow<Boolean> = _targetSilent.asStateFlow()

    private val detector = SilenceDetector(silenceTimeoutMs = silenceTimeoutMs)

    /**
     * The waveform of this session, computed while the audio is captured.
     *
     * The captured PCM is already in memory as it is written to the file, so
     * bucketing it costs almost nothing — and it saves the editor from having
     * to decode the whole recording again when the user opens it (DESIGN
     * §12.4). Without this, a 30 minute recording took minutes to draw.
     */
    private val peakBins = PeakBins(
        sampleRate = format.sampleRate,
        channelCount = format.channelCount,
        bucketsPerSecond = PeakData.DEFAULT_BUCKETS_PER_SECOND,
    )

    /** How long each finished file was, in order. Written by the capture thread. */
    private val segmentDurationsMs: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var writer: AudioWriter? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    /**
     * Set by whichever caller gets to end this session first.
     *
     * The user's stop, the projection being revoked, and the capture thread
     * failing are three independent events that all end the same session. Only
     * one of them may close the writer and publish a finished result; the others
     * must return without touching anything.
     */
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var stopReason = StopReason.UserStop

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Quick-settings chip, screen lock, or another session taking over.
            //
            // This callback is delivered on the main looper, and finishing a
            // session joins the capture thread and flushes the encoder — several
            // seconds. Doing that here froze the UI and ended in an ANR the
            // moment the user locked the screen, so the teardown is handed to its
            // own thread and the callback returns immediately.
            stopAsync(StopReason.ProjectionRevoked)
        }
    }

    fun attachProjection(projection: MediaProjection) {
        this.projection = projection
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
    }

    /**
     * Ends the session from a thread that must not block.
     *
     * Used by the projection callback, which Android delivers on the main
     * looper. Everything [stop] does — joining the capture thread, draining the
     * encoder, closing the muxer — belongs off the UI thread, so it is moved to
     * a short-lived thread of its own.
     */
    fun stopAsync(reason: StopReason) {
        Thread({ stop(reason) }, "drecorder-stop").start()
    }

    fun start(): Result<Unit> = runCatching {
        val mediaProjection = projection ?: error("attachProjection() must be called first")
        _state.value = CaptureState.Preparing

        val channelMask =
            if (format.channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(format.sampleRate)
            .setChannelMask(channelMask)
            .build()

        val minBufferBytes =
            AudioRecord.getMinBufferSize(format.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minBufferBytes > 0) { "AudioRecord.getMinBufferSize() returned $minBufferBytes" }

        val bytesPerSecond = format.sampleRate * format.channelCount * 2
        val bufferBytes = maxOf(minBufferBytes * 2, bytesPerSecond / 5)

        val audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(buildCaptureConfig(mediaProjection))
            .build()

        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialise playback capture"
        }

        recorder = audioRecord
        files.clear()
        segmentDurationsMs.clear()
        writer = createWriter(bytesPerSecond)
        detector.reset()
        _targetSilent.value = false
        _level.value = 0f
        stopReason = StopReason.UserStop
        stopRequested.set(false)
        running = true

        worker = Thread({ pump(audioRecord) }, "drecorder-capture").apply { start() }
        _state.value = CaptureState.Recording(files.first().absolutePath, System.currentTimeMillis())
    }.onFailure { error ->
        running = false
        // A writer that was created but never fed has already created its file;
        // left behind it is an empty recording in the library. It also has to be
        // closed, or the encoder and its file descriptor stay allocated.
        writer?.let { runCatching { it.close() } }
        writer = null
        finishFailedFiles()
        releaseQuietly()
        _state.value = CaptureState.Failed(error.message ?: error::class.java.simpleName)
    }

    /** Deletes every file this (never started) session created. */
    private fun finishFailedFiles() {
        files.forEach { file -> runCatching { file.delete() } }
        files.clear()
        segmentDurationsMs.clear()
    }

    /**
     * Stops capture and finishes the file. Safe to call twice; the second call
     * returns null.
     */
    fun stop(reason: StopReason = StopReason.UserStop): RecordingHandle? {
        // The revocation callback, the user's stop, and the capture thread failing
        // can all arrive; only the first one gets to finish the file. The rest
        // return null without writing markers, peaks and a Finished state again
        // (the writer is already closed by then).
        if (!stopRequested.compareAndSet(false, true)) return null
        val wasRunning = running
        running = false
        if (wasRunning && (stopReason == StopReason.UserStop || reason == StopReason.Error)) {
            stopReason = reason
        }
        // A failure that broke out of the capture loop leaves the recorder in an
        // undefined state; stopping it here keeps the join below bounded.
        if (!wasRunning) runCatching { recorder?.stop() }

        // A blocking read cannot see [running] turn false, so the join can time out
        // while the pump is still inside read()/encode(). Stopping the recorder
        // bounces that read; closing the writer underneath it would corrupt the file.
        worker?.let { thread ->
            runCatching { thread.join(WORKER_JOIN_TIMEOUT_MS) }
            if (thread.isAlive) {
                runCatching { recorder?.stop() }
                runCatching { thread.join(WORKER_BOUNCE_TIMEOUT_MS) }
            }
        }
        worker = null

        // Taken out of the pump's reach before it is closed: an encode() after
        // close() throws inside the worker thread.
        val closing = writer
        writer = null
        val durationMs = closing?.close() ?: 0L
        val peak = detector.peakDbfs()
        releaseQuietly()

        val handle = if ((wasRunning || durationMs > 0L) && files.isNotEmpty()) {
            // The waveform of the whole session, split into the files it was
            // written to. Computing it here means the editor never has to
            // decode the recording again (DESIGN §12.4).
            val durations = if (segmentDurationsMs.isEmpty()) {
                listOf(durationMs)
            } else {
                segmentDurationsMs.toList()
            }
            val peaks = PeakSplitter.split(peakBins.finish(), durations)
            RecordingHandle(
                files = files.toList(),
                durationMs = durationMs,
                stopReason = stopReason,
                peakDbfs = peak,
                everHadSignal = peak > detector.thresholdDbfs,
                peaks = peaks,
            )
        } else {
            null
        }

        if (handle != null) {
            _state.value = CaptureState.Finished(
                outputPath = handle.file.absolutePath,
                durationMs = durationMs,
                reason = stopReason,
                everHadSignal = handle.everHadSignal,
                segmentCount = handle.files.size,
                // The service completes the session from this state, so a
                // revocation (which ends the session inside this class, not in
                // the service) writes markers and peaks like a user stop.
                handle = handle,
            )
        }
        return handle
    }

    /**
     * One writer for an unsegmented session, or a [SegmentingWriter] that opens the
     * next slice when the current one is full. Each file is registered in [files]
     * only after its writer exists, so a failed rotation cannot advertise a file
     * that was never written.
     */
    private fun createWriter(bytesPerSecond: Int): AudioWriter {
        val open: (Int) -> AudioWriter = { index ->
            val file = if (segmentDurationMs > 0L) {
                resolvedEncoder.outputFileForSegment(outputDirectory, baseName, index + 1)
            } else {
                resolvedEncoder.outputFileFor(outputDirectory, baseName)
            }
            val created = requireNotNull(
                resolvedEncoder.createWriter(file, format.sampleRate, format.channelCount),
            ) { "failed to create a writer for $resolvedEncoder" }
            files += file
            created
        }

        if (segmentDurationMs <= 0L) return open(0)
        return SegmentingWriter(
            segmentBytes = segmentDurationMs * bytesPerSecond / 1_000L,
            bytesPerSecond = bytesPerSecond.toLong(),
            open = open,
            onSegmentClosed = { _, durationMs -> segmentDurationsMs += durationMs },
        )
    }

    private fun buildCaptureConfig(mediaProjection: MediaProjection): AudioPlaybackCaptureConfiguration =
        AudioPlaybackCaptureConfiguration.Builder(mediaProjection).apply {
            when (scope) {
                is CaptureScope.AllMedia -> {
                    // addMatchingUsage and excludeUsage are mutually exclusive.
                    addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    addMatchingUsage(AudioAttributes.USAGE_GAME)
                    addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                }

                is CaptureScope.OnlyApps -> {
                    // addMatchingUid and excludeUid are mutually exclusive.
                    scope.distinctUids.forEach { addMatchingUid(it) }
                }
            }
        }.build()

    private fun pump(audioRecord: AudioRecord) {
        val framesPerRead = format.sampleRate / 50 // 20 ms
        val samples = ShortArray(framesPerRead * format.channelCount)
        val bytes = ByteArray(samples.size * 2)
        val shortView = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        try {
            // Inside the try: a token revoked between start() and here makes this
            // throw, and an exception escaping a bare Thread kills the process.
            audioRecord.startRecording()
            while (running) {
                val read = audioRecord.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    stopReason = StopReason.Error
                    break
                }
                if (read == 0) continue

                detector.addSamples(samples, read, format.sampleRate, format.channelCount)
                _level.value = levelFraction(detector.currentDbfs())
                // Both ways: a source that starts playing again after a silent
                // stretch has to lose the warning, not keep it for the session.
                _targetSilent.value = detector.isSilent()

                // The same samples that go into the file also fill the
                // waveform's buckets.
                peakBins.addPcm16(samples, read)

                shortView.clear()
                shortView.put(samples, 0, read)
                writer?.encode(bytes, read * 2)
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "capture loop stopped", throwable)
        } finally {
            runCatching { audioRecord.stop() }
            // Every exit from this loop ends the session. Leaving it without a
            // Finished state meant a failed capture kept the UI saying
            // 「正在录制」 for ever and left an .m4a with no moov atom on disk:
            // the file is only playable because somebody closes the muxer.
            //
            // A user stop sets [running] false first and does the teardown
            // itself; [stopRequested] then already belongs to it and this call
            // returns immediately. A failure breaks out with [running] still
            // true, which is the case this exists for.
            if (running) {
                stopReason = StopReason.Error
                stopAsync(StopReason.Error)
            }
        }
    }

    private fun releaseQuietly() {
        runCatching { recorder?.release() }
        recorder = null
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        projection = null
    }

    private companion object {
        const val TAG = "DrecorderEngine"

        const val WORKER_JOIN_TIMEOUT_MS = 3_000L

        /** How long the bounced read gets to return after the recorder is stopped. */
        const val WORKER_BOUNCE_TIMEOUT_MS = 1_000L
    }
}

/** -60 dBFS and below reads as an empty meter, 0 dBFS as full. */
internal fun levelFraction(dbfs: Float): Float = ((-dbfs) / 60f).coerceIn(0f, 1f)