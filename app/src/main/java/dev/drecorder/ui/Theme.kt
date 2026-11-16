package dev.drecorder.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One look for every screen: a bright accent family on a calm blue-tinted
 * neutral, a bold type scale and a much rounder shape ladder than Material's
 * defaults. The accent family is chosen in settings ([ColorStyle]); everything
 * else stays fixed so contrast and layout do not move under the user.
 *
 * The typography is declared explicitly instead of inheriting Material's
 * defaults: the default scale inherits the device font, and a phone whose system
 * font is a script face then renders a 24sp title that looks like handwriting.
 * Sizes and weights here keep the hierarchy readable whatever the system font
 * is; only the family is pinned to SansSerif.
 */
private val DrecorderTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
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
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
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
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

/**
 * The shape ladder: pill buttons (50%) come from the components, and these five
 * steps keep every card and sheet in the same family (12 / 16 / 24 / 32 / 42).
 */
private val DrecorderShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(42.dp),
)

@Composable
fun DrecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorStyle: ColorStyle = ColorStyle.Sky,
    content: @Composable () -> Unit,
) {
    // Still deliberately not dynamic colour: the app has exactly one primary
    // action, and a wallpaper-derived scheme can hand it a low-contrast, muddy
    // pair. The five fixed styles keep "start recording" recognisable
    // (user choice, 2026-10-18; styles added 2026-11).
    val colorScheme = colorSchemeFor(colorStyle, darkTheme)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DrecorderTypography,
        shapes = DrecorderShapes,
        content = content,
    )
}
