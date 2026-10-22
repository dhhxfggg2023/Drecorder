package dev.drecorder.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.drecorder.audio.edit.EditClip
import dev.drecorder.audio.edit.EditPreview
import dev.drecorder.audio.edit.PreviewItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Plays the track the editor is working on, as an ordinary playlist.
 *
 * This is a plain `ExoPlayer` with one clipped [MediaItem] per clip: the same
 * player, and the same media items, that play a recording in the library. The
 * editor used to play a `CompositionPlayer` built from the export's
 * `Composition` — the argument being that preview and export should be the same
 * object — and that argument cost three releases: the composition player loaded,
 * reported no error, produced no sound, and had no way to say why, so every
 * report of "试听还是失败" arrived with nothing to read (m00678).
 *
 * What the two DO share is the clips: preview and export read the same
 * [EditClip] list, so an edit that sounds wrong is wrong in both. Sharing the
 * *muxer* was never necessary, and it is the muxer that would not play.
 *
 * Seeking is by track time: [dev.drecorder.audio.edit.EditPreview.locate] turns
 * a moment into (playlist index, offset inside the clip), which is exactly what
 * `setMediaItems(items, index, positionMs)` and `seekTo(index, positionMs)` take.
 * There is no pending seek to apply later, which is what "play from here" used
 * to get wrong.
 */
class EditPreviewPlayer(context: Context) {

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    /** The playlist the last [play] loaded, kept to translate positions both ways. */
    private var items: List<PreviewItem> = emptyList()

    private var trackMs: Long = 0L

    private val _finished = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val finished: SharedFlow<Unit> = _finished

    private val _failed = MutableSharedFlow<Throwable>(extraBufferCapacity = 4)
    val failed: SharedFlow<Throwable> = _failed

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            log("state=${stateWord(playbackState)} position=$positionMs of $trackMs")
            if (playbackState == Player.STATE_ENDED) _finished.tryEmit(Unit)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            log("isPlaying=$isPlaying state=$stateLabel position=$positionMs")
        }

        override fun onPlayerError(error: PlaybackException) {
            // The line the user is asked to send is this one.
            log("error=${describe(error)}")
            _failed.tryEmit(error)
        }
    }

    init {
        player.addListener(listener)
        // The preview is ordinary playback: it takes audio focus, so the app
        // that was playing before (the one being recorded, or a music player)
        // stops instead of playing over the edit.
        runCatching { player.setAudioAttributes(AudioAttributes.DEFAULT, true) }
    }

    /**
     * Loads [clips] as a playlist and starts playing at [fromMs] of the track.
     *
     * Returns false when there is nothing to play or the player rejects the
     * clips outright; everything that fails later arrives on [failed].
     */
    fun play(clips: List<EditClip>, fromMs: Long = 0L): Boolean {
        val playlist = EditPreview.build(clips)
        val seek = EditPreview.locate(playlist, fromMs)
        log("play from=$fromMs clips=${playlist.size} seek=${seek?.index}@${seek?.offsetMs}")
        if (seek == null) {
            lastError = "nothing to play"
            return false
        }
        val result = runCatching {
            items = playlist
            trackMs = EditPreview.totalMs(playlist)
            player.setMediaItems(
                playlist.map { item -> mediaItemFor(item) },
                seek.index,
                seek.offsetMs,
            )
            player.prepare()
            // The flag rather than play(): it survives the buffering that
            // follows, so playback starts as soon as there is audio.
            player.playWhenReady = true
        }
        lastError = result.exceptionOrNull()?.let { describe(it) }
        log("play returned success=${result.isSuccess} error=${lastError ?: "none"}")
        return result.isSuccess
    }

    /** One clipped media item for one clip. */
    private fun mediaItemFor(item: PreviewItem): MediaItem {
        val clip = item.clip
        return MediaItem.Builder()
            .setUri(Uri.fromFile(clip.file))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.startMs)
                    .setEndPositionMs(clip.endMs)
                    .build(),
            )
            .build()
    }

    /**
     * Moves playback to [outputMs] of the track.
     *
     * While playing this is what "tap the waveform to hear from there" does; a
     * seek into the item that is already playing stays inside it.
     */
    fun seekTo(outputMs: Long) {
        val seek = EditPreview.locate(items, outputMs) ?: return
        log("seek to=$outputMs index=${seek.index} offset=${seek.offsetMs}")
        runCatching { player.seekTo(seek.index, seek.offsetMs) }
    }

    /** Pauses without dropping the playlist, so pressing play again resumes. */
    fun pause() {
        log("pause at $positionMs")
        runCatching { player.pause() }
    }

    fun stop() {
        log("stop")
        runCatching { player.stop() }
    }

    /** Why the last [play] failed, or null when it worked. */
    var lastError: String? = null
        private set

    /** The track position of the player, in ms. */
    val positionMs: Long
        get() = runCatching {
            val index = player.currentMediaItemIndex
            val within = EditPreview.outputMsAt(items, index, player.currentPosition)
            within ?: 0L
        }.getOrDefault(0L)

    /** Length of the last playlist, in ms. */
    val durationMs: Long
        get() = runCatching {
            if (trackMs > 0L) trackMs else player.duration.coerceAtLeast(0L)
        }.getOrDefault(0L)

    val isPlaying: Boolean
        get() = runCatching { player.isPlaying }.getOrDefault(false)

    val isBuffering: Boolean
        get() = runCatching {
            player.playbackState == Player.STATE_BUFFERING
        }.getOrDefault(false)

    /** The player's state in one word, for the message shown when it stalls. */
    val stateLabel: String
        get() = runCatching { stateWord(player.playbackState) }.getOrDefault("unknown")

    private fun stateWord(state: Int): String = when (state) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "state=$state"
    }

    /**
     * One line naming why playback failed, for the message the user reads.
     *
     * The code comes first and the cause second: the code is the thing
     * `PlaybackException` documents, and the message underneath says which
     * file.
     */
    fun describe(error: Throwable): String {
        val playback = error as? PlaybackException
            ?: return error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        val cause = playback.cause?.message?.takeIf { it.isNotBlank() }
        return cause?.let { "${playback.errorCodeName}：$it" } ?: playback.errorCodeName
    }

    fun release() {
        runCatching {
            player.removeListener(listener)
            player.release()
        }
    }

    /**
     * Writes one line to logcat.
     *
     * Everything the user reports arrives after the phone has been put down, so
     * the sequence of play/stop/state/seek is written where `adb logcat -d -s
     * Drecorder` can read it.
     */
    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        /** The one tag the app logs under, so `logcat -s Drecorder` is enough. */
        const val TAG = "Drecorder"
    }
}
