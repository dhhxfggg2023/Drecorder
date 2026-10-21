package dev.drecorder.ui.editor

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Stop
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
import dev.drecorder.audio.edit.EditHandle
import dev.drecorder.audio.edit.EditOverview
import dev.drecorder.audio.edit.EditPlan
import dev.drecorder.audio.edit.EditPlanner
import dev.drecorder.audio.edit.EditSilence
import dev.drecorder.audio.edit.EditSnapping
import dev.drecorder.audio.edit.EditTimeline
import dev.drecorder.audio.edit.EditViewport
import dev.drecorder.audio.edit.EditViewportMath
import dev.drecorder.audio.edit.KeepRange
import dev.drecorder.audio.edit.PeakData
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
 * looks at, the keep list is what the exporter is given.
 */
data class EditorSource(
    val file: File,
    val durationMs: Long,
    val displayName: String,
    /**
     * The whole recording's waveform, at the coarse resolution.
     *
     * It is what the overview strip draws and what the detail view falls back
     * to when the visible window does not need anything finer.
     */
    val peaks: PeakData? = null,
    /**
     * The visible window's waveform, at a finer resolution.
     *
     * `null` means the coarse waveform is good enough — the detail view then
     * draws the coarse one and nothing is decoded.
     */
    val detailPeaks: PeakData? = null,
    val keep: List<KeepRange> = emptyList(),
    val loading: Boolean = false,
    /** How far the waveform generation has got, 0..100; -1 when not known. */
    val loadProgress: Int = -1,
    val loadError: String? = null,
)

/**
 * The editor.
 *
 * The whole model is "a list of ranges to keep" per source (DESIGN §12.2), and
 * every gesture ends up calling [EditPlanner]. The screen itself only draws and
 * forwards intent, which is why the interesting logic can be unit-tested on the
 * JVM.
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

    var sources by remember { mutableStateOf<List<EditorSource>>(emptyList()) }
    var selectedIndex by remember { mutableStateOf(0) }
    var selection by remember { mutableStateOf<TimeSelection?>(null) }
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

    // The decoder checks this between chunks, so a long waveform generation can
    // be stopped. It is reset for every source that is decoded.
    val decodeCancelled = remember { AtomicBoolean(false) }

    // The window decodes have their own flag: the user who cancels the detail
    // waveform must not also cancel the waveform of the whole recording, and
    // the other way round.
    val windowDecodeCancelled = remember { AtomicBoolean(false) }

    // The viewport is shared by all sources so that a zoom level means the same
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
        // The detail view opens on a short window rather than on the whole
        // recording: thirty seconds is what a waveform can actually show, and
        // the overview strip above it carries the map of the rest
        // (DESIGN §12.4).
        val longest = initial.maxOf { it.durationMs }.coerceAtLeast(1L)
        val opening = EditOverview.initialViewport(longest)
        viewportSpanMs = opening.spanMs
        viewportStartMs = opening.startMs

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
                    // over after every segment: the overview fills in while
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
    LaunchedEffect(previewPlaying) {
        while (previewPlaying) {
            previewPositionMs = previewPlayer.positionMs
            previewDurationMs = previewPlayer.durationMs
            delay(200)
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

    DisposableEffect(Unit) {
        onDispose {
            previewPlayer.release()
        }
    }

    val current = sources.getOrNull(selectedIndex)

    // --- the detail window's finer waveform ------------------------------------
    //
    // The coarse waveform covers the whole recording and is enough for the
    // overview. The detail view wants more resolution than the coarse one can
    // carry, and decoding the whole file again for that would take minutes.
    // Instead only the visible window is decoded — a few seconds of audio,
    // which takes milliseconds (DESIGN §12.4).
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
        // A window that was cancelled stays cancelled until the window moves
        // again, so the user is not spammed with a decode they just refused.
        windowDecodeCancelled.set(false)
        if (!needsDetail) {
            if (source.detailPeaks != null) {
                sources = sources.replaceAt(selectedIndex) { it.copy(detailPeaks = null) }
            }
            return@LaunchedEffect
        }
        val start = viewportStartMs
        val end = viewportStartMs + viewportSpanMs
        val result = withContext(Dispatchers.IO) {
            PeakDecoder().decodeWindow(
                file = source.file,
                startMs = start,
                endMs = end,
                bucketsPerSecond = detailResolution,
                cancelled = windowDecodeCancelled,
            )
        }
        // The window may have moved while the decode ran; a result for the old
        // window is stale and is dropped rather than shown.
        if (start != viewportStartMs || end != viewportStartMs + viewportSpanMs) {
            return@LaunchedEffect
        }
        if (result is PeakDecoder.Result.Success) {
            sources = sources.replaceAt(selectedIndex) { it.copy(detailPeaks = result.data) }
        }
    }

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
    val removed = current?.let {
        EditPlanner.complement(it.keep, it.durationMs)
    } ?: emptyList()

    // The waveform the detail view draws: the fine one when it belongs to the
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
        // least as fine as the window needs. The length is compared with a
        // tolerance of one bucket: the last bucket of a window is usually
        // short, and rejecting the waveform over that would leave the user
        // with the coarse one forever.
        val bucketMs = 1_000L / (fine?.bucketsPerSecond?.coerceAtLeast(1) ?: 1).toLong()
        val coversWindow = fine != null &&
            fine.offsetMs == viewportStartMs &&
            fine.bucketsPerSecond >= detailResolution &&
            fine.endMs + bucketMs >= viewportStartMs + viewportSpanMs
        if (coversWindow) fine else current?.peaks
    }

    // The source the preview is currently playing, so the playhead can sit on
    // the recording that is actually sounding.
    val playingIndex = remember(previewPositionMs, plan) {
        plan.sources.indices.firstOrNull { index ->
            EditTimeline.sourceTimeForOutputMs(plan, index, previewPositionMs) != null
        }
    }
    val playheadMs = playingIndex?.let { index ->
        EditTimeline.sourceTimeForOutputMs(plan, index, previewPositionMs)
    }

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
                                selection = null
                                // The window is shared by all the sources, but
                                // one source may be shorter than the window
                                // that is on screen: the window is pulled back
                                // onto the recording that was just chosen,
                                // otherwise the detail view would show a
                                // stretch of audio that does not exist.
                                val total = sources.getOrNull(index)?.durationMs
                                    ?.coerceAtLeast(1L) ?: return@FilterChip
                                val span = viewportSpanMs.coerceIn(
                                    EditViewportMath.MIN_SPAN_MS.coerceAtMost(total),
                                    total,
                                )
                                viewportSpanMs = span
                                viewportStartMs = viewportStartMs.coerceIn(0L, total - span)
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

            // --- the overview strip: the whole recording, with the viewport ---
            OverviewStrip(
                peaks = current?.peaks,
                totalMs = current?.durationMs ?: 0L,
                viewportStartMs = viewportStartMs,
                viewportSpanMs = viewportSpanMs,
                onViewportMove = { start, span ->
                    viewportStartMs = start
                    viewportSpanMs = span
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(4.dp))

            // --- the waveform ---
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp),
                ) {
                    when {
                        current == null -> Unit

                        // A waveform that exists is drawn, even while the rest
                        // is still being decoded. A spinner that covers the
                        // whole view is what made a long recording look
                        // broken: the detail waveform is ready in milliseconds
                        // and the coarse one takes minutes, and neither of
                        // them should hide the other.
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
                                selection = selection,
                                playheadMs = playheadMs,
                                onSelectionChange = { selection = it },
                                onSelectionCommit = { selection = it },
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

            // --- the exact numbers of the selection ---
            SelectionPanel(
                selection = selection,
                totalMs = current?.durationMs ?: 0L,
                onSelectionChange = { selection = it },
            )

            Spacer(modifier = Modifier.height(10.dp))

            // --- the selection actions ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val pending = selection
                val overlapsRemoved = pending != null && removed.any { range ->
                    pending.startMs < range.endMs && range.startMs < pending.endMs
                }
                Button(
                    enabled = pending != null && pending.durationMs > 0 && !overlapsRemoved,
                    onClick = {
                        val target = pending ?: return@Button
                        val snapped = EditSnapping.snapRange(
                            current?.peaks,
                            KeepRange(target.startMs, target.endMs),
                            enabled = snapEnabled,
                        )
                        val updated = EditPlanner.remove(
                            keep = current?.keep.orEmpty(),
                            fromMs = snapped.startMs,
                            toMs = snapped.endMs,
                            durationMs = current?.durationMs ?: 0L,
                        )
                        sources = sources.replaceAt(selectedIndex) { it.copy(keep = updated) }
                        selection = null
                    },
                ) {
                    Icon(
                        Icons.Filled.ContentCut,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.editor_delete_range))
                }

                Button(
                    enabled = pending != null && pending.durationMs > 0 && overlapsRemoved,
                    onClick = {
                        val target = pending ?: return@Button
                        val restored = EditPlanner.normalize(
                            keep = current?.keep.orEmpty() +
                                KeepRange(target.startMs, target.endMs),
                            durationMs = current?.durationMs ?: 0L,
                        )
                        sources = sources.replaceAt(selectedIndex) { it.copy(keep = restored) }
                        selection = null
                    },
                ) {
                    Icon(
                        Icons.Filled.Restore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.editor_restore_range))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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
                ) {
                    Icon(
                        imageVector = if (previewPlaying) {
                            Icons.Filled.Stop
                        } else {
                            Icons.Filled.PlayArrow
                        },
                        contentDescription = stringResource(R.string.editor_preview),
                    )
                }

                Text(
                    text = stringResource(
                        R.string.editor_position,
                        formatDuration(previewPositionMs),
                        formatDuration(previewDurationMs),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
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
