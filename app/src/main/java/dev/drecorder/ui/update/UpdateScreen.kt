package dev.drecorder.ui.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import dev.drecorder.data.UpdateSafety
import dev.drecorder.ui.design.IconTile
import dev.drecorder.ui.design.OutlinedActionButton
import dev.drecorder.ui.design.PrimaryActionButton
import dev.drecorder.ui.design.TextActionButton

/** What the update screen is currently showing. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val versionName: String, val notes: String, val forced: Boolean) : UpdateUiState

    /**
     * A download is in flight.
     *
     * @param downloaded bytes fetched so far
     * @param total the size of the file, or -1 while the server has not said
     *   (the progress bar is then indeterminate instead of guessing)
     */
    data class Downloading(val downloaded: Long, val total: Long) : UpdateUiState {
        /** The download in 0..100, or null while the total size is unknown. */
        val percent: Int?
            get() = if (total > 0L) {
                ((downloaded.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            } else {
                null
            }
    }
    data class ReadyToInstall(val versionName: String) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState

    /**
     * Whether "check for updates" is a sensible thing to offer right now.
     *
     * It is not, once a version has been found: the next step is to download it
     * or install it, and a second button offering to start over is a choice the
     * user should not have to make. Nor is it while a download is running, and
     * nor once the file is on the phone waiting to be installed.
     */
    val canCheckAgain: Boolean
        get() = when (this) {
            Idle, UpToDate, Checking, is Failed -> true
            is Available, is Downloading, is ReadyToInstall -> false
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    currentVersionName: String,
    state: UpdateUiState,
    manifestUrl: String,
    autoCheck: Boolean,
    lastCheckLabel: String?,
    canInstallPackages: Boolean,
    notice: String?,
    onNoticeShown: () -> Unit,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenReleases: () -> Unit,
    onOpenUnknownSources: () -> Unit,
    onManifestUrlChange: (String) -> Unit,
    onAutoCheckChange: (Boolean) -> Unit,
    /**
     * What installing this update would do to the recordings already on the
     * phone. [UpdateSafety.PreservesData] is the normal case and shows nothing;
     * everything else has to be visible *before* the download, because the
     * consequence is an uninstall and the default recordings folder lives
     * inside the app.
     */
    dataSafety: UpdateSafety = UpdateSafety.Unknown,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var urlDraft by remember(manifestUrl) { mutableStateOf(manifestUrl) }

    LaunchedEffect(notice) {
        val message = notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onNoticeShown()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.update_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
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
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(
                            icon = Icons.Filled.CloudDownload,
                            size = 46.dp,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.update_current, currentVersionName),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = lastCheckLabel
                                    ?.let { stringResource(R.string.update_last_check, it) }
                                    ?: stringResource(R.string.update_never),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    when (state) {
                        UpdateUiState.Checking -> {
                            CircularProgressIndicator()
                        }

                        UpdateUiState.UpToDate -> StatusText(stringResource(R.string.update_up_to_date))

                        is UpdateUiState.Available -> {
                            StatusText(
                                stringResource(R.string.update_available, state.versionName),
                                highlight = true,
                            )
                            // `forced` was computed from the manifest's minimum supported
                            // version, carried into this state — and never shown, so a
                            // build that declared itself unsupported looked exactly like
                            // an optional upgrade.
                            if (state.forced) {
                                StatusText(
                                    stringResource(R.string.update_required),
                                    highlight = true,
                                )
                            }
                            if (state.notes.isNotBlank()) {
                                Text(text = state.notes, style = MaterialTheme.typography.bodySmall)
                            }
                            DataSafetyNote(dataSafety)
                            PrimaryActionButton(
                                text = stringResource(R.string.update_download),
                                onClick = onDownload,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        is UpdateUiState.Downloading -> {
                            val percent = state.percent
                            Text(
                                text = if (percent != null) {
                                    stringResource(R.string.update_downloading, percent)
                                } else {
                                    stringResource(R.string.update_downloading_unknown)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (percent != null) {
                                LinearProgressIndicator(
                                    progress = { percent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                // The size is not known yet: an indeterminate bar
                                // says "working" where a 0% bar says "stuck".
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Text(
                                text = formatBytes(state.downloaded),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        is UpdateUiState.ReadyToInstall -> {
                            StatusText(
                                stringResource(R.string.update_available, state.versionName),
                                highlight = true,
                            )
                            DataSafetyNote(dataSafety)
                            if (!canInstallPackages) {
                                Text(
                                    text = stringResource(R.string.update_unknown_sources),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                PrimaryActionButton(
                                    text = stringResource(R.string.update_unknown_sources),
                                    onClick = onOpenUnknownSources,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                PrimaryActionButton(
                                    text = stringResource(R.string.update_install),
                                    onClick = onInstall,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }

                        is UpdateUiState.Failed -> StatusText(state.message, highlight = true, isError = true)

                        UpdateUiState.Idle -> Unit
                    }

                    // "Check for updates" is only offered when there is nothing
                    // to do with a version that has already been found. Showing
                    // it next to "Download" asked the user to choose between two
                    // buttons that both looked like the next step, and next to
                    // "Install" it offered to throw the downloaded file away
                    // right before using it (m01839).
                    if (state.canCheckAgain) {
                        TonalCheckButton(
                            text = if (state is UpdateUiState.Checking) {
                                stringResource(R.string.update_checking)
                            } else {
                                stringResource(R.string.update_check)
                            },
                            onClick = onCheck,
                        )
                    }

                    // Never leave the user stuck when the download or a mirror fails.
                    TextActionButton(
                        text = stringResource(R.string.update_open_releases),
                        onClick = onOpenReleases,
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAutoCheckChange(!autoCheck) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTile(
                            icon = Icons.Filled.Settings,
                            size = 38.dp,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.padding(horizontal = 5.dp))
                        Text(
                            text = stringResource(R.string.update_auto_check),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = autoCheck, onCheckedChange = onAutoCheckChange)
                    }
                    Text(
                        text = stringResource(R.string.update_auto_check_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it },
                        label = { Text(stringResource(R.string.update_manifest_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextActionButton(
                        // This is an action, not a receipt: the old label said
                        // "settings saved" before anything had been saved.
                        text = stringResource(R.string.common_save),
                        enabled = urlDraft.isNotBlank() && urlDraft.trim() != manifestUrl,
                        onClick = { onManifestUrlChange(urlDraft.trim()) },
                    )
                }
            }
        }
    }
}

/** The secondary "check again" action: tonal, full width, same geometry as the primary. */
@Composable
private fun TonalCheckButton(text: String, onClick: () -> Unit) {
    OutlinedActionButton(
        text = text,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * What the update does to the data already on the device.
 *
 * Only the two states that can cost something say anything: an in-place upgrade
 * is the expected behaviour and does not need a paragraph, but "this cannot be
 * installed over your app, you will have to uninstall first" is the one warning
 * that has to arrive before the user taps anything.
 */
@Composable
private fun DataSafetyNote(safety: UpdateSafety) {
    when (safety) {
        UpdateSafety.PreservesData -> Text(
            text = stringResource(R.string.update_keeps_data),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is UpdateSafety.RequiresUninstall -> {
            Text(
                text = stringResource(R.string.update_needs_uninstall),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.update_backup_first),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        UpdateSafety.Unknown -> Unit
    }
}

@Composable
private fun StatusText(text: String, highlight: Boolean = false, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
        color = when {
            isError -> MaterialTheme.colorScheme.error
            highlight -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        },
    )
}

/** A byte count as something a person can read: "12.3 MB". */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1_024L -> bytes.toString() + " B"
    bytes < 1_048_576L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "%.1f MB".format(bytes / 1_048_576.0)
}
