package dev.drecorder.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.drecorder.R
import dev.drecorder.capture.RecordSettings
import dev.drecorder.data.DerivedMarker
import dev.drecorder.data.Recording
import dev.drecorder.data.RecordingLibrary
import dev.drecorder.data.SilentMarker
import dev.drecorder.data.formatDuration
import dev.drecorder.data.formatSize
import dev.drecorder.playback.RecordingPlayer
import dev.drecorder.ui.recordingWhenLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The recording list.
 *
 * Long lists of test recordings are the normal case, so selection is a first-class mode:
 * tap "选择", tick rows (or "全选"), delete them in one confirmed action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onEdit: (List<Recording>) -> Unit = {},
    editingDisabled: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val player = remember { RecordingPlayer(context) }

    val library = remember(context) {
        RecordingLibrary(
            directoryProvider = {
                RecordSettings.outputDirectory(context)?.let(::File) ?: defaultOutputDirectory(context)
            },
            marker = SilentMarker(context),
            derivedMarker = DerivedMarker(context),
        )
    }

    var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var playingPath by remember { mutableStateOf<String?>(null) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var pendingDelete by remember { mutableStateOf<Recording?>(null) }
    var pendingRename by remember { mutableStateOf<Recording?>(null) }
    var deleteSelectedVisible by remember { mutableStateOf(false) }

    val failed = stringResource(R.string.library_playback_failed)
    val openFailed = stringResource(R.string.library_open_failed)
    val deleted = stringResource(R.string.library_deleted)
    val deleteFailed = stringResource(R.string.library_delete_failed)
    val mergeNeedTwo = stringResource(R.string.library_merge_need_two)
    val editBusy = stringResource(R.string.library_edit_busy)

    suspend fun reload() {
        loading = true
        recordings = withContext(Dispatchers.IO) { library.recordings() }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    LaunchedEffect(Unit) {
        player.finished.collect { playingPath = null }
    }

    LaunchedEffect(Unit) {
        player.failed.collect { playingPath = null; snackbarHostState.showSnackbar(failed) }
    }

    LaunchedEffect(playingPath) {
        while (playingPath != null) {
            positionMs = player.positionMs
            durationMs = player.durationMs
            delay(250)
        }
        positionMs = 0L
        durationMs = 0L
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    fun notify(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun exitSelection() {
        selectionMode = false
        selected = emptySet()
    }

    fun deleteRecordings(targets: List<Recording>) {
        if (targets.isEmpty()) return
        val playing = playingPath
        if (playing != null && targets.any { it.file.absolutePath == playing }) {
            player.stop()
            playingPath = null
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { targets.all { library.delete(it) } }
            reload()
            if (ok) snackbarHostState.showSnackbar(deleted) else snackbarHostState.showSnackbar(deleteFailed)
        }
    }

    val allSelected = recordings.isNotEmpty() && selected.size == recordings.size

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) {
                            stringResource(R.string.library_selected_count, selected.size)
                        } else {
                            stringResource(R.string.library_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (selectionMode) exitSelection() else onBack() },
                    ) {
                        Icon(
                            imageVector = if (selectionMode) {
                                Icons.Filled.Close
                            } else {
                                Icons.AutoMirrored.Filled.ArrowBack
                            },
                            contentDescription = stringResource(R.string.record_back),
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        TextButton(
                            onClick = {
                                selected = if (allSelected) {
                                    emptySet()
                                } else {
                                    recordings.map { it.displayName }.toSet()
                                }
                            },
                        ) {
                            Text(
                                stringResource(
                                    if (allSelected) {
                                        R.string.library_clear_selection
                                    } else {
                                        R.string.library_select_all
                                    },
                                ),
                            )
                        }
                        TextButton(
                            onClick = { deleteSelectedVisible = true },
                            enabled = selected.isNotEmpty(),
                        ) {
                            Text(stringResource(R.string.library_delete))
                        }
                        // Merging is a multi-select action, so it lives in the same
                        // toolbar the selection already opened — no second entry
                        // point to discover.
                        TextButton(
                            onClick = {
                                val targets = recordings.filter { it.displayName in selected }
                                if (targets.size < 2) {
                                    notify(mergeNeedTwo)
                                } else {
                                    onEdit(targets)
                                }
                            },
                            enabled = selected.size >= 2 && !editingDisabled,
                        ) {
                            Text(stringResource(R.string.library_merge))
                        }
                    } else if (recordings.isNotEmpty()) {
                        TextButton(onClick = { selectionMode = true }) {
                            Text(stringResource(R.string.library_select))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                recordings.isEmpty() -> EmptyLibrary()

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(items = recordings, key = { it.displayName }) { recording ->
                        RecordingRow(
                            recording = recording,
                            selectionMode = selectionMode,
                            selected = recording.displayName in selected,
                            isPlaying = playingPath == recording.file.absolutePath,
                            positionMs = if (playingPath == recording.file.absolutePath) positionMs else 0L,
                            durationMs = if (playingPath == recording.file.absolutePath) durationMs else 0L,
                            onToggleSelect = {
                                selected = if (recording.displayName in selected) {
                                    selected - recording.displayName
                                } else {
                                    selected + recording.displayName
                                }
                            },
                            onTogglePlay = {
                                if (playingPath == recording.file.absolutePath) {
                                    player.stop()
                                    playingPath = null
                                } else {
                                    val started = player.play(recording.file)
                                    if (started) {
                                        playingPath = recording.file.absolutePath
                                    } else {
                                        notify(failed)
                                    }
                                }
                            },
                            onSeek = { target ->
                                player.seekTo(target)
                                positionMs = target
                            },
                            onShare = {
                                val uri = runCatching {
                                    FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        recording.file,
                                    )
                                }.getOrNull()
                                if (uri == null) {
                                    notify(openFailed)
                                } else {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "audio/*"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent.createChooser(intent, recording.displayName),
                                        )
                                    }.onFailure { notify(openFailed) }
                                }
                            },
                            onRename = { pendingRename = recording },
                            onEdit = {
                                if (editingDisabled) {
                                    notify(editBusy)
                                } else {
                                    onEdit(listOf(recording))
                                }
                            },
                            onDelete = { pendingDelete = recording },
                            editingDisabled = editingDisabled,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.library_delete_confirm_title)) },
            text = { Text(stringResource(R.string.library_delete_confirm_body, target.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    deleteRecordings(listOf(target))
                }) { Text(stringResource(R.string.library_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (deleteSelectedVisible) {
        val targets = recordings.filter { it.displayName in selected }
        AlertDialog(
            onDismissRequest = { deleteSelectedVisible = false },
            title = { Text(stringResource(R.string.library_delete_selected_title, targets.size)) },
            text = { Text(stringResource(R.string.library_delete_selected_body, targets.size)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteSelectedVisible = false
                    exitSelection()
                    deleteRecordings(targets)
                }) { Text(stringResource(R.string.library_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteSelectedVisible = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    pendingRename?.let { target ->
        RenameDialog(
            original = target.displayName,
            onDismiss = { pendingRename = null },
            onConfirm = { newName ->
                pendingRename = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { library.rename(target, newName) }
                    reload()
                    if (!ok) snackbarHostState.showSnackbar(deleteFailed)
                }
            },
        )
    }
}

@Composable
private fun EmptyLibrary() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.GraphicEq,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.library_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RenameDialog(
    original: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(original) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_rename)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank() && text.trim() != original,
            ) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun RecordingRow(
    recording: Recording,
    selectionMode: Boolean,
    selected: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onToggleSelect: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    editingDisabled: Boolean = false,
) {
    var menuVisible by remember { mutableStateOf(false) }
    var dragValue by remember(recording.displayName) { mutableStateOf<Float?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = if (selectionMode) onToggleSelect else onTogglePlay)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
        } else {
            Surface(
                shape = CircleShape,
                color = if (isPlaying) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (isPlaying) R.string.library_playing else R.string.library_play,
                        ),
                        tint = if (isPlaying) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = recordingWhenLabel(recording),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.library_duration_size,
                        formatDuration(recording.durationMs),
                        formatSize(recording.sizeBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (recording.wasSilent) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.errorContainer,
                                MaterialTheme.shapes.extraSmall,
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.library_silent_badge),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                if (recording.wasEdited) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.shapes.extraSmall,
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.library_edited_badge),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            if (isPlaying && !selectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatDuration(positionMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = (dragValue ?: positionMs.toFloat())
                            .coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
                        onValueChange = { dragValue = it },
                        onValueChangeFinished = {
                            dragValue?.let { onSeek(it.toLong()) }
                            dragValue = null
                        },
                        valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .padding(horizontal = 6.dp),
                    )
                    Text(
                        text = formatDuration(durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!selectionMode) {
            Box {
                IconButton(onClick = { menuVisible = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.library_more),
                    )
                }
                DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_share)) },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        onClick = { menuVisible = false; onShare() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_rename)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = { menuVisible = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_edit)) },
                        leadingIcon = { Icon(Icons.Filled.ContentCut, contentDescription = null) },
                        onClick = { menuVisible = false; onEdit() },
                        enabled = !editingDisabled,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menuVisible = false; onDelete() },
                    )
                }
            }
        }
    }
}
