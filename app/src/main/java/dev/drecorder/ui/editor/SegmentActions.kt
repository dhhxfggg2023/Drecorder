package dev.drecorder.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import dev.drecorder.audio.edit.EditSegment
import dev.drecorder.data.formatDuration
import dev.drecorder.ui.design.CaptionIconButton

/**
 * What can be done to the stretch the user picked on the waveform.
 *
 * The stretch is picked by tapping between two cut points, so this row only
 * exists while something is picked — a row of actions that acts on "whatever
 * the playhead happens to be inside" is a row of actions the user cannot aim.
 *
 * All three are about the picked stretch and nothing else: take it out, keep
 * only it, or play it a second time in place. Each one asks first, because none
 * of them can be undone from inside the editor.
 *
 * Three icon buttons with 10sp captions:
 * three pill buttons with Chinese labels overflowed a narrow phone, and an icon
 * alone did not say what it does (the original complaint behind m00376).
 */
@Composable
fun SegmentActionsRow(
    segment: EditSegment?,
    enabled: Boolean,
    repeatEnabled: Boolean,
    onDelete: () -> Unit,
    onKeepOnly: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val picked = segment ?: return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                R.string.editor_segment_picked,
                formatDuration(picked.startMs),
                formatDuration(picked.endMs),
                formatDuration(picked.durationMs),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CaptionIconButton(
                icon = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.editor_segment_delete),
                caption = stringResource(R.string.editor_segment_delete),
                enabled = enabled,
                danger = true,
                onClick = onDelete,
            )
            CaptionIconButton(
                icon = Icons.Filled.ContentCut,
                contentDescription = stringResource(R.string.editor_segment_keep),
                caption = stringResource(R.string.editor_segment_keep),
                enabled = enabled,
                onClick = onKeepOnly,
            )
            CaptionIconButton(
                icon = Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.editor_segment_repeat),
                caption = stringResource(R.string.editor_segment_repeat),
                enabled = enabled && repeatEnabled,
                onClick = onRepeat,
            )
        }
    }
}
