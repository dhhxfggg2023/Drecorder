package dev.drecorder.audio.edit

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * How an export ended.
 *
 * [Success.transmuxed] is not decoration: it is the direct answer to "did the
 * transformer re-encode the audio", which media3 reports itself instead of us
 * having to infer it from how long the export took.
 */
sealed interface EditExportResult {

    data class Success(
        val file: File,
        val durationMs: Long,
        val fileSizeBytes: Long,
        val transmuxed: Boolean,
    ) : EditExportResult

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : EditExportResult
}

/**
 * Writes an [EditPlan] out as a new recording.
 *
 * The transformer runs on its own thread so a long export never blocks the UI,
 * and the result is written to a temporary file that is only renamed into place
 * once the export succeeded — a cancelled or failed export therefore never leaves
 * a half-written file in the library (DESIGN §12.3).
 */
class EditExporter(private val context: Context) {

    /**
     * Exports [plan] next to its first source.
     *
     * @param destination the file the result replaces, or `null` to write a new
     *   recording beside the source. A replacement is written to a temporary
     *   file first and only renamed over the original once the export has
     *   succeeded: the transformer is reading from that very file, so writing
     *   into it directly would truncate the audio it is still decoding, and a
     *   failed overwrite would leave the user with no recording at all.
     * @param onProgress receives a percentage in 0..100 while the export runs.
     */
    suspend fun export(
        plan: EditPlan,
        destination: File? = null,
        onProgress: ((Int) -> Unit)? = null,
    ): EditExportResult {
        if (plan.isEmpty) {
            return EditExportResult.Failure("至少保留一段音频")
        }

        val source = plan.sources.first().file
        if (!source.isFile) {
            return EditExportResult.Failure("源文件已不存在")
        }

        // Everything up to the transformer runs on the caller's thread, and the
        // caller is a screen: a plan media3 refuses to compile, a directory that
        // vanished, a name that cannot be derived — each of those used to throw
        // straight out of the coroutine and take the app with it. An export that
        // cannot start is a failure to report, not a crash (m00001).
        var temporary: File? = null
        var committed = false
        return try {
            val output = destination ?: EditOutputNaming.outputFileFor(source)
            val temp = EditOutputNaming.temporaryFileFor(output)
            temporary = temp
            runCatching { temp.delete() }

            val composition = EditComposition.build(plan)

            val result = withContext(Dispatchers.IO) {
                runTransformer(composition, temp, onProgress)
            }

            when (result) {
                is TransformOutcome.Failed -> EditExportResult.Failure(result.message, result.cause)

                is TransformOutcome.Succeeded -> {
                    // The rename is the commit: until it happens the library cannot see
                    // the file, so an interrupted export leaves nothing behind.
                    val renamed = runCatching {
                        if (!temp.renameTo(output)) {
                            temp.copyTo(output, overwrite = true)
                            temp.delete()
                        }
                    }.isSuccess
                    if (!renamed) {
                        EditExportResult.Failure("导出文件无法重命名")
                    } else {
                        committed = true
                        EditExportResult.Success(
                            file = output,
                            durationMs = result.durationMs,
                            fileSizeBytes = output.length(),
                            transmuxed = result.transmuxed,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            EditExportResult.Failure(failure.message ?: failure.javaClass.simpleName, failure)
        } finally {
            // Whatever ended the export — success, failure, or the screen being
            // torn down mid-run — an uncommitted temporary is not left on disk.
            // It carries a recording extension, so leaving it there would put a
            // half-written recording in the library.
            if (!committed) temporary?.let { runCatching { it.delete() } }
        }
    }

    private sealed interface TransformOutcome {
        data class Succeeded(
            val durationMs: Long,
            val transmuxed: Boolean,
        ) : TransformOutcome

        data class Failed(
            val message: String,
            val cause: Throwable?,
        ) : TransformOutcome
    }

    /**
     * Runs the transformer on a dedicated thread and waits for its callback.
     *
     * The transformer does its work on the looper it was given, so it gets its own
     * thread rather than the caller's — an export of a long recording must not
     * compete with the UI for the main thread.
     */
    private suspend fun runTransformer(
        composition: androidx.media3.transformer.Composition,
        output: File,
        onProgress: ((Int) -> Unit)?,
    ): TransformOutcome {
        val thread = HandlerThread("drecorder-export").apply { start() }
        val looper = thread.looper
        val handler = Handler(looper)

        return try {
            suspendCancellableCoroutine { continuation ->
                val holder = ProgressHolder()
                val transformer = Transformer.Builder(context)
                    .setLooper(looper)
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(
                                composition: androidx.media3.transformer.Composition,
                                exportResult: ExportResult,
                            ) {
                                continuation.resumeSafely(
                                    TransformOutcome.Succeeded(
                                        // durationMs is deprecated in favour of this one.
                                        durationMs = exportResult.approximateDurationMs,
                                        transmuxed = exportResult.audioConversionProcess ==
                                            ExportResult.CONVERSION_PROCESS_TRANSMUXED,
                                    ),
                                )
                            }

                            override fun onError(
                                composition: androidx.media3.transformer.Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                continuation.resumeSafely(
                                    TransformOutcome.Failed(
                                        message = exportException.errorCodeName,
                                        cause = exportException,
                                    ),
                                )
                            }
                        },
                    )
                    .build()

                // The transformer may only be touched on the looper it was built
                // with. Cancelling it from the thread that cancelled the coroutine
                // is what crashed the app with "Transformer is accessed on the
                // wrong thread" (2026-10-22, m00001).
                continuation.invokeOnCancellation {
                    // Posted, not called: this runs on whichever thread cancelled
                    // the coroutine. The export thread is left to drain its queue
                    // (quitSafely), so the cancel still happens, just there.
                    handler.post { runCatching { transformer.cancel() } }
                }

                // The progress poll belongs on the same looper for the same
                // reason: read from anywhere else it throws, and the exception was
                // being swallowed into a progress bar that never moved.
                if (onProgress != null) {
                    val ticker = object : Runnable {
                        override fun run() {
                            if (!continuation.isActive) return
                            val state = runCatching { transformer.getProgress(holder) }
                                .getOrDefault(Transformer.PROGRESS_STATE_UNAVAILABLE)
                            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                                onProgress(holder.progress / 100)
                            }
                            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
                        }
                    }
                    handler.postDelayed(ticker, PROGRESS_INTERVAL_MS)
                }

                handler.post {
                    if (continuation.isActive) {
                        runCatching { transformer.start(composition, output.absolutePath) }
                            .onFailure { failure ->
                                continuation.resumeSafely(
                                    TransformOutcome.Failed(
                                        message = failure.message ?: "导出启动失败",
                                        cause = failure,
                                    ),
                                )
                            }
                    }
                }
            }
        } finally {
            thread.quitSafely()
        }
    }

    private fun CancellableContinuation<TransformOutcome>.resumeSafely(value: TransformOutcome) {
        if (isActive) resume(value)
    }

    private companion object {
        /** How often the transformer is asked how far it has got, in ms. */
        const val PROGRESS_INTERVAL_MS = 200L
    }
}
