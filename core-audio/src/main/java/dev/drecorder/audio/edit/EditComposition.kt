package dev.drecorder.audio.edit

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence

/**
 * Compiles an [EditPlan] into the media3 [Composition] that both the exporter and
 * the preview player consume.
 *
 * One plan is one ordered list of clips, so trimming and merging are the same
 * operation here: a merge is a plan with more than one source, a trim is a plan
 * with one source and a hole in its keep list. The UI therefore never needs two
 * code paths (DESIGN §12.2).
 */
object EditComposition {

    /**
     * Builds the composition for [plan].
     *
     * `setTransmuxAudio(true)` is the switch that keeps this lossless: without it
     * media3 transcodes every track as soon as a composition holds more than one
     * media item, which would cost a generation of audio quality and minutes of
     * CPU on a long recording (DESIGN §12.1).
     */
    fun build(plan: EditPlan): Composition {
        val items = plan.sources.flatMap { source ->
            source.keep.map { range -> clip(source, range) }
        }
        val sequence = EditedMediaItemSequence.withAudioFrom(items)
        return Composition.Builder(sequence)
            .setTransmuxAudio(true)
            .build()
    }

    private fun clip(source: SourceSpan, range: KeepRange): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(source.file))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(range.startMs)
                    .setEndPositionMs(range.endMs)
                    .build(),
            )
            .build()

        return EditedMediaItem.Builder(mediaItem)
            // The composition player requires an explicit duration on every audio
            // item, and setting it to the kept length keeps the preview and the
            // export describing the same thing.
            .setDurationUs(range.durationMs * 1_000)
            .build()
    }
}
