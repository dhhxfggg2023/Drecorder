package dev.drecorder.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import dev.drecorder.audio.edit.EditHandle
import dev.drecorder.audio.edit.EditRange
import dev.drecorder.audio.edit.EditSelectionMath
import dev.drecorder.data.formatDuration

/**
 * The numeric panel under the waveform.
 *
 * A finger on glass cannot place a cut on an exact millisecond, so the exact
 * moment has to be enterable. The panel shows the two edges of the selection
 * and their length, lets the user type them, and offers a nudge of ten
 * milliseconds — a tenth of a frame at 100 fps, which is the difference
 * between a cut that clicks and one that does not (DESIGN §12.4).
 *
 * Each edge has its own row, so the user always knows which edge a nudge
 * moves: there is no "the edge that happens to be grabbed" to guess about.
 *
 * The typed number is **not** applied while it is being typed. A field that
 * applies every keystroke would turn the first digit of "1500" into a
 * selection of one and a half seconds, and the waveform would jump under the
 * user's finger; the number is applied when the field is finished with — the
 * keyboard's done key, or the focus moving away.
 *
 * The panel is the reason a selection can be made at all on a long recording:
 * the user can jump to a moment, type the exact number, and cut it.
 */
@Composable
fun SelectionPanel(
    selection: TimeSelection?,
    totalMs: Long,
    onSelectionChange: (TimeSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selection == null) return
    val range = EditRange(selection.startMs, selection.endMs)

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        EdgeRow(
            label = stringResource(R.string.editor_selection_start),
            valueMs = selection.startMs,
            onValueChange = { newMs ->
                // The start can never pass the end, and the selection can
                // never become empty.
                val limit = (range.endMs - EditSelectionMath.MIN_SELECTION_MS)
                    .coerceAtLeast(0L)
                onSelectionChange(
                    timeSelectionOf(
                        EditRange(newMs.coerceIn(0L, limit), range.endMs),
                    ),
                )
            },
            onNudge = { deltaMs ->
                onSelectionChange(
                    timeSelectionOf(
                        EditSelectionMath.nudge(
                            range = range,
                            handle = EditHandle.Start,
                            deltaMs = deltaMs,
                            totalMs = totalMs,
                        ),
                    ),
                )
            },
        )

        Spacer(modifier = Modifier.width(4.dp))

        EdgeRow(
            label = stringResource(R.string.editor_selection_end),
            valueMs = selection.endMs,
            onValueChange = { newMs ->
                val limit = (range.startMs + EditSelectionMath.MIN_SELECTION_MS)
                    .coerceAtLeast(0L)
                onSelectionChange(
                    timeSelectionOf(
                        EditRange(
                            range.startMs,
                            newMs.coerceIn(limit, totalMs.coerceAtLeast(0L)),
                        ),
                    ),
                )
            },
            onNudge = { deltaMs ->
                onSelectionChange(
                    timeSelectionOf(
                        EditSelectionMath.nudge(
                            range = range,
                            handle = EditHandle.End,
                            deltaMs = deltaMs,
                            totalMs = totalMs,
                        ),
                    ),
                )
            },
        )

        Spacer(modifier = Modifier.width(4.dp))

        // The length of the selection, editable like the two edges.
        //
        // Which edge grows depends on the room there is: a length that fits
        // after the start grows the end, and one that only fits before the end
        // grows the start. The user asked for a length, not for a direction.
        LengthRow(
            lengthMs = selection.durationMs,
            totalMs = totalMs,
            onLengthChange = { wantedMs ->
                val start = range.startMs
                val total = totalMs.coerceAtLeast(0L)
                val handle = if (start + wantedMs <= total) {
                    EditHandle.End
                } else {
                    EditHandle.Start
                }
                onSelectionChange(
                    timeSelectionOf(
                        EditSelectionMath.setDuration(
                            range = range,
                            handle = handle,
                            durationMs = wantedMs,
                            totalMs = totalMs,
                        ),
                    ),
                )
            },
        )

    }
}

/**
 * One edge of the selection: its exact moment, and the buttons that move it.
 *
 * The text field keeps its own copy of what was typed and hands it over when
 * the field is done, so that a half-typed number never reaches the waveform.
 */
@Composable
private fun EdgeRow(
    label: String,
    valueMs: Long,
    onValueChange: (Long) -> Unit,
    onNudge: (Long) -> Unit,
) {
    var text by remember(valueMs) { mutableStateOf(valueMs.toString()) }
    var focused by remember { mutableStateOf(false) }

    fun commit() {
        val parsed = text.trim().toLongOrNull()
        if (parsed != null && parsed != valueMs) onValueChange(parsed)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { entered ->
                text = entered.filter { it.isDigit() }
            },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .width(140.dp)
                .onFocusChanged { state ->
                    val wasFocused = focused
                    focused = state.isFocused
                    // Losing the focus ends the edit: the number is applied
                    // then, so the user does not have to press the done key.
                    if (wasFocused && !state.isFocused) commit()
                },
        )
        NudgeButton(text = "-${EditSelectionMath.NUDGE_LARGE_MS}") {
            onNudge(-EditSelectionMath.NUDGE_LARGE_MS)
        }
        NudgeButton(text = "-${EditSelectionMath.NUDGE_MS}") {
            onNudge(-EditSelectionMath.NUDGE_MS)
        }
        NudgeButton(text = "+${EditSelectionMath.NUDGE_MS}") {
            onNudge(EditSelectionMath.NUDGE_MS)
        }
        NudgeButton(text = "+${EditSelectionMath.NUDGE_LARGE_MS}") {
            onNudge(EditSelectionMath.NUDGE_LARGE_MS)
        }
    }
}

/**
 * The length of the selection, in the same style as the two edges.
 */
@Composable
private fun LengthRow(
    lengthMs: Long,
    totalMs: Long,
    onLengthChange: (Long) -> Unit,
) {
    var text by remember(lengthMs) { mutableStateOf(lengthMs.toString()) }

    fun commit() {
        val parsed = text.trim().toLongOrNull()
        if (parsed != null && parsed != lengthMs) onLengthChange(parsed)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { entered ->
                text = entered.filter { it.isDigit() }
            },
            label = { Text(stringResource(R.string.editor_selection_duration)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .width(140.dp)
                .onFocusChanged { state ->
                    if (!state.isFocused) commit()
                },
        )
        Text(
            text = stringResource(
                R.string.editor_selection_length,
                formatDuration(lengthMs),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** A small button that moves one edge of the selection. */
@Composable
private fun NudgeButton(
    text: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Text(text = text)
    }
}

/** Wraps a range as the selection the editor holds. */
private fun timeSelectionOf(range: EditRange): TimeSelection =
    TimeSelection(range.startMs, range.endMs)
