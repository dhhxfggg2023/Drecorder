package dev.drecorder.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/**
 * Plays finished recordings through Media3.
 *
 * ExoPlayer (rather than `MediaPlayer`) because it reads both `.m4a` and `.wav` with
 * the same code path and reports errors as a typed [PlaybackException] instead of
 * a boolean.
 *
 * Playback is deliberately foreground-only: the capture path already competes for
 * the audio stack, and a background player would fight the very stream we record.
 */
class RecordingPlayer(context: Context) {

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

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

    /** Returns false when the file cannot even be handed to the player. */
    fun play(file: File): Boolean {
        if (!file.isFile) return false
        return runCatching {
            player.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            player.prepare()
            player.play()
        }.isSuccess
    }

    fun stop() {
        runCatching {
            player.stop()
            player.clearMediaItems()
        }
    }

    fun release() {
        runCatching {
            player.removeListener(listener)
            player.release()
        }
    }
}
