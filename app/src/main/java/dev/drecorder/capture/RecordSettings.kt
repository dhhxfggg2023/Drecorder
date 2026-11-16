package dev.drecorder.capture

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.drecorder.audio.EncoderSpec

/**
 * Recording settings, persisted in plain [SharedPreferences].
 *
 * Deliberately not Room or DataStore: these are a handful of scalars read once
 * per capture session, and the service must be able to read them without pulling
 * in a database or a coroutine scope.
 */
data class RecordSettings(
    val sampleRate: Int,
    val channelCount: Int,
    val bitRate: Int,
    val encoder: EncoderSpec,
    /** Slice length of one recording in minutes; 0 writes a single file. */
    val segmentMinutes: Int,
    /** True when only [appUids] should be captured instead of every media stream. */
    val appsOnly: Boolean,
    val appUids: List<Int>,
) {

    val isWav: Boolean get() = encoder is EncoderSpec.Wav

    /**
     * How much disk one minute of this configuration costs, in bytes.
     *
     * The home screen shows it so the user can tell whether the quality they
     * picked is worth it *before* recording, instead of after: 320 kbps stereo is
     * ~2.3 MB/min, an hour is ~140 MB, and that is not obvious from a bitrate
     * number.
     */
    val estimatedBytesPerMinute: Long
        get() = bytesPerMinute(sampleRate, channelCount, bitRate, isWav)

    val segmentDurationMs: Long get() = segmentMinutes * 60_000L

    /** The filter the capture service should apply right now. */
    fun captureScope(): dev.drecorder.audio.CaptureScope =
        if (appsOnly && appUids.isNotEmpty()) {
            dev.drecorder.audio.CaptureScope.OnlyApps(appUids)
        } else {
            dev.drecorder.audio.CaptureScope.AllMedia
        }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_SAMPLE_RATE = "sampleRate"
        private const val KEY_CHANNEL_COUNT = "channelCount"
        private const val KEY_BIT_RATE = "bitRate"
        private const val KEY_FORMAT = "format"
        private const val KEY_OUTPUT_DIR = "outputDir"
        private const val KEY_SEGMENT_MINUTES = "segmentMinutes"
        private const val KEY_APPS_ONLY = "appsOnly"
        private const val KEY_APP_UIDS = "appUids"

        /** Slice lengths offered in settings; 0 means "one file, however long". */
        val SEGMENT_CHOICES = listOf(0, 5, 15, 30, 60)

        /**
         * Bytes per minute of audio for one configuration.
         *
         * AAC is a constant bitrate, so the arithmetic is exact; WAV is 16-bit
         * PCM, so it follows from the sample rate and the channel count (and is
         * why switching to WAV multiplies the estimate by six).
         */
        fun bytesPerMinute(sampleRate: Int, channelCount: Int, bitRate: Int, isWav: Boolean): Long =
            if (isWav) {
                sampleRate.toLong() * channelCount * 2L * 60L
            } else {
                bitRate.toLong() / 8L * 60L
            }

        val DEFAULT = RecordSettings(
            sampleRate = 48_000,
            channelCount = 2,
            bitRate = 192_000,
            encoder = EncoderSpec.Aac(192_000),
            segmentMinutes = 30,
            appsOnly = false,
            appUids = emptyList(),
        )

        fun load(context: Context): RecordSettings {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val defaults = DEFAULT
            val sampleRate = prefs.getInt(KEY_SAMPLE_RATE, defaults.sampleRate)
            val bitRate = prefs.getInt(KEY_BIT_RATE, defaults.bitRate)
            val format = prefs.getString(KEY_FORMAT, "aac") ?: "aac"

            return RecordSettings(
                sampleRate = if (sampleRate == 44_100) 44_100 else 48_000,
                channelCount = prefs.getInt(KEY_CHANNEL_COUNT, defaults.channelCount)
                    // CaptureFormat rejects anything but mono/stereo, and it
                    // does so on the main thread inside the service: a stored
                    // value from an older build must not become a crash.
                    .takeIf { it == 1 || it == 2 } ?: defaults.channelCount,
                // A changed AAC bit rate has to be reflected in the encoder spec too,
                // otherwise the UI slider would silently do nothing.
                bitRate = bitRate,
                encoder = if (format == "wav") EncoderSpec.Wav else EncoderSpec.Aac(bitRate),
                segmentMinutes = prefs.getInt(KEY_SEGMENT_MINUTES, defaults.segmentMinutes)
                    .takeIf { it in SEGMENT_CHOICES } ?: defaults.segmentMinutes,
                appsOnly = prefs.getBoolean(KEY_APPS_ONLY, false),
                appUids = prefs.getString(KEY_APP_UIDS, "")
                    .orEmpty()
                    .split(',')
                    .mapNotNull { it.trim().toIntOrNull() },
            )
        }

        fun store(
            context: Context,
            sampleRate: Int,
            channelCount: Int,
            bitRate: Int,
            format: String,
        ) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit {
                putInt(KEY_SAMPLE_RATE, sampleRate)
                putInt(KEY_CHANNEL_COUNT, channelCount)
                putInt(KEY_BIT_RATE, bitRate)
                putString(KEY_FORMAT, format)
            }
        }

        /** Slice length in minutes; 0 keeps one file per session. */
        fun storeSegmentMinutes(context: Context, minutes: Int) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit { putInt(KEY_SEGMENT_MINUTES, minutes) }
        }

        /** The capture range: every media stream, or exactly [uids]. */
        fun storeRange(context: Context, appsOnly: Boolean, uids: List<Int>) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit {
                putBoolean(KEY_APPS_ONLY, appsOnly && uids.isNotEmpty())
                putString(KEY_APP_UIDS, uids.joinToString(","))
            }
        }

        fun storeOutputDirectory(context: Context, path: String) {            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit { putString(KEY_OUTPUT_DIR, path) }
        }

        fun outputDirectory(context: Context): String? =
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_OUTPUT_DIR, null)
    }
}