package dev.drecorder.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import dev.drecorder.capture.RecordSettings
import dev.drecorder.data.InstalledAppsRepository
import dev.drecorder.data.LaunchableApp
import dev.drecorder.ui.UiPreferences
import dev.drecorder.ui.home.AppPickerSheet
import kotlinx.coroutines.launch

/**
 * Settings as four grouped cards, not one endless form.
 *
 * Eleven sections in a single column read as one long strip: the user could not
 * tell where "recording" ended and "appearance" began (2026-11-04 「一个界面
 * 大长条不好看」). A raised card per group gives each cluster a boundary that
 * can be taken in at a glance, without hiding a single option.
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
    onRangeChange: (appsOnly: Boolean, uids: List<Int>) -> Unit,
    appCount: Int,
    onChange: (sampleRate: Int, channelCount: Int, bitRate: Int, format: String) -> Unit,
    language: String = "",
    onLanguageChange: (String) -> Unit = {},
    theme: UiPreferences.Theme = UiPreferences.Theme.System,
    onThemeChange: (UiPreferences.Theme) -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { InstalledAppsRepository(context) }

    var sampleRate by remember(settings) { mutableIntStateOf(settings.sampleRate) }
    var channelCount by remember(settings) { mutableIntStateOf(settings.channelCount) }
    var bitRate by remember(settings) { mutableIntStateOf(settings.bitRate) }
    var segmentMinutes by remember(settings) { mutableIntStateOf(settings.segmentMinutes) }
    var format by remember(settings) { mutableStateOf(if (settings.isWav) "wav" else "aac") }
    var pickerVisible by remember { mutableStateOf(false) }
    var pickerApps by remember { mutableStateOf(emptyList<LaunchableApp>()) }
    var pickerLoading by remember { mutableStateOf(false) }
    var pickerError by remember { mutableStateOf<String?>(null) }
    val pickerLoadError = stringResource(R.string.picker_load_error)
    val nothingSelected = stringResource(R.string.picker_empty)

    suspend fun loadApps() {
        pickerLoading = true
        pickerError = null
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            // No back arrow: this page is a sibling of the home page in the bar
            // below, not a screen pushed on top of it.
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = bottomBar,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SettingsGroup(stringResource(R.string.settings_group_recording)) {
                SectionTitle(stringResource(R.string.settings_scope))
                ChoiceRow(
                    label = stringResource(R.string.settings_scope_all),
                    selected = !settings.appsOnly,
                    onSelect = { onRangeChange(false, settings.appUids) },
                )
                ChoiceRow(
                    label = stringResource(R.string.settings_scope_apps),
                    selected = settings.appsOnly,
                    onSelect = {
                        if (settings.appUids.isEmpty()) pickerVisible = true
                        else onRangeChange(true, settings.appUids)
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.apps_count, appCount, appCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { pickerVisible = true }) {
                        Text(stringResource(R.string.settings_choose_apps_action))
                    }
                }
            }

            SettingsGroup(stringResource(R.string.settings_group_quality)) {
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
                            pluralStringResource(R.plurals.minutes_count, minutes, minutes)
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
            }

            SettingsGroup(stringResource(R.string.settings_group_app)) {
                SectionTitle(stringResource(R.string.settings_language))
                UiPreferences.LANGUAGES.forEach { tag ->
                    ChoiceRow(
                        label = when (tag) {
                            "zh" -> stringResource(R.string.settings_language_zh)
                            "en" -> stringResource(R.string.settings_language_en)
                            else -> stringResource(R.string.settings_language_system)
                        },
                        selected = language == tag,
                        onSelect = { if (language != tag) onLanguageChange(tag) },
                    )
                }

                SectionTitle(stringResource(R.string.settings_theme))
                listOf(
                    UiPreferences.Theme.System to stringResource(R.string.settings_theme_system),
                    UiPreferences.Theme.Light to stringResource(R.string.settings_theme_light),
                    UiPreferences.Theme.Dark to stringResource(R.string.settings_theme_dark),
                ).forEach { (value, label) ->
                    ChoiceRow(
                        label = label,
                        selected = theme == value,
                        onSelect = { if (theme != value) onThemeChange(value) },
                    )
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
            }

            SettingsGroup(stringResource(R.string.settings_group_about)) {
                Text(
                    text = stringResource(R.string.settings_about_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
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
                onRangeChange(true, picked.distinctUids)
            },
        )
    }
}

/** A compact subheading inside a group card. */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
    )
}

/**
 * One visual group of settings: a raised card with its own title, so eleven
 * sections are four clusters rather than one endless strip.
 */
@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
            )
            content()
            Spacer(modifier = Modifier.height(6.dp))
        }
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
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
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
            .heightIn(min = 52.dp)
            .selectable(selected = selected, onClick = onSelect)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        RadioButton(selected = selected, onClick = onSelect)
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
    )
}
