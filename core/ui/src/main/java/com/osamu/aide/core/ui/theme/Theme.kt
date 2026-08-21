package com.osamu.aide.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val AideDarkColors = darkColorScheme(
    primary = AideBlue,
    onPrimary = AideOnAccent,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    inversePrimary = AideBlueDeep,

    secondary = AideGreen,
    onSecondary = AideOnAccent,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,

    tertiary = AideAmber,
    onTertiary = AideOnAccent,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,

    error = AideRed,
    onError = AideOnAccent,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,

    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = AideBlue,
    surfaceContainerLowest = DarkSurfaceLowest,
    surfaceContainerLow = DarkSurfaceLow,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = DarkSurfaceHighest,
    surfaceBright = DarkSurfaceHighest,
    surfaceDim = DarkSurfaceLowest,

    primaryFixed = LightPrimaryContainer,
    primaryFixedDim = AideBlue,
    onPrimaryFixed = LightOnPrimaryContainer,
    onPrimaryFixedVariant = AideBlueDeep,

    secondaryFixed = LightSecondaryContainer,
    secondaryFixedDim = AideGreen,
    onSecondaryFixed = LightOnSecondaryContainer,
    onSecondaryFixedVariant = AideGreenDeep,

    tertiaryFixed = LightTertiaryContainer,
    tertiaryFixedDim = AideAmber,
    onTertiaryFixed = LightOnTertiaryContainer,
    onTertiaryFixedVariant = AideAmberDeep,


    inverseSurface = DarkOnSurface,
    inverseOnSurface = DarkSurface,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = Color.Black,
)

internal val AideLightColors = lightColorScheme(
    primary = AideBlueDeep,
    onPrimary = Color.White,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    inversePrimary = AideBlue,

    secondary = AideGreenDeep,
    onSecondary = Color.White,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,

    tertiary = AideAmberDeep,
    onTertiary = Color.White,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,

    error = AideRedDeep,
    onError = Color.White,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,

    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = AideBlueDeep,
    surfaceContainerLowest = LightSurfaceLowest,
    surfaceContainerLow = LightSurfaceLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceHigh,
    surfaceContainerHighest = LightSurfaceHighest,
    surfaceBright = LightBackground,
    surfaceDim = LightSurfaceHighest,

    primaryFixed = LightPrimaryContainer,
    primaryFixedDim = AideBlue,
    onPrimaryFixed = LightOnPrimaryContainer,
    onPrimaryFixedVariant = AideBlueDeep,

    secondaryFixed = LightSecondaryContainer,
    secondaryFixedDim = AideGreen,
    onSecondaryFixed = LightOnSecondaryContainer,
    onSecondaryFixedVariant = AideGreenDeep,

    tertiaryFixed = LightTertiaryContainer,
    tertiaryFixedDim = AideAmber,
    onTertiaryFixed = LightOnTertiaryContainer,
    onTertiaryFixedVariant = AideAmberDeep,


    inverseSurface = LightOnSurface,
    inverseOnSurface = LightSurfaceLow,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = Color.Black,
)

/**
 * Dynamic color is deliberately not used: syntax highlighting and diagnostic
 * severity colors must stay stable and high-contrast regardless of the user's
 * wallpaper, or red-for-error stops reading as an error.
 */
@Composable
fun AideTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AideDarkColors else AideLightColors,
        typography = AideTypography,
        content = content,
    )
}
