package dev.drecorder.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The colour styles offered in settings.
 *
 * Five, not one: a recorder is a very personal tool and the old single teal
 * scheme made "start recording" look identical to every other utility app. Each
 * style keeps the same neutrals (background, surface ladder, outline, error) so
 * layout contrast and accessibility do not change when the user picks another
 * one — only the accents move.
 */
enum class ColorStyle {
    Sky,
    Mint,
    Cherry,
    Sunset,
    Grape,
}

/**
 * The three accent families of one style.
 *
 * Everything else — surfaces, outlines, error colours — is shared between
 * styles by [neutralLight]/[neutralDark], so a new style is twelve colours and
 * cannot break the contrast of the five surface levels the lists sit on.
 */
private data class Accents(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
)

private fun accents(style: ColorStyle, dark: Boolean): Accents = when (style) {
    ColorStyle.Sky -> if (dark) {
        Accents(
            primary = Color(0xFF74C6FF), onPrimary = Color(0xFF003354),
            primaryContainer = Color(0xFF0D4E77), onPrimaryContainer = Color(0xFFC8E9FF),
            secondary = Color(0xFF5FDADA), onSecondary = Color(0xFF003A38),
            secondaryContainer = Color(0xFF005151), onSecondaryContainer = Color(0xFFB7F3F0),
            tertiary = Color(0xFFFF94B6), onTertiary = Color(0xFF5E0032),
            tertiaryContainer = Color(0xFF8B2450), onTertiaryContainer = Color(0xFFFFD9E3),
        )
    } else {
        Accents(
            primary = Color(0xFF0089E0), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFD3ECFF), onPrimaryContainer = Color(0xFF003550),
            secondary = Color(0xFF00A6A6), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFB9F3F0), onSecondaryContainer = Color(0xFF00332F),
            tertiary = Color(0xFFFF4F81), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFFD9E3), onTertiaryContainer = Color(0xFF3F0015),
        )
    }

    ColorStyle.Mint -> if (dark) {
        Accents(
            primary = Color(0xFF6FE0B8), onPrimary = Color(0xFF00382A),
            primaryContainer = Color(0xFF0B5540), onPrimaryContainer = Color(0xFFC8F4E4),
            secondary = Color(0xFF93CBF5), onSecondary = Color(0xFF002B44),
            secondaryContainer = Color(0xFF17475F), onSecondaryContainer = Color(0xFFD6E9FA),
            tertiary = Color(0xFFF0BE72), onTertiary = Color(0xFF3D2200),
            tertiaryContainer = Color(0xFF6B4200), onTertiaryContainer = Color(0xFFFFE7C6),
        )
    } else {
        Accents(
            primary = Color(0xFF0E9F74), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFC8F4E4), onPrimaryContainer = Color(0xFF00382A),
            secondary = Color(0xFF2E8FD6), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFD6E9FA), onSecondaryContainer = Color(0xFF08283D),
            tertiary = Color(0xFFC97A16), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFFE7C6), onTertiaryContainer = Color(0xFF3D2200),
        )
    }

    ColorStyle.Cherry -> if (dark) {
        Accents(
            primary = Color(0xFFFFA5BF), onPrimary = Color(0xFF47001F),
            primaryContainer = Color(0xFF7D2348), onPrimaryContainer = Color(0xFFFFD9E4),
            secondary = Color(0xFFC7A8F5), onSecondary = Color(0xFF2C0F52),
            secondaryContainer = Color(0xFF44306E), onSecondaryContainer = Color(0xFFE7DBFB),
            tertiary = Color(0xFF7FD0E8), onTertiary = Color(0xFF00202A),
            tertiaryContainer = Color(0xFF0C4B5C), onTertiaryContainer = Color(0xFFCDEDF5),
        )
    } else {
        Accents(
            primary = Color(0xFFE2426F), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFD9E4), onPrimaryContainer = Color(0xFF40001A),
            secondary = Color(0xFF8E63D6), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFE7DBFB), onSecondaryContainer = Color(0xFF2B1152),
            tertiary = Color(0xFF1786A6), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFCDEDF5), onTertiaryContainer = Color(0xFF00202A),
        )
    }

    ColorStyle.Sunset -> if (dark) {
        Accents(
            primary = Color(0xFFFFA583), onPrimary = Color(0xFF551F00),
            primaryContainer = Color(0xFF7C3213), onPrimaryContainer = Color(0xFFFFE2D2),
            secondary = Color(0xFFECC168), onSecondary = Color(0xFF3A2A00),
            secondaryContainer = Color(0xFF564112), onSecondaryContainer = Color(0xFFFCE9BD),
            tertiary = Color(0xFFAEB7FF), onTertiary = Color(0xFF20265C),
            tertiaryContainer = Color(0xFF3A4585), onTertiaryContainer = Color(0xFFDFE2FB),
        )
    } else {
        Accents(
            primary = Color(0xFFD9622F), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFE2D2), onPrimaryContainer = Color(0xFF3A1200),
            secondary = Color(0xFFC08A16), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFCE9BD), onSecondaryContainer = Color(0xFF3A2A00),
            tertiary = Color(0xFF6F7BD8), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFDFE2FB), onTertiaryContainer = Color(0xFF1A1F52),
        )
    }

    ColorStyle.Grape -> if (dark) {
        Accents(
            primary = Color(0xFFC0AEFF), onPrimary = Color(0xFF2B0F6B),
            primaryContainer = Color(0xFF45318C), onPrimaryContainer = Color(0xFFE6DEFF),
            secondary = Color(0xFFFBA8D9), onSecondary = Color(0xFF4A0035),
            secondaryContainer = Color(0xFF6E2A58), onSecondaryContainer = Color(0xFFFBD7EF),
            tertiary = Color(0xFF58D9D2), onTertiary = Color(0xFF00201E),
            tertiaryContainer = Color(0xFF00504C), onTertiaryContainer = Color(0xFFC2F2EE),
        )
    } else {
        Accents(
            primary = Color(0xFF6A4FD0), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFE6DEFF), onPrimaryContainer = Color(0xFF21005D),
            secondary = Color(0xFFB14C93), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFBD7EF), onSecondaryContainer = Color(0xFF3D0029),
            tertiary = Color(0xFF0E8C86), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFC2F2EE), onTertiaryContainer = Color(0xFF00201E),
        )
    }
}

private fun neutralLight(a: Accents): ColorScheme = lightColorScheme(
    primary = a.primary,
    onPrimary = a.onPrimary,
    primaryContainer = a.primaryContainer,
    onPrimaryContainer = a.onPrimaryContainer,
    secondary = a.secondary,
    onSecondary = a.onSecondary,
    secondaryContainer = a.secondaryContainer,
    onSecondaryContainer = a.onSecondaryContainer,
    tertiary = a.tertiary,
    onTertiary = a.onTertiary,
    tertiaryContainer = a.tertiaryContainer,
    onTertiaryContainer = a.onTertiaryContainer,
    background = Color(0xFFFBFDFF),
    onBackground = Color(0xFF1B2A38),
    surface = Color(0xFFFBFDFF),
    onSurface = Color(0xFF1B2A38),
    surfaceVariant = Color(0xFFEAF3FA),
    onSurfaceVariant = Color(0xFF546E7E),
    outline = Color(0xFF7FA6BC),
    outlineVariant = Color(0xFFC7E2F2),
    inverseSurface = Color(0xFF2F4A63),
    inverseOnSurface = Color(0xFFF0F7FD),
    inversePrimary = a.primary,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7FBFF),
    surfaceContainer = Color(0xFFF0F8FF),
    surfaceContainerHigh = Color(0xFFE8F3FC),
    surfaceContainerHighest = Color(0xFFE0EDF8),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private fun neutralDark(a: Accents): ColorScheme = darkColorScheme(
    primary = a.primary,
    onPrimary = a.onPrimary,
    primaryContainer = a.primaryContainer,
    onPrimaryContainer = a.onPrimaryContainer,
    secondary = a.secondary,
    onSecondary = a.onSecondary,
    secondaryContainer = a.secondaryContainer,
    onSecondaryContainer = a.onSecondaryContainer,
    tertiary = a.tertiary,
    onTertiary = a.onTertiary,
    tertiaryContainer = a.tertiaryContainer,
    onTertiaryContainer = a.onTertiaryContainer,
    background = Color(0xFF0B1420),
    onBackground = Color(0xFFE2ECF6),
    surface = Color(0xFF0B1420),
    onSurface = Color(0xFFE2ECF6),
    surfaceVariant = Color(0xFF405A6E),
    onSurfaceVariant = Color(0xFFB6CDE0),
    outline = Color(0xFF7E97AA),
    outlineVariant = Color(0xFF41586B),
    inverseSurface = Color(0xFFE2ECF6),
    inverseOnSurface = Color(0xFF2A3A49),
    inversePrimary = a.primary,
    surfaceContainerLowest = Color(0xFF081019),
    surfaceContainerLow = Color(0xFF131E2A),
    surfaceContainer = Color(0xFF172330),
    surfaceContainerHigh = Color(0xFF22303E),
    surfaceContainerHighest = Color(0xFF2D3B4A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** The scheme for one style in one mode. */
fun colorSchemeFor(style: ColorStyle, dark: Boolean): ColorScheme {
    val accents = accents(style, dark)
    return if (dark) neutralDark(accents) else neutralLight(accents)
}

/**
 * The colour a style is recognised by: used for the swatches in settings, so the
 * picker does not need its own copy of the palette.
 */
fun previewColor(style: ColorStyle): Color = accents(style, dark = false).primary
