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
import androidx.compose.material3.Button
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import dev.drecorder.ui.recordingWhenLabel
import kotlinx.coroutines.Dispatchers
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
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var pendingDelete by remember { mutableStateOf<Recording?>(null) }
    var pendingRename by remember { mutableStateOf<Recording?>(null) }
    var deleteSelectedVisible by remember { mutableStateOf(false) }

    val failed = stringResource(R.string.library_playback_failed)
    val openFailed = stringResource(R.string.library_open_failed)
    val deleted = stringResource(R.string.library_deleted)
    val deleteFailed = stringResource(R.string.library_delete_failed)
    val editBusy = stringResource(R.string.library_edit_busy)

    suspend fun reload() {
        loading = true
        recordings = withContext(Dispatchers.IO) { library.recordings() }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    fun notify(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun exitSelection() {
        selectionMode = false
        selected = emptySet()
    }

    fun deleteRecordings(targets: List<Recording>) {
        if (targets.isEmpty()) return
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
                    // Only the "select" entry point stays in the bar. The three
                    // selection actions used to live here as well, and on a
                    // phone the third one (合并) was pushed off the edge — the
                    // merge existed but could not be found. They are a bottom
                    // bar now, where they fit and where a thumb reaches them.
                    if (!selectionMode && recordings.isNotEmpty()) {
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
        bottomBar = {
            if (selectionMode) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
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
                        Spacer(modifier = Modifier.weight(1f))
                        // Merging starts here: picking two recordings is what
                        // "merge these" looks like in a library, and the order
                        // is settled on the waveform afterwards, where the join
                        // can be heard (m03765).
                        TextButton(
                            onClick = {
                                val picked = recordings.filter { it.displayName in selected }
                                if (picked.size < 2) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.library_merge_need_two),
                                        )
                                    }
                                } else {
                                    exitSelection()
                                    onEdit(picked)
                                }
                            },
                        ) {
                            Text(stringResource(R.string.library_merge))
                        }
                        TextButton(
                            onClick = { deleteSelectedVisible = true },
                            enabled = selected.isNotEmpty(),
                        ) {
                            Text(stringResource(R.string.library_delete))
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                recordings.isEmpty() -> EmptyLibrary()

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    // Cards rather than a flat list: on a near-black background a
                    // recording is a surface with an edge, and the gap between
                    // two of them is what says "these are separate things".
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = recordings, key = { it.displayName }) { recording ->
                        RecordingRow(
                            recording = recording,
                            selectionMode = selectionMode,
                            selected = recording.displayName in selected,
                            onToggleSelect = {
                                selected = if (recording.displayName in selected) {
                                    selected - recording.displayName
                                } else {
                                    selected + recording.displayName
                                }
                            },
                            onOpen = {
                                // A recording opens the editor, which is where it
                                // is played: the library is a list, not a player
                                // (m00678).
                                if (editingDisabled) {
                                    notify(editBusy)
                                } else {
                                    onEdit(listOf(recording))
                                }
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
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    editingDisabled: Boolean = false,
) {
    var menuVisible by remember { mutableStateOf(false) }

    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Tapping a recording opens it — in the editor, which plays it.
                // There is no play button here any more: a list that plays and an
                // editor that plays are two players, and the one in the list was
                // the one the user never asked for (m00678).
                .clickable(onClick = if (selectionMode) onToggleSelect else onOpen)
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
            } else {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
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
}
