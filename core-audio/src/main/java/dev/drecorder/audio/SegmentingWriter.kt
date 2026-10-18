package dev.drecorder.audio

/**
 * Splits one continuous capture across several writer files.
 *
 * A two-hour session that is a single file is a single point of failure: one
 * unreadable container and the whole recording is gone (`docs/DESIGN.md` M4,
 * "segment every 30 minutes"). Rotation is measured in PCM bytes rather than
 * wall-clock time, because the capture format fixes bytes per second exactly —
 * no timer thread, no drift, and the boundary lands on a frame the encoder can
 * still close cleanly.
 *
 * Rotation happens between reads, so no sample is lost or duplicated: the read
 * that crosses the boundary is written to the closing file whole, and the next
 * read opens the following one.
 *
 * @param segmentBytes size of one slice; 0 disables rotation entirely.
 * @param bytesPerSecond PCM bytes per second of the capture format, used to report
 *   each slice's duration.
 * @param open creates a writer for the segment at [open]'s index (0-based).
 * @param onSegmentClosed called once per finished slice with its index and duration.
 */
class SegmentingWriter(
    private val segmentBytes: Long,
    private val bytesPerSecond: Long,
    private val open: (index: Int) -> AudioWriter,
    private val onSegmentClosed: (index: Int, durationMs: Long) -> Unit = { _, _ -> },
) : AudioWriter {

    private var index = 0
    private var current: AudioWriter = open(0)
    private var segmentOpen = true
    private var segmentBytesWritten = 0L
    private var totalBytesWritten = 0L
    private var closed = false

    override fun encode(pcm: ByteArray, size: Int) {
        check(!closed) { "writer already closed" }
        if (size <= 0) return

        current.encode(pcm, size)
        segmentBytesWritten += size
        totalBytesWritten += size

        if (segmentBytes > 0 && segmentBytesWritten >= segmentBytes) {
            rotate()
        }
    }

    /** Total audio duration of every slice written so far. */
    override fun close(): Long {
        if (!closed) {
            closed = true
            closeSegment()
        }
        return millis(totalBytesWritten)
    }

    private fun rotate() {
        closeSegment()
        index += 1
        // If opening the next slice fails, the exception propagates to the caller,
        // which stops capture; the slices already closed remain playable.
        current = open(index)
        segmentOpen = true
    }

    private fun closeSegment() {
        if (!segmentOpen) return
        segmentOpen = false
        current.close()
        onSegmentClosed(index, millis(segmentBytesWritten))
        segmentBytesWritten = 0L
    }

    private fun millis(bytes: Long): Long =
        if (bytesPerSecond <= 0) 0L else bytes * 1_000L / bytesPerSecond
}
