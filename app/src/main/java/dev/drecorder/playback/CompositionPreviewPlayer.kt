package dev.drecorder.playback

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
                Player.STATE_READY -> applyPendingSeek()
                Player.STATE_ENDED -> _finished.tryEmit(Unit)
                else -> Unit
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // The composition's timeline is not built until the player has
            // prepared it, and a seek before then is clamped into an empty
            // timeline — so the seek is retried the moment the timeline exists.
            applyPendingSeek()
        }

        override fun onPlayerError(error: PlaybackException) {
            pendingSeekMs = null
            _failed.tryEmit(error)
        }
    }

    /**
     * Applies the seek [play] asked for, once the player can honour it.
     *
     * A seek issued before the composition has a timeline is clamped into a
     * zero-length one, i.e. back to the start of the recording — which is how
     * "play from here" became "play from the beginning" and stayed that way
     * through a `STATE_READY` that arrived before the duration did. The seek is
     * therefore held until the player reports a real duration, and dropped only
     * when it lands.
     */
    private fun applyPendingSeek() {
        val pending = pendingSeekMs ?: return
        val duration = runCatching { player.duration }.getOrDefault(0L)
        if (duration <= 0L) return
        if (pending >= duration) {
            pendingSeekMs = null
            return
        }
        runCatching { player.seekTo(pending) }
        pendingSeekMs = null
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

    /**
     * Stops the sound and keeps the composition loaded and its position.
     *
     * The editor pauses and resumes far more often than it stops — every edit
     * pauses, and pressing play again has to pick up where the playhead is — so
     * this must not tear the composition down the way [stop] does.
     */
    fun pause() {
        runCatching { player.pause() }
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
