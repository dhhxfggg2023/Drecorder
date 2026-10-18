package dev.drecorder.ui.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import dev.drecorder.audio.CaptureState
import dev.drecorder.audio.StopReason
import dev.drecorder.capture.CaptureService
import dev.drecorder.capture.CaptureSession
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val state by CaptureSession.state.collectAsState()
    val level by CaptureSession.level.collectAsState()
    val targetSilent by CaptureSession.targetSilent.collectAsState()
    val handle by CaptureSession.lastHandle.collectAsState()

    // The one mitigation we have against Android 15's "locking the screen ends
    // the projection" rule, since this build does not use Shizuku (DESIGN §3.2).
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (state is CaptureState.Recording) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.record_title)) },
                actions = { TextButton(onClick = onDone) { Text(stringResource(R.string.record_back)) } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val current = state) {
                is CaptureState.Recording -> {
                    val elapsedMs = (now - current.startedAtMillis).coerceAtLeast(0L)
                    Text(
                        text = formatDuration(elapsedMs),
                        style = MaterialTheme.typography.displaySmall,
                    )
                    LinearProgressIndicator(
                        progress = { level },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.record_scope_all),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.record_keep_screen_on),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (targetSilent) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Text(
                                text = stringResource(R.string.record_silent_warning),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { CaptureService.stop(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.record_stop))
                    }
                }

                is CaptureState.Preparing -> {
                    Text(stringResource(R.string.record_title))
                }

                is CaptureState.Finished -> {
                    val fileName = current.outputPath.substringAfterLast(File.separatorChar)
                    val segments = if (current.segmentCount > 1) {
                        stringResource(R.string.record_segments, current.segmentCount)
                    } else {
                        null
                    }
                    val revoked = if (current.reason == StopReason.ProjectionRevoked) {
                        stringResource(R.string.record_revoked)
                    } else {
                        null
                    }
                    ResultCard(
                        text = if (current.everHadSignal) {
                            stringResource(R.string.record_saved, fileName)
                        } else {
                            stringResource(R.string.record_saved_silent, fileName)
                        },
                        detail = listOfNotNull(segments, revoked).joinToString(" · ")
                            .ifEmpty { null },
                        isWarning = !current.everHadSignal || current.reason == StopReason.ProjectionRevoked,
                    )
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.record_back))
                    }
                }

                is CaptureState.Failed -> {
                    ResultCard(text = current.message, detail = null, isWarning = true)
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.record_back))
                    }
                }

                CaptureState.Idle -> {
                    Text(stringResource(R.string.record_idle))
                    val last = handle
                    if (last != null) {
                        ResultCard(
                            text = if (last.everHadSignal) {
                                stringResource(R.string.record_saved, last.file.name)
                            } else {
                                stringResource(R.string.record_saved_silent, last.file.name)
                            },
                            detail = formatDuration(last.durationMs),
                            isWarning = !last.everHadSignal,
                        )
                    }
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.record_back))
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(text: String, detail: String?, isWarning: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isWarning) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            if (detail != null) {
                Text(text = detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1_000
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
