package dev.drecorder.ui.library

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.drecorder.R
import dev.drecorder.capture.RecordSettings
import dev.drecorder.data.DerivedMarker
import dev.drecorder.data.Recording
import dev.drecorder.data.RecordingLibrary
import dev.drecorder.data.SilentMarker
import dev.drecorder.data.formatDuration
import dev.drecorder.data.formatSize
import dev.drecorder.ui.design.DangerActionButton
import dev.drecorder.ui.design.DangerConfirmButton
import dev.drecorder.ui.design.DrecorderEmptyState
import dev.drecorder.ui.design.IconTile
import dev.drecorder.ui.design.TextActionButton
import dev.drecorder.ui.design.TonalActionButton
import dev.drecorder.ui.recordingTitle
import dev.drecorder.ui.recordingWhenLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The recording list.
 *
 * Long lists of test recordings are the normal case, so selection is a
 * first-class mode: tap "select", tick rows (or "all"), then merge or delete
 * them in one confirmed action.
 *
 * Each recording is a soft card with a gradient
 * icon tile, the selection actions float in a capsule above the navigation bar,
 * and the empty list explains itself with an action instead of one grey line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onEdit: (List<Recording>) -> Unit = {},
    editingDisabled: Boolean = false,
    /**
     * The file the capture session is writing right now, if any. The engine opens
     * its file under the final name, so it would otherwise be listed as a finished
     * recording — and deleting it would pull the file out from under the writer.
     */
    activeRecordingName: String? = null,
    /** Changes every time the screen is entered, so a stale list cannot survive. */
    screenKey: Int = 0,
    onImport: () -> Unit = {},
) {
    val context = LocalContext.current
    // Reads text through the resources rather than the context, so a language
    // change is picked up (Lint: LocalContextGetResourceValueCall).
    val resources = LocalResources.current
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

    val openFailed = stringResource(R.string.library_open_failed)
    val deleted = stringResource(R.string.library_deleted)
    val deleteFailed = stringResource(R.string.library_delete_failed)
    // A rename that fails is not a delete that failed: the file is untouched.
    val renameFailed = stringResource(R.string.library_rename_failed)
    val editBusy = stringResource(R.string.library_edit_busy)

    suspend fun reload() {
        loading = true
        recordings = withContext(Dispatchers.IO) { library.recordings(activeRecordingName) }
        loading = false
    }

    // Reloaded whenever the screen is (re)entered, and whenever the file being
    // recorded changes. Keying it on the recording name alone meant that coming
    // back from the editor — where a new recording may just have been written —
    // showed the list as it was before the export.
    LaunchedEffect(activeRecordingName, screenKey) { reload() }

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
            // `all { }` stopped at the first failure, so a batch containing one
            // undeletable file silently left the rest behind while the toast said
            // it had failed. Every target is attempted, and the message reports
            // whether all of them went.
            val deletedCount = withContext(Dispatchers.IO) { targets.count { library.delete(it) } }
            reload()
            if (deletedCount == targets.size) {
                snackbarHostState.showSnackbar(deleted)
            } else {
                snackbarHostState.showSnackbar(deleteFailed)
            }
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
                            pluralStringResource(R.plurals.recordings_selected, selected.size, selected.size)
                        } else {
                            stringResource(R.string.library_title)
                        },
                        fontWeight = FontWeight.Bold,
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
                    // Import sits here because it belongs to the library, not to
                    // the recorder: the file becomes a recording like any other.
                    if (!selectionMode) {
                        IconButton(onClick = onImport) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.library_import),
                            )
                        }
                    }
                    // Only the "select" entry point stays in the bar. The three
                    // selection actions used to live here as well, and on a
                    // phone the third one (merge) was pushed off the edge — the
                    // merge existed but could not be found. They are a floating
                    // capsule now, where they fit and where a thumb reaches them.
                    if (!selectionMode && recordings.isNotEmpty()) {
                        TextActionButton(
                            text = stringResource(R.string.library_select),
                            onClick = { selectionMode = true },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(percent = 50),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 3.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextActionButton(
                                text = stringResource(
                                    if (allSelected) {
                                        R.string.library_clear_selection
                                    } else {
                                        R.string.library_select_all
                                    },
                                ),
                                onClick = {
                                    selected = if (allSelected) {
                                        emptySet()
                                    } else {
                                        recordings.map { it.displayName }.toSet()
                                    }
                                },
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            // Merging starts here: picking two recordings is what
                            // "merge these" looks like in a library, and the order
                            // is settled on the waveform afterwards, where the join
                            // can be heard (m03765).
                            TonalActionButton(
                                text = stringResource(R.string.library_merge),
                                icon = Icons.Filled.ContentCut,
                                onClick = {
                                    val picked = recordings.filter { it.displayName in selected }
                                    if (picked.size < 2) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                resources.getString(R.string.library_merge_need_two),
                                            )
                                        }
                                    } else {
                                        exitSelection()
                                        onEdit(picked)
                                    }
                                },
                            )
                            DangerActionButton(
                                text = stringResource(R.string.library_delete),
                                icon = Icons.Filled.Delete,
                                enabled = selected.isNotEmpty(),
                                onClick = { deleteSelectedVisible = true },
                            )
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

                recordings.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    DrecorderEmptyState(
                        icon = Icons.Filled.GraphicEq,
                        title = stringResource(R.string.home_empty),
                        description = stringResource(R.string.library_empty),
                        actionLabel = stringResource(R.string.library_import),
                        actionIcon = Icons.Filled.Add,
                        onAction = onImport,
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    // Cards rather than a flat list: on a near-white background a
                    // recording is a surface with an edge, and the gap between two
                    // of them is what says "these are separate things".
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
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
                                scope.launch {
                                    val uri = shareUri(context, recording.file)
                                    if (uri == null) {
                                        notify(openFailed)
                                        return@launch
                                    }
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
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.library_delete_confirm_title)) },
            // The dialog names the recording the way the row does, so the file
            // being deleted is the one the user just tapped.
            text = { Text(stringResource(R.string.library_delete_confirm_body, recordingTitle(target))) },
            confirmButton = {
                DangerConfirmButton(
                    text = stringResource(R.string.library_delete),
                    icon = Icons.Filled.Delete,
                    onClick = {
                        pendingDelete = null
                        deleteRecordings(listOf(target))
                    },
                )
            },
            dismissButton = {
                TextActionButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { pendingDelete = null },
                )
            },
        )
    }

    if (deleteSelectedVisible) {
        val targets = recordings.filter { it.displayName in selected }
        AlertDialog(
            onDismissRequest = { deleteSelectedVisible = false },
            title = {
                Text(
                    pluralStringResource(
                        R.plurals.recordings_delete_title,
                        targets.size,
                        targets.size,
                    ),
                )
            },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.recordings_delete_body,
                        targets.size,
                        targets.size,
                    ),
                )
            },
            confirmButton = {
                DangerConfirmButton(
                    text = stringResource(R.string.library_delete),
                    icon = Icons.Filled.Delete,
                    onClick = {
                        deleteSelectedVisible = false
                        exitSelection()
                        deleteRecordings(targets)
                    },
                )
            },
            dismissButton = {
                TextActionButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { deleteSelectedVisible = false },
                )
            },
        )
    }

    pendingRename?.let { target ->
        RenameDialog(
            // The dialog edits the name, not the file name: the extension is what
            // the library needs to keep listing the recording, so it is not offered
            // for editing (a typed name without one keeps the original format).
            original = target.nameWithoutExtension,
            onDismiss = { pendingRename = null },
            onConfirm = { newName ->
                pendingRename = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { library.rename(target, newName) }
                    reload()
                    if (!ok) snackbarHostState.showSnackbar(renameFailed)
                }
            },
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
            TonalActionButton(
                text = stringResource(R.string.common_confirm),
                enabled = text.isNotBlank() && text.trim() != original,
                onClick = { onConfirm(text.trim()) },
            )
        },
        dismissButton = {
            TextActionButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
            )
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
    val scheme = MaterialTheme.colorScheme

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                scheme.primaryContainer
            } else {
                scheme.surfaceContainerLow
            },
        ),
        // Selection is a ring, not a second coloured block: the card already has
        // a fill, and stacking two tints made the chosen row look like a
        // different kind of item.
        border = if (selected) BorderStroke(1.6.dp, scheme.primary) else null,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.large,
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
                .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) scheme.primary else scheme.outline,
                    modifier = Modifier.size(26.dp),
                )
            } else {
                IconTile(
                    icon = Icons.Filled.GraphicEq,
                    size = 48.dp,
                    tint = scheme.onPrimaryContainer,
                )
            }
            // A named recording leads with its name: the user typed it, so it is
            // the one part of the row that says what this recording *is*. The time
            // stays underneath, because the name does not replace it. Files that
            // still carry the engine's generated name keep the time as the title —
            // `drecorder-20261018-101722-p01` is not something anyone reads (issue #1).
            val customName = recording.customName
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = customName ?: recordingWhenLabel(recording),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (customName != null) {
                            stringResource(
                                R.string.library_named_meta,
                                recordingWhenLabel(recording),
                                formatDuration(recording.durationMs),
                                formatSize(recording.sizeBytes),
                            )
                        } else {
                            stringResource(
                                R.string.library_duration_size,
                                formatDuration(recording.durationMs),
                                formatSize(recording.sizeBytes),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (recording.wasSilent) {
                        Spacer(Modifier.width(8.dp))
                        Badge(
                            text = stringResource(R.string.library_silent_badge),
                            container = scheme.errorContainer,
                            content = scheme.onErrorContainer,
                        )
                    }
                    if (recording.wasEdited) {
                        Spacer(Modifier.width(8.dp))
                        Badge(
                            text = stringResource(R.string.library_edited_badge),
                            container = scheme.secondaryContainer,
                            content = scheme.onSecondaryContainer,
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
                            tint = scheme.onSurfaceVariant,
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
                            text = { Text(stringResource(R.string.library_delete), color = scheme.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = scheme.error) },
                            onClick = { menuVisible = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

/** A small rounded label: silent / edited. */
@Composable
private fun Badge(text: String, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .background(container, RoundedCornerShape(percent = 50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

/**
 * A content URI for [file], copying it into the app cache when it sits outside
 * every path the provider exports.
 *
 * The output folder is the user's choice, so a recording may live anywhere on
 * shared storage. Exporting all of shared storage through the provider would let
 * any app holding one of our URIs read any file on the device; copying the one
 * recording being shared keeps that surface to that one file.
 */
private suspend fun shareUri(context: Context, file: File): Uri? = withContext(Dispatchers.IO) {
    val authority = context.packageName + ".fileprovider"
    val direct = runCatching {
        FileProvider.getUriForFile(context, authority, file)
    }.getOrNull()
    if (direct != null) return@withContext direct

    runCatching {
        val directory = File(context.cacheDir, "sharing").apply { mkdirs() }
        val copy = File(directory, file.name)
        if (!copy.isFile || copy.length() != file.length()) {
            file.copyTo(copy, overwrite = true)
        }
        FileProvider.getUriForFile(context, authority, copy)
    }.getOrNull()
}
