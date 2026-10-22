package dev.drecorder.ui.editor

import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import dev.drecorder.audio.edit.EditComposition
import dev.drecorder.audio.edit.EditExporter
import dev.drecorder.audio.edit.EditExportResult
import dev.drecorder.audio.edit.EditFollow
import dev.drecorder.audio.edit.EditMarkerPlan
import dev.drecorder.audio.edit.EditMarkers
import dev.drecorder.audio.edit.EditOrder
import dev.drecorder.audio.edit.EditSegment
import dev.drecorder.audio.edit.EditTrack
import dev.drecorder.audio.edit.EditViewport
import dev.drecorder.audio.edit.EditViewportMath
import dev.drecorder.audio.edit.PeakData
import dev.drecorder.audio.edit.PeakDecoder
import dev.drecorder.audio.edit.PeaksCache
import dev.drecorder.audio.edit.TrackPeaks
import dev.drecorder.data.Recording
import dev.drecorder.data.formatDuration
import dev.drecorder.playback.CompositionPreviewPlayer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the editor knows about one recording's waveform.
 *
 * The track draws a waveform built from the clips, but the *source* waveforms are
 * still per file: a recording is decoded once, however many times it appears on
 * the track.
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
 * Something the user asked for that changes the recording without a way back,
 * waiting for a yes.
 *
 * Every one of these throws audio away or replaces a file on disk, and none of
 * them can be undone from inside the editor, so none of them happen on the first
 * tap. They share one dialog because they share one question — "this cannot be
 * undone, do you mean it?" — and three dialogs is how three of them drift into
 * asking differently.
 */
private sealed interface PendingEdit {
    data class DeleteSegment(val segment: EditSegment) : PendingEdit

    data class KeepOnly(val segment: EditSegment) : PendingEdit

    data class Repeat(val segment: EditSegment) : PendingEdit

    data class Overwrite(val name: String) : PendingEdit
}

/**
 * The editor: one track, one playhead, one waveform.
 *
 * The track is a list of [EditClip]s — pieces of recordings laid end to end — so
 * cutting, deleting, repeating and merging are all the same list being edited,
 * and the waveform is built from that list rather than from one file at an
 * offset. The screen draws and forwards intent; everything that decides what an
 * edit means lives in core-audio and is unit-tested on the JVM.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    recordings: List<Recording>,
    onBack: () -> Unit,
    onExported: (File) -> Unit = {},
    /** Lists the recordings that can be spliced into this session. */
    loadLibrary: suspend () -> List<Recording> = { emptyList() },
) {
    val context = LocalContext.current
    val scope: CoroutineScope = rememberCoroutineScope()

    var clips by remember { mutableStateOf(emptyList<EditClip>()) }
    var waveforms by remember { mutableStateOf(emptyMap<String, Waveform>()) }

    /** Where the playhead is, in track time. */
    var playheadMs by remember { mutableLongStateOf(0L) }

    var viewportStartMs by remember { mutableStateOf(0L) }
    var viewportSpanMs by remember { mutableStateOf(0L) }

    /**
     * The stretch the user picked, in track time.
     *
     * Only ever what the user tapped: nothing is picked on their behalf, so a cut
     * point that was just dropped never turns the rest of the track into a
     * candidate for deletion.
     */
    var pickedSegment by remember { mutableStateOf<EditSegment?>(null) }

    /** The cut point the user tapped, so it can be joined back. */
    var pickedCutMs by remember { mutableStateOf<Long?>(null) }

    var previewPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    /** The moment playback was asked to start at, so the follow loop can wait for it. */
    var startedAtMs by remember { mutableLongStateOf(0L) }
    var waveformDragging by remember { mutableStateOf(false) }

    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableLongStateOf(0L) }
    var notice by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf<String?>(null) }

    var showExportDialog by remember { mutableStateOf(false) }
    var pendingEdit by remember { mutableStateOf<PendingEdit?>(null) }
    var insertCandidates by remember { mutableStateOf<List<Recording>?>(null) }

    val decodeCancelled = remember { AtomicBoolean(false) }
    val previewPlayer = remember { CompositionPreviewPlayer(context) }
    val exporter = remember { EditExporter(context) }

    val totalMs = EditTrack.totalMs(clips)
    val cutPoints = EditTrack.cutPoints(clips)
    val files = remember(clips) { EditTrack.files(clips) }

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
     * stretch costs nothing: the second copy of the clip draws from the same
     * waveform.
     */
    suspend fun ensureWaveform(recording: Recording) {
        val key = recording.file.path
        if (waveforms[key]?.peaks != null || waveforms[key]?.loading == true) return
        waveforms = waveforms + (key to Waveform(
            displayName = recording.displayName,
            durationMs = recording.durationMs,
            loading = true,
            progress = 0,
        ))

        val cacheFile = PeaksCache.fileFor(recording.file, context.filesDir)
        val cached = withContext(Dispatchers.IO) { PeaksCache.read(cacheFile, recording.file) }
        if (cached != null) {
            waveforms = waveforms + (key to Waveform(
                displayName = recording.displayName,
                durationMs = recording.durationMs,
                peaks = cached.data,
            ))
            return
        }

        val decoded = withContext(Dispatchers.IO) {
            PeakDecoder().decode(
                file = recording.file,
                onProgress = { percent ->
                    scope.launch {
                        waveforms = waveforms + (key to (
                            waveforms[key] ?: Waveform(recording.displayName, recording.durationMs)
                            ).copy(progress = percent))
                    }
                },
                // A long recording is decoded in segments, and the waveform of
                // the part already done is handed over after every segment: the
                // timeline fills in while the rest is still being decoded.
                onPartial = { partial ->
                    scope.launch {
                        waveforms = waveforms + (key to (
                            waveforms[key] ?: Waveform(recording.displayName, recording.durationMs)
                            ).copy(peaks = partial))
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
                waveforms + (key to Waveform(
                    displayName = recording.displayName,
                    durationMs = recording.durationMs,
                    peaks = decoded.data,
                ))
            }

            is PeakDecoder.Result.Failure -> waveforms + (key to Waveform(
                displayName = recording.displayName,
                durationMs = recording.durationMs,
                error = when (decoded.reason) {
                    PeakDecoder.Reason.Cancelled -> context.getString(
                        R.string.editor_waveform_cancelled,
                    )
                    PeakDecoder.Reason.Stalled -> context.getString(R.string.editor_waveform_stalled)
                    PeakDecoder.Reason.Unknown -> decoded.message
                },
            ))
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
        val span = EditMarkerPlan.initialSpanMs(EditTrack.totalMs(clips))
        viewportSpanMs = span
        viewportStartMs = EditFollow.pinned(0L, span, EditTrack.totalMs(clips)).startMs
        recordings.forEach { recording -> launch { ensureWaveform(recording) } }
    }

    // A decode still running when the editor closes is a decode nobody waits for.
    DisposableEffect(Unit) {
        onDispose {
            decodeCancelled.set(true)
            previewPlayer.release()
        }
    }

    // --- playback --------------------------------------------------------------

    /**
     * Starts playing where the playhead is.
     *
     * The seek happens before the first frame is shown, so playback begins at the
     * playhead rather than at the beginning of the track and then jumping: the
     * user pressed play *here*, and hearing the first second of the recording
     * instead is the bug this replaces.
     */
    fun togglePlay() {
        if (previewPlaying) {
            previewPlayer.pause()
            previewPlaying = false
            return
        }
        if (clips.isEmpty() || totalMs <= 0L) return
        val from = playheadMs.coerceIn(0L, (totalMs - 1L).coerceAtLeast(0L))
        startedAtMs = from
        buffering = true
        val started = previewPlayer.play(EditComposition.build(plan), fromMs = from)
        previewPlaying = started
        if (!started) notice = context.getString(R.string.editor_preview_failed)
    }

    /**
     * Follows the player while it plays.
     *
     * The playhead is the player's position, and the window is pinned to the
     * playhead, so the waveform slides past a red line that never moves. It is
     * driven by the frame clock rather than a timer: a tenth of a second is
     * visible as a stutter at this size.
     *
     * The position is ignored until the player has actually arrived at
     * [startedAtMs]. A player that has not finished seeking reports zero, and
     * zero is a position like any other — reading it here would drag the
     * playhead back to the beginning of the track in the first frame and turn
     * "play from here" into "play from the start".
     */
    LaunchedEffect(previewPlaying, totalMs, startedAtMs) {
        if (!previewPlaying) return@LaunchedEffect
        var armed = false
        while (previewPlaying) {
            withFrameMillis {
                buffering = previewPlayer.isBuffering
                if (buffering) return@withFrameMillis
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
                    playheadMs = at
                }
                if (!waveformDragging && viewportSpanMs > 0L) {
                    val window = EditFollow.pinned(playheadMs, viewportSpanMs, totalMs)
                    viewportStartMs = window.startMs
                    viewportSpanMs = window.spanMs
                }
            }
        }
    }

    /** Every edit lands the playhead back on the track it produced. */
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
        val span = viewportSpanMs.coerceIn(1L, length)
        viewportSpanMs = span
        viewportStartMs = EditFollow.pinned(playheadMs, span, length).startMs
    }

    fun movePlayhead(toMs: Long) {
        if (totalMs <= 0L) return
        playheadMs = toMs.coerceIn(0L, totalMs)
        if (previewPlaying) {
            // Dragging the playhead while it plays is a seek, not a pause.
            runCatching { previewPlayer.seekTo(playheadMs) }
        } else if (viewportSpanMs > 0L && !waveformDragging) {
            viewportStartMs = EditFollow.pinned(playheadMs, viewportSpanMs, totalMs).startMs
        }
    }

    // --- export ----------------------------------------------------------------

    // The file an overwrite replaces, chosen in the export dialog.
    var exportToRun by remember { mutableStateOf<File?>(null) }
    var exportRequested by remember { mutableStateOf(false) }

    /**
     * Starts an export of the current track.
     *
     * The work is started from a `LaunchedEffect` rather than here, because the
     * progress callback writes state on every percent and a plain `launch` would
     * let two exports overlap when the button is tapped twice.
     */
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (clips.map { it.file }.distinct().size > 1) {
                            stringResource(
                                R.string.editor_title_merged,
                                clips.map { it.file }.distinct().size,
                            )
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
                .fillMaxSize()
                .padding(horizontal = 12.dp),
        ) {
            if (clips.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.editor_empty))
                }
                return@Scaffold
            }

            // The waveform takes whatever the controls leave: this is the thing
            // the user works on, and a strip at the top of an otherwise empty
            // screen was the layout they rejected.
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
                        onTimelineDrag = { delta -> movePlayhead(playheadMs + delta) },
                        onMarkerSelect = { ms ->
                            pickedCutMs = ms
                            pickedSegment = null
                        },
                        onSegmentTap = { timeMs ->
                            pickedSegment = EditMarkers.segmentContaining(
                                cutPoints,
                                timeMs,
                                totalMs,
                            )
                            pickedCutMs = null
                        },
                        onViewportGesture = { zoom, pan, focus ->
                            val next = EditViewportMath.apply(
                                current = EditViewport(viewportStartMs, viewportSpanMs),
                                totalMs = totalMs,
                                zoomFactor = zoom,
                                panFraction = pan,
                                focusFraction = focus,
                            )
                            viewportStartMs = next.startMs
                            viewportSpanMs = next.spanMs
                        },
                        onDraggingChanged = { dragging ->
                            waveformDragging = dragging
                            if (!dragging && !previewPlaying && viewportSpanMs > 0L) {
                                viewportStartMs =
                                    EditFollow.pinned(playheadMs, viewportSpanMs, totalMs).startMs
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        height = 240.dp,
                        selectedRange = picked,
                    )
                }
            }

            // --- transport ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { togglePlay() }) {
                    Icon(
                        imageVector = if (previewPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.editor_preview),
                    )
                }
                Text(
                    stringResource(
                        R.string.editor_position,
                        formatDuration(playheadMs),
                        formatDuration(totalMs),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    val updated = EditTrack.cutAt(clips, playheadMs)
                    if (updated.size == clips.size) {
                        notice = context.getString(R.string.editor_select_need_markers)
                    } else {
                        pickedCutMs = null
                        afterEdit(updated)
                    }
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.editor_marker_add))
                }
                IconButton(onClick = { insertCandidates = null; scope.launch { insertCandidates = loadLibrary() } }) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = stringResource(R.string.editor_insert_audio),
                    )
                }
            }

            if (exporting) {
                LinearProgressIndicator(
                    progress = { exportProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // --- what the picked stretch can be turned into ---
            if (pickedCutMs != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.editor_marker_picked, formatDuration(pickedCutMs!!)),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val at = pickedCutMs
                        pickedCutMs = null
                        if (at != null) {
                            afterEdit(EditTrack.removeCutAt(clips, at))
                            notice = context.getString(R.string.editor_marker_deleted)
                        }
                    }) {
                        Text(stringResource(R.string.editor_marker_delete, formatDuration(pickedCutMs!!)))
                    }
                }
            } else {
                SegmentActionsRow(
                    segment = picked,
                    enabled = pickedHasAudio,
                    repeatEnabled = picked != null,
                    onDelete = {
                        picked?.let { pendingEdit = PendingEdit.DeleteSegment(it) }
                    },
                    onKeepOnly = {
                        picked?.let { pendingEdit = PendingEdit.KeepOnly(it) }
                    },
                    onRepeat = {
                        picked?.let { pendingEdit = PendingEdit.Repeat(it) }
                    },
                )
            }

            notice?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            // --- the track, in play order ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.editor_merge_order),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                clips.forEachIndexed { index, clip ->
                    val name = waveforms[clip.file.path]?.displayName ?: clip.file.name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(20.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            Text(
                                "${formatDuration(EditTrack.outputStartOf(clips, index))} · " +
                                    formatDuration(clip.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        TextButton(
                            onClick = {
                                if (index > 0) afterEdit(EditOrder.move(clips, index, index - 1))
                            },
                            enabled = index > 0,
                        ) { Text("↑") }
                        TextButton(
                            onClick = {
                                if (index < clips.size - 1) {
                                    afterEdit(EditOrder.move(clips, index, index + 1))
                                }
                            },
                            enabled = index < clips.size - 1,
                        ) { Text("↓") }
                        TextButton(
                            onClick = { afterEdit(clips.filterIndexed { at, _ -> at != index }) },
                        ) { Text("✕") }
                    }
                }
                Spacer(Modifier.height(24.dp))
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
                val others = candidates.filter { candidate ->
                    clips.none { it.file == candidate.file } || true
                }
                if (others.isEmpty()) {
                    Text(stringResource(R.string.editor_insert_none))
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        others.forEach { candidate ->
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
 * A seek lands on the frame it lands on, not exactly on the millisecond that was
 * asked for, and a player that has not been seeked yet reports zero — so the
 * comparison needs enough slack to accept the landing, and nowhere near enough to
 * accept the zero it is meant to reject.
 */
private const val START_TOLERANCE_MS = 250L
