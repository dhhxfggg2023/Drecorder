package dev.drecorder.ui.editor

import android.widget.Toast
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import dev.drecorder.audio.edit.AudioFormatProbe
import dev.drecorder.audio.edit.AudioFormatSpec
import dev.drecorder.audio.edit.EditClip
import dev.drecorder.audio.edit.EditCursor
import dev.drecorder.audio.edit.EditExportResult
import dev.drecorder.audio.edit.EditExporter
import dev.drecorder.audio.edit.EditFollow
import dev.drecorder.audio.edit.EditMarkerPlan
import dev.drecorder.audio.edit.EditMarkers
import dev.drecorder.audio.edit.EditSegment
import dev.drecorder.audio.edit.EditTrack
import dev.drecorder.audio.edit.PeakData
import dev.drecorder.audio.edit.PeakDecoder
import dev.drecorder.audio.edit.PeaksCache
import dev.drecorder.audio.edit.TrackPeaks
import dev.drecorder.data.Recording
import dev.drecorder.data.formatDuration
import dev.drecorder.playback.EditPreviewPlayer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What the editor knows about one recording's waveform.
 *
 * The track draws one waveform built from the clips, but the *source* waveforms
 * are per file: a recording is decoded once however many times it appears.
 */
private data class Waveform(
    val displayName: String,
    val durationMs: Long,
    val peaks: PeakData? = null,
    val loading: Boolean = false,
    /** How far the waveform generation has got, 0..100; -1 when not known. */
    val progress: Int = -1,
    val error: String? = null,
)

/**
 * Something the user asked for that changes the recording without a way back.
 *
 * Every one of these throws audio away or replaces a file on disk and cannot be
 * undone from inside the editor, so each one is held until the user says yes.
 */
private sealed interface PendingEdit {
    data class DeleteSegment(val segment: EditSegment) : PendingEdit

    data class KeepOnly(val segment: EditSegment) : PendingEdit

    data class Repeat(val segment: EditSegment) : PendingEdit

    data class Overwrite(val name: String) : PendingEdit
}

/**
 * The editor: one track, one playhead, one waveform — the shape every mobile
 * editor has, and the one the user asked for twice.
 *
 * Top to bottom: the toolbar, the waveform (which takes the space it needs, not
 * a strip at the top), the merge strip *only* when more than one recording is in
 * play, the actions for the picked stretch, and a transport bar at the bottom
 * where a thumb reaches it. Cut points live on the waveform; the only list is
 * the list of recordings being merged, because that is the one order the user
 * has to be able to change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    recordings: List<Recording>,
    onBack: () -> Unit,
    onExported: (File) -> Unit = {},
    /** Lists the recordings that can be appended to this session. */
    loadLibrary: suspend () -> List<Recording> = { emptyList() },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var clips by remember { mutableStateOf(emptyList<EditClip>()) }
    var waveforms by remember { mutableStateOf(emptyMap<String, Waveform>()) }

    /**
     * Where the playhead is, in track time — and, with [viewportSpanMs], the
     * whole state of the timeline.
     *
     * The red line is drawn in the middle of the screen, always, so the window
     * is not state at all: it is [viewportStartMs] below, derived from these
     * two numbers. That is what makes the line unable to move — there is no
     * second owner of the viewport left for it to drift with (2026-10-23
     * 「红色光标固定在屏幕中间，不要动」).
     */
    var playheadMs by remember { mutableLongStateOf(0L) }

    /** How much of the recording is on screen: the zoom. */
    var viewportSpanMs by remember { mutableStateOf(0L) }

    /** The first moment on screen, derived so the line always has the middle. */
    val viewportStartMs = EditFollow.window(playheadMs, viewportSpanMs).startMs

    /**
     * The stretch the user picked, in track time.
     *
     * Only ever what the user tapped, and tapping the same stretch again clears
     * it: a selection that can only be replaced is a selection the user cannot
     * get rid of.
     */
    var pickedSegment by remember { mutableStateOf<EditSegment?>(null) }

    /** The cut point the user tapped, so it can be joined back. */
    var pickedCutMs by remember { mutableStateOf<Long?>(null) }

    var previewPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    /** The moment playback was asked to start at, so the follow loop can wait for it. */
    var startedAtMs by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    /** The fling still scrolling the timeline, so a new touch can stop it. */
    var flingJob by remember { mutableStateOf<Job?>(null) }

    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableLongStateOf(0L) }
    var notice by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf<String?>(null) }

    var showExportDialog by remember { mutableStateOf(false) }
    var showOrder by remember { mutableStateOf(false) }
    var pendingEdit by remember { mutableStateOf<PendingEdit?>(null) }
    var insertCandidates by remember { mutableStateOf<List<Recording>?>(null) }

    val decodeCancelled = remember { AtomicBoolean(false) }
    val previewPlayer = remember { EditPreviewPlayer(context) }
    val exporter = remember { EditExporter(context) }

    val totalMs = EditTrack.totalMs(clips)
    val cutPoints = EditTrack.cutPoints(clips)

    /** The recordings the track is built from, in play order. */
    val sources = remember(clips) {
        EditTrack.files(clips).map { file ->
            file to clips.filter { it.file == file }.sumOf { it.durationMs }
        }
    }

    /** The track's waveform, built from the clips; null until they exist. */
    val trackPeaks: PeakData? = remember(clips, waveforms) {
        TrackPeaks.build(clips = clips, peaksOf = { file -> waveforms[file.path]?.peaks })
    }

    /** The plan the exporter and the preview player take: one clip, one source. */
    val plan = remember(clips, waveforms) {
        EditTrack.toPlan(clips) { file -> waveforms[file.path]?.durationMs ?: 0L }
    }

    // --- waveforms -------------------------------------------------------------

    /**
     * Decodes one recording's waveform, cache first.
     *
     * A recording is decoded once however many clips point at it, so repeating a
     * stretch costs nothing.
     */
    suspend fun ensureWaveform(recording: Recording) {
        val key = recording.file.path
        if (waveforms[key]?.peaks != null || waveforms[key]?.loading == true) return
        waveforms = waveforms + (
            key to Waveform(
                displayName = recording.displayName,
                durationMs = recording.durationMs,
                loading = true,
                progress = 0,
            )
            )

        val cacheFile = PeaksCache.fileFor(recording.file, context.filesDir)
        val cached = withContext(Dispatchers.IO) { PeaksCache.read(cacheFile, recording.file) }
        if (cached != null) {
            waveforms = waveforms + (
                key to Waveform(
                    displayName = recording.displayName,
                    durationMs = recording.durationMs,
                    peaks = cached.data,
                )
                )
            return
        }

        val decoded = withContext(Dispatchers.IO) {
            PeakDecoder().decode(
                file = recording.file,
                onProgress = { percent ->
                    scope.launch {
                        val known = waveforms[key]
                            ?: Waveform(recording.displayName, recording.durationMs)
                        waveforms = waveforms + (key to known.copy(progress = percent))
                    }
                },
                // A long recording is decoded in segments, and the waveform of
                // the part already done is handed over after every segment: the
                // timeline fills in while the rest is still being decoded.
                onPartial = { partial ->
                    scope.launch {
                        val known = waveforms[key]
                            ?: Waveform(recording.displayName, recording.durationMs)
                        waveforms = waveforms + (key to known.copy(peaks = partial))
                    }
                },
                cancelled = decodeCancelled,
            )
        }

        waveforms = when (decoded) {
            is PeakDecoder.Result.Success -> {
                withContext(Dispatchers.IO) {
                    runCatching { PeaksCache.write(cacheFile, decoded.data, recording.file) }
                }
                waveforms + (
                    key to Waveform(
                        displayName = recording.displayName,
                        durationMs = recording.durationMs,
                        peaks = decoded.data,
                    )
                    )
            }

            is PeakDecoder.Result.Failure -> waveforms + (
                key to Waveform(
                    displayName = recording.displayName,
                    durationMs = recording.durationMs,
                    error = when (decoded.reason) {
                        PeakDecoder.Reason.Cancelled -> context.getString(
                            R.string.editor_waveform_cancelled,
                        )
                        PeakDecoder.Reason.Stalled -> context.getString(
                            R.string.editor_waveform_stalled,
                        )
                        PeakDecoder.Reason.Unknown -> decoded.message
                    },
                )
                )
        }
    }

    /** Fills the track from the recordings this session opened with. */
    LaunchedEffect(recordings) {
        if (clips.isNotEmpty()) return@LaunchedEffect
        clips = recordings.map { recording ->
            EditClip(file = recording.file, startMs = 0L, endMs = recording.durationMs)
        }
        waveforms = recordings.associate { recording ->
            recording.file.path to Waveform(
                displayName = recording.displayName,
                durationMs = recording.durationMs,
            )
        }
        val length = EditTrack.totalMs(clips)
        viewportSpanMs = EditMarkerPlan.initialSpanMs(length)
        playheadMs = 0L
        recordings.forEach { recording -> launch { ensureWaveform(recording) } }
    }

    DisposableEffect(Unit) {
        onDispose {
            decodeCancelled.set(true)
            previewPlayer.release()
        }
    }

    /**
     * Says why the preview died when it dies *after* the press.
     *
     * `play()` can only report what throws while the composition is handed over;
     * a file that fails to load, decode or route reports through the player's
     * error callback instead, and nothing was listening to it. The result was the
     * worst of both: no sound, no reason, and a button stuck on Pause claiming to
     * be playing — every report of "试听还是失败" arrived with nothing to read.
     */
    LaunchedEffect(previewPlayer) {
        previewPlayer.failed.collect { error ->
            previewPlaying = false
            buffering = false
            notice = context.getString(R.string.editor_preview_failed) +
                "：" + previewPlayer.describe(error)
        }
    }

    /**
     * Puts the button back to Play when the composition reaches its end.
     *
     * Without this the transport kept showing Pause over a player that had
     * nothing left to play, so the next press paused instead of playing.
     */
    LaunchedEffect(previewPlayer) {
        previewPlayer.finished.collect {
            previewPlaying = false
            buffering = false
        }
    }

    // --- playback --------------------------------------------------------------

    /**
     * Starts playing where the playhead is.
     *
     * The seek happens before the first frame is shown, so playback begins at the
     * playhead instead of at the beginning of the track and then jumping.
     */
    fun togglePlay() {
        if (previewPlaying) {
            previewPlayer.pause()
            previewPlaying = false
            return
        }
        if (clips.isEmpty() || totalMs <= 0L) return
        // Standing on the end means "from the beginning". A seek to the end of
        // the composition lands on silence and ends immediately, and the user
        // reads that as the second press failing (m00376).
        val from = if (playheadMs >= totalMs - END_TOLERANCE_MS) {
            0L
        } else {
            playheadMs.coerceIn(0L, (totalMs - 1L).coerceAtLeast(0L))
        }
        playheadMs = from
        startedAtMs = from
        buffering = true
        // A playlist of clipped media items, on the same player the library
        // uses. The composition the exporter needs is not on this path any
        // more: a composition exists to be muxed into one file, and asking its
        // player for the sound of an edit is what answered with silence for
        // three releases (m00678).
        val started = previewPlayer.play(clips, fromMs = from)
        previewPlaying = started
        if (!started) {
            buffering = false
            notice = context.getString(R.string.editor_preview_failed) +
                (previewPlayer.lastError?.let { error -> "：$error" } ?: "")
        }
    }

    /**
     * The editor is the player: opening a recording starts it.
     *
     * The library no longer has a play button of its own — tapping a recording
     * opens it here, and the thing the user wanted to hear starts playing
     * (m00678). It waits for the clips the first frame fills in, and does it
     * once: an edit that pauses playback must not be restarted by a recompose.
     */
    var autoStarted by remember { mutableStateOf(false) }
    LaunchedEffect(clips.isNotEmpty(), totalMs) {
        if (!autoStarted && clips.isNotEmpty() && totalMs > 0L) {
            autoStarted = true
            togglePlay()
        }
    }

    /**
     * Follows the player while it plays.
     *
     * The playhead is the player's position and the window is pinned to it, so
     * the waveform slides past a red line that never moves. Driven by the frame
     * clock rather than a timer: a tenth of a second is visible at this size.
     *
     * The position is ignored until the player has actually arrived at
     * [startedAtMs]: a player that has not finished seeking reports zero, and
     * zero is a position like any other — reading it would drag the playhead to
     * the start and turn "play from here" into "play from the beginning".
     */
    LaunchedEffect(previewPlaying, totalMs, startedAtMs) {
        if (!previewPlaying) return@LaunchedEffect
        var armed = false
        while (previewPlaying) {
            withFrameMillis {
                buffering = previewPlayer.isBuffering
                if (buffering || dragging) return@withFrameMillis
                val at = previewPlayer.positionMs
                if (at < 0L || totalMs <= 0L) return@withFrameMillis
                if (!armed) {
                    if (at < startedAtMs - START_TOLERANCE_MS) return@withFrameMillis
                    armed = true
                }
                if (at >= totalMs - END_TOLERANCE_MS) {
                    previewPlayer.pause()
                    previewPlaying = false
                    playheadMs = totalMs
                } else {
                    // That is the whole follow rule: the moment moves, the
                    // window follows it. The line stands in the middle of the
                    // screen because the window is *derived* from the playhead
                    // (EditFollow.window), so there is nothing here that could
                    // also move it — every version that kept a window of its
                    // own ended up with the line drifting (v0.3.6) or snapping
                    // (2026-10-23 「红色光标固定在屏幕中间，不要动」).
                    playheadMs = at
                }
            }
        }
    }

    /**
     * Reports the failure the player cannot report itself: loaded, no error, no
     * sound.
     *
     * A composition that neither plays nor errors left the screen saying it was
     * playing while the speaker stayed silent — and it is that state, not a
     * crash, that "试听还是失败" kept describing. The watchdog only reads the
     * player (state word and duration); it never moves the playhead, so it cannot
     * undo the "play from here" seek the way the follow loop once did.
     */
    LaunchedEffect(previewPlaying) {
        if (!previewPlaying) return@LaunchedEffect
        val deadline = System.currentTimeMillis() + PREVIEW_STALL_MS
        while (previewPlaying && !previewPlayer.isPlaying) {
            if (System.currentTimeMillis() >= deadline) {
                val state = previewPlayer.stateLabel
                val duration = previewPlayer.durationMs
                previewPlayer.stop()
                previewPlaying = false
                buffering = false
                notice = context.getString(
                    R.string.editor_preview_stalled,
                    state,
                    PREVIEW_STALL_MS / 1000L,
                    duration,
                )
                return@LaunchedEffect
            }
            delay(250L)
        }
    }

    /**
     * Scrolls the timeline under the finger.
     *
     * The line keeps its pixel, so scrolling the content is what changes the
     * moment: the waveform moves with the finger and the audio under the line
     * changes by the same amount of time. That is the only thing a drag can do
     * now — with the window derived from the playhead there is no window for it
     * to move instead, and no follow rule to undo it (m03765).
     */
    fun panBy(deltaMs: Long) {
        if (deltaMs == 0L || viewportSpanMs <= 0L || totalMs <= 0L) return
        // A finger that is already moving the timeline owns it: whatever the
        // last flick was still doing is over (m03765).
        flingJob?.cancel()
        playheadMs = EditFollow.scrubbed(
            cursor = EditCursor(playheadMs, viewportSpanMs),
            deltaMs = deltaMs,
            totalMs = totalMs,
        ).playheadMs
        pickedCutMs = null
    }

    /**
     * Keeps the timeline moving after the finger lifts.
     *
     * A drag that stops dead when the finger leaves reads as a heavy picture
     * rather than as a timeline (「滑动性和灵动性太差」, 2026-10-23): the content
     * has weight, so it coasts and slows down. The velocity comes from the
     * waveform view in milliseconds per second, already converted from pixels.
     *
     * It is not run while the player is playing: the player owns the moment
     * then, and two owners of one value is the bug this screen has had twice
     * (m03765).
     */
    fun flingBy(velocityMsPerSecond: Float) {
        flingJob?.cancel()
        if (previewPlaying || totalMs <= 0L) return
        if (velocityMsPerSecond.isNaN() || abs(velocityMsPerSecond) < 1f) return
        flingJob = scope.launch {
            animateDecay(
                initialValue = playheadMs.toFloat(),
                initialVelocity = velocityMsPerSecond,
                // A friction that stops a hard fling in about a second: a long
                // coast on a phone reads as the app lagging behind the finger.
                animationSpec = FloatExponentialDecaySpec(frictionMultiplier = 1.6f),
            ) { value, _ ->
                playheadMs = value.roundToLong().coerceIn(0L, totalMs)
            }
        }
    }

    /**
     * Moves the playhead to [toMs]; used by a tap on the waveform and by the
     * merge strip.
     *
     * The moment is put under the line, so the waveform scrolls to it: the line
     * cannot land under the finger, because it does not move at all.
     */
    fun seekTo(toMs: Long) {
        if (totalMs <= 0L) return
        // A tap says which moment to be at; a flick still coasting says an
        // older one, and it would win the frame race.
        flingJob?.cancel()
        val at = toMs.coerceIn(0L, totalMs)
        playheadMs = at
        // Playing already and asked to hear from somewhere else: the playlist
        // is seeked, which is what "listen from the cursor" means.
        if (previewPlaying) {
            previewPlayer.seekTo(at)
        }
    }

    /** Pauses, replaces the track and lands the playhead back on it. */
    fun afterEdit(updated: List<EditClip>) {
        if (previewPlaying) {
            previewPlayer.pause()
            previewPlaying = false
        }
        clips = updated
        pickedSegment = null
        pickedCutMs = null
        val length = EditTrack.totalMs(updated)
        playheadMs = playheadMs.coerceIn(0L, length)
        if (length <= 0L) return
        // The line keeps standing in the middle; only the length of the
        // recording changed, so only the zoom has to be brought back in range.
        viewportSpanMs = viewportSpanMs.coerceIn(
            EditFollow.MIN_SPAN_MS.coerceAtMost(length),
            length,
        )
    }

    /** Splits the track where the playhead stands. */
    fun addCut() {
        val updated = EditTrack.cutAt(clips, playheadMs)
        if (updated.size == clips.size) {
            notice = context.getString(R.string.editor_cut_needs_room)
            return
        }
        pickedCutMs = null
        afterEdit(updated)
    }

    // --- export ----------------------------------------------------------------

    var exportToRun by remember { mutableStateOf<File?>(null) }
    var exportRequested by remember { mutableStateOf(false) }

    fun startExport(destination: File?) {
        exportToRun = destination
        exportRequested = true
    }

    LaunchedEffect(exportRequested) {
        if (!exportRequested) return@LaunchedEffect
        val destination = exportToRun
        exportRequested = false
        exportToRun = null
        exporting = true
        exportProgress = 0L
        val result = exporter.export(
            plan = plan,
            destination = destination,
            onProgress = { percent -> exportProgress = percent.toLong() },
        )
        exporting = false
        when (result) {
            is EditExportResult.Success -> {
                Toast.makeText(
                    context,
                    context.getString(R.string.editor_exported, result.file.name),
                    Toast.LENGTH_LONG,
                ).show()
                onExported(result.file)
            }

            is EditExportResult.Failure -> notice = result.message
        }
    }

    // --- the screen ------------------------------------------------------------

    val picked = pickedSegment
    val pickedHasAudio = picked != null && EditTrack.totalMs(
        EditTrack.keepOnly(clips, picked.startMs, picked.endMs),
    ) > 0L

    /** Whether the picked cut point can actually be removed. */
    val cutJoinable = pickedCutMs?.let { at -> EditTrack.canJoinAt(clips, at) } ?: false

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (sources.size > 1) {
                            stringResource(R.string.editor_title_merged, sources.size)
                        } else {
                            stringResource(R.string.editor_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showExportDialog = true },
                        enabled = !exporting && clips.isNotEmpty() && totalMs > 0L,
                    ) {
                        Text(stringResource(R.string.editor_export))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (clips.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.editor_empty))
                }
                return@Scaffold
            }

            // The merge strip: the only list in the editor, and it names the
            // recordings being laid end to end — never the cut points, which are
            // already drawn on the waveform and are not something to reorder.
            if (sources.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    sources.forEachIndexed { index, (file, length) ->
                        val name = waveforms[file.path]?.displayName ?: file.name
                        AssistChip(
                            onClick = {
                                // Tapping a recording on the strip jumps the
                                // playhead to where it starts.
                                seekTo(
                                    clips.takeWhile { it.file != file }
                                        .sumOf { it.durationMs },
                                )
                            },
                            label = {
                                Text(
                                    "${index + 1} ${name.take(8)} · ${formatDuration(length)}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }

            // The waveform takes whatever height is left. This is the thing the
            // user works on, so it gets the space, not the toolbar.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val peaks = trackPeaks
                if (peaks == null) {
                    WaveformPlaceholder(
                        loading = waveforms.values.any { it.loading },
                        error = waveforms.values.firstOrNull { it.error != null }?.error,
                    )
                } else {
                    WaveformView(
                        peaks = peaks,
                        durationMs = totalMs,
                        startMs = viewportStartMs,
                        spanMs = viewportSpanMs,
                        removed = emptyList(),
                        markers = cutPoints,
                        playheadMs = playheadMs,
                        onTimelineDrag = { delta -> panBy(delta) },
                        onMarkerSelect = { ms ->
                            pickedCutMs = if (pickedCutMs == ms) null else ms
                            pickedSegment = null
                        },
                        onSegmentTap = { timeMs ->
                            // Tapping the picked stretch again lets it go.
                            val at = EditMarkers.segmentContaining(cutPoints, timeMs, totalMs)
                            pickedSegment = if (pickedSegment == at) null else at
                            pickedCutMs = null
                            // …and the tap is also "listen from here": the
                            // editor is the player now, so a tap on the audio is
                            // a seek, not just a selection (m00678).
                            seekTo(timeMs)
                        },
                        onViewportGesture = { zoom, pan ->
                            val next = EditFollow.zoomed(
                                cursor = EditCursor(playheadMs, viewportSpanMs),
                                totalMs = totalMs,
                                zoomFactor = zoom,
                                panFraction = pan,
                            )
                            viewportSpanMs = next.spanMs
                            playheadMs = next.playheadMs
                        },
                        onFling = { velocityMsPerSecond -> flingBy(velocityMsPerSecond) },
                        onDraggingChanged = { isDragging ->
                            dragging = isDragging
                            // The finger owns the moment while it is down; when
                            // it lifts, the sound has to catch up with what the
                            // screen shows, or the editor would be playing one
                            // moment and displaying another.
                            if (!isDragging && previewPlaying) {
                                previewPlayer.seekTo(playheadMs)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        // The waveform is one band on the page, not the page.
                        // Filling every pixel of height made the bars a wall
                        // (「波形不要整个界面都挤满了」, 2026-10-23).
                        height = WAVEFORM_HEIGHT,
                        selectedRange = picked,
                    )
                }
            }

            if (exporting) {
                LinearProgressIndicator(
                    progress = { exportProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            notice?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            // What can be done to the picked stretch — above the transport, so
            // the thumb travels between them and nothing moves under it.
            if (pickedCutMs != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // A cut that cannot be removed says so instead of the
                        // button announcing a deletion that never happened:
                        // "切点删除失败" was the screen lying about work it had
                        // not done (m00376).
                        if (cutJoinable) {
                            stringResource(R.string.editor_marker_picked, formatDuration(pickedCutMs!!))
                        } else {
                            stringResource(R.string.editor_marker_not_joinable)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = cutJoinable,
                        onClick = {
                            val at = pickedCutMs
                            pickedCutMs = null
                            if (at != null) {
                                afterEdit(EditTrack.removeCutAt(clips, at))
                                notice = context.getString(R.string.editor_marker_deleted)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.editor_marker_delete, formatDuration(pickedCutMs!!)))
                    }
                }
            } else if (picked != null) {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    SegmentActionsRow(
                        segment = picked,
                        enabled = pickedHasAudio,
                        repeatEnabled = true,
                        onDelete = { pendingEdit = PendingEdit.DeleteSegment(picked) },
                        onKeepOnly = { pendingEdit = PendingEdit.KeepOnly(picked) },
                        onRepeat = { pendingEdit = PendingEdit.Repeat(picked) },
                    )
                }
            }

            // The things a thumb does mid-edit, in one labeled row just above
            // the transport. These were a music note in the toolbar and a bare
            // plus floating next to the clock; neither looked like something to
            // press, and neither was where the thumb already is (m00376).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { addCut() }) {
                    Text(stringResource(R.string.editor_marker_add))
                }
                TextButton(
                    onClick = {
                        insertCandidates = null
                        scope.launch { insertCandidates = loadLibrary() }
                    },
                ) {
                    Text(stringResource(R.string.editor_insert_audio))
                }
                if (sources.size > 1) {
                    TextButton(onClick = { showOrder = true }) {
                        Text(stringResource(R.string.editor_order))
                    }
                }
            }

            // The transport: what a thumb reaches first.
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatDuration(playheadMs),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(56.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    FilledIconButton(
                        onClick = { togglePlay() },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            imageVector = if (previewPlaying) {
                                Icons.Filled.Pause
                            } else {
                                Icons.Filled.PlayArrow
                            },
                            contentDescription = stringResource(R.string.editor_preview),
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatDuration(totalMs),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(56.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
        }
    }

    // --- dialogs ---------------------------------------------------------------

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.editor_export_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.editor_export_body_new))
                    Spacer(Modifier.height(12.dp))
                    clips.firstOrNull()?.let { first ->
                        Text(
                            stringResource(
                                R.string.editor_export_body_overwrite,
                                waveforms[first.file.path]?.displayName ?: first.file.name,
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    startExport(null)
                }) { Text(stringResource(R.string.editor_export_save_as)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        val first = clips.firstOrNull() ?: return@TextButton
                        pendingEdit = PendingEdit.Overwrite(
                            waveforms[first.file.path]?.displayName ?: first.file.name,
                        )
                    },
                ) { Text(stringResource(R.string.editor_export_overwrite)) }
            },
        )
    }

    pendingEdit?.let { edit ->
        val (title, body) = when (edit) {
            is PendingEdit.DeleteSegment -> R.string.editor_delete_confirm_title to
                context.getString(
                    R.string.editor_delete_confirm_body,
                    formatDuration(edit.segment.startMs),
                    formatDuration(edit.segment.endMs),
                    formatDuration(edit.segment.durationMs),
                )

            is PendingEdit.KeepOnly -> R.string.editor_keep_only_confirm_title to
                context.getString(
                    R.string.editor_keep_only_confirm_body,
                    formatDuration(edit.segment.startMs),
                    formatDuration(edit.segment.endMs),
                    formatDuration(edit.segment.durationMs),
                )

            is PendingEdit.Repeat -> R.string.editor_repeat_confirm_title to
                context.getString(
                    R.string.editor_repeat_confirm_body,
                    formatDuration(edit.segment.startMs),
                    formatDuration(edit.segment.endMs),
                    formatDuration(edit.segment.durationMs),
                )

            is PendingEdit.Overwrite -> R.string.editor_overwrite_confirm_title to
                context.getString(R.string.editor_overwrite_confirm_body, edit.name)
        }
        AlertDialog(
            onDismissRequest = { pendingEdit = null },
            title = { Text(stringResource(title)) },
            text = { Text(body) },
            dismissButton = {
                TextButton(onClick = { pendingEdit = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val confirmed = edit
                    pendingEdit = null
                    when (confirmed) {
                        is PendingEdit.DeleteSegment -> {
                            afterEdit(
                                EditTrack.removeRange(
                                    clips,
                                    confirmed.segment.startMs,
                                    confirmed.segment.endMs,
                                ),
                            )
                            notice = context.getString(R.string.editor_segment_deleted)
                        }

                        is PendingEdit.KeepOnly -> {
                            afterEdit(
                                EditTrack.keepOnly(
                                    clips,
                                    confirmed.segment.startMs,
                                    confirmed.segment.endMs,
                                ),
                            )
                            notice = context.getString(R.string.editor_segment_kept)
                        }

                        is PendingEdit.Repeat -> {
                            afterEdit(
                                EditTrack.duplicateRange(
                                    clips,
                                    confirmed.segment.startMs,
                                    confirmed.segment.endMs,
                                ),
                            )
                        }

                        is PendingEdit.Overwrite -> startExport(clips.first().file)
                    }
                }) {
                    Text(
                        stringResource(
                            when (edit) {
                                is PendingEdit.DeleteSegment -> R.string.editor_segment_delete
                                is PendingEdit.KeepOnly -> R.string.editor_segment_keep
                                is PendingEdit.Repeat -> R.string.editor_segment_repeat
                                is PendingEdit.Overwrite -> R.string.editor_export_overwrite
                            },
                        ),
                    )
                }
            },
        )
    }

    insertCandidates?.let { candidates ->
        val reference = clips.firstOrNull()?.file
        val referenceFormat = remember(reference) {
            reference?.let { AudioFormatProbe.probe(it) }
        }
        AlertDialog(
            onDismissRequest = { insertCandidates = null },
            title = { Text(stringResource(R.string.editor_insert_audio)) },
            text = {
                if (candidates.isEmpty()) {
                    Text(stringResource(R.string.editor_insert_none))
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        candidates.forEach { candidate ->
                            val format = AudioFormatProbe.probe(candidate.file)
                            val compatible = referenceFormat != null &&
                                format != null && sameFormat(referenceFormat, format)
                            TextButton(
                                onClick = {
                                    insertCandidates = null
                                    if (!compatible) {
                                        mismatch = context.getString(
                                            R.string.editor_format_mismatch,
                                            candidate.displayName,
                                        )
                                        return@TextButton
                                    }
                                    scope.launch { ensureWaveform(candidate) }
                                    afterEdit(
                                        EditTrack.insertAt(
                                            clips = clips,
                                            outputMs = playheadMs,
                                            added = listOf(
                                                EditClip(
                                                    file = candidate.file,
                                                    startMs = 0L,
                                                    endMs = candidate.durationMs,
                                                ),
                                            ),
                                        ),
                                    )
                                },
                            ) {
                                Text(
                                    stringResource(
                                        R.string.editor_insert_entry,
                                        candidate.displayName,
                                        formatDuration(candidate.durationMs),
                                    ),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { insertCandidates = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showOrder) {
        AlertDialog(
            onDismissRequest = { showOrder = false },
            title = { Text(stringResource(R.string.editor_merge_order)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    sources.forEachIndexed { index, (file, length) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${index + 1}. ${waveforms[file.path]?.displayName ?: file.name}" +
                                    "  ${formatDuration(length)}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = index > 0,
                                onClick = {
                                    afterEdit(EditTrack.moveSource(clips, index, index - 1))
                                },
                            ) {
                                Text(stringResource(R.string.editor_order_earlier))
                            }
                            TextButton(
                                enabled = index < sources.lastIndex,
                                onClick = {
                                    afterEdit(EditTrack.moveSource(clips, index, index + 1))
                                },
                            ) {
                                Text(stringResource(R.string.editor_order_later))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOrder = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    mismatch?.let { message ->
        AlertDialog(
            onDismissRequest = { mismatch = null },
            title = { Text(stringResource(R.string.editor_insert_audio)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { mismatch = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

/** Whether two recordings can be spliced into one file. */
private fun sameFormat(left: AudioFormatSpec, right: AudioFormatSpec): Boolean =
    left.mimeType == right.mimeType &&
        left.sampleRate == right.sampleRate &&
        left.channelCount == right.channelCount

/** What the waveform area shows while there is nothing to draw yet. */
@Composable
private fun WaveformPlaceholder(loading: Boolean, error: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            error != null -> Text(error)
            loading -> {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.editor_waveform_loading, 0))
            }

            else -> Text(stringResource(R.string.editor_waveform_failed))
        }
    }
}

/**
 * How close to the end of the track counts as "the end", in ms.
 *
 * The player reports a position that lands on the frame it reached, not on the
 * millisecond asked for, so playback has to be allowed to finish a frame short.
 */
private const val END_TOLERANCE_MS = 60L

/**
 * How close the player's position must be to the moment play was pressed before
 * the editor starts following it, in ms.
 *
 * A seek lands on the frame it lands on, and a player that has not been seeked
 * yet reports zero — so the comparison needs enough slack to accept the landing
 * and nowhere near enough to accept the zero it is meant to reject.
 */
private const val START_TOLERANCE_MS = 250L

/**
 * How long the preview may stay silent before the screen says so, in ms.
 *
 * Long enough for a long recording to prepare — buffering a 30-minute file takes
 * a noticeable moment — and far short of the "I pressed play and nothing
 * happened" the user had to report from memory.
 */
private const val PREVIEW_STALL_MS = 8_000L

/**
 * The height of the waveform band.
 *
 * One band on the page, not the page: the waveform used to be given every pixel
 * of height the controls left over, which made it a wall of bars that pushed
 * everything else to the bottom (「波形不要整个界面都挤满了」, 2026-10-23).
 * 200 dp is tall enough to read a syllable and short enough to leave the page
 * looking like a page.
 */
private val WAVEFORM_HEIGHT = 200.dp
