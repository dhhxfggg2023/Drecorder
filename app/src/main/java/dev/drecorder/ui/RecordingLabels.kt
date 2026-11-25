package dev.drecorder.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.drecorder.R
import dev.drecorder.data.Recording
import dev.drecorder.data.RecordingDay
import dev.drecorder.data.clockLabel
import dev.drecorder.data.dayLabel

/**
 * "今天 10:17" / "昨天 21:03" / "10月16日 09:12" / "2025年12月31日".
 *
 * The generated file name is an implementation detail; a list shows when the
 * recording happened instead (`drecorder-20261018-101722-p01.m4a` is unreadable on a
 * 6 cm wide screen). A name the user typed wins over the time — see [recordingTitle].
 */
@Composable
fun recordingWhenLabel(recording: Recording, nowMillis: Long = System.currentTimeMillis()): String {
    val day = recording.dayLabel(nowMillis)
    val clock = recording.clockLabel()
    return when (day) {
        RecordingDay.Today -> stringResource(R.string.record_when_today, clock)
        RecordingDay.Yesterday -> stringResource(R.string.record_when_yesterday, clock)
        is RecordingDay.SameYear ->
            stringResource(R.string.record_when_same_year, day.month, day.day, clock)

        is RecordingDay.Older ->
            stringResource(R.string.record_when_older, day.year, day.month, day.day)
    }
}

/**
 * The line a recording leads with: the name the user gave it, or when it happened
 * while the file still carries the engine's generated name.
 *
 * Renaming only moves the file, so a list that never reads [Recording.customName]
 * makes the rename invisible — the feature the menu offers would have no effect the
 * user can see (issue #1). The time does not disappear: rows with a name keep it on
 * the line below.
 */
@Composable
fun recordingTitle(recording: Recording, nowMillis: Long = System.currentTimeMillis()): String =
    recording.customName ?: recordingWhenLabel(recording, nowMillis)
