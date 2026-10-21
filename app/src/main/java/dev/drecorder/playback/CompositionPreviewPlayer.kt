package dev.drecorder.playback

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Plays an edit plan back exactly as it will be exported.
 *
 * The player takes a ready-made [Composition] rather than a file, so the
 * preview and the export are the same object: what the user hears while
 * dragging is byte-for-byte the sequence that [dev.drecorder.audio.edit.EditExporter]
 * will write (DESIGN §12.5). Re-deriving the composition for the preview would
 * let the two drift apart — a preview that sounds right and an export that
 * does not is the worst possible outcome for an editing tool.
 *
 * The interface mirrors [RecordingPlayer] on purpose: the editor and the
 * library then talk to their players the same way, and the existing list
 * playback stays untouched.
 */
class CompositionPreviewPlayer(context: Context) {

    private val player: CompositionPlayer =
        CompositionPlayer.Builder(context.applicationContext).build()

    private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val finished: SharedFlow<Unit> = _finished

    private val _failed = MutableSharedFlow<Throwable>(extraBufferCapacity = 4)
    val failed: SharedFlow<Throwable> = _failed

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                _finished.tryEmit(Unit)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _failed.tryEmit(error)
        }
    }

    init {
        player.addListener(listener)
    }

    /**
     * Loads [composition] and starts playing it from [fromMs].
     *
     * Playback starts where the playhead is, not at the beginning of the file:
     * pressing play after pointing at a moment means "from here" (DESIGN
     * §12.4). The seek is issued after `prepare()` so the player has the
     * timeline to seek on, and before `play()` so the first frame is the right
     * one.
     *
     * Returns false when the composition cannot be handed to the player at all.
     */
    fun play(composition: Composition, fromMs: Long = 0L): Boolean {
        return runCatching {
            player.setComposition(composition)
            player.prepare()
            if (fromMs > 0L) player.seekTo(fromMs)
            player.play()
        }.isSuccess
    }

    fun stop() {
        runCatching {
            // CompositionPlayer has no clearComposition(); setComposition is its
            // only mutator. Stopping is enough — the next play() replaces the
            // composition anyway.
            player.stop()
        }
    }

    /** Current playback position in ms; 0 while nothing is loaded. */
    val positionMs: Long
        get() = runCatching { player.currentPosition }.getOrDefault(0L)

    /** Duration of the composition in ms; 0 until the player has prepared it. */
    val durationMs: Long
        get() = runCatching { player.duration }.getOrDefault(0L).coerceAtLeast(0L)

    /** True while the player is playing. */
    val isPlaying: Boolean
        get() = runCatching { player.isPlaying }.getOrDefault(false)

    fun seekTo(positionMs: Long) {
        runCatching { player.seekTo(positionMs.coerceAtLeast(0L)) }
    }

    fun release() {
        runCatching {
            player.removeListener(listener)
            player.release()
        }
    }
}
