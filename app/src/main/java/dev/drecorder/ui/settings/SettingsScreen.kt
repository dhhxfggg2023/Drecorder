package dev.drecorder.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import dev.drecorder.capture.RecordSettings

/**
 * Settings as one continuous list, not a stack of cards.
 *
 * A settings screen is scanned, not browsed: section labels plus divider-separated rows
 * put every option in the same visual rhythm, and the radio sits at the trailing edge
 * where the thumb already is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: RecordSettings,
    aacAvailable: Boolean,
    outputDirectory: String?,
    notice: String?,
    onNoticeShown: () -> Unit,
    onBack: () -> Unit,
    onPickOutputDirectory: () -> Unit,
    onSegmentChange: (minutes: Int) -> Unit,
    onOpenUpdate: () -> Unit,
    onChange: (sampleRate: Int, channelCount: Int, bitRate: Int, format: String) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    var sampleRate by remember(settings) { mutableStateOf(settings.sampleRate) }
    var channelCount by remember(settings) { mutableStateOf(settings.channelCount) }
    var bitRate by remember(settings) { mutableStateOf(settings.bitRate) }
    var segmentMinutes by remember(settings) { mutableStateOf(settings.segmentMinutes) }
    var format by remember(settings) {
        mutableStateOf(if (settings.isWav) "wav" else "aac")
    }

    LaunchedEffect(notice) {
        val message = notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onNoticeShown()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_format))
            ChoiceRow(
                label = stringResource(R.string.settings_format_aac),
                selected = format == "aac",
                onSelect = {
                    format = "aac"
                    onChange(sampleRate, channelCount, bitRate, "aac")
                },
            )
            if (!aacAvailable) {
                Note(stringResource(R.string.settings_aac_fallback), isError = true)
            }
            ChoiceRow(
                label = stringResource(R.string.settings_format_wav),
                selected = format == "wav",
                onSelect = {
                    format = "wav"
                    onChange(sampleRate, channelCount, bitRate, "wav")
                },
            )
            if (format == "wav") {
                Note(stringResource(R.string.settings_bit_rate_irrelevant_wav))
            }

            SectionTitle(stringResource(R.string.settings_sample_rate))
            ChoiceRow(
                label = stringResource(R.string.settings_hz, 48_000),
                selected = sampleRate == 48_000,
                onSelect = {
                    sampleRate = 48_000
                    onChange(sampleRate, channelCount, bitRate, format)
                },
            )
            ChoiceRow(
                label = stringResource(R.string.settings_hz, 44_100),
                selected = sampleRate == 44_100,
                onSelect = {
                    sampleRate = 44_100
                    onChange(sampleRate, channelCount, bitRate, format)
                },
            )

            SectionTitle(stringResource(R.string.settings_channels))
            ChoiceRow(
                label = stringResource(R.string.settings_channel_stereo),
                selected = channelCount == 2,
                onSelect = {
                    channelCount = 2
                    onChange(sampleRate, channelCount, bitRate, format)
                },
            )
            ChoiceRow(
                label = stringResource(R.string.settings_channel_mono),
                selected = channelCount == 1,
                onSelect = {
                    channelCount = 1
                    onChange(sampleRate, channelCount, bitRate, format)
                },
            )

            if (format == "aac") {
                SectionTitle(stringResource(R.string.settings_bit_rate))
                listOf(128_000, 192_000, 256_000, 320_000).forEach { rate ->
                    ChoiceRow(
                        label = stringResource(R.string.settings_kbps, rate / 1000),
                        selected = bitRate == rate,
                        onSelect = {
                            bitRate = rate
                            onChange(sampleRate, channelCount, bitRate, format)
                        },
                    )
                }
            }

            SectionTitle(stringResource(R.string.settings_segment))
            RecordSettings.SEGMENT_CHOICES.forEach { minutes ->
                ChoiceRow(
                    label = if (minutes == 0) {
                        stringResource(R.string.settings_segment_off)
                    } else {
                        stringResource(R.string.settings_segment_minutes, minutes)
                    },
                    selected = segmentMinutes == minutes,
                    onSelect = {
                        segmentMinutes = minutes
                        onSegmentChange(minutes)
                    },
                )
            }
            Note(stringResource(R.string.settings_segment_note))

            SectionTitle(stringResource(R.string.settings_output_dir))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = outputDirectory ?: stringResource(R.string.settings_output_dir_unset),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onPickOutputDirectory) {
                    Text(stringResource(R.string.settings_change_output_dir))
                }
            }

            SectionTitle(stringResource(R.string.settings_update))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .selectable(selected = false, onClick = onOpenUpdate),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.update_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.update_auto_check_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionTitle(stringResource(R.string.settings_about))
            Text(
                text = stringResource(R.string.settings_about_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun Note(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
    )
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        RadioButton(selected = selected, onClick = onSelect)
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 20.dp, end = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
    )
}
