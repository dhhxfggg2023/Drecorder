package dev.drecorder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
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
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFD4E8E9),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF0E1F20),
    surface = androidx.compose.ui.graphics.Color(0xFFF7FAFA),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE3EAEA),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF3F4949),
    error = androidx.compose.ui.graphics.Color(0xFFBA1A1A),
)

private val FallbackDark = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF80D4DA),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF003739),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF004F53),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF9CF1F6),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF324B4D),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFD4E8E9),
    surface = androidx.compose.ui.graphics.Color(0xFF0E1515),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF3F4949),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFBEC9C9),
)

@Composable
fun DrecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> FallbackDark
        else -> FallbackLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DrecorderTypography,
        shapes = DrecorderShapes,
        content = content,
    )
}
