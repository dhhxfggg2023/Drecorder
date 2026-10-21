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

    /**
     * Where the seek asked for by [play] has not been applied yet, in ms.
     *
     * A seek issued right after `prepare()` is a seek into a timeline the
     * player has not built yet: `CompositionPlayer` accepts it, then drops it
     * when the composition is prepared, and playback starts from zero. The
     * user pressed play *at a moment*, so the seek is held here and applied
     * once the player reports it is ready (DESIGN §12.4).
     */
    private var pendingSeekMs: Long? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    val pending = pendingSeekMs
                    if (pending != null) {
                        pendingSeekMs = null
                        runCatching { player.seekTo(pending) }
                    }
                }

                Player.STATE_ENDED -> _finished.tryEmit(Unit)
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            pendingSeekMs = null
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
     * §12.4).
     *
     * Returns false when the composition cannot be handed to the player at all.
     */
    fun play(composition: Composition, fromMs: Long = 0L): Boolean {
        return runCatching {
            // A previous composition may still be loaded; `setComposition` is
            // only legal before `prepare()` or after `stop()`, so the player is
            // stopped first and the new composition replaces the old one.
            player.stop()
            player.setComposition(composition)
            pendingSeekMs = fromMs.takeIf { it > 0L }
            player.prepare()
            // `playWhenReady` rather than `play()`: the flag survives the
            // buffering that follows, so playback starts as soon as there is
            // audio instead of being dropped while the composition is built.
            player.playWhenReady = true
        }.isSuccess
    }

    fun stop() {
        runCatching {
            pendingSeekMs = null
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

    /**
     * True while the player has the composition but cannot play it yet.
     *
     * The editor shows this, so a press that produces no sound for a moment
     * reads as "preparing" rather than as "the button is broken".
     */
    val isBuffering: Boolean
        get() = runCatching {
            player.playbackState == Player.STATE_BUFFERING
        }.getOrDefault(false)

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
