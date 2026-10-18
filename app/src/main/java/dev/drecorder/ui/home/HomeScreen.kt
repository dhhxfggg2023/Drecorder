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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import dev.drecorder.data.Recording
import dev.drecorder.data.formatDuration
import dev.drecorder.data.formatSize
import dev.drecorder.ui.recordingWhenLabel

/**
 * The recording deck: one range summary line, one big button, recent recordings.
 *
 * The range (everything / a few apps) is configured in Settings on purpose: it changes
 * rarely, and putting a two-segment picker on the home screen made starting a recording
 * look like a two-step decision. The button label always states what will be recorded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    notice: String?,
    onNoticeShown: () -> Unit,
    onStartCapture: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    appsOnly: Boolean,
    appCount: Int,
    recents: List<Recording> = emptyList(),
    recordingNow: Boolean = false,
    onOpenRecording: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val deviceHasSecureLock = remember(context) {
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }
    var disclosureVisible by remember { mutableStateOf(false) }

    LaunchedEffect(notice) {
        val message = notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onNoticeShown()
    }

    val usesApps = appsOnly && appCount > 0
    val rangeLabel = if (usesApps) {
        stringResource(R.string.home_scope_apps, appCount)
    } else {
        stringResource(R.string.home_scope_all)
    }
    val startLabel = when {
        recordingNow -> stringResource(R.string.home_open_recording)
        usesApps -> stringResource(R.string.home_start_picked, appCount)
        else -> stringResource(R.string.home_start)
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.home_scope_label, rangeLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.home_change))
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            DeckButton(
                recording = recordingNow,
                onClick = { if (recordingNow) onOpenRecording() else onStartCapture() },
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = startLabel,
                style = MaterialTheme.typography.titleMedium,
                color = if (recordingNow) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            TextButton(onClick = { disclosureVisible = true }) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.home_why_authorization))
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp),
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
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                )
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items = recents, key = { it.displayName }) { recording ->
                        RecentCard(recording = recording, onClick = onOpenLibrary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
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
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }
}

/**
 * The one button. 112dp is big enough to be the screen's anchor and still fits beside
 * the system gesture bar, and its colour change (teal → error red) is the only state the
 * home screen needs to convey.
 */
@Composable
private fun DeckButton(recording: Boolean, onClick: () -> Unit) {
    val container = if (recording) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val content = if (recording) {
        MaterialTheme.colorScheme.onError
    } else {
        MaterialTheme.colorScheme.onPrimary
    }
    Box(
        modifier = Modifier
            .padding(4.dp)
            .size(112.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // A dot while idle and a square while recording: the classic transport-control
        // pair, and unambiguous without any label or accessibility text.
        if (recording) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(content),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(content),
            )
        }
    }
}

@Composable
private fun RecentCard(recording: Recording, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(200.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = recordingWhenLabel(recording),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
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
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.library_silent_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
