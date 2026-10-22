package dev.drecorder.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import dev.drecorder.audio.edit.EditSegment
import dev.drecorder.data.formatDuration

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
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SegmentAction(
                icon = Icons.Filled.Delete,
                label = R.string.editor_segment_delete,
                enabled = enabled,
                onClick = onDelete,
            )
            SegmentAction(
                icon = Icons.Filled.ContentCut,
                label = R.string.editor_segment_keep,
                enabled = enabled,
                onClick = onKeepOnly,
            )
            SegmentAction(
                icon = Icons.Filled.ContentCopy,
                label = R.string.editor_segment_repeat,
                enabled = enabled && repeatEnabled,
                onClick = onRepeat,
            )
        }
    }
}

@Composable
private fun SegmentAction(
    icon: ImageVector,
    label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.heightIn(min = 44.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(label),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
