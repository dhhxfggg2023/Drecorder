package dev.drecorder.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import dev.drecorder.capture.RecordSettings
import dev.drecorder.data.formatSize
import dev.drecorder.ui.ColorStyle
import dev.drecorder.ui.UiPreferences
import dev.drecorder.ui.design.IconTile

/**
 * The settings hub: six entries, each a page of its own.
 *
 * The first version of this screen was one long column of every option (eleven
 * sections, then four cards). Both were still one page: to change the bitrate a
 * user scrolled past the language, the output folder and the update address.
 * Settings are now a list of destinations — scope, quality, storage, appearance,
 * updates, about — and each destination holds only its own question.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: RecordSettings,
    appCount: Int,
    language: String = "",
    theme: UiPreferences.Theme = UiPreferences.Theme.System,
    colorStyle: ColorStyle = ColorStyle.Sky,
    onOpenScope: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenAbout: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    val sizePerMinute = formatSize(settings.estimatedBytesPerMinute)
    val scopeSummary = if (settings.appsOnly && settings.appUids.isNotEmpty()) {
        pluralStringResource(R.plurals.apps_count, appCount, appCount)
    } else {
        stringResource(R.string.settings_scope_all)
    }
    val audioSummary = listOf(
        stringResource(
            if (settings.isWav) R.string.settings_format_wav_short else R.string.settings_format_aac_short,
        ),
        stringResource(R.string.home_size_per_minute, sizePerMinute),
    ).joinToString(" · ")
    val storageSummary = if (settings.segmentMinutes == 0) {
        stringResource(R.string.settings_segment_off)
    } else {
        pluralStringResource(R.plurals.minutes_count, settings.segmentMinutes, settings.segmentMinutes)
    }
    val appearanceSummary = stringResource(colorStyle.labelRes()) +
        " · " +
        stringResource(
            when (theme) {
                UiPreferences.Theme.System -> R.string.settings_theme_system
                UiPreferences.Theme.Light -> R.string.settings_theme_light
                UiPreferences.Theme.Dark -> R.string.settings_theme_dark
            },
        )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            // No back arrow: this page is a sibling of the home page in the bar
            // below, not a screen pushed on top of it.
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = bottomBar,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SettingsEntry(
                icon = Icons.Filled.RadioButtonChecked,
                title = stringResource(R.string.settings_scope),
                summary = scopeSummary,
                onClick = onOpenScope,
            )
            SettingsEntry(
                icon = Icons.Filled.GraphicEq,
                title = stringResource(R.string.settings_group_audio),
                summary = audioSummary,
                onClick = onOpenAudio,
            )
            SettingsEntry(
                icon = Icons.Filled.Folder,
                title = stringResource(R.string.settings_group_storage),
                summary = storageSummary,
                onClick = onOpenStorage,
            )
            SettingsEntry(
                icon = Icons.Filled.Palette,
                title = stringResource(R.string.settings_group_appearance),
                summary = appearanceSummary,
                onClick = onOpenAppearance,
            )
            SettingsEntry(
                icon = Icons.Filled.CloudDownload,
                title = stringResource(R.string.settings_update),
                summary = stringResource(R.string.update_auto_check_summary),
                onClick = onOpenUpdate,
            )
            SettingsEntry(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.settings_group_about),
                summary = null,
                onClick = onOpenAbout,
            )
        }
    }
}

/** One destination: gradient icon, title, current value, chevron. */
@Composable
private fun SettingsEntry(
    icon: ImageVector,
    title: String,
    summary: String?,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(
                icon = icon,
                size = 44.dp,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!summary.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
