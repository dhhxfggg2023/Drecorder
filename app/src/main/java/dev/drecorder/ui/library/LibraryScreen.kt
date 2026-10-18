package dev.drecorder.ui.library

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    val loadFailed = stringResource(R.string.library_load_error)
    val deleted = stringResource(R.string.library_deleted)
    val deleteFailed = stringResource(R.string.library_delete_failed)

    suspend fun reload() {
        loading = true
        val rows = withContext(Dispatchers.IO) { library.recordings() }
        recordings = rows
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    LaunchedEffect(Unit) {
        player.finished.collect {
            playingPath = null
        }
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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.record_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                recordings.isEmpty() -> Text(
                    text = stringResource(R.string.library_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "audio/*"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    runCatching {
                                        context.startActivity(
                                            Intent.createChooser(intent, recording.displayName),
                                        )
                                    }.onFailure { notify(openFailed) }
                                }
                            },
                            onRename = { pendingRename = recording },
                            onDelete = { pendingDelete = recording },
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
                    Text(stringResource(R.string.record_back))
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
                    if (!ok) snackbarHostState.showSnackbar(loadFailed)
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
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank() && text.trim() != original,
            ) { Text(stringResource(R.string.settings_saved)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.record_back)) }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (recording.wasSilent) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onTogglePlay) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.library_playing else R.string.library_title,
                    ),
                )
            }
            Column(
                modifier = Modifier.weight(1f).clickable(onClick = onRename),
            ) {
                Text(
                    text = recording.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                    Text(
                        text = stringResource(R.string.library_silent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.library_share))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.library_delete))
            }
        }
    }
}
