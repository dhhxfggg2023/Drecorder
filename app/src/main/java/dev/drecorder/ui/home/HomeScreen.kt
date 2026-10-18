package dev.drecorder.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import dev.drecorder.audio.CaptureScope
import dev.drecorder.data.InstalledAppsRepository
import dev.drecorder.data.LaunchableApp
import dev.drecorder.data.Recording
import dev.drecorder.data.formatDuration
import dev.drecorder.data.formatSize
import kotlinx.coroutines.launch
import android.app.KeyguardManager

/**
 * Functional-selection home. Layout follows docs/DESIGN.md §6.2: one primary
 * mode card, one secondary mode card, recent recordings, and a single CTA.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    notice: String?,
    onNoticeShown: () -> Unit,
    onStartCapture: (CaptureScope) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUpdate: () -> Unit,
    recents: List<Recording> = emptyList(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val enginePending = stringResource(R.string.todo_engine)
    val context = LocalContext.current
    val deviceHasSecureLock = remember(context) {
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }

    val repository = remember(context) { InstalledAppsRepository(context) }
    var pickerVisible by remember { mutableStateOf(false) }
    var pickerApps by remember { mutableStateOf(emptyList<LaunchableApp>()) }
    var pickerLoading by remember { mutableStateOf(false) }
    var pickerError by remember { mutableStateOf<String?>(null) }
    val pickerLoadError = stringResource(R.string.picker_load_error)
    val nothingSelected = stringResource(R.string.picker_empty)

    suspend fun loadApps() {
        pickerLoading = true
        pickerError = null
        // Never offer Drecorder itself as a source: capturing our own output is
        // pointless and confusing.
        val result = runCatching { repository.launchableApps(setOf(context.packageName)) }
        pickerApps = result.getOrDefault(emptyList())
        pickerError = when {
            result.isFailure -> pickerLoadError
            pickerApps.isEmpty() -> nothingSelected
            else -> null
        }
        pickerLoading = false
    }

    LaunchedEffect(pickerVisible) {
        if (pickerVisible && !pickerLoading && pickerApps.isEmpty() && pickerError == null) {
            loadApps()
        }
    }

    LaunchedEffect(notice) {
        val message = notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onNoticeShown()
    }

    if (pickerVisible) {
        AppPickerSheet(
            apps = pickerApps,
            isLoading = pickerLoading,
            loadError = pickerError,
            onRetry = { scope.launch { loadApps() } },
            onDismiss = { pickerVisible = false },
            onConfirm = { selection ->
                val scopeForCapture = selection.toCaptureScope()
                if (scopeForCapture == null) {
                    scope.launch { snackbarHostState.showSnackbar(nothingSelected) }
                } else {
                    pickerVisible = false
                    onStartCapture(scopeForCapture)
                }
            },
        )
    }

    fun show(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = onOpenUpdate) {
                        Text(stringResource(R.string.update_check))
                    }
                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.home_settings))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(
                onClick = { pickerVisible = true },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.home_mode_apps_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.home_mode_apps_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Card(
                onClick = { onStartCapture(CaptureScope.AllMedia) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.home_mode_all_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.home_mode_all_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.disclosure_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.disclosure_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Only warn about the lock-screen limit on devices that actually
                    // have a secure keyguard: on Android 15+ locking the screen ends
                    // the projection session (docs/DESIGN.md §3.2).
                    if (deviceHasSecureLock) {
                        Text(
                            text = stringResource(R.string.disclosure_lock_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_recent),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenLibrary) {
                    Text(stringResource(R.string.home_view_all))
                }
            }
            if (recents.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenLibrary)
                        .padding(vertical = 24.dp),
                )
            } else {
                recents.forEach { recording ->
                    RecentRecordingRow(recording = recording, onClick = onOpenLibrary)
                }
            }

            Button(
                onClick = { onStartCapture(CaptureScope.AllMedia) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            ) {
                Text(stringResource(R.string.home_start))
            }
        }
    }
}

/**
 * One line of the "recent recordings" list. Read-only on purpose: tapping opens the
 * library, where play, rename, share and delete live, so the home screen has no
 * second copy of those actions.
 */
@Composable
private fun RecentRecordingRow(recording: Recording, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = recording.displayName,
                style = MaterialTheme.typography.bodyMedium,
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
        }
        if (recording.wasSilent) {
            Text(
                text = stringResource(R.string.library_silent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
