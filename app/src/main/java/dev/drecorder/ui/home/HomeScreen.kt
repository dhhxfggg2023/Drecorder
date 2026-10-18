package dev.drecorder.ui.home

import android.app.KeyguardManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import dev.drecorder.audio.AppSelection
import dev.drecorder.audio.CaptureScope
import dev.drecorder.data.InstalledAppsRepository
import dev.drecorder.data.LaunchableApp
import dev.drecorder.data.Recording
import dev.drecorder.data.formatDuration
import dev.drecorder.data.formatSize
import dev.drecorder.ui.recordingWhenLabel
import kotlinx.coroutines.launch

/**
 * Home: choose a source, then one primary action.
 *
 * Layout rules this screen follows (docs/DESIGN.md §6.2, revised 2026-10-18 after the
 * first real-device pass):
 * - exactly one filled button — "开始录制" — in the bottom bar, so the primary action
 *   never competes with the source rows for emphasis;
 * - the source rows are compact and carry an icon, a title, one line of explanation
 *   and a chevron, so they read as tappable instead of as text blocks;
 * - the permission disclosure is a single link that opens a dialog, because it is read
 *   once and then never again;
 * - recents show a date + clock, not a file name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    notice: String?,
    onNoticeShown: () -> Unit,
    onStartCapture: (CaptureScope) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    recents: List<Recording> = emptyList(),
    recordingNow: Boolean = false,
    onOpenRecording: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val deviceHasSecureLock = remember(context) {
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }

    val repository = remember(context) { InstalledAppsRepository(context) }
    var pickerVisible by remember { mutableStateOf(false) }
    var pickerApps by remember { mutableStateOf(emptyList<LaunchableApp>()) }
    var pickerLoading by remember { mutableStateOf(false) }
    var pickerError by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf(AppSelection(emptyList())) }
    var usePickedApps by remember { mutableStateOf(false) }
    var disclosureVisible by remember { mutableStateOf(false) }

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

    val startLabel = if (usePickedApps) {
        stringResource(R.string.home_start_picked)
    } else {
        stringResource(R.string.home_start)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.home_settings),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    Button(
                        onClick = {
                            when {
                                !usePickedApps -> onStartCapture(CaptureScope.AllMedia)
                                selection.distinctUids.isEmpty() -> pickerVisible = true
                                else -> selection.toCaptureScope()?.let(onStartCapture)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .height(54.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FiberManualRecord,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = startLabel)
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            if (recordingNow) {
                RecordingBanner(onClick = onOpenRecording)
            }

            SectionLabel(stringResource(R.string.home_source))
            SourceRow(
                icon = Icons.Filled.GraphicEq,
                title = stringResource(R.string.home_mode_all_title),
                subtitle = stringResource(R.string.home_mode_all_body),
                selected = !usePickedApps,
                onClick = { usePickedApps = false },
            )
            Spacer(modifier = Modifier.height(8.dp))
            SourceRow(
                icon = Icons.Filled.Apps,
                title = stringResource(R.string.home_mode_apps_title),
                subtitle = if (selection.distinctUids.isEmpty()) {
                    stringResource(R.string.home_mode_apps_body)
                } else {
                    stringResource(R.string.home_selected_apps, selection.distinctUids.size)
                },
                selected = usePickedApps,
                onClick = {
                    usePickedApps = true
                    pickerVisible = true
                },
            )

            TextButton(
                onClick = { disclosureVisible = true },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.home_why_authorization))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_recent),
                    style = MaterialTheme.typography.titleMedium,
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenLibrary)
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                )
            } else {
                recents.forEach { recording ->
                    RecentRecordingRow(recording = recording, onClick = onOpenLibrary)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (disclosureVisible) {
        AlertDialog(
            onDismissRequest = { disclosureVisible = false },
            title = { Text(stringResource(R.string.disclosure_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.disclosure_body))
                    if (deviceHasSecureLock) {
                        Text(
                            text = stringResource(R.string.disclosure_lock_warning),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { disclosureVisible = false }) {
                    Text(stringResource(R.string.record_back))
                }
            },
        )
    }

    if (pickerVisible) {
        AppPickerSheet(
            apps = pickerApps,
            isLoading = pickerLoading,
            loadError = pickerError,
            onRetry = { scope.launch { loadApps() } },
            onDismiss = { pickerVisible = false },
            onConfirm = { picked ->
                pickerVisible = false
                selection = picked
                usePickedApps = picked.distinctUids.isNotEmpty()
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

/**
 * A source option. Selected state is carried by the container colour and the radio
 * dot, never by colour alone: the dot is the non-colour cue.
 */
@Composable
private fun SourceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecordingBanner(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.FiberManualRecord,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.home_recording_now),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.home_open_recording),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
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
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = recordingWhenLabel(recording),
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
        }
        if (recording.wasSilent) {
            Text(
                text = stringResource(R.string.library_silent),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
