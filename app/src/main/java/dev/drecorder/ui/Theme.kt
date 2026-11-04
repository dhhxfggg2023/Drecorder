package dev.drecorder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme

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

/** Slightly rounder than stock, matching the tonal surfaces used on every screen. */
private val DrecorderShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

/**
 * Dynamic colour on Android 12+, a calm teal fallback elsewhere. The fallback exists
 * because a recording app has exactly one action and a grey-violet default scheme
 * gives it no emphasis.
 */
private val FallbackLight = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF00696E),
    onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF9CF1F6),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF002022),
    secondary = androidx.compose.ui.graphics.Color(0xFF4A6365),
    onSecondary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFD4E8E9),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF0E1F20),
    // A ladder, not a single flat plane: the list rows, the raised controls and
    // the page itself have to be told apart without drawing borders around
    // everything. Material 3 gives the five levels and the rows are placed on
    // them; leaving them unset collapses the whole app onto one white.
    surface = androidx.compose.ui.graphics.Color(0xFFF7FAFA),
    surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF0F5F5),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFFEBF1F1),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFE5ECEC),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFDFE7E7),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE3EAEA),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF3F4949),
    outline = androidx.compose.ui.graphics.Color(0xFF6F7979),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFBEC9C9),
    error = androidx.compose.ui.graphics.Color(0xFFBA1A1A),
    errorContainer = androidx.compose.ui.graphics.Color(0xFFFFDAD6),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFF410002),
)

private val FallbackDark = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF80D4DA),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF003739),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF004F53),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF9CF1F6),
    secondary = androidx.compose.ui.graphics.Color(0xFFB1CCCE),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF1B3436),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF324B4D),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFD4E8E9),
    surface = androidx.compose.ui.graphics.Color(0xFF0E1515),
    surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF090F0F),
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF161D1D),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFF1A2121),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF242B2B),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF2F3535),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF3F4949),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFBEC9C9),
    outline = androidx.compose.ui.graphics.Color(0xFF899393),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF3F4949),
    error = androidx.compose.ui.graphics.Color(0xFFFFB4AB),
    errorContainer = androidx.compose.ui.graphics.Color(0xFF93000A),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFFFFDAD6),
)

@Composable
fun DrecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Deliberately not dynamic colour: the app has exactly one primary action, and a
    // wallpaper-derived scheme can hand it a low-contrast, muddy colour pair. The fixed
    // teal scheme keeps "start recording" recognisable across devices (user choice,
    // 2026-10-18).
    val colorScheme = if (darkTheme) FallbackDark else FallbackLight

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DrecorderTypography,
        shapes = DrecorderShapes,
        content = content,
    )
}
