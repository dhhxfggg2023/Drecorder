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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    /**
     * True when the captured stream has been silent long enough that the source
     * is probably refusing to be captured (Spotify, Chrome, SoundCloud). The
     * engine has known this since v0.1 — the meter it drives was never shown, so
     * the only warning the user got was a 「无声音」 badge after the fact.
     */
    targetSilent: Boolean = false,
    /** The shared bottom navigation; the host owns it so it does not jump between pages. */
    bottomBar: @Composable () -> Unit = {},
    /**
     * The live input level, 0f..1f, while recording.
     *
     * A recorder that shows nothing moving is indistinguishable from one that
     * has stopped working, and the engine has been meters-deep in this value
     * all along — it never reached a pixel. The halo around the button breathes
     * with it instead, which is the only place on this screen where motion says
     * something true about the audio.
     */
    level: Float = 0f,
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
        pluralStringResource(R.plurals.apps_count, appCount, appCount)
    } else {
        stringResource(R.string.home_scope_all)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            // No gear here any more: settings is a sibling page in the bar below,
            // not a sub-screen buried behind this one.
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = bottomBar,
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )

            // The recording source is a setting, but it is also the first thing a
            // user needs to see before pressing the button, so it is a chip that
            // states the current scope and is itself the way to change it. A
            // sentence plus a separate 「更改」 link made the same information take
            // two controls and a whole row of its own.
            AssistChip(
                onClick = onOpenSettings,
                label = { Text(rangeLabel) },
                leadingIcon = {
                    Icon(
                        imageVector = if (usesApps) Icons.Outlined.Apps else Icons.Outlined.Public,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )

            if (targetSilent && recording) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.record_silent_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            DeckButton(
                recording = recording,
                level = level,
                onClick = { if (recording) onStopCapture() else onStartCapture() },
            )

            Spacer(modifier = Modifier.height(20.dp))

            // The one line that changes with the state of the recorder. Idle it
            // names the action; recording it is the clock, set in the same place
            // the eye already is, so nothing jumps when the session starts.
            Text(
                text = if (recording) {
                    formatElapsed(elapsed)
                } else if (usesApps) {
                    pluralStringResource(R.plurals.apps_count, appCount, appCount)
                } else {
                    stringResource(R.string.home_start)
                },
                style = if (recording) {
                    // Monospaced digits: a proportional clock shifts its own
                    // width every time a digit changes, and at 46sp that reads
                    // as the whole line twitching once a second.
                    MaterialTheme.typography.displaySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.sp,
                    )
                } else {
                    MaterialTheme.typography.titleMedium
                },
                color = if (recording) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (recording) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.home_tap_to_stop),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

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

            // The library is the second thing anyone wants, so it gets a footer
            // of a real height on a raised surface rather than an outlined pill
            // floating above the navigation bar.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenLibrary)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                Icon(
                    imageVector = Icons.Outlined.LibraryMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.home_files, recordingCount),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                }
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
private fun DeckButton(recording: Boolean, level: Float, onClick: () -> Unit) {
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
    // Idle: the halo breathes on its own, slowly, so the disc reads as alive.
    // Recording: it follows the input level, so the pulse is the sound and a
    // silent capture stops pulsing — which is exactly the question the user has
    // in front of a recorder that may be capturing nothing.
    val idleHalo by rememberInfiniteTransition(label = "halo").animateFloat(
        initialValue = 0.16f,
        targetValue = 0.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "haloAlpha",
    )
    val halo = if (recording) (0.10f + level * 0.45f).coerceIn(0.10f, 0.55f) else idleHalo
    val ringColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    Box(
        modifier = Modifier
            .size(196.dp)
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
                // A level ring instead of a bar: it hugs the button that causes
                // the sound, and it is read at a glance from across a room.
                // Only while recording — idle, an empty ring is just decoration.
                if (recording) {
                    val stroke = 6.dp.toPx()
                    val inset = stroke / 2f + 4.dp.toPx()
                    val arcRadius = radius - inset
                    drawArc(
                        color = trackColor,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                        size = Size(arcRadius * 2f, arcRadius * 2f),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * level.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                        size = Size(arcRadius * 2f, arcRadius * 2f),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
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