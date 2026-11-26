package dev.drecorder.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import dev.drecorder.capture.RecordSettings
import dev.drecorder.data.InstalledAppsRepository
import dev.drecorder.data.LaunchableApp
import dev.drecorder.data.formatSize
import dev.drecorder.ui.ColorStyle
import dev.drecorder.ui.UiPreferences
import dev.drecorder.ui.design.ChoicePillGroup
import dev.drecorder.ui.design.HintText
import dev.drecorder.ui.design.SectionCard
import dev.drecorder.ui.design.SettingsSectionTitle
import dev.drecorder.ui.design.TextActionButton
import dev.drecorder.ui.home.AppPickerSheet
import dev.drecorder.ui.home.SelectedAppChip
import dev.drecorder.ui.previewColor
import kotlinx.coroutines.launch

/**
 * One settings page: a title, a back arrow, and only this page's options.
 *
 * Sub-pages deliberately do not carry the bottom navigation: they are places
 * pushed on top of settings, closed with the arrow or the system back gesture,
 * and a page that both has a back arrow and a tab bar invites the user to leave
 * it two different ways.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(text = title, fontWeight = FontWeight.Bold) },
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 28.dp),
            content = content,
        )
    }
}

// ---- scope ------------------------------------------------------------------

@Composable
fun ScopeSettingsScreen(
    settings: RecordSettings,
    appCount: Int,
    onRangeChange: (appsOnly: Boolean, uids: List<Int>) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { InstalledAppsRepository(context) }
    var pickerVisible by remember { mutableStateOf(false) }
    var pickerApps by remember { mutableStateOf(emptyList<LaunchableApp>()) }
    // The apps behind the saved uids, so the page can name them. Resolved from
    // whatever list has been loaded; an app that is no longer installed simply
    // does not appear, which is honest — it will not be captured either.
    val selectedApps: List<LaunchableApp> = remember(pickerApps, settings.appUids) {
        pickerApps.filter { app -> app.uid in settings.appUids }
    }
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

    SettingsPageScaffold(title = stringResource(R.string.settings_scope), onBack = onBack) {
        SectionCard(
            title = stringResource(R.string.settings_group_recording),
            icon = Icons.Filled.RadioButtonChecked,
        ) {
            SettingsSectionTitle(stringResource(R.string.settings_scope))
            ChoicePillGroup(
                options = listOf(false, true),
                selected = settings.appsOnly,
                label = {
                    stringResource(
                        if (it) R.string.settings_scope_apps else R.string.settings_scope_all,
                    )
                },
                onSelect = { appsOnly ->
                    if (appsOnly && settings.appUids.isEmpty()) {
                        pickerVisible = true
                    } else {
                        onRangeChange(appsOnly, settings.appUids)
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pluralStringResource(R.plurals.apps_count, appCount, appCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextActionButton(
                    text = stringResource(R.string.settings_choose_apps_action),
                    onClick = { pickerVisible = true },
                )
            }
            // The chosen apps, by icon and name, **on this page**.
            //
            // A bare count ("3 个应用") says a filter exists but not which apps
            // are in it, so the only way to check the setting was to open the
            // picker and hunt for the ticks. They belong where the setting is.
            if (settings.appsOnly && selectedApps.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                // Wrapped, not scrolled sideways: a row that scrolls hides part
                // of the answer behind an edge the user has no reason to suspect
                // (「挤不下的就不要放在一行上面可以吗，明明可以纵向的」). Three apps
                // looked like two because Chrome sat past the right edge.
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (app in selectedApps) {
                        SelectedAppChip(
                            app = app,
                            onRemove = {
                                val remaining = settings.appUids - app.uid
                                onRangeChange(remaining.isNotEmpty(), remaining)
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            HintText(stringResource(R.string.picker_note))
        }
    }

    if (pickerVisible) {
        AppPickerSheet(
            apps = pickerApps,
            isLoading = pickerLoading,
            loadError = pickerError,
            // Seeded from the saved filter so the sheet opens showing what is
            // already chosen, and a tapped-off app is actually removed.
            initialUids = settings.appUids,
            onRetry = { scope.launch { loadApps() } },
            onDismiss = { pickerVisible = false },
            onConfirm = { picked ->
                pickerVisible = false
                onRangeChange(true, picked.distinctUids)
            },
        )
    }
}

// ---- audio quality ----------------------------------------------------------

@Composable
fun AudioSettingsScreen(
    settings: RecordSettings,
    aacAvailable: Boolean,
    onChange: (sampleRate: Int, channelCount: Int, bitRate: Int, format: String) -> Unit,
    onBack: () -> Unit,
) {
    var sampleRate by remember(settings) { mutableIntStateOf(settings.sampleRate) }
    var channelCount by remember(settings) { mutableIntStateOf(settings.channelCount) }
    var bitRate by remember(settings) { mutableIntStateOf(settings.bitRate) }
    var format by remember(settings) { mutableStateOf(if (settings.isWav) "wav" else "aac") }

    val bytesPerMinute = RecordSettings.bytesPerMinute(sampleRate, channelCount, bitRate, format == "wav")

    SettingsPageScaffold(title = stringResource(R.string.settings_group_audio), onBack = onBack) {
        SectionCard(
            title = stringResource(R.string.settings_group_audio),
            icon = Icons.Filled.GraphicEq,
        ) {
            SettingsSectionTitle(stringResource(R.string.settings_format))
            ChoicePillGroup(
                options = listOf("aac", "wav"),
                selected = format,
                label = {
                    stringResource(
                        if (it == "aac") R.string.settings_format_aac else R.string.settings_format_wav,
                    )
                },
                onSelect = { next ->
                    format = next
                    onChange(sampleRate, channelCount, bitRate, next)
                },
            )
            if (!aacAvailable) {
                Spacer(Modifier.height(6.dp))
                HintText(stringResource(R.string.settings_aac_fallback), isError = true)
            }
            if (format == "wav") {
                Spacer(Modifier.height(6.dp))
                HintText(stringResource(R.string.settings_bit_rate_irrelevant_wav))
            }

            SettingsSectionTitle(stringResource(R.string.settings_sample_rate))
            ChoicePillGroup(
                options = listOf(48_000, 44_100),
                selected = sampleRate,
                label = { stringResource(R.string.settings_hz, it) },
                onSelect = { next ->
                    sampleRate = next
                    onChange(next, channelCount, bitRate, format)
                },
            )

            SettingsSectionTitle(stringResource(R.string.settings_channels))
            ChoicePillGroup(
                options = listOf(2, 1),
                selected = channelCount,
                label = {
                    stringResource(
                        if (it == 2) R.string.settings_channel_stereo else R.string.settings_channel_mono,
                    )
                },
                onSelect = { next ->
                    channelCount = next
                    onChange(sampleRate, next, bitRate, format)
                },
            )

            if (format == "aac") {
                SettingsSectionTitle(stringResource(R.string.settings_bit_rate))
                ChoicePillGroup(
                    options = listOf(128_000, 192_000, 256_000, 320_000),
                    selected = bitRate,
                    label = { stringResource(R.string.settings_kbps, it / 1000) },
                    onSelect = { next ->
                        bitRate = next
                        onChange(sampleRate, channelCount, next, format)
                    },
                )
            }

            Spacer(Modifier.height(10.dp))
            HintText(stringResource(R.string.home_size_per_minute, formatSize(bytesPerMinute)))
        }
    }
}

// ---- storage ----------------------------------------------------------------

@Composable
fun StorageSettingsScreen(
    settings: RecordSettings,
    outputDirectory: String?,
    onSegmentChange: (minutes: Int) -> Unit,
    onPickOutputDirectory: () -> Unit,
    onBack: () -> Unit,
) {
    var segmentMinutes by remember(settings) { mutableIntStateOf(settings.segmentMinutes) }

    SettingsPageScaffold(title = stringResource(R.string.settings_group_storage), onBack = onBack) {
        SectionCard(
            title = stringResource(R.string.settings_group_storage),
            icon = Icons.Filled.Folder,
        ) {
            SettingsSectionTitle(stringResource(R.string.settings_segment))
            ChoicePillGroup(
                options = RecordSettings.SEGMENT_CHOICES,
                selected = segmentMinutes,
                label = {
                    if (it == 0) {
                        stringResource(R.string.settings_segment_off)
                    } else {
                        pluralStringResource(R.plurals.minutes_count, it, it)
                    }
                },
                onSelect = { next ->
                    segmentMinutes = next
                    onSegmentChange(next)
                },
            )
            Spacer(Modifier.height(6.dp))
            HintText(stringResource(R.string.settings_segment_note))

            SettingsSectionTitle(stringResource(R.string.settings_output_dir))
            NavRow(
                label = outputDirectory ?: stringResource(R.string.settings_output_dir_unset),
                labelStyle = MaterialTheme.typography.bodyMedium,
                onClick = onPickOutputDirectory,
                action = stringResource(R.string.settings_change_output_dir),
            )
        }
    }
}

// ---- appearance -------------------------------------------------------------

@Composable
fun AppearanceSettingsScreen(
    language: String,
    theme: UiPreferences.Theme,
    colorStyle: ColorStyle,
    onLanguageChange: (String) -> Unit,
    onThemeChange: (UiPreferences.Theme) -> Unit,
    onColorStyleChange: (ColorStyle) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageScaffold(title = stringResource(R.string.settings_group_appearance), onBack = onBack) {
        SectionCard(
            title = stringResource(R.string.settings_group_appearance),
            icon = Icons.Filled.Palette,
        ) {
            SettingsSectionTitle(stringResource(R.string.settings_color_style))
            ColorStyleRow(selected = colorStyle, onSelect = onColorStyleChange)

            SettingsSectionTitle(stringResource(R.string.settings_theme_mode))
            ChoicePillGroup(
                options = listOf(
                    UiPreferences.Theme.System,
                    UiPreferences.Theme.Light,
                    UiPreferences.Theme.Dark,
                ),
                selected = theme,
                label = {
                    stringResource(
                        when (it) {
                            UiPreferences.Theme.System -> R.string.settings_theme_system
                            UiPreferences.Theme.Light -> R.string.settings_theme_light
                            UiPreferences.Theme.Dark -> R.string.settings_theme_dark
                        },
                    )
                },
                onSelect = { if (theme != it) onThemeChange(it) },
            )

            SettingsSectionTitle(stringResource(R.string.settings_language))
            ChoicePillGroup(
                options = UiPreferences.LANGUAGES,
                selected = language,
                label = {
                    when (it) {
                        "zh" -> stringResource(R.string.settings_language_zh)
                        "en" -> stringResource(R.string.settings_language_en)
                        else -> stringResource(R.string.settings_language_system)
                    }
                },
                onSelect = { if (language != it) onLanguageChange(it) },
            )
        }
    }
}

// ---- about ------------------------------------------------------------------

@Composable
fun AboutSettingsScreen(onBack: () -> Unit) {
    SettingsPageScaffold(title = stringResource(R.string.settings_group_about), onBack = onBack) {
        SectionCard(
            title = stringResource(R.string.settings_group_about),
            icon = Icons.Filled.Info,
        ) {
            Text(
                text = stringResource(R.string.settings_about_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
    }
}

// ---- shared bits ------------------------------------------------------------

/** The five accent families, as swatches. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorStyleRow(
    selected: ColorStyle,
    onSelect: (ColorStyle) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ColorStyle.values().forEach { style ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    // 5 x 52 + 4 x 8 fits a 360dp phone in one row; at 58dp the
                    // fifth swatch wrapped alone onto a second line.
                    .width(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { if (style != selected) onSelect(style) }
                    .padding(vertical = 6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(previewColor(style))
                        .then(
                            if (style == selected) {
                                Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            } else {
                                Modifier
                            },
                        ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(style.labelRes()),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (style == selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

internal fun ColorStyle.labelRes(): Int = when (this) {
    ColorStyle.Sky -> R.string.color_style_sky
    ColorStyle.Mint -> R.string.color_style_mint
    ColorStyle.Cherry -> R.string.color_style_cherry
    ColorStyle.Sunset -> R.string.color_style_sunset
    ColorStyle.Grape -> R.string.color_style_grape
}

/** A tappable row that leads somewhere: title, optional one-line summary, and an
 * optional action word before the chevron. */
@Composable
internal fun NavRow(
    label: String,
    onClick: () -> Unit,
    summary: String? = null,
    action: String? = null,
    labelStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = labelStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (action != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
