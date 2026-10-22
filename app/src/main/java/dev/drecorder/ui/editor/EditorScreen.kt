package dev.drecorder.ui.editor

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.withFrameMillis
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
import dev.drecorder.audio.edit.EditOrder
import dev.drecorder.audio.edit.EditPlan
import dev.drecorder.audio.edit.EditSegment
import dev.drecorder.audio.edit.EditPlanner
import dev.drecorder.audio.edit.EditSilence
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
 * Something the user asked for that changes the recording without a way back,
 * waiting for a yes.
 *
 * Every one of these throws audio away: a cut removes it from the plan, "only
 * this stretch" throws away everything else in the file, and an overwrite
 * replaces the recording on disk. None of them can be undone from inside the
 * editor, so none of them happen on the first tap (DESIGN §12.4).
 *
 * They share one dialog rather than three, because they share one question —
 * "this cannot be undone, do you mean it?" — and a dialog per action is how
 * the two of them drift into asking differently.
 */
private sealed interface PendingEdit {
    /** The user wants this stretch taken out. */
    data class DeleteSegment(val segment: EditSegment) : PendingEdit

    /** The user wants to throw away everything but this stretch. */
    data class KeepOnly(val segment: EditSegment) : PendingEdit

    /** The user wants this stretch played twice. */
    data class Repeat(val segment: EditSegment) : PendingEdit

    /** The user wants the export to replace [name]. */
    data class Overwrite(val name: String) : PendingEdit
}

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
    /**
     * Lists the recordings that can be spliced into this session.
     *
     * The editor is opened on a selection, but merging is not something the
     * library can offer before the user knows what the recordings sound like
     * together: picking the second file belongs next to the waveform, not
     * behind a back-press. It is a loader rather than a list so the screen
     * reads the library at the moment the user asks for it.
     */
    loadLibrary: suspend () -> List<Recording> = { emptyList() },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current

    var sources by remember { mutableStateOf<List<EditorSource>>(emptyList()) }
    var selectedIndex by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Non-null when the sources cannot be spliced without re-encoding. The merge is
    // then refused outright instead of quietly transcoding (DESIGN §12.1).
    var formatMismatch by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf(0) }
    var previewPlaying by remember { mutableStateOf(false) }
    var previewPositionMs by remember { mutableStateOf(0L) }
    var previewDurationMs by remember { mutableStateOf(0L) }
    /** True while the player is preparing the composition and cannot sound yet. */
    var previewPreparing by remember { mutableStateOf(false) }

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
     * The stretch of audio the user picked by tapping the waveform, if any.
     *
     * It is what the small buttons below the waveform act on. Nothing picks a
     * stretch on the user's behalf: the playhead moving through a recording is
     * not a selection, and treating it as one is what made the old "delete
     * between the cut points" button offer to delete the tail of the recording
     * the moment a second cut point appeared (m01839).
     */
    var selectedSegment by remember { mutableStateOf<EditSegment?>(null) }
    /**
     * The recordings that can be spliced in, once the user has asked to see
     * them. `null` means the picker is closed.
     */
    var insertCandidates by remember { mutableStateOf<List<Recording>?>(null) }

    /**
     * Whether a finger is on the waveform right now.
     *
     * While it is, the window belongs to the finger and not to the playhead:
     * the two would otherwise fight over the same screen, and the waveform
     * would jump back under the finger every 100 ms.
     */
    var waveformDragging by remember { mutableStateOf(false) }
    /**
     * The change the user is about to make that cannot be undone.
     *
     * Deleting a stretch, keeping only one, repeating one and overwriting a
     * recording all throw audio away for good, so each of them is held here
     * until the user says yes (DESIGN §12.4).
     */
    var pendingEdit by remember { mutableStateOf<PendingEdit?>(null) }

    /** True while the export dialog asks where the result should go. */
    var showExportDialog by remember { mutableStateOf(false) }

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

    /**
     * Decodes one recording's waveform into the source at [index].
     *
     * It is a function rather than a loop body because the editor can gain a
     * source after it has opened — the user inserts another recording, or
     * repeats a stretch — and a source that arrives later needs exactly the
     * same cache-then-decode treatment as one it opened with.
     */
    suspend fun loadWaveform(index: Int, recording: Recording) {
        val cacheFile = PeaksCache.fileFor(recording.file, context.filesDir)
        val cached = withContext(Dispatchers.IO) { PeaksCache.read(cacheFile, recording.file) }
        if (cached != null) {
            sources = sources.replaceAt(index) { it.copy(peaks = cached.data) }
            return
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
            loadWaveform(index, recordings[index])
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

    // The playhead is read once per frame while the preview plays.
    //
    // A fixed hundred-millisecond poll is what made the motion look like a
    // slideshow: the position was read, the waveform jumped, and then nothing
    // moved for ten frames. Reading it on the frame clock instead keeps the
    // waveform moving with the audio (DESIGN §12.4).
    //
    // The readout and the playhead move here rather than in an effect keyed on
    // `previewPositionMs`: a key that changes every frame restarts the effect
    // every frame, and anything downstream of it (the window, and the window's
    // decode) then restarts every frame too. That storm of window decodes is
    // what starved the codec and stopped the audio after a couple of seconds.
    LaunchedEffect(previewPlaying) {
        while (previewPlaying) {
            withFrameMillis { }
            previewPreparing = previewPlayer.isBuffering
            // Nothing is followed until the player has actually reached the
            // moment play was pressed at. Before then its position is still
            // zero — the composition is being built and the seek has not been
            // applied — and following it dragged the playhead, and the window
            // with it, back to the beginning of the recording the instant the
            // button was pressed: which is what "it does not play from where
            // the cursor is" looked like (m01839).
            if (previewPreparing || waveformDragging) continue
            val position = previewPlayer.positionMs
            previewPositionMs = position
            previewDurationMs = previewPlayer.durationMs
            val index = plan.sources.indices.firstOrNull { candidate ->
                EditTimeline.sourceTimeForOutputMs(plan, candidate, position) != null
            } ?: continue
            val sourceTime = EditTimeline.sourceTimeForOutputMs(plan, index, position) ?: continue
            playheadMs = sourceTime
            if (index != selectedIndex) {
                selectedIndex = index
                selectedMarkerMs = null
            }
            val total = sources.getOrNull(index)?.durationMs ?: continue
            // The window is derived from the playhead, never from itself: the
            // anchor is where the playhead stands, so the line is the same
            // pixel for the whole playback and the recording slides under it.
            viewportStartMs = EditFollow.pinned(
                playheadMs = sourceTime,
                spanMs = viewportSpanMs,
                totalMs = total,
            ).startMs
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

    // While something plays, the playhead and the window follow it — but that
    // happens in the frame loop above, not here: an effect keyed on
    // `previewPositionMs` restarts every frame, and the window decode
    // downstream of it then restarts every frame too.

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

    // The window that is *decoded* is coarser than the window that is shown.
    //
    // While the preview plays, the visible window moves every frame — that is
    // what makes the waveform scroll. Keying the decode on it would therefore
    // restart the decode sixty times a second, and each restart creates a
    // MediaCodec: the codec pool is exhausted, the audio starves, and playback
    // stops after a second or two (the user's report, m01084, and the log that
    // goes with it).
    //
    // So the decode window is snapped down to a quarter of the span and is
    // decoded a little wider than the visible one. It then has to change only
    // once every quarter span of scrolling, while still always covering what
    // is on screen.
    val decodeStepMs = remember(viewportSpanMs) {
        (viewportSpanMs / 4).coerceAtLeast(1L)
    }
    val decodeStartMs = remember(viewportStartMs, decodeStepMs) {
        val step = decodeStepMs
        Math.floorDiv(viewportStartMs, step) * step
    }
    val decodeSpanMs = remember(viewportSpanMs, decodeStepMs) {
        viewportSpanMs + decodeStepMs
    }

    LaunchedEffect(current?.file, decodeStartMs, decodeSpanMs, detailResolution, previewPlaying) {
        val source = current ?: return@LaunchedEffect
        // Starting a new window stops the decode of the previous one: a
        // pinch gesture moves the window many times a second, and every one
        // of those windows would otherwise be decoded.
        val cancelled = windowDecodeGate.start()
        // Nothing is decoded while the preview plays.
        //
        // The audio pipeline and this decoder both want a MediaCodec, and the
        // phone has a handful of them: a window decode that runs during
        // playback took the codec the audio was about to use, and the sound
        // stopped after a second or two. Scrolling the coarse waveform — which
        // is already in memory and needs no codec — is what the user sees
        // meanwhile, and the fine one comes back the moment playback stops.
        // Playback is also what makes the window move, so the window this
        // would decode is out of date before it finishes.
        if (previewPlaying) return@LaunchedEffect
        if (!needsDetail) {
            if (source.detailPeaks != null) {
                sources = sources.replaceAt(selectedIndex) { it.copy(detailPeaks = null) }
            }
            return@LaunchedEffect
        }
        val windowStart = decodeStartMs
        val windowSpan = decodeSpanMs
        // A window that was decoded before is read back instead of decoded
        // again. Without this, every return to a stretch — and every reopening
        // of the editor — paid for the same decode a second time, which is the
        // "why does the waveform load every time" the user asked about
        // (m02684).
        val windowCacheFile = PeaksCache.windowFileFor(
            recording = source.file,
            cacheDirectory = context.filesDir,
            bucketsPerSecond = detailResolution,
            startMs = windowStart,
            spanMs = windowSpan,
        )
        val windowCached = withContext(Dispatchers.IO) {
            PeaksCache.read(windowCacheFile, source.file)
        }
        if (windowCached != null) {
            sources = sources.replaceAt(selectedIndex) {
                it.copy(detailPeaks = windowCached.data)
            }
            return@LaunchedEffect
        }
        // Only the part of the file that the window shows is decoded. The
        // window may reach before the recording — the playhead stands in the
        // middle of the screen — and the time before the recording has no
        // audio in it (DESIGN §12.4).
        val range = EditWindow.fileRange(
            viewport = EditViewport(decodeStartMs, decodeSpanMs),
            totalMs = source.durationMs,
        ) ?: return@LaunchedEffect
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
        // The decode window may have changed while the decode ran; a result
        // for an old window is stale and is dropped rather than shown. It is
        // compared with the decode window, not with the visible one: the two
        // deliberately differ (see decodeStepMs).
        if (windowStart != decodeStartMs || windowSpan != decodeSpanMs) {
            return@LaunchedEffect
        }
        if (result is PeakDecoder.Result.Success) {
            sources = sources.replaceAt(selectedIndex) { it.copy(detailPeaks = result.data) }
            withContext(Dispatchers.IO) {
                runCatching {
                    PeaksCache.write(windowCacheFile, result.data, source.file)
                }
                PeaksCache.pruneWindows(context.filesDir)
            }
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
        previewPlaying,
    ) {
        val fine = current?.detailPeaks
        // While the preview plays, the coarse waveform is the one that is
        // drawn, even when the fine one is sitting right there.
        //
        // The fine waveform belongs to one window, and during playback the
        // window moves every frame; showing it would mean decoding a new one
        // every frame, and the decoder would be competing with the audio for
        // the phone's few codecs. The coarse waveform is in memory, costs
        // nothing, and its lower resolution is not what the user is looking at
        // while listening — they are watching the recording slide past the
        // playhead. The fine one is back as soon as playback stops.
        if (previewPlaying) return@remember current?.peaks ?: fine
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

    // The stretch the small buttons below the waveform act on. It is only ever
    // what the user tapped: tapping between two cut points picks that stretch.
    // Nothing is picked on the user's behalf, so a cut point that was just
    // dropped never turns the whole tail of the recording into a candidate for
    // deletion — that suggestion was the thing that made two cut points feel
    // like a trap.
    val pickedSegment = selectedSegment
    val segmentHasAudio = current?.let {
        pickedSegment != null && EditMarkers.segmentHasAudio(it.keep, pickedSegment)
    } ?: false

    /**
     * Writes the plan out, either as a new recording or over [destination].
     *
     * An edit used to have exactly one outcome — a new file beside the original
     * — which meant every trim left the user with two recordings and no way to
     * say "this is the same recording, edited". The choice is the user's; the
     * overwrite is the one that needs a confirmation, because the file it
     * replaces is not recoverable.
     */
    fun runExport(destination: File?) {
        scope.launch {
            exporting = true
            exportProgress = 0
            val result = exporter.export(plan, destination) { percent ->
                exportProgress = percent
            }
            exporting = false
            when (result) {
                is EditExportResult.Success -> {
                    // A derived file is a new recording: it gets its own
                    // "edited" mark, and its silence is measured again rather
                    // than inherited (DESIGN §12.3).
                    derivedMarker.markDerived(result.file.name)
                    val silent = EditSilence.keptAudioIsSilent(
                        sources.map { source -> source.keep to source.peaks },
                    )
                    silentMarker.write(result.file.name, wasSilent = silent)
                    notice = context.getString(R.string.editor_exported, result.file.name)
                    onExported(result.file)
                }

                is EditExportResult.Failure -> {
                    notice = result.message
                }
            }
        }
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
                            style = MaterialTheme.typography.labelMedium.copy(
                                // Tabular digits: this line counts down while
                                // audio is cut, and a proportional "1" makes the
                                // whole title row twitch on every tick.
                                fontFeatureSettings = "tnum",
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    //
                    // The "fit" button that used to sit here is gone: zoom is
                    // a pinch on the waveform, and a toolbar button that only
                    // repeated what the fingers already do was one more thing
                    // to read on a screen that is mostly waveform.
                    TextButton(
                        enabled = !exporting && plan.keptDurationMs > 0 && formatMismatch == null,
                        onClick = {
                            // Where the result goes is a question, not a
                            // default: a new recording and the same recording
                            // edited are both legitimate, and only the user
                            // knows which one this is (m02684).
                            showExportDialog = true
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
                .padding(horizontal = 16.dp)
                // The screen is taller than a phone once the waveform, the
                // transport, the cut points and the merge order are all on it.
                // Without this the rows below the fold — the merge order among
                // them — existed but could not be reached, which is why the
                // user could not change the order of a merge (m01839).
                .verticalScroll(rememberScrollState()),
        ) {
            if (sources.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
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
                                val head = playheadMs.coerceIn(0L, total)
                                playheadMs = head
                                viewportStartMs = EditFollow
                                    .pinned(playheadMs = head, spanMs = span, totalMs = total)
                                    .startMs
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

            // --- the order of the merge ---
            //
            // A merge is not a set: "A then B" and "B then A" are two different
            // recordings, and the library can only say *which* ones were
            // picked. The order is therefore shown here and is changeable,
            // before the export (DESIGN §12.2). Without this row the order was
            // whatever the library happened to hand over — the user asked for a
            // merge and could not say in which order.
            if (sources.size > 1) {
                MergeOrderRow(
                    names = sources.map { it.displayName },
                    // Every track says how long it is: ordering a merge is
                    // ordering lengths, and a list of titles alone does not
                    // tell the user which one they are about to move.
                    lengths = sources.map { source ->
                        formatDuration(source.keep.sumOf { it.endMs - it.startMs })
                    },
                    selectedIndex = selectedIndex,
                    onMove = { from, delta ->
                        // The recording the user is looking at stays the one on
                        // screen: reordering the list must not silently switch
                        // the waveform under the playhead.
                        selectedIndex = EditOrder.destinationOf(sources, from, delta)
                        sources = EditOrder.move(sources, from, delta)
                    },
                    onRemove = { index ->
                        // The recording itself stays in the library; only its
                        // place in this merge goes away, and "插入音频" puts it
                        // back (DESIGN §12.2).
                        sources = sources.toMutableList().apply { removeAt(index) }
                        selectedIndex = selectedIndex.coerceIn(0, sources.lastIndex)
                        selectedSegment = null
                        selectedMarkerMs = null
                        playheadMs = 0L
                    },
                    onSelect = { index ->
                        val target = sources.getOrNull(index) ?: return@MergeOrderRow
                        selectedIndex = index
                        selectedMarkerMs = null
                        val total = target.durationMs.coerceAtLeast(1L)
                        val span = viewportSpanMs.coerceIn(
                            EditViewportMath.MIN_SPAN_MS.coerceAtMost(total),
                            total,
                        )
                        viewportSpanMs = span
                        val head = playheadMs.coerceIn(0L, total)
                        playheadMs = head
                        viewportStartMs = EditFollow
                            .pinned(playheadMs = head, spanMs = span, totalMs = total)
                            .startMs
                    },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // --- splice another recording into this one ---
            //
            // The library can start a merge, but it cannot offer it *while*
            // the user is looking at the waveform, and that is when they know
            // what they want next in the recording. The button is here, next
            // to the order row it feeds (DESIGN §12.2).
            TextButton(
                onClick = {
                    scope.launch {
                        val have = sources.map { it.file }.toSet()
                        insertCandidates = loadLibrary()
                            .filter { it.file !in have }
                            .sortedByDescending { it.modifiedAtMillis }
                    }
                },
                enabled = sources.size < MAX_EDIT_SOURCES,
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.editor_insert_audio))
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
                                selectedRange = selectedSegment,
                                onTimelineDrag = { deltaMs ->
                                    // The anchor stands still, so moving the
                                    // recording left means the playhead is
                                    // reading a later moment. The window is
                                    // then derived from the playhead — the
                                    // line cannot move (DESIGN §12.4).
                                    val total = current.durationMs
                                    val moved = (playheadMs + deltaMs)
                                        .coerceIn(0L, total)
                                    playheadMs = moved
                                    viewportStartMs = EditFollow
                                        .pinned(moved, viewportSpanMs, total)
                                        .startMs
                                    seekToSourceMs(selectedIndex, moved)
                                },
                                onMarkerSelect = { ms ->
                                    // Tapping a cut point picks it, so the bar
                                    // offers to delete it. It does not move the
                                    // playhead: the cut points are drawn on the
                                    // waveform and the user is aiming at one,
                                    // not at a moment to listen from.
                                    selectedMarkerMs = ms
                                    selectedSegment = null
                                },
                                onSegmentTap = { timeMs ->
                                    // Tapping the audio picks the stretch
                                    // between the two cut points around it.
                                    // Nothing else happens: a tap that also
                                    // moved the playhead would fight the drag.
                                    //
                                    // With no cut points there is no stretch to
                                    // pick — the whole recording is not a
                                    // "segment", and offering to delete it one
                                    // tap from the waveform is the trap the user
                                    // hit (m01839).
                                    val total = current.durationMs
                                    if (current.markers.isEmpty()) {
                                        // A tap that does nothing looks broken.
                                        // Saying what is missing is the whole
                                        // difference between "this app ignores
                                        // me" and "I need two cut points first".
                                        selectedSegment = null
                                        notice = context.getString(R.string.editor_select_need_markers)
                                    } else {
                                        selectedSegment = EditMarkers.segmentContaining(
                                            markers = current.markers,
                                            timeMs = timeMs,
                                            totalMs = total,
                                        )
                                    }
                                    selectedMarkerMs = null
                                },
                                onViewportGesture = { zoom, panFraction, _ ->
                                    // The maths lives in core-audio so it can be
                                    // unit-tested: a window that leaves the recording
                                    // would show a waveform that matches no audio.
                                    //
                                    // A pinch zooms around the playhead, not around
                                    // the fingers: the line is the anchor and may
                                    // not move (DESIGN §12.4).
                                    val total = sources.maxOf { it.durationMs }.coerceAtLeast(1L)
                                    val next = EditFollow.zoomed(
                                        current = EditViewport(viewportStartMs, viewportSpanMs),
                                        playheadMs = playheadMs,
                                        totalMs = total,
                                        zoomFactor = zoom,
                                        panFraction = panFraction,
                                    )
                                    viewportStartMs = next.startMs
                                    viewportSpanMs = next.spanMs
                                    playheadMs = EditFollow.anchorMs(next)
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
            //
            // The +/− buttons are gone: two fingers on the waveform zoom and pan
            // it, which is what the user asked for and what every other mobile
            // editor does. What is left of the row is the readout, so the
            // gesture still says how much of the recording is on screen.
            Text(
                text = stringResource(
                    R.string.editor_span,
                    formatDuration(viewportSpanMs),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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
                                // Playback starts where the playhead stands,
                                // not at the beginning of the merged stream:
                                // the user pointed at a moment and pressed
                                // play, which means "from here" (DESIGN §12.4).
                                val from = EditTimeline
                                    .nearestOutputMsForSourceTime(plan, selectedIndex, playheadMs)
                                    ?: 0L
                                if (previewPlayer.play(composition, from)) {
                                    previewPlaying = true
                                    previewPositionMs = from
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

                // The player builds the composition before it can sound: a
                // second or two with no audio is normal, and saying so is what
                // keeps it from reading as "the button did nothing" — which is
                // exactly what the user reported (m01084).
                if (previewPreparing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(4.dp))

            // --- the markers: the cut points ---
            MarkerBar(
                markers = current?.markers.orEmpty(),
                durationMs = current?.durationMs ?: 0L,
                selectedMarkerMs = selectedMarkerMs,
                onSelect = { ms ->
                    selectedMarkerMs = ms
                    // A marker is a place to jump to: it becomes the moment
                    // under the anchor.
                    playheadMs = ms
                    seekToSourceMs(selectedIndex, ms)
                    val total = current?.durationMs ?: 0L
                    viewportStartMs = EditFollow
                        .pinned(playheadMs = ms, spanMs = viewportSpanMs, totalMs = total)
                        .startMs
                },
                onAddAtPlayhead = {
                    val source = current ?: return@MarkerBar
                    // The cut lands exactly where the line stands. It used to
                    // be nudged to the quietest nearby moment, which is not
                    // what "cut here" means: the playhead is the one thing on
                    // this screen the user aimed at deliberately, and moving it
                    // afterwards made the cut unplaceable.
                    val at = playheadMs.coerceIn(0L, source.durationMs)
                    sources = sources.replaceAt(selectedIndex) {
                        it.copy(
                            markers = EditMarkers.insert(
                                markers = it.markers,
                                atMs = at,
                                totalMs = it.durationMs,
                            ),
                        )
                    }
                    // The cut point stays picked, and the stretch it closes —
                    // from the cut before it to this one — is picked with it, so
                    // the buttons below act on the piece the user just marked
                    // off. It is *this* piece and not the tail of the
                    // recording, which is what two cuts used to offer.
                    selectedMarkerMs = at
                    selectedSegment = EditMarkers.segmentEndingAt(
                        markers = source.markers,
                        atMs = at,
                        totalMs = source.durationMs,
                    )
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

            // --- the picked stretch: what the small buttons act on ---
            //
            // The row only exists while the user has picked a stretch by
            // tapping it. There is no fallback to "the stretch the playhead is
            // inside": a playhead is not a selection, and a delete button that
            // silently retargets itself is one tap away from wiping the
            // recording — which is exactly what the user hit (m01839).
            if (pickedSegment != null) {
                SegmentActionsRow(
                    segment = pickedSegment,
                    enabled = segmentHasAudio,
                    repeatEnabled = sources.size < MAX_EDIT_SOURCES,
                    onDelete = {
                        // Deleting a stretch of audio cannot be undone, so the
                        // button asks first (DESIGN §12.4).
                        pendingEdit = PendingEdit.DeleteSegment(pickedSegment)
                    },
                    onKeepOnly = {
                        // Throwing away everything except one stretch is the
                        // largest cut the editor can make, and it used to
                        // happen on a single tap. It asks (m02684).
                        pendingEdit = PendingEdit.KeepOnly(pickedSegment)
                    },
                    onRepeat = {
                        // A repeat cannot be undone either: it adds a source
                        // that the export then writes out. It asks too.
                        pendingEdit = PendingEdit.Repeat(pickedSegment)
                    },
                )
            }

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

    // --- the confirmation before audio is thrown away ---
    //
    // None of these can be undone: a cut removes audio from the plan, "only
    // this stretch" throws away the rest of the file, a repeat adds a whole
    // source, and an overwrite replaces the recording on disk. The dialog says
    // which stretch (or which file) it is about to act on, because "确定" over
    // a question the user cannot check is how the wrong stretch gets deleted
    // (DESIGN §12.4).
    pendingEdit?.let { edit ->
        val (title, body) = when (edit) {
            is PendingEdit.DeleteSegment -> stringResource(R.string.editor_delete_confirm_title) to
                stringResource(
                    R.string.editor_delete_confirm_body,
                    formatDuration(edit.segment.startMs),
                    formatDuration(edit.segment.endMs),
                    formatDuration(edit.segment.durationMs),
                )

            is PendingEdit.KeepOnly -> stringResource(R.string.editor_keep_only_confirm_title) to
                stringResource(
                    R.string.editor_keep_only_confirm_body,
                    formatDuration(edit.segment.startMs),
                    formatDuration(edit.segment.endMs),
                    formatDuration(edit.segment.durationMs),
                )

            is PendingEdit.Repeat -> stringResource(R.string.editor_repeat_confirm_title) to
                stringResource(
                    R.string.editor_repeat_confirm_body,
                    formatDuration(edit.segment.startMs),
                    formatDuration(edit.segment.endMs),
                    formatDuration(edit.segment.durationMs),
                )

            is PendingEdit.Overwrite -> stringResource(R.string.editor_overwrite_confirm_title) to
                stringResource(R.string.editor_overwrite_confirm_body, edit.name)
        }
        AlertDialog(
            onDismissRequest = { pendingEdit = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingEdit = null
                        when (edit) {
                            is PendingEdit.DeleteSegment -> {
                                val source = current ?: return@TextButton
                                val result = EditMarkers.deleteSegment(
                                    keep = source.keep,
                                    markers = source.markers,
                                    segment = edit.segment,
                                    durationMs = source.durationMs,
                                )
                                sources = sources.replaceAt(selectedIndex) {
                                    it.copy(keep = result.keep, markers = result.markers)
                                }
                                selectedMarkerMs = null
                                selectedSegment = null
                                notice = context.getString(R.string.editor_segment_deleted)
                            }

                            is PendingEdit.KeepOnly -> {
                                sources = sources.replaceAt(selectedIndex) { source ->
                                    val cut = EditPlanner.remove(
                                        keep = source.keep,
                                        fromMs = 0L,
                                        toMs = edit.segment.startMs,
                                        durationMs = source.durationMs,
                                    )
                                    source.copy(
                                        keep = EditPlanner.remove(
                                            keep = cut,
                                            fromMs = edit.segment.endMs,
                                            toMs = source.durationMs,
                                            durationMs = source.durationMs,
                                        ),
                                        markers = emptyList(),
                                    )
                                }
                                selectedSegment = null
                                selectedMarkerMs = null
                                notice = context.getString(R.string.editor_segment_kept)
                            }

                            is PendingEdit.Repeat -> {
                                // Playing the stretch a second time is not
                                // something a keep list can express — it says
                                // which parts of a file are kept, in file order,
                                // once each. It is expressed the way a merge is:
                                // the same recording spliced in again, right
                                // after itself, carrying only the picked stretch
                                // (DESIGN §12.2).
                                val at = selectedIndex
                                val copy = sources[at].copy(
                                    displayName = context.getString(
                                        R.string.editor_repeat_name,
                                        sources[at].displayName,
                                    ),
                                    keep = listOf(
                                        KeepRange(edit.segment.startMs, edit.segment.endMs),
                                    ),
                                    markers = emptyList(),
                                )
                                sources = sources.toMutableList().apply { add(at + 1, copy) }
                                selectedIndex = at + 1
                                selectedSegment = null
                                playheadMs = 0L
                            }

                            is PendingEdit.Overwrite -> {
                                runExport(destination = sources.firstOrNull()?.file)
                            }
                        }
                    },
                ) {
                    // The button says what it does. "确定" would let the same
                    // word confirm a delete, an overwrite and a repeat, and the
                    // one place it is read is the one place a mistake is
                    // irreversible.
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
            dismissButton = {
                TextButton(onClick = { pendingEdit = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    // --- export: a new recording, or the one that is already there ---
    if (showExportDialog) {
        val first = sources.firstOrNull()
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.editor_export_title)) },
            text = {
                Text(
                    if (sources.size > 1) {
                        stringResource(
                            R.string.editor_export_body_overwrite,
                            first?.displayName ?: "",
                        )
                    } else {
                        stringResource(R.string.editor_export_body_new)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        runExport(destination = null)
                    },
                ) {
                    Text(stringResource(R.string.editor_export_save_as))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        val target = first
                        if (target != null) {
                            pendingEdit = PendingEdit.Overwrite(target.displayName)
                        }
                    },
                ) {
                    Text(stringResource(R.string.editor_export_overwrite))
                }
            },
        )
    }

    // --- pick a recording to splice in ---
    insertCandidates?.let { candidates ->
        AlertDialog(
            onDismissRequest = { insertCandidates = null },
            title = { Text(stringResource(R.string.editor_insert_audio)) },
            text = {
                if (candidates.isEmpty()) {
                    Text(stringResource(R.string.editor_insert_none))
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        candidates.forEach { recording ->
                            TextButton(
                                onClick = {
                                    insertCandidates = null
                                    if (sources.size >= MAX_EDIT_SOURCES) return@TextButton
                                    val added = EditorSource(
                                        file = recording.file,
                                        durationMs = recording.durationMs,
                                        displayName = recording.displayName,
                                        keep = EditPlanner.fullLength(recording.durationMs),
                                    )
                                    // The new recording goes last, which is
                                    // where "and then this one" means, and it
                                    // becomes the one on screen so the user can
                                    // trim it before exporting.
                                    sources = sources + added
                                    selectedIndex = sources.lastIndex
                                    selectedMarkerMs = null
                                    selectedSegment = null
                                    playheadMs = 0L
                                    viewportStartMs = 0L
                                    // A merge has to be one encoding all the
                                    // way through, so adding a recording that
                                    // cannot be spliced with the others says so
                                    // here rather than failing at export.
                                    scope.launch {
                                        val specs = withContext(Dispatchers.IO) {
                                            listOf(recording.displayName to
                                                AudioFormatProbe.probe(recording.file))
                                        }
                                        val base = withContext(Dispatchers.IO) {
                                            AudioFormatProbe.probe(sources.first().file)
                                        }
                                        formatMismatch = if (base == null) {
                                            null
                                        } else {
                                            EditFormats.incompatible(
                                                base = base,
                                                others = specs.map { (name, spec) ->
                                                    name to (spec ?: base.copy(mimeType = "未知"))
                                                },
                                            ).takeIf { it.isNotEmpty() }
                                                ?.joinToString("；") { "${it.first}：${it.second}" }
                                        }
                                        loadWaveform(sources.lastIndex, recording)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.editor_insert_entry,
                                        recording.displayName,
                                        formatDuration(recording.durationMs),
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { insertCandidates = null }) {
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
 * How many recordings one edit session may splice together.
 *
 * A merge is a handful of files, and repeating a stretch adds one entry per
 * tap. The cap is here so a stuck gesture cannot grow the list without bound
 * while the export has to decode every entry.
 */
private const val MAX_EDIT_SOURCES = 32

/**
 * How often the playhead is read while the preview plays, in ms.
 *
 * A tenth of a second is fast enough for the playhead to look smooth and
 * slow enough to leave the phone's battery alone.
 */
private const val POLL_INTERVAL_MS = 100L
