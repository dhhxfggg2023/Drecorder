package dev.drecorder.ui.editor

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import dev.drecorder.audio.edit.AudioFormatProbe
import dev.drecorder.audio.edit.EditComposition
import dev.drecorder.audio.edit.EditExportResult
import dev.drecorder.audio.edit.EditExporter
import dev.drecorder.audio.edit.EditFormats
import dev.drecorder.audio.edit.EditFollow
import dev.drecorder.audio.edit.EditMarkerPlan
import dev.drecorder.audio.edit.EditMarkers
import dev.drecorder.audio.edit.EditPlan
import dev.drecorder.audio.edit.EditSegment
import dev.drecorder.audio.edit.EditPlanner
import dev.drecorder.audio.edit.EditSilence
import dev.drecorder.audio.edit.EditSnapping
import dev.drecorder.audio.edit.EditTimeline
import dev.drecorder.audio.edit.EditViewport
import dev.drecorder.audio.edit.EditViewportMath
import dev.drecorder.audio.edit.EditWindow
import dev.drecorder.audio.edit.KeepRange
import dev.drecorder.audio.edit.PeakData
import dev.drecorder.audio.edit.PeakDecodeGate
import dev.drecorder.audio.edit.PeakDecoder
import dev.drecorder.audio.edit.PeakLod
import dev.drecorder.audio.edit.PeaksCache
import dev.drecorder.audio.edit.SourceSpan
import dev.drecorder.data.DerivedMarker
import dev.drecorder.data.Recording
import dev.drecorder.data.SilentMarker
import dev.drecorder.data.formatDuration
import dev.drecorder.playback.CompositionPreviewPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One recording being edited.
 *
 * The waveform and the keep list travel together: the waveform is what the user
 * looks at, the keep list is what the exporter is given. The markers are the
 * user's cut points and live in the same place (DESIGN §12.4).
 */
data class EditorSource(
    val file: File,
    val durationMs: Long,
    val displayName: String,
    /**
     * The whole recording's waveform, at the coarse resolution.
     *
     * It covers the whole file, so it is always something to draw, and it is
     * what the timeline falls back to while the finer waveform of the visible
     * window is being decoded.
     */
    val peaks: PeakData? = null,
    /**
     * The visible window's waveform, at a finer resolution.
     *
     * `null` means the coarse waveform is good enough — the timeline then
     * draws the coarse one and nothing is decoded.
     */
    val detailPeaks: PeakData? = null,
    val keep: List<KeepRange> = emptyList(),
    /**
     * The cut points, in source time.
     *
     * A marker is at once a bookmark and a cut point: the stretch between two
     * markers is a segment that can be taken out (DESIGN §12.4).
     */
    val markers: List<Long> = emptyList(),
    val loading: Boolean = false,
    /** How far the waveform generation has got, 0..100; -1 when not known. */
    val loadProgress: Int = -1,
    val loadError: String? = null,
)

/**
 * The editor.
 *
 * One waveform, one playhead, and the markers the user cut on — the way a
 * video editor works (DESIGN §12.4). The screen itself only draws and
 * forwards intent, which is why the interesting logic lives in core-audio and
 * can be unit-tested on the JVM.
 *
 * One source is a trim; several sources are a merge. The difference is only in
 * how many sources the plan holds — the same waveform, the same gestures, the
 * same exporter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    recordings: List<Recording>,
    onBack: () -> Unit,
    onExported: (File) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current

    var sources by remember { mutableStateOf<List<EditorSource>>(emptyList()) }
    var selectedIndex by remember { mutableStateOf(0) }
    var snapEnabled by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Non-null when the sources cannot be spliced without re-encoding. The merge is
    // then refused outright instead of quietly transcoding (DESIGN §12.1).
    var formatMismatch by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0) }
    var previewPlaying by remember { mutableStateOf(false) }
    var previewPositionMs by remember { mutableStateOf(0L) }
    var previewDurationMs by remember { mutableStateOf(0L) }

    /**
     * The playhead, in the **source** time of the recording on screen.
     *
     * The playhead is kept in source time rather than in the time of the
     * merged stream, because the waveform it sits on is a source's own
     * timeline (DESIGN §12.4).
     */
    var playheadMs by remember { mutableStateOf(0L) }
    /** The marker the user picked, if any. */
    var selectedMarkerMs by remember { mutableStateOf<Long?>(null) }
    /**
     * Whether a finger is on the waveform right now.
     *
     * While it is, the window belongs to the finger and not to the playhead:
     * the two would otherwise fight over the same screen, and the waveform
     * would jump back under the finger every 100 ms.
     */
    var waveformDragging by remember { mutableStateOf(false) }
    /**
     * The stretch the user is about to delete, waiting for a confirmation.
     *
     * Deleting a stretch of audio cannot be undone, so the button asks
     * first: the segment is held here until the user says yes (DESIGN §12.4).
     */
    var pendingSegment by remember { mutableStateOf<EditSegment?>(null) }

    // The decoder checks this between chunks, so a long waveform generation can
    // be stopped. It is reset for every source that is decoded.
    val decodeCancelled = remember { AtomicBoolean(false) }

    // The window decodes have their own flag: the user who cancels the detail
    // waveform must not also cancel the waveform of the whole recording, and
    // the other way round.
    //
    // The gate is what makes a fast gesture affordable: every window that
    // comes along stops the decode that is still running for the previous
    // one. Without it, a pinch would leave a dozen decodes behind, each of
    // them busy with a window nobody looks at any more.
    val windowDecodeGate = remember { PeakDecodeGate() }

    // The window is shared by all sources so that a zoom level means the same
    // thing wherever the user looks.
    var viewportStartMs by remember { mutableStateOf(0L) }
    var viewportSpanMs by remember { mutableStateOf(0L) }

    val previewPlayer = remember { CompositionPreviewPlayer(context) }
    val exporter = remember { EditExporter(context) }
    val derivedMarker = remember { DerivedMarker(context) }
    val silentMarker = remember { SilentMarker(context) }

    // --- load the waveforms ----------------------------------------------------
    LaunchedEffect(recordings) {
        if (recordings.isEmpty()) return@LaunchedEffect
        val initial = recordings.map { recording ->
            EditorSource(
                file = recording.file,
                durationMs = recording.durationMs,
                displayName = recording.displayName,
                keep = EditPlanner.fullLength(recording.durationMs),
            )
        }
        sources = initial
        // The timeline opens on a span that shows a phrase rather than a
        // hairline, and the user zooms out from there (DESIGN §12.4).
        val opening = EditMarkerPlan.initialSpanMs(initial.first().durationMs)
        viewportSpanMs = opening
        viewportStartMs = 0L

        // Transmuxing cannot convert one encoding into another, so a merge of
        // mismatched recordings is refused here, by name, rather than silently
        // transcoded by the exporter (DESIGN §12.1).
        if (recordings.size > 1) {
            val specs = withContext(Dispatchers.IO) {
                recordings.map { recording ->
                    recording.displayName to AudioFormatProbe.probe(recording.file)
                }
            }
            val base = specs.firstOrNull()?.second
            if (base != null) {
                val clashes = EditFormats.incompatible(
                    base = base,
                    others = specs.drop(1).map { (name, spec) ->
                        name to (spec ?: base.copy(mimeType = "未知"))
                    },
                )
                if (clashes.isNotEmpty()) {
                    formatMismatch = clashes.joinToString("；") { "${it.first}：${it.second}" }
                }
            }
        }

        for (index in initial.indices) {
            val recording = recordings[index]
            val cacheFile = PeaksCache.fileFor(recording.file, context.filesDir)
            val cached = withContext(Dispatchers.IO) { PeaksCache.read(cacheFile, recording.file) }
            if (cached != null) {
                sources = sources.replaceAt(index) { it.copy(peaks = cached.data) }
                continue
            }
            sources = sources.replaceAt(index) { it.copy(loading = true, loadProgress = 0) }
            val decoded = withContext(Dispatchers.IO) {
                PeakDecoder().decode(
                    recording.file,
                    onProgress = { percent ->
                        // Progress comes from the decoder thread, so it is
                        // posted to the main thread; without it the UI would
                        // read a stale value or touch state off-thread.
                        scope.launch {
                            sources = sources.replaceAt(index) {
                                it.copy(loadProgress = percent)
                            }
                        }
                    },
                    // A long recording is decoded in segments, and the
                    // waveform of the part that is already done is handed
                    // over after every segment: the timeline fills in while
                    // the rest is still being decoded.
                    onPartial = { partial ->
                        scope.launch {
                            sources = sources.replaceAt(index) { it.copy(peaks = partial) }
                        }
                    },
                    cancelled = decodeCancelled,
                )
            }
            sources = when (decoded) {
                is PeakDecoder.Result.Success -> {
                    withContext(Dispatchers.IO) {
                        runCatching { PeaksCache.write(cacheFile, decoded.data, recording.file) }
                    }
                    sources.replaceAt(index) { it.copy(peaks = decoded.data, loading = false) }
                }
                is PeakDecoder.Result.Failure ->
                    sources.replaceAt(index) {
                        it.copy(
                            loading = false,
                            loadProgress = -1,
                            loadError = when (decoded.reason) {
                                PeakDecoder.Reason.Cancelled ->
                                    context.getString(R.string.editor_waveform_cancelled)

                                PeakDecoder.Reason.Stalled ->
                                    context.getString(R.string.editor_waveform_stalled)

                                PeakDecoder.Reason.Unknown -> decoded.message
                            },
                        )
                    }
            }
            // The next source starts with a fresh cancel flag.
            decodeCancelled.set(false)
        }
    }

    // --- preview ---------------------------------------------------------------
    val plan = remember(sources) {
        EditPlan(
            sources.map { source ->
                SourceSpan(
                    file = source.file,
                    durationMs = source.durationMs,
                    keep = source.keep,
                )
            },
        )
    }

    /**
     * Moves the playhead of the merged preview to a moment of the source on
     * screen.
     *
     * A moment that was cut away has no position in the merged stream, so the
     * nearest moment that still exists is used: the user meant the audio
     * around there, not silence (DESIGN §12.4).
     */
    fun seekToSourceMs(sourceIndex: Int, sourceTimeMs: Long) {
        val output = EditTimeline.nearestOutputMsForSourceTime(plan, sourceIndex, sourceTimeMs)
        if (output != null) {
            previewPlayer.seekTo(output)
            previewPositionMs = output
        }
    }

    LaunchedEffect(previewPlaying) {
        while (previewPlaying) {
            previewPositionMs = previewPlayer.positionMs
            previewDurationMs = previewPlayer.durationMs
            delay(POLL_INTERVAL_MS)
        }
    }

    LaunchedEffect(Unit) {
        previewPlayer.finished.collect {
            previewPlaying = false
        }
    }

    LaunchedEffect(Unit) {
        previewPlayer.failed.collect { error ->
            previewPlaying = false
            notice = error.message ?: context.getString(R.string.editor_preview_failed)
        }
    }

    // The editor is a portrait screen. The landscape timeline was tried and
    // dropped: it turned the phone without laying the page out for it, and
    // the user was left with a view that did not work (DESIGN §12.4).
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            previewPlayer.release()
            // The screen belongs to the app again once the editor is closed.
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val current = sources.getOrNull(selectedIndex)

    /**
     * The source that is sounding right now.
     *
     * While the merged preview plays, the timeline follows the audio: the
     * recording that is playing is the one on screen, so the user watches the
     * waveform of the part they are hearing (DESIGN §12.4).
     */
    val playingIndex = remember(previewPositionMs, plan) {
        plan.sources.indices.firstOrNull { index ->
            EditTimeline.sourceTimeForOutputMs(plan, index, previewPositionMs) != null
        }
    }

    // While something plays, the playhead and the window follow it.
    //
    // `waveformDragging` is part of the key on purpose: the effect reads it
    // when it runs, and it has to run again when the finger is lifted, or
    // the window would stay wherever the drag left it.
    LaunchedEffect(previewPlaying, previewPositionMs, plan, waveformDragging) {
        if (!previewPlaying) return@LaunchedEffect
        val index = playingIndex ?: return@LaunchedEffect
        val sourceTime = EditTimeline.sourceTimeForOutputMs(plan, index, previewPositionMs)
            ?: return@LaunchedEffect
        playheadMs = sourceTime
        if (index != selectedIndex) {
            selectedIndex = index
            selectedMarkerMs = null
        }
        // The window scrolls so that the playhead stays where it is on the
        // screen and the waveform moves under it.
        val total = sources.getOrNull(index)?.durationMs ?: return@LaunchedEffect
        val next = EditFollow.follow(
            current = EditViewport(viewportStartMs, viewportSpanMs),
            playheadMs = sourceTime,
            totalMs = total,
            // A finger on the waveform owns the window while it is down.
            follow = !waveformDragging,
        )
        viewportStartMs = next.startMs
    }

    // --- the visible window's finer waveform ------------------------------------
    //
    // The coarse waveform covers the whole recording and is enough to draw
    // the timeline from. The window wants more resolution than the coarse one
    // can carry, and decoding the whole file again for that would take
    // minutes. Instead only the visible window is decoded — a few seconds of
    // audio, which takes milliseconds (DESIGN §12.4).
    //
    // The resolution needed is decided by the window and the screen width, and
    // it is one of a fixed ladder, so the same window always asks for the same
    // one and the result can be cached.
    val detailResolution = remember(viewportSpanMs) {
        PeakLod.bucketsPerSecondFor(viewportSpanMs, DETAIL_WIDTH_PX)
    }
    // The finer waveform is needed whenever the coarse one cannot carry the
    // window's resolution. That is true even while the coarse waveform is
    // still being decoded: the window is a few seconds of audio and is ready
    // in milliseconds, while the whole recording takes minutes. Waiting for
    // the coarse one first is what made the editor feel broken.
    val needsDetail = remember(current?.peaks, detailResolution) {
        val cached = current?.peaks
        cached == null || PeakLod.needsFinerWaveform(
            spanMs = viewportSpanMs,
            widthPx = DETAIL_WIDTH_PX,
            cachedBucketsPerSecond = cached.bucketsPerSecond,
        )
    }

    LaunchedEffect(current?.file, viewportStartMs, viewportSpanMs, detailResolution) {
        val source = current ?: return@LaunchedEffect
        // Starting a new window stops the decode of the previous one: a
        // pinch gesture moves the window many times a second, and every one
        // of those windows would otherwise be decoded.
        val cancelled = windowDecodeGate.start()
        if (!needsDetail) {
            if (source.detailPeaks != null) {
                sources = sources.replaceAt(selectedIndex) { it.copy(detailPeaks = null) }
            }
            return@LaunchedEffect
        }
        // Only the part of the file that the window shows is decoded. The
        // window may reach before the recording — the playhead stands in the
        // middle of the screen — and the time before the recording has no
        // audio in it (DESIGN §12.4).
        val range = EditWindow.fileRange(
            viewport = EditViewport(viewportStartMs, viewportSpanMs),
            totalMs = source.durationMs,
        ) ?: return@LaunchedEffect
        val windowStart = viewportStartMs
        val windowSpan = viewportSpanMs
        val start = range.first
        val end = range.last + 1
        val result = withContext(Dispatchers.IO) {
            PeakDecoder().decodeWindow(
                file = source.file,
                startMs = start,
                endMs = end,
                bucketsPerSecond = detailResolution,
                cancelled = cancelled,
            )
        }
        // The window may have moved while the decode ran; a result for the old
        // window is stale and is dropped rather than shown.
        if (windowStart != viewportStartMs || windowSpan != viewportSpanMs) {
            return@LaunchedEffect
        }
        if (result is PeakDecoder.Result.Success) {
            sources = sources.replaceAt(selectedIndex) { it.copy(detailPeaks = result.data) }
        }
    }

    // A decode that is still running when the editor is closed is a decode
    // nobody waits for.
    DisposableEffect(Unit) {
        onDispose {
            windowDecodeGate.stop()
        }
    }

    val removed = current?.let {
        EditPlanner.complement(it.keep, it.durationMs)
    } ?: emptyList()

    // The waveform the timeline draws: the fine one when it belongs to the
    // window that is on screen, the coarse one otherwise. A fine waveform of
    // an earlier window would show the wrong part of the recording.
    val visiblePeaks = remember(
        current?.detailPeaks,
        current?.peaks,
        viewportStartMs,
        viewportSpanMs,
        detailResolution,
    ) {
        val fine = current?.detailPeaks
        // The fine waveform has to be *this* window's, and it has to be at
        // least as fine as the window needs.
        //
        // The length is compared with a tolerance: the decoder stops feeding
        // at the end of the window, and the last compressed frame reaches a
        // little past it — or a little short of it, depending on where the
        // frame boundary falls. Rejecting the waveform over a fraction of a
        // frame would leave the user with the coarse one forever.
        val coversWindow = EditWindow.covers(
            peaks = fine,
            viewport = EditViewport(viewportStartMs, viewportSpanMs),
            totalMs = current?.durationMs ?: 0L,
            requiredBucketsPerSecond = detailResolution,
            toleranceMs = FRAME_TOLERANCE_MS,
        )
        if (coversWindow) fine else current?.peaks
    }

    // The segment the "delete" button is about: the one that starts at the
    // marker the user picked, or the one the playhead is in.
    val currentSegment = remember(current?.markers, selectedMarkerMs, playheadMs, current?.durationMs) {
        val markers = current?.markers.orEmpty()
        val total = current?.durationMs ?: 0L
        val picked = selectedMarkerMs
        if (picked != null) {
            val end = EditMarkers.next(markers, picked) ?: total
            EditSegment(picked, maxOf(picked, end))        } else {
            EditMarkers.segmentContaining(markers, playheadMs, total)
        }
    }
    val segmentHasAudio = current?.let {
        EditMarkers.segmentHasAudio(it.keep, currentSegment)
    } ?: false

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (sources.size > 1) {
                                stringResource(R.string.editor_title_merged, sources.size)
                            } else {
                                stringResource(R.string.editor_title)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.editor_kept_length,
                                formatDuration(plan.keptDurationMs),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.record_back),
                        )
                    }
                },
                actions = {
                    // The editor locks the screen into portrait. The
                    // landscape timeline was a half-finished idea: it turned
                    // the phone, it did not lay the page out for it, and it
                    // left the user with a view they had to fix. The
                    // portrait timeline is the one that works (DESIGN §12.4).
                    IconButton(                        onClick = {
                            val total = current?.durationMs ?: 0L
                            viewportSpanMs = EditMarkerPlan.fitSpanMs(total)
                            viewportStartMs = 0L
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ZoomOutMap,
                            contentDescription = stringResource(R.string.editor_zoom_fit),
                        )
                    }

                    TextButton(
                        enabled = !exporting && plan.keptDurationMs > 0 && formatMismatch == null,
                        onClick = {
                            scope.launch {
                                exporting = true
                                exportProgress = 0
                                val result = exporter.export(plan) { percent -> exportProgress = percent }
                                exporting = false
                                when (result) {
                                    is EditExportResult.Success -> {
                                        // A derived file is a new recording: it gets
                                        // its own "edited" mark, and its silence is
                                        // measured again rather than inherited
                                        // (DESIGN §12.3).
                                        derivedMarker.markDerived(result.file.name)
                                        val silent = EditSilence.keptAudioIsSilent(
                                            sources.map { source ->
                                                source.keep to source.peaks
                                            },
                                        )
                                        silentMarker.write(result.file.name, wasSilent = silent)
                                        notice = context.getString(
                                            R.string.editor_exported,
                                            result.file.name,
                                        )
                                        onExported(result.file)
                                    }

                                    is EditExportResult.Failure -> {
                                        notice = result.message
                                    }
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.editor_export))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (sources.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.editor_empty))
                }
                return@Column
            }

            if (sources.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sources.forEachIndexed { index, source ->
                        FilterChip(
                            selected = index == selectedIndex,
                            onClick = {
                                selectedIndex = index
                                selectedMarkerMs = null
                                // The window is shared by all the sources, but
                                // one source may be shorter than the window
                                // that is on screen: the window is pulled back
                                // onto the recording that was just chosen,
                                // otherwise the timeline would show a
                                // stretch of audio that does not exist.
                                val total = sources.getOrNull(index)?.durationMs
                                    ?.coerceAtLeast(1L) ?: return@FilterChip
                                val span = viewportSpanMs.coerceIn(
                                    EditViewportMath.MIN_SPAN_MS.coerceAtMost(total),
                                    total,
                                )
                                viewportSpanMs = span
                                viewportStartMs = viewportStartMs.coerceIn(0L, total - span)
                                playheadMs = playheadMs.coerceIn(0L, total)
                            },
                            label = {
                                Text(
                                    text = source.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }

            // --- the waveform: one track, with the playhead and the markers ---
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                ) {
                    when {
                        current == null -> Unit

                        // A waveform that exists is drawn, even while the rest
                        // is still being decoded. A spinner that covers the
                        // whole view is what made a long recording look
                        // broken: the detail waveform is ready in milliseconds
                        // and the coarse one takes minutes.
                        visiblePeaks != null -> Box {
                            WaveformView(
                                // The finer waveform of the visible window
                                // wins when it is there; the coarse one is
                                // the fallback while the window is being
                                // decoded.
                                peaks = visiblePeaks,
                                durationMs = current.durationMs,
                                startMs = viewportStartMs,
                                spanMs = viewportSpanMs,
                                removed = removed,
                                markers = current.markers,
                                playheadMs = playheadMs,
                                onScrub = { ms ->
                                    playheadMs = ms
                                    seekToSourceMs(selectedIndex, ms)
                                },
                                onTimelineDrag = { grabbedMs, targetMs ->
                                    // The waveform follows the finger, and
                                    // the playhead follows the waveform: the
                                    // moment the user grabbed is the moment
                                    // that is now playing.
                                    val total = current.durationMs
                                    val next = EditFollow.dragged(
                                        current = EditViewport(viewportStartMs, viewportSpanMs),
                                        grabbedMs = grabbedMs,
                                        targetMs = targetMs,
                                        totalMs = total,
                                    )
                                    viewportStartMs = next.startMs
                                    playheadMs = grabbedMs
                                    seekToSourceMs(selectedIndex, grabbedMs)
                                },
                                onMarkerDrag = { fromMs, toMs ->
                                    sources = sources.replaceAt(selectedIndex) {
                                        it.copy(
                                            markers = EditMarkers.move(
                                                markers = it.markers,
                                                fromMs = fromMs,
                                                toMs = toMs,
                                                totalMs = it.durationMs,
                                            ),
                                        )
                                    }
                                },
                                onViewportGesture = { zoom, panFraction, focusFraction ->
                                    // The maths lives in core-audio so it can be
                                    // unit-tested: a window that leaves the recording
                                    // would show a waveform that matches no audio.
                                    val total = sources.maxOf { it.durationMs }.coerceAtLeast(1L)
                                    val next = EditViewportMath.apply(
                                        current = EditViewport(viewportStartMs, viewportSpanMs),
                                        totalMs = total,
                                        zoomFactor = zoom,
                                        panFraction = panFraction,
                                        focusFraction = focusFraction,
                                    )
                                    viewportStartMs = next.startMs
                                    viewportSpanMs = next.spanMs
                                },
                                onDraggingChanged = { dragging ->
                                    waveformDragging = dragging
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            // A quiet hint in the corner, so the user knows
                            // the waveform is still being sharpened.
                            if (current.loading || current.loadProgress in 0..99) {
                                Text(
                                    text = stringResource(
                                        R.string.editor_waveform_loading,
                                        current.loadProgress.coerceAtLeast(0),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(6.dp),
                                )
                            }
                        }

                        current.loading -> Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            if (current.loadProgress >= 0) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(
                                        R.string.editor_waveform_loading,
                                        current.loadProgress,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = {
                                    decodeCancelled.set(true)
                                },
                            ) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }

                        else -> Text(
                            text = current.loadError
                                ?: stringResource(R.string.editor_waveform_failed),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- the zoom: the window onto the recording ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = {
                        val total = current?.durationMs ?: 0L
                        val next = EditMarkerPlan.nextSpanMs(
                            current = viewportSpanMs,
                            zoomIn = true,
                            totalMs = total,
                        )
                        viewportSpanMs = next
                        viewportStartMs = viewportStartMs.coerceIn(
                            0L,
                            (total - next).coerceAtLeast(0L),
                        )
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.ZoomIn,
                        contentDescription = stringResource(R.string.editor_zoom_in),
                    )
                }
                IconButton(
                    onClick = {
                        val total = current?.durationMs ?: 0L
                        val next = EditMarkerPlan.nextSpanMs(
                            current = viewportSpanMs,
                            zoomIn = false,
                            totalMs = total,
                        )
                        viewportSpanMs = next
                        viewportStartMs = viewportStartMs.coerceIn(
                            0L,
                            (total - next).coerceAtLeast(0L),
                        )
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.ZoomOut,
                        contentDescription = stringResource(R.string.editor_zoom_out),
                    )
                }
                Text(
                    text = stringResource(
                        R.string.editor_span,
                        formatDuration(viewportSpanMs),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
            }

            // --- the transport: play, and where the playhead is ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButton(
                    onClick = {
                        if (previewPlaying) {
                            previewPlayer.stop()
                            previewPlaying = false
                        } else {
                            if (formatMismatch != null) {
                                notice = context.getString(
                                    R.string.editor_format_mismatch,
                                    formatMismatch.orEmpty(),
                                )
                            } else {
                                val composition = EditComposition.build(plan)
                                if (previewPlayer.play(composition)) {
                                    previewPlaying = true
                                    previewPositionMs = 0L
                                } else {
                                    notice = context.getString(R.string.editor_preview_failed)
                                }
                            }
                        }
                    },
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        imageVector = if (previewPlaying) {
                            Icons.Filled.Stop
                        } else {
                            Icons.Filled.PlayArrow
                        },
                        contentDescription = stringResource(R.string.editor_preview),
                        modifier = Modifier.size(36.dp),
                    )
                }

                Text(
                    text = stringResource(
                        R.string.editor_position,
                        formatDuration(playheadMs),
                        formatDuration(current?.durationMs ?: 0L),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )

                Spacer(modifier = Modifier.weight(1f))

                TextButton(onClick = { snapEnabled = !snapEnabled }) {
                    Text(
                        text = stringResource(
                            if (snapEnabled) {
                                R.string.editor_snap_on
                            } else {
                                R.string.editor_snap_off
                            },
                        ),
                        fontWeight = if (snapEnabled) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Normal
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // --- the markers: the cut points ---
            MarkerBar(
                markers = current?.markers.orEmpty(),
                durationMs = current?.durationMs ?: 0L,
                selectedMarkerMs = selectedMarkerMs,
                onSelect = { ms ->
                    selectedMarkerMs = ms
                    // A marker is a place to jump to.
                    playheadMs = ms
                    seekToSourceMs(selectedIndex, ms)
                    val total = current?.durationMs ?: 0L
                    val next = EditFollow.centered(
                        current = EditViewport(viewportStartMs, viewportSpanMs),
                        targetMs = ms,
                        totalMs = total,
                    )
                    viewportStartMs = next.startMs
                },
                onAddAtPlayhead = {
                    val source = current ?: return@MarkerBar
                    val raw = playheadMs
                    // A cut is nudged to the nearest pause: cutting in the
                    // middle of a word is the most common way to make an edit
                    // sound wrong (DESIGN §12.4).
                    val snapped = EditSnapping.snapTime(
                        peaks = source.peaks,
                        timeMs = raw,
                        enabled = snapEnabled,
                    )
                    sources = sources.replaceAt(selectedIndex) {
                        it.copy(
                            markers = EditMarkers.insert(
                                markers = it.markers,
                                atMs = snapped,
                                totalMs = it.durationMs,
                            ),
                        )
                    }
                    selectedMarkerMs = snapped
                },
                onDelete = { ms ->
                    sources = sources.replaceAt(selectedIndex) {
                        it.copy(
                            markers = EditMarkers.removeAt(
                                markers = it.markers,
                                atMs = ms,
                            ),
                        )
                    }
                    if (selectedMarkerMs == ms) selectedMarkerMs = null
                },
            )

            Spacer(modifier = Modifier.height(4.dp))

            // --- the cut: take the stretch between two markers out ---
            SegmentDeleteRow(
                segment = currentSegment,
                enabled = segmentHasAudio,
                onDelete = {
                    // Deleting a stretch of audio cannot be undone, so the
                    // button asks first (DESIGN §12.4).
                    pendingSegment = currentSegment
                },
            )

            // --- the old range cut, kept for the stretches without markers ---
            if (current != null && removed.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Restore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.editor_removed_summary,
                            removed.sumOf { it.durationMs }.let { formatDuration(it) },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (exporting) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { exportProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.editor_exporting, exportProgress),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            notice?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            formatMismatch?.let { reason ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = context.getString(R.string.editor_format_mismatch, reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // --- the confirmation before a stretch of audio is taken out ---
    //
    // A cut cannot be undone: the audio is gone from the plan and only the
    // export is left to undo it. The button therefore asks first, and says
    // which stretch it is about to remove (DESIGN §12.4).
    pendingSegment?.let { segment ->
        AlertDialog(
            onDismissRequest = { pendingSegment = null },
            title = { Text(stringResource(R.string.editor_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.editor_delete_confirm_body,
                        formatDuration(segment.startMs),
                        formatDuration(segment.endMs),
                        formatDuration(segment.durationMs),
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSegment = null
                        val source = current ?: return@TextButton
                        val result = EditMarkers.deleteSegment(
                            keep = source.keep,
                            markers = source.markers,
                            segment = segment,
                            durationMs = source.durationMs,
                        )
                        sources = sources.replaceAt(selectedIndex) {
                            it.copy(keep = result.keep, markers = result.markers)
                        }
                        selectedMarkerMs = null
                    },
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSegment = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** Replaces one source in the list, or returns the list unchanged if it is gone. */
private fun List<EditorSource>.replaceAt(
    index: Int,
    transform: (EditorSource) -> EditorSource,
): List<EditorSource> =
    if (index !in indices) {
        this
    } else {
        toMutableList().also { it[index] = transform(it[index]) }
    }

/**
 * The width the detail view is assumed to have, in pixels.
 *
 * The resolution the window needs depends on how many columns it is drawn in,
 * and the real width is only known once the layout has run. A phone screen is
 * roughly 1080 pixels wide, and asking for a waveform that is one step finer
 * than that is not worth a second decode — so the assumption is a wide one and
 * the ladder absorbs the difference.
 */
private const val DETAIL_WIDTH_PX = 1_080

/**
 * How much the length of a decoded window may fall short of the window it
 * was asked for, in ms.
 *
 * An AAC frame holds 1024 samples — about 21 ms at 48 kHz — and the decoder
 * stops feeding at the end of the window, so the waveform it produces ends
 * within one frame of the window's end. Rejecting it over that would mean
 * the detail view never shows the fine waveform at all.
 */
private const val FRAME_TOLERANCE_MS = 50L

/**
 * How often the playhead is read while the preview plays, in ms.
 *
 * A tenth of a second is fast enough for the playhead to look smooth and
 * slow enough to leave the phone's battery alone.
 */
private const val POLL_INTERVAL_MS = 100L
