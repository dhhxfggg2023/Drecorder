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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
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
import dev.drecorder.data.Recording
import dev.drecorder.data.RecordingLibrary
import dev.drecorder.data.SilentMarker
import dev.drecorder.data.formatDuration
import dev.drecorder.data.formatSize
import dev.drecorder.playback.RecordingPlayer
import dev.drecorder.ui.recordingWhenLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The recording list.
 *
 * Rows are flat list items, not cards: a list of cards reads as unrelated tiles, while
 * a divider-separated list reads as one collection. Each row has one primary target
 * (play) and one overflow menu, instead of three icon buttons competing for the thumb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onBack: () -> Unit) {
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
        )
    }

    var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var playingPath by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Recording?>(null) }
    var pendingRename by remember { mutableStateOf<Recording?>(null) }

    val failed = stringResource(R.string.library_playback_failed)
    val openFailed = stringResource(R.string.library_open_failed)
    val deleted = stringResource(R.string.library_deleted)
    val deleteFailed = stringResource(R.string.library_delete_failed)

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

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    fun notify(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.record_back),
                        )
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
                            isPlaying = playingPath == recording.file.absolutePath,
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
                            onDelete = { pendingDelete = recording },
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
                    if (playingPath == target.file.absolutePath) {
                        player.stop()
                        playingPath = null
                    }
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { library.delete(target) }
                        if (ok) {
                            reload()
                            snackbarHostState.showSnackbar(deleted)
                        } else {
                            snackbarHostState.showSnackbar(deleteFailed)
                        }
                    }
                }) { Text(stringResource(R.string.library_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
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
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuVisible by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTogglePlay)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
            }
        }
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
                    text = { Text(stringResource(R.string.library_delete)) },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = { menuVisible = false; onDelete() },
                )
            }
        }
    }
}
