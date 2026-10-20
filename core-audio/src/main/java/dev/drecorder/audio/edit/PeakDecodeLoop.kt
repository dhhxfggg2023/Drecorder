package dev.drecorder.audio.edit

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The decode loop of the waveform subsystem, without any Android dependency.
 *
 * The loop exists as its own class because it was the place where the whole
 * feature appeared to hang: the first version fed the decoder **one** input
 * buffer and then immediately asked for an output buffer. A decoder needs a few
 * frames before it produces anything, so that output poll always timed out, and
 * the timeout was paid **per compressed frame**: a 30 minute recording is
 * ~84 000 AAC frames, and 84 000 × 10 ms of timeout is ~14 minutes of doing
 * nothing. The waveform then never appeared (DESIGN §12.4).
 *
 * The rule the loop follows instead: *never wait while there is still input to
 * give*. Input is fed with a zero timeout until the decoder stops asking for
 * more; only when there is nothing left to feed **and** nothing came out does
 * the loop wait for the decoder to produce something — and that wait is real
 * work, not a timeout.
 *
 * The numbers are in [PeakDecodeStats] so the behaviour can be asserted in a
 * unit test: a 30 minute recording must produce a handful of blocking waits,
 * not one per frame.
 */
class PeakDecodeLoop(
    private val driver: PeakCodecDriver,
    private val source: PeakSampleSource,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bucketsPerSecond: Int = PeakData.DEFAULT_BUCKETS_PER_SECOND,
    private val durationUs: Long = 0L,
    /** Called with a percentage in 0..99 as the decode proceeds. */
    private val onProgress: ((Int) -> Unit)? = null,
) {

    /** What the loop did; filled in as the decode proceeds. */
    val stats = PeakDecodeStats()

    private val codecBuffer = PeakCodecBuffer()
    private val bins = PeakBins(sampleRate, channelCount, bucketsPerSecond)
    private val pcm = ShortArray(PCM_CHUNK_FRAMES * channelCount)
    private var formatChangedTo: PeakCodecFormat? = null
    private var sawOutputEnd = false
    private var lastPercent = -1
    private var emptyWaits = 0

    /**
     * Runs the decode to the end.
     *
     * @param cancelled consulted between steps so a long decode can be stopped
     */
    fun run(cancelled: AtomicBoolean = AtomicBoolean(false)): PeakDecodeOutcome {
        var sawInputEnd = false

        while (!sawOutputEnd) {
            if (cancelled.get()) return PeakDecodeOutcome.Cancelled

            // 1. Feed the decoder until it stops asking for input. A zero
            //    timeout here is deliberate: the decoder's input queue is the
            //    only place where waiting is pointless.
            var fed = 0
            while (!sawInputEnd) {
                val index = driver.dequeueInputBuffer(0L)
                if (index < 0) break
                val buffer = driver.inputBuffer(index)
                val size = if (buffer != null) source.readSample(buffer) else -1
                if (size < 0) {
                    driver.queueEndOfStream(index)
                    sawInputEnd = true
                } else {
                    driver.queueInputBuffer(index, size, source.sampleTimeUs)
                    source.advance()
                    stats.inputBuffers++
                    fed++
                }
            }

            // 2. Take whatever the decoder has produced, without waiting.
            var produced = drainOutputs(firstTimeoutUs = 0L)
            val mismatch = takeFormatMismatch()
            if (mismatch != null) return mismatch

            // 3. Nothing to give and nothing to take: the decoder is working.
            //    This is the only place the loop waits, and the wait ends as
            //    soon as there is output.
            if (produced == 0 && fed == 0) {
                stats.blockingWaits++
                emptyWaits++
                if (emptyWaits > MAX_EMPTY_WAITS) {
                    return PeakDecodeOutcome.Stalled
                }
                produced = drainOutputs(firstTimeoutUs = BLOCKING_TIMEOUT_US)
                if (produced == 0) {
                    // Still nothing after a full timeout: the decoder is not
                    // going to answer.
                    continue
                }
                emptyWaits = 0
                val lateMismatch = takeFormatMismatch()
                if (lateMismatch != null) return lateMismatch
            } else {
                emptyWaits = 0
            }

            if (produced > 0) {
                // Progress must never go backwards: the end-of-stream buffer
                // carries no presentation time, and a bar that jumps back to
                // zero at the end looks like a restart.
                val percent = percentOf(codecBuffer.presentationTimeUs)
                if (percent > lastPercent) {
                    lastPercent = percent
                    stats.lastPercent = percent
                    onProgress?.invoke(percent)
                }
            }
        }

        return PeakDecodeOutcome.Completed(bins.finish(), stats)
    }

    /** The format the decoder changed to, if it changed and it matters. */
    private fun takeFormatMismatch(): PeakDecodeOutcome.FormatMismatch? {
        val actual = formatChangedTo ?: return null
        formatChangedTo = null
        if (actual.sampleRate == sampleRate && actual.channelCount == channelCount) return null
        return PeakDecodeOutcome.FormatMismatch(actual.sampleRate, actual.channelCount)
    }

    /**
     * Takes output buffers until the codec has none left.
     *
     * Only the **first** poll may wait; after one buffer arrived the rest of
     * the queue is drained without a timeout, so a decoder that produces many
     * buffers per wake-up is never slowed down by a timeout per buffer.
     */
    private fun drainOutputs(firstTimeoutUs: Long): Int {
        var timeoutUs = firstTimeoutUs
        var produced = 0
        while (true) {
            when (val result = driver.dequeueOutputBuffer(codecBuffer, timeoutUs)) {
                is PollResult.Ready -> {
                    val output = driver.outputBuffer(result.index)
                    if (output != null && codecBuffer.size > 0) {
                        readInto(bins, output, codecBuffer, channelCount, pcm)
                    }
                    driver.releaseOutputBuffer(result.index)
                    stats.outputBuffers++
                    produced++
                    timeoutUs = 0L
                    if (codecBuffer.endOfStream) {
                        sawOutputEnd = true
                        return produced
                    }
                }

                PollResult.FormatChanged -> {
                    formatChangedTo = driver.outputFormat()
                    timeoutUs = 0L
                }

                PollResult.None -> return produced
            }
        }
    }

    private fun percentOf(presentationTimeUs: Long): Int {
        if (durationUs <= 0L) return 0
        return ((presentationTimeUs * 100L) / durationUs).toInt().coerceIn(0, 99)
    }

    /**
     * Copies one decoded buffer into the bins.
     *
     * The decoder hands back raw PCM16 in native byte order, which on every
     * Android device is little endian — reading it as big endian would turn a
     * quiet recording into a loud one and vice versa, so the bytes are
     * converted by hand rather than through a byte-order-sensitive overload
     * that Android's `ByteBuffer` does not have.
     */
    private fun readInto(
        bins: PeakBins,
        buffer: ByteBuffer,
        info: PeakCodecBuffer,
        channelCount: Int,
        pcm: ShortArray,
    ) {
        val end = (info.offset + info.size).coerceAtMost(buffer.capacity())
        if (end <= info.offset) return
        buffer.position(info.offset)
        buffer.limit(end)

        val bytes = ByteArray(end - info.offset)
        buffer.get(bytes)

        var total = 0
        var index = 0
        while (index + 1 < bytes.size) {
            val low = bytes[index].toInt() and 0xFF
            val high = bytes[index + 1].toInt()
            pcm[total] = ((high shl 8) or low).toShort()
            total++
            index += 2
            if (total == pcm.size) {
                bins.addPcm16(pcm, total)
                total = 0
            }
        }
        if (total > 0) {
            bins.addPcm16(pcm, total)
        }
    }

    private companion object {
        const val PCM_CHUNK_FRAMES = 4_096
        const val BLOCKING_TIMEOUT_US = 10_000L

        /**
         * How many consecutive timeouts the decoder may produce before the
         * decode is called off.
         *
         * A healthy decoder answers within a few milliseconds; 200 consecutive
         * timeouts (two seconds) mean it is stuck, and looping forever would
         * hang the editor again — the very bug this loop was written to fix.
         */
        const val MAX_EMPTY_WAITS = 200
    }
}

/** What a decode run produced. */
sealed interface PeakDecodeOutcome {
    /** The recording was decoded; [data] holds the peaks. */
    data class Completed(val data: PeakData, val stats: PeakDecodeStats) : PeakDecodeOutcome

    /**
     * The decoder produced a different format than the container announced.
     * The caller has to start over with the real numbers.
     */
    data class FormatMismatch(val sampleRate: Int, val channelCount: Int) : PeakDecodeOutcome

    /** The user cancelled the decode. */
    data object Cancelled : PeakDecodeOutcome

    /**
     * The decoder stopped answering.
     *
     * Better than an endless spinner: the caller can say so and let the user
     * try again, or work with a partial waveform.
     */
    data object Stalled : PeakDecodeOutcome
}

/**
 * What one decode run did.
 *
 * [blockingWaits] is the number worth watching: it counts how often the loop
 * had to wait for the decoder. One wait per output batch is normal; one wait
 * per compressed frame is the bug this class was written to remove.
 */
class PeakDecodeStats {
    /** Compressed samples handed to the decoder. */
    var inputBuffers: Int = 0

    /** Decoded buffers taken back from the decoder. */
    var outputBuffers: Int = 0

    /** Times the loop had to wait for the decoder to produce something. */
    var blockingWaits: Int = 0

    /** The last progress percentage seen, 0..99. */
    var lastPercent: Int = 0
}
