package dev.drecorder.audio.edit

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

        val output = destination ?: EditOutputNaming.outputFileFor(source)
        val temporary = EditOutputNaming.temporaryFileFor(output)
        runCatching { temporary.delete() }

        val composition = EditComposition.build(plan)

        val result = withContext(Dispatchers.IO) {
            runTransformer(composition, temporary, onProgress)
        }

        return when (result) {
            is TransformOutcome.Failed -> {
                runCatching { temporary.delete() }
                EditExportResult.Failure(result.message, result.cause)
            }

            is TransformOutcome.Succeeded -> {
                // The rename is the commit: until it happens the library cannot see
                // the file, so an interrupted export leaves nothing behind.
                val renamed = runCatching {
                    if (!temporary.renameTo(output)) {
                        temporary.copyTo(output, overwrite = true)
                        temporary.delete()
                    }
                }.isSuccess
                if (!renamed) {
                    runCatching { temporary.delete() }
                    EditExportResult.Failure("导出文件无法重命名")
                } else {
                    EditExportResult.Success(
                        file = output,
                        durationMs = result.durationMs,
                        fileSizeBytes = output.length(),
                        transmuxed = result.transmuxed,
                    )
                }
            }
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

                continuation.invokeOnCancellation { transformer.cancel() }

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

                if (onProgress != null) {
                    Thread {
                        while (continuation.isActive) {
                            val state = runCatching { transformer.getProgress(holder) }.getOrDefault(0)
                            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                                onProgress(holder.progress / 100)
                            }
                            Thread.sleep(200)
                        }
                    }.apply { isDaemon = true }.start()
                }
            }
        } finally {
            thread.quitSafely()
        }
    }

    private fun CancellableContinuation<TransformOutcome>.resumeSafely(value: TransformOutcome) {
        if (isActive) resume(value)
    }
}
