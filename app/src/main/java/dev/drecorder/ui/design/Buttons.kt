package dev.drecorder.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The app's button ladder: five fixed levels, one per emphasis.
 *
 * Before this file every screen mixed Material's Button / OutlinedButton /
 * TextButton by hand, so the primary action on one screen was the third-level
 * action on the next. The ladder fixes that:
 *
 * | level | component | used for |
 * |---|---|---|
 * | 1 | [PrimaryActionButton] | the one thing the screen is for (record, save, install) |
 * | 2 | [TonalActionButton] | a sibling choice next to the primary |
 * | 3 | [OutlinedActionButton] | low emphasis but still visible |
 * | 4 | [TextActionButton] | cancel / skip / dismiss |
 * | 5 | [DangerActionButton] + [DangerConfirmButton] | delete entry, then its confirmation |
 *
 * Shared geometry: pill corners, 7dp icon gap, 18dp icon inside a button.
 */
private val PillShape = RoundedCornerShape(percent = 50)
private val ButtonPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp)

/** The bright sky-blue → teal gradient that marks anything primary. */
@Composable
fun brandGradientBrush(): Brush {
    val scheme = MaterialTheme.colorScheme
    return Brush.linearGradient(listOf(scheme.primary, scheme.secondary))
}

/** The "this is live" gradient: cherry → red, used only while recording. */
@Composable
fun recordingGradientBrush(): Brush {
    val scheme = MaterialTheme.colorScheme
    return Brush.linearGradient(listOf(scheme.tertiary, scheme.error))
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Level 1. The gradient is painted on a Box and the Button draws transparent on
 * top, so the ripple and the accessibility semantics survive while the fill can
 * be a gradient (Button's containerColor only takes a flat colour).
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(PillShape)
            .background(if (enabled) brandGradientBrush() else Brush.linearGradient(listOf(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.colorScheme.surfaceContainerHighest,
            ))),
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = PillShape,
            contentPadding = ButtonPadding,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            elevation = null,
        ) {
            ButtonContent(text, icon)
        }
    }
}

/** Level 2. Tonal: pale primary fill, deep primary text. */
@Composable
fun TonalActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = PillShape,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        ButtonContent(text, icon)
    }
}

/** Level 3. Outlined, primary-coloured label. */
@Composable
fun OutlinedActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = PillShape,
        contentPadding = ButtonPadding,
        border = BorderStroke(1.4.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        ButtonContent(text, icon)
    }
}

/** Level 4. Text only: cancel, skip, "why is this here". */
@Composable
fun TextActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = PillShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        ButtonContent(text, icon)
    }
}

/** Level 5a. The delete entry point: pale red, not the final confirmation. */
@Composable
fun DangerActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = PillShape,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(text, fontWeight = FontWeight.Bold)
        }
    }
}

/** Level 5b. Solid red, only inside the confirmation dialog. */
@Composable
fun DangerConfirmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = PillShape,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(text, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * A toolbar icon button, optionally with a 10sp caption underneath.
 *
 * The caption exists because a bare icon is not always readable ("what does
 * this circle do?"). It is only for icon-only toolbars: a button that already
 * has a text label must not also get a caption.
 */
@Composable
fun CaptionIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    caption: String? = null,
) {
    val tint = if (danger) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val button: @Composable () -> Unit = {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(42.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(21.dp),
            )
        }
    }
    if (caption == null) {
        button()
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            button()
            Text(
                text = caption,
                color = tint.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}
