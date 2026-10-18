package dev.drecorder.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import dev.drecorder.capture.RecordSettings

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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Format
            SectionCard(title = stringResource(R.string.settings_format)) {
                ChoiceRow(
                    label = stringResource(R.string.settings_format_aac),
                    selected = format == "aac",
                    enabled = true,
                    onSelect = {
                        format = "aac"
                        onChange(sampleRate, channelCount, bitRate, "aac")
                    },
                )
                if (!aacAvailable) {
                    Text(
                        text = stringResource(R.string.settings_aac_fallback),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ChoiceRow(
                    label = stringResource(R.string.settings_format_wav),
                    selected = format == "wav",
                    enabled = true,
                    onSelect = {
                        format = "wav"
                        onChange(sampleRate, channelCount, bitRate, "wav")
                    },
                )
                if (format == "wav") {
                    Text(
                        text = stringResource(R.string.settings_bit_rate_irrelevant_wav),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Sample rate
            SectionCard(title = stringResource(R.string.settings_sample_rate)) {
                ChoiceRow(
                    label = stringResource(R.string.settings_hz, 48_000),
                    selected = sampleRate == 48_000,
                    enabled = true,
                    onSelect = {
                        sampleRate = 48_000
                        onChange(sampleRate, channelCount, bitRate, format)
                    },
                )
                ChoiceRow(
                    label = stringResource(R.string.settings_hz, 44_100),
                    selected = sampleRate == 44_100,
                    enabled = true,
                    onSelect = {
                        sampleRate = 44_100
                        onChange(sampleRate, channelCount, bitRate, format)
                    },
                )
            }

            // Channels
            SectionCard(title = stringResource(R.string.settings_channels)) {
                ChoiceRow(
                    label = stringResource(R.string.settings_channel_stereo),
                    selected = channelCount == 2,
                    enabled = true,
                    onSelect = {
                        channelCount = 2
                        onChange(sampleRate, channelCount, bitRate, format)
                    },
                )
                ChoiceRow(
                    label = stringResource(R.string.settings_channel_mono),
                    selected = channelCount == 1,
                    enabled = true,
                    onSelect = {
                        channelCount = 1
                        onChange(sampleRate, channelCount, bitRate, format)
                    },
                )
            }

            // Slice length
            SectionCard(title = stringResource(R.string.settings_segment)) {
                RecordSettings.SEGMENT_CHOICES.forEach { minutes ->
                    ChoiceRow(
                        label = if (minutes == 0) {
                            stringResource(R.string.settings_segment_off)
                        } else {
                            stringResource(R.string.settings_segment_minutes, minutes)
                        },
                        selected = segmentMinutes == minutes,
                        enabled = true,
                        onSelect = {
                            segmentMinutes = minutes
                            onSegmentChange(minutes)
                        },
                    )
                }
                Text(
                    text = stringResource(R.string.settings_segment_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // Bit rate (AAC only)
            if (format == "aac") {
                SectionCard(title = stringResource(R.string.settings_bit_rate)) {
                    listOf(128_000, 192_000, 256_000, 320_000).forEach { rate ->
                        ChoiceRow(
                            label = stringResource(R.string.settings_kbps, rate / 1000),
                            selected = bitRate == rate,
                            enabled = true,
                            onSelect = {
                                bitRate = rate
                                onChange(sampleRate, channelCount, bitRate, format)
                            },
                        )
                    }
                }
            }

            // Output directory
            SectionCard(title = stringResource(R.string.settings_output_dir)) {
                Text(
                    text = outputDirectory
                        ?.let { stringResource(R.string.settings_output_dir_value, it) }
                        ?: stringResource(R.string.settings_output_dir_unset),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onPickOutputDirectory) {
                    Text(stringResource(R.string.settings_change_output_dir))
                }
            }

            SectionCard(title = stringResource(R.string.settings_about)) {
                Text(
                    text = stringResource(R.string.settings_about_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            content()
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
