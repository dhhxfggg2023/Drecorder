package dev.drecorder.data

import android.media.MediaMetadataRetriever
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads a recording's duration.
 *
 * WAV is parsed directly because the size is in a fixed header; `.m4a` needs
 * `MediaMetadataRetriever`, which is Android-only and therefore cannot be unit
 * tested. A failure returns 0 rather than throwing, because a file whose duration
 * is unknown should still be listed (and deletable) instead of crashing the screen.
 */
internal object DurationProbe {

    fun readDurationMs(file: File): Long = runCatching {
        if (file.name.endsWith(".wav", ignoreCase = true)) {
            readWavDurationMs(file)
        } else {
            readWithRetriever(file)
        }
    }.getOrDefault(0L)

    /** Walks the RIFF chunks to find `fmt ` (byte rate) and `data` (payload size). */
    internal fun readWavDurationMs(file: File): Long {
        RandomAccessFile(file, "r").use { handle ->
            // 12-byte RIFF/WAVE header, then a chunk list
            handle.seek(0)
            val header = ByteArray(12)
            if (handle.read(header) < 12) return 0L
            if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return 0L

            var byteRate = 0L
            var dataBytes = 0L
            var offset = 12L
            val chunkHeader = ByteArray(8)

            while (offset + 8 <= handle.length()) {
                handle.seek(offset)
                if (handle.read(chunkHeader) < 8) break
                val id = String(chunkHeader, 0, 4)
                val size = leInt(chunkHeader, 4).toLong() and 0xFFFFFFFFL
                val body = offset + 8

                when (id) {
                    "fmt " -> {
                        if (size >= 16) {
                            val fmt = ByteArray(16)
                            handle.seek(body)
                            handle.read(fmt)
                            byteRate = leInt(fmt, 8).toLong() and 0xFFFFFFFFL
                        }
                    }

                    "data" -> {
                        dataBytes = size
                        break
                    }
                }

                // Chunks are word-aligned.
                offset = body + size + (size and 1L)
            }

            if (byteRate <= 0L || dataBytes <= 0L) return 0L
            return dataBytes * 1_000L / byteRate
        }
    }

    private fun readWithRetriever(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
