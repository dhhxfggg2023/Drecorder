package dev.drecorder.ui.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.drecorder.R

/** What the update screen is currently showing. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val versionName: String, val notes: String, val forced: Boolean) : UpdateUiState
    data class Downloading(val percent: Int) : UpdateUiState
    data class ReadyToInstall(val versionName: String) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
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
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var urlDraft by remember(manifestUrl) { mutableStateOf(manifestUrl) }

    LaunchedEffect(notice) {
        val message = notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onNoticeShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.update_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.record_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.update_current, currentVersionName),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = lastCheckLabel
                            ?.let { stringResource(R.string.update_last_check, it) }
                            ?: stringResource(R.string.update_never),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

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
                            if (state.notes.isNotBlank()) {
                                Text(text = state.notes, style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.update_download))
                            }
                        }

                        is UpdateUiState.Downloading -> {
                            Text(
                                text = stringResource(R.string.update_downloading, state.percent),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            LinearProgressIndicator(
                                progress = { state.percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        is UpdateUiState.ReadyToInstall -> {
                            StatusText(
                                stringResource(R.string.update_available, state.versionName),
                                highlight = true,
                            )
                            if (!canInstallPackages) {
                                Text(
                                    text = stringResource(R.string.update_unknown_sources),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Button(
                                    onClick = onOpenUnknownSources,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.update_unknown_sources))
                                }
                            } else {
                                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.update_install))
                                }
                            }
                        }

                        is UpdateUiState.Failed -> StatusText(state.message, highlight = true, isError = true)

                        UpdateUiState.Idle -> Unit
                    }

                    Button(
                        onClick = onCheck,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state !is UpdateUiState.Checking && state !is UpdateUiState.Downloading,
                    ) {
                        Text(
                            if (state is UpdateUiState.Checking) {
                                stringResource(R.string.update_checking)
                            } else {
                                stringResource(R.string.update_check)
                            },
                        )
                    }

                    // Never leave the user stuck when the download or a mirror fails.
                    TextButton(onClick = onOpenReleases) {
                        Text(stringResource(R.string.update_open_releases))
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAutoCheckChange(!autoCheck) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.update_auto_check),
                            style = MaterialTheme.typography.titleSmall,
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
                    TextButton(
                        onClick = { onManifestUrlChange(urlDraft.trim()) },
                        enabled = urlDraft.isNotBlank() && urlDraft.trim() != manifestUrl,
                    ) { Text(stringResource(R.string.settings_saved)) }
                }
            }
        }
    }
}

@Composable
private fun StatusText(text: String, highlight: Boolean = false, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = when {
            isError -> MaterialTheme.colorScheme.error
            highlight -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        },
    )
}
