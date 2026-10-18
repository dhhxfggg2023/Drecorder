package dev.drecorder.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    private val format: AudioFormatSpec,
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

    /** Every file this session writes, oldest first; more than one when segmented. */
    private val files = mutableListOf<File>()

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    /** 0f..1f meter value derived from the current analysis window. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** True once the captured stream has been silent for [silenceTimeoutMs]. */
    private val _targetSilent = MutableStateFlow(false)
    val targetSilent: StateFlow<Boolean> = _targetSilent.asStateFlow()

    private val detector = SilenceDetector(silenceTimeoutMs = silenceTimeoutMs)

    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var writer: AudioWriter? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var stopReason = StopReason.UserStop

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Quick-settings chip, screen lock, or another session taking over.
            stop(StopReason.ProjectionRevoked)
        }
    }

    fun attachProjection(projection: MediaProjection) {
        this.projection = projection
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
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
        writer = createWriter(bytesPerSecond)
        detector.reset()
        _targetSilent.value = false
        _level.value = 0f
        stopReason = StopReason.UserStop
        running = true

        worker = Thread({ pump(audioRecord) }, "drecorder-capture").apply { start() }
        _state.value = CaptureState.Recording(files.first().absolutePath, System.currentTimeMillis())
    }.onFailure { error ->
        running = false
        releaseQuietly()
        _state.value = CaptureState.Failed(error.message ?: error::class.java.simpleName)
    }

    /**
     * Stops capture and finishes the file. Safe to call twice; the second call
     * returns null.
     */
    fun stop(reason: StopReason = StopReason.UserStop): RecordingHandle? {
        val wasRunning = running
        running = false
        if (wasRunning && (stopReason == StopReason.UserStop || reason == StopReason.Error)) {
            stopReason = reason
        }

        worker?.let { thread ->
            runCatching { thread.join(WORKER_JOIN_TIMEOUT_MS) }
        }
        worker = null

        val durationMs = writer?.close() ?: 0L
        val peak = detector.peakDbfs()
        releaseQuietly()

        val handle = if ((wasRunning || durationMs > 0L) && files.isNotEmpty()) {
            RecordingHandle(
                files = files.toList(),
                durationMs = durationMs,
                stopReason = stopReason,
                peakDbfs = peak,
                everHadSignal = peak > detector.thresholdDbfs,
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

        audioRecord.startRecording()
        try {
            while (running) {
                val read = audioRecord.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    stopReason = StopReason.Error
                    break
                }
                if (read == 0) continue

                detector.addSamples(samples, read, format.sampleRate, format.channelCount)
                _level.value = levelFraction(detector.currentDbfs())
                if (detector.isSilent()) _targetSilent.value = true

                shortView.clear()
                shortView.put(samples, 0, read)
                writer?.encode(bytes, read * 2)
            }
        } catch (throwable: Throwable) {
            stopReason = StopReason.Error
        } finally {
            runCatching { audioRecord.stop() }
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
        const val WORKER_JOIN_TIMEOUT_MS = 3_000L
    }
}

/** -60 dBFS and below reads as an empty meter, 0 dBFS as full. */
internal fun levelFraction(dbfs: Float): Float = ((-dbfs) / 60f).coerceIn(0f, 1f)
