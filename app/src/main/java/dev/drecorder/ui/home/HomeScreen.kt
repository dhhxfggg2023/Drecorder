package dev.drecorder.ui.home

import android.app.KeyguardManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.drecorder.R
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * One screen for recording: range line, one big button, one way to the library.
 *
 * The separate "recording in progress" screen is gone because it duplicated what this
 * screen already shows: while a session runs, the button turns into the stop control
 * and its label carries the elapsed time. Tapping the button stops; it never navigates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    notice: String?,
    onNoticeShown: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    appsOnly: Boolean,
    appCount: Int,
    recordingSinceMillis: Long?,
    recordingCount: Int,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val deviceHasSecureLock = remember(context) {
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }
    var disclosureVisible by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(notice) {
        val message = notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onNoticeShown()
    }

    LaunchedEffect(recordingSinceMillis) {
        while (recordingSinceMillis != null) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    val recording = recordingSinceMillis != null
    val elapsed = if (recording) {
        (now - recordingSinceMillis).coerceAtLeast(0L)
    } else {
        0L
    }
    val usesApps = appsOnly && appCount > 0
    val rangeLabel = if (usesApps) {
        stringResource(R.string.home_scope_apps, appCount)
    } else {
        stringResource(R.string.home_scope_all)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.home_settings),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.home_scope_label, rangeLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.home_change))
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            DeckButton(
                recording = recording,
                onClick = { if (recording) onStopCapture() else onStartCapture() },
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (recording) {
                Text(
                    text = formatElapsed(elapsed),
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = stringResource(R.string.home_tap_to_stop),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    text = if (usesApps) {
                        stringResource(R.string.home_start_picked, appCount)
                    } else {
                        stringResource(R.string.home_start)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            TextButton(onClick = { disclosureVisible = true }) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.home_why_authorization))
            }

            Spacer(modifier = Modifier.weight(1f))

            OutlinedButton(
                onClick = onOpenLibrary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(52.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.home_files, recordingCount))
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    if (disclosureVisible) {
        AlertDialog(
            onDismissRequest = { disclosureVisible = false },
            title = { Text(stringResource(R.string.disclosure_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.disclosure_body))
                    if (deviceHasSecureLock) {
                        Text(
                            text = stringResource(R.string.disclosure_lock_warning),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { disclosureVisible = false }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }
}

/**
 * The one button: idle, or recording. Colour and shape carry the state, and a tap
 * always does the obvious thing for that state.
 *
 * It sits inside a halo that breathes on its own time. The screen is otherwise
 * almost empty — one button, one line of text — and a flat disc on a near-black
 * background reads as a placeholder; the slow pulse is what makes the button
 * look alive and, while recording, look live. It is the only motion on the
 * screen, so it never competes with the elapsed time.
 */
@Composable
private fun DeckButton(recording: Boolean, onClick: () -> Unit) {
    val container = if (recording) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val content = if (recording) {
        MaterialTheme.colorScheme.onError
    } else {
        MaterialTheme.colorScheme.onPrimary
    }
    val halo by rememberInfiniteTransition(label = "halo").animateFloat(
        initialValue = 0.16f,
        targetValue = 0.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "haloAlpha",
    )
    Box(
        modifier = Modifier
            .size(184.dp)
            .drawBehind {
                val radius = size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(container.copy(alpha = halo), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(container)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (recording) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(content),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(content),
                )
            }
        }
    }
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1_000
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}
