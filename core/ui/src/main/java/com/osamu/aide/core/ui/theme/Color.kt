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

val AideBlue = Color(0xFF38BDF8)
val AideBlueDeep = Color(0xFF0284C7)
val AideGreen = Color(0xFF34D399)
val AideGreenDeep = Color(0xFF059669)
val AideAmber = Color(0xFFFBBF24)
val AideAmberDeep = Color(0xFFD97706)
val AideRed = Color(0xFFFB7185)
val AideRedDeep = Color(0xFFE11D48)
val AidePurple = Color(0xFFA78BFA)
val AidePurpleDeep = Color(0xFF7C3AED)

// -- Dark scheme ------------------------------------------------------------

val DarkBackground = Color(0xFF0B0E14)
val DarkSurface = Color(0xFF111722)
val DarkSurfaceVariant = Color(0xFF1A2333)
val DarkSurfaceLowest = Color(0xFF07090E)
val DarkSurfaceLow = Color(0xFF0E131C)
val DarkSurfaceHigh = Color(0xFF202B3F)
val DarkSurfaceHighest = Color(0xFF29374F)
val DarkOutline = Color(0xFF2D3B52)
val DarkOutlineVariant = Color(0xFF1E2838)
val DarkOnSurface = Color(0xFFF1F5F9)
val DarkOnSurfaceVariant = Color(0xFF94A3B8)

val DarkPrimaryContainer = Color(0xFF0C3559)
val DarkOnPrimaryContainer = Color(0xFFBAE6FD)
val DarkSecondaryContainer = Color(0xFF064E3B)
val DarkOnSecondaryContainer = Color(0xFFA7F3D0)
val DarkTertiaryContainer = Color(0xFF451A03)
val DarkOnTertiaryContainer = Color(0xFFFDE68A)
val DarkErrorContainer = Color(0xFF4C0519)
val DarkOnErrorContainer = Color(0xFFFFCCD5)

/** Near-black used as the "on" color for the bright accents in the dark scheme. */
val AideOnAccent = DarkBackground

// -- Light scheme -----------------------------------------------------------

val LightBackground = Color(0xFFF8FAFC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF1F5F9)
val LightSurfaceLowest = Color(0xFFFFFFFF)
val LightSurfaceLow = Color(0xFFF8FAFC)
val LightSurfaceContainer = Color(0xFFF1F5F9)
val LightSurfaceHigh = Color(0xFFE2E8F0)
val LightSurfaceHighest = Color(0xFFCBD5E1)
val LightOutline = Color(0xFFCBD5E1)
val LightOutlineVariant = Color(0xFFE2E8F0)
val LightOnSurface = Color(0xFF0F172A)
val LightOnSurfaceVariant = Color(0xFF475569)

val LightPrimaryContainer = Color(0xFFE0F2FE)
val LightOnPrimaryContainer = Color(0xFF0369A1)
val LightSecondaryContainer = Color(0xFFD1FAE5)
val LightOnSecondaryContainer = Color(0xFF047857)
val LightTertiaryContainer = Color(0xFFFEF3C7)
val LightOnTertiaryContainer = Color(0xFFB45309)
val LightErrorContainer = Color(0xFFFFE4E6)
val LightOnErrorContainer = Color(0xFFBE123C)
