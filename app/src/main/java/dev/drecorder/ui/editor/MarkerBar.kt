package dev.drecorder.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import dev.drecorder.audio.edit.EditSegment
import dev.drecorder.data.formatDuration

/**
 * The marker bar: the cut points of the recording, and what can be done to
 * them.
 *
 * A marker is at once a bookmark and a cut point (DESIGN §12.4). The bar
 * shows them in order, lets the user jump to one, and offers the two actions
 * that make sense for a cut point: drop a new one, and take the stretch
 * between two of them out.
 *
 * The bar is a **row of chips** rather than a list, so it stays on one line
 * in portrait and scrolls sideways when there are many markers. The chips are
 * big: they are touched with a finger, not read.
 */
@Composable
fun MarkerBar(
    markers: List<Long>,
    durationMs: Long,
    selectedMarkerMs: Long?,
    onSelect: (ms: Long) -> Unit,
    onAddAtPlayhead: () -> Unit,
    onDelete: (ms: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Flag,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.editor_markers, markers.size),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onAddAtPlayhead,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.editor_marker_add))
            }
        }

        if (markers.isEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.editor_markers_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(modifier = Modifier.height(4.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(markers) { marker ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        AssistChip(
                            onClick = { onSelect(marker) },
                            label = {
                                Text(
                                    text = formatDuration(marker),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Flag,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            colors = if (marker == selectedMarkerMs) {
                                AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                )
                            } else {
                                AssistChipDefaults.assistChipColors()
                            },
                            modifier = Modifier.heightIn(min = 44.dp),
                        )
                        // A cut point that cannot be taken back is a mistake
                        // the user has to live with, so every chip carries its
                        // own delete button.
                        IconButton(
                            onClick = { onDelete(marker) },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(
                                    R.string.editor_marker_delete,
                                    formatDuration(marker),
                                ),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The action that removes the stretch between two markers.
 *
 * It is a separate row because it is the destructive one, and because it
 * needs to say what it is about to do: which stretch, and how long it is.
 */
@Composable
fun SegmentDeleteRow(
    segment: EditSegment?,
    enabled: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            onClick = onDelete,
            enabled = enabled && segment != null,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (segment != null) {
                    stringResource(
                        R.string.editor_delete_segment,
                        formatDuration(segment.startMs),
                        formatDuration(segment.endMs),
                        formatDuration(segment.durationMs),
                    )
                } else {
                    stringResource(R.string.editor_delete_segment_none)
                },
            )
        }
    }
}
