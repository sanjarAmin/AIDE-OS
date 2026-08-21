package com.osamu.aide.core.ui.theme

import androidx.compose.ui.graphics.Color

// AIDE-OS is dark-first: an IDE is stared at for hours, frequently at night, and
// on OLED phone panels a near-black ground is measurably cheaper on battery.
// Values are hand-picked for code legibility rather than derived from a seed.
//
// Both schemes are defined in full. Material3's darkColorScheme()/lightColorScheme()
// default every unnamed role to the baseline purple palette, so a partial
// override leaves purple showing through wherever a component reaches for a
// role you forgot -- a FAB uses primaryContainer, not primary.

// -- Accents ----------------------------------------------------------------

val AideBlue = Color(0xFF6EA8FE)
val AideBlueDeep = Color(0xFF0B62D6)
val AideGreen = Color(0xFF7EE787)
val AideGreenDeep = Color(0xFF1A7F37)
val AideAmber = Color(0xFFFFA657)
val AideAmberDeep = Color(0xFFBC4C00)
val AideRed = Color(0xFFFF7B72)
val AideRedDeep = Color(0xFFCF222E)

// -- Dark scheme ------------------------------------------------------------

val DarkBackground = Color(0xFF0F1115)
val DarkSurface = Color(0xFF151922)
val DarkSurfaceVariant = Color(0xFF1D2430)
val DarkSurfaceLowest = Color(0xFF0B0D12)
val DarkSurfaceLow = Color(0xFF12161E)
val DarkSurfaceHigh = Color(0xFF1B2230)
val DarkSurfaceHighest = Color(0xFF222B3B)
val DarkOutline = Color(0xFF3A4553)
val DarkOutlineVariant = Color(0xFF262F3B)
val DarkOnSurface = Color(0xFFE6EDF3)
val DarkOnSurfaceVariant = Color(0xFF9AA7B4)

val DarkPrimaryContainer = Color(0xFF123A6B)
val DarkOnPrimaryContainer = Color(0xFFCFE0FF)
val DarkSecondaryContainer = Color(0xFF12361A)
val DarkOnSecondaryContainer = Color(0xFFC6F6CB)
val DarkTertiaryContainer = Color(0xFF4A2A0C)
val DarkOnTertiaryContainer = Color(0xFFFFD9B8)
val DarkErrorContainer = Color(0xFF5C1A16)
val DarkOnErrorContainer = Color(0xFFFFD5D1)

/** Near-black used as the "on" color for the bright accents in the dark scheme. */
val AideOnAccent = DarkBackground

// -- Light scheme -----------------------------------------------------------

val LightBackground = Color(0xFFFFFFFF)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEAEEF2)
val LightSurfaceLowest = Color(0xFFFFFFFF)
val LightSurfaceLow = Color(0xFFF6F8FA)
val LightSurfaceContainer = Color(0xFFEFF2F5)
val LightSurfaceHigh = Color(0xFFE8ECF0)
val LightSurfaceHighest = Color(0xFFE1E6EB)
val LightOutline = Color(0xFFD0D7DE)
val LightOutlineVariant = Color(0xFFE4E8EC)
val LightOnSurface = Color(0xFF1F2328)
val LightOnSurfaceVariant = Color(0xFF636C76)

val LightPrimaryContainer = Color(0xFFD6E4FF)
val LightOnPrimaryContainer = Color(0xFF041E49)
val LightSecondaryContainer = Color(0xFFCFF3D6)
val LightOnSecondaryContainer = Color(0xFF052E12)
val LightTertiaryContainer = Color(0xFFFFE0C7)
val LightOnTertiaryContainer = Color(0xFF3D1900)
val LightErrorContainer = Color(0xFFFFDAD8)
val LightOnErrorContainer = Color(0xFF410006)
