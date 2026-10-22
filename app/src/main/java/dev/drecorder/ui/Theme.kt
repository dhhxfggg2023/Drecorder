package dev.drecorder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One look for every screen.
 *
 * The typography is declared explicitly instead of inheriting Material's defaults:
 * the default scale inherits the device font, and a phone whose system font is a
 * script face then renders a 24sp title that looks like handwriting. Sizes and
 * weights here keep the hierarchy readable whatever the system font is.
 */
private val DrecorderTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 46.sp,
        lineHeight = 52.sp,
        letterSpacing = (-1).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
)

/** Rounder than stock: a dark surface reads as a card only when its corners are soft. */
private val DrecorderShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(36.dp),
)

/**
 * The palette: a dark studio, not a system-themed app.
 *
 * One near-black blue for the background, one step lighter for anything that
 * sits on it, and exactly one accent that means "the live thing" — the record
 * button, the playhead, the track that is on screen. A recording app is mostly
 * an empty screen with one button and a waveform, so the empty screen has to
 * read as deliberate rather than unfinished, and the accent has to be the only
 * saturated colour on it.
 *
 * Deliberately not Material You and deliberately not light: a wallpaper-derived
 * scheme hands the one action a different colour on every phone, and a light
 * background puts a bright rectangle behind a dark waveform (user choice,
 * 2026-10-22).
 */
private val Ink = androidx.compose.ui.graphics.Color(0xFF070A10)
private val InkRaised = androidx.compose.ui.graphics.Color(0xFF10151F)
private val InkLine = androidx.compose.ui.graphics.Color(0xFF1E2635)
private val Accent = androidx.compose.ui.graphics.Color(0xFF4DA3FF)
private val AccentDeep = androidx.compose.ui.graphics.Color(0xFF12263A)
private val Mint = androidx.compose.ui.graphics.Color(0xFF35D6C0)
private val TextHigh = androidx.compose.ui.graphics.Color(0xFFE9F0FA)
private val TextLow = androidx.compose.ui.graphics.Color(0xFF8C9CB4)

private val StudioColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = androidx.compose.ui.graphics.Color(0xFF04121F),
    primaryContainer = AccentDeep,
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFCFE4FF),
    secondary = Mint,
    onSecondary = androidx.compose.ui.graphics.Color(0xFF00201C),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF1B2533),
    onSecondaryContainer = TextHigh,
    tertiary = Mint,
    onTertiary = androidx.compose.ui.graphics.Color(0xFF00201C),
    background = Ink,
    onBackground = TextHigh,
    surface = Ink,
    onSurface = TextHigh,
    surfaceVariant = InkLine,
    onSurfaceVariant = TextLow,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = InkRaised,
    surfaceContainer = InkRaised,
    surfaceContainerHigh = InkLine,
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF232D3D),
    outline = androidx.compose.ui.graphics.Color(0xFF2A3546),
    outlineVariant = InkLine,
    error = androidx.compose.ui.graphics.Color(0xFFFF5A5F),
    onError = androidx.compose.ui.graphics.Color(0xFF2A0507),
    errorContainer = androidx.compose.ui.graphics.Color(0xFF3A1114),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFFFFD9DA),
    inverseSurface = TextHigh,
    inverseOnSurface = Ink,
    scrim = androidx.compose.ui.graphics.Color(0xFF000000),
)

@Composable
fun DrecorderTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    // The app is dark, always. The parameter is kept so a caller can be
    // explicit, but there is one scheme: a "studio" whose colours flip with the
    // system is two designs to keep in step, and the waveform, the playhead and
    // the record button are all tuned against this one (user choice, 2026-10-22).
    @Suppress("UNUSED_PARAMETER")
    val scheme = StudioColorScheme

    MaterialTheme(
        colorScheme = scheme,
        typography = DrecorderTypography,
        shapes = DrecorderShapes,
        content = content,
    )
}
