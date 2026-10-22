package dev.drecorder.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import dev.drecorder.audio.edit.EditSegment
import dev.drecorder.data.formatDuration

/**
 * The cut points of the recording, and what can be done to them.
 *
 * A marker is at once a bookmark and a cut point (DESIGN §12.4). It is drawn on
 * the waveform itself — that is where a cut point belongs, on the audio it cuts
 * — so this bar deliberately does **not** list them as a row of chips: the user
 * asked for the two views not to compete, and a sideways list of times is hard
 * to aim at and tells you nothing the waveform does not already show.
 *
 * What is left is the two actions: drop a cut point where the playhead stands,
 * and take out the stretch between two of them. The count is shown so the user
 * knows the taps landed.
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
            // The picked cut point can be removed from here: the flag on the
            // waveform is small, and a cut point that cannot be taken back is
            // a mistake the user has to live with.
            val picked = selectedMarkerMs
            if (picked != null && markers.contains(picked)) {
                IconButton(
                    onClick = { onDelete(picked) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(
                            R.string.editor_marker_delete,
                            formatDuration(picked),
                        ),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
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

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (markers.isEmpty()) {
                stringResource(R.string.editor_markers_empty)
            } else {
                // The selected one is named, because it is what the delete
                // action above is about to remove.
                val picked = selectedMarkerMs
                if (picked != null && markers.contains(picked)) {
                    stringResource(R.string.editor_marker_picked, formatDuration(picked))
                } else {
                    stringResource(R.string.editor_markers_hint)
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What can be done to the stretch the user picked on the waveform.
 *
 * The stretch is picked by tapping between two cut points, so this row only
 * exists while something is picked — a row of actions that acts on "whatever
 * the playhead happens to be inside" is a row of actions the user cannot aim.
 *
 * All three are about the picked stretch and nothing else: take it out, keep
 * only it, or play it a second time in place. The destructive one says which
 * stretch it means, because a delete that cannot be described is a delete the
 * user has to guess at.
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

/**
 * The tracks that make up a merge, in the order they will be played.
 *
 * A merge is not a set: "A then B" and "B then A" are different recordings, and
 * the library can only say *which* ones were picked. The order therefore has to
 * be visible and changeable here, before the export (DESIGN §12.2).
 *
 * It is drawn as a stack of tracks rather than a list of names, because that is
 * what it is: every row is one stretch of audio that will be laid end to end.
 * Each row carries its own length, so what the user is ordering is the shape of
 * the result and not just a list of titles. A row can be moved and taken out,
 * and the row the timeline is showing is the one drawn in the accent colour.
 *
 * Only shown for a merge; a single recording has nothing to order.
 */
@Composable
fun MergeOrderRow(
    names: List<String>,
    lengths: List<String>,
    selectedIndex: Int,
    onMove: (from: Int, delta: Int) -> Unit,
    onSelect: (index: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.editor_merge_order),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${names.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        names.forEachIndexed { index, name ->
            val selected = index == selectedIndex
            Surface(
                // The track the waveform is showing is the one the user is
                // cutting on, so it is the one that carries the accent colour.
                color = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // The position is the thing the user is changing, so it is
                    // written out: "1", "2", "3" is what makes the order
                    // readable at a glance.
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                MaterialTheme.shapes.extraSmall,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    TextButton(
                        onClick = { onSelect(index) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (selected) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                },
                            )
                            Text(
                                text = lengths.getOrElse(index) { "" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    IconButton(
                        onClick = { onMove(index, -1) },
                        enabled = index > 0,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.editor_move_earlier),
                        )
                    }
                    IconButton(
                        onClick = { onMove(index, 1) },
                        enabled = index < names.lastIndex,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.editor_move_later),
                        )
                    }
                    // Taking a track out of a merge is not destructive in the
                    // way a cut is: the recording is still in the library and
                    // "插入音频" puts it back. That is why it needs no dialog.
                    if (names.size > 1) {
                        IconButton(
                            onClick = { onRemove(index) },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(
                                    R.string.editor_remove_track,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
