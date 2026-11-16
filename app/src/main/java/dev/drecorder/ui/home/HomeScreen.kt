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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drecorder.R
import dev.drecorder.data.formatSize
import dev.drecorder.ui.design.IconTile
import dev.drecorder.ui.design.TextActionButton
import dev.drecorder.ui.design.brandGradientBrush
import dev.drecorder.ui.design.recordingGradientBrush
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The recording deck: scope pill, one big gradient disc, one way to the library.
 *
 * Restyled (2026-11): a gradient primary surface instead of a
 * flat disc, a floating card for the library, a caption under every icon and a
 * much bolder title. The behaviour is unchanged — while a session runs the disc
 * turns cherry-red, the label carries the elapsed time, and tapping always
 * stops instead of navigating.
 */
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
     * has stopped working. The halo around the disc breathes with it instead,
     * which is the only place on this screen where motion says something true
     * about the audio.
     */
    level: Float = 0f,
    /**
     * Rough disk cost of the current settings for one minute of audio, in bytes.
     *
     * Shown under the start label so the quality choice can be judged before
     * recording: "320 kbps" means nothing, "~2.3 MB per minute" does.
     */
    sizePerMinuteBytes: Long = 0L,
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = bottomBar,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header: the app name large, one honest line under it. No app bar,
            // so the gradient disc below is the only thing that competes.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 18.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.weight(1f))

            // The recording source is a setting, but it is also the first thing a
            // user needs to see before pressing the button, so it is a pill that
            // states the current scope and is itself the way to change it.
            Surface(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (usesApps) Icons.Outlined.Apps else Icons.Outlined.Public,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = rangeLabel,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            DeckButton(
                recording = recording,
                level = level,
                onClick = { if (recording) onStopCapture() else onStartCapture() },
            )

            Spacer(Modifier.height(20.dp))

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
                    // width every time a digit changes, and at 40sp that reads
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
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.home_tap_to_stop),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (sizePerMinuteBytes > 0L) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.home_size_per_minute,
                        formatSize(sizePerMinuteBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (targetSilent && recording) {
                Spacer(Modifier.height(14.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.record_silent_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // The library is the second thing anyone wants, so it gets a real
            // card with a gradient icon tile rather than a thin outlined row.
            Surface(
                onClick = onOpenLibrary,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(
                        icon = Icons.Outlined.LibraryMusic,
                        size = 44.dp,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        // One line only: the card already says "recordings" in
                        // the count, and a second label under it repeated the
                        // same word on the screenshot (2026-11-16).
                        Text(
                            text = stringResource(R.string.home_files, recordingCount),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            TextActionButton(
                text = stringResource(R.string.home_why_authorization),
                icon = Icons.Filled.Info,
                onClick = { disclosureVisible = true },
            )

            Spacer(Modifier.height(8.dp))
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
                TextActionButton(
                    text = stringResource(R.string.common_confirm),
                    onClick = { disclosureVisible = false },
                )
            },
        )
    }
}

/**
 * The one control: idle, or recording. Gradient and shape carry the state, and a
 * tap always does the obvious thing for that state.
 *
 * It sits inside a halo that breathes on its own time. The screen is otherwise
 * almost empty — one disc, one line of text — and a flat circle on a near-white
 * background reads as a placeholder; the slow pulse is what makes the control
 * look alive and, while recording, look live. It is the only motion on the
 * screen, so it never competes with the elapsed time.
 */
@Composable
private fun DeckButton(recording: Boolean, level: Float, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val startLabel = stringResource(R.string.home_start)
    val stopLabel = stringResource(R.string.record_stop)
    val brush = if (recording) recordingGradientBrush() else brandGradientBrush()
    val glyphColor = if (recording) scheme.onError else scheme.onPrimary
    val haloColor = if (recording) scheme.tertiary else scheme.primary

    val idleHalo by rememberInfiniteTransition(label = "halo").animateFloat(
        initialValue = 0.16f,
        targetValue = 0.40f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "haloAlpha",
    )
    val halo = if (recording) (0.12f + level * 0.45f).coerceIn(0.12f, 0.58f) else idleHalo
    val trackColor = scheme.outlineVariant.copy(alpha = 0.35f)

    Box(
        modifier = Modifier
            .size(210.dp)
            .drawBehind {
                val radius = size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(haloColor.copy(alpha = halo), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                )
                // A level ring instead of a bar: it hugs the control that causes
                // the sound, and it is read at a glance from across a room.
                // Only while recording — idle, an empty ring is just decoration.
                if (recording) {
                    val stroke = 7.dp.toPx()
                    val inset = stroke / 2f + 5.dp.toPx()
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
                        color = scheme.primary,
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
                .size(136.dp)
                .shadow(
                    elevation = 18.dp,
                    shape = CircleShape,
                    ambientColor = haloColor.copy(alpha = 0.35f),
                    spotColor = haloColor.copy(alpha = 0.45f),
                )
                .clip(CircleShape)
                .background(brush)
                .clickable(onClick = onClick)
                // The disc is the only control on the screen and it has no text
                // inside it, so the label has to come from semantics or a
                // screen-reader user gets an unlabelled button.
                .semantics {
                    role = Role.Button
                    contentDescription = if (recording) stopLabel else startLabel
                },
            contentAlignment = Alignment.Center,
        ) {
            if (recording) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(glyphColor),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(glyphColor),
                )
            }
        }
    }
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1_000
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}
