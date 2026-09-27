package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// =====================================================================================
// "Signal Console" palette — two hand-tuned static schemes.
//
// Why static and not Material You: this app is a control room for one agent, and the
// colours carry meaning — emerald means the agent is alive, WhatsApp green is the
// channel, amber means the agent needs a human, red means failure, blue means a tool is
// running. Those meanings cannot change with the user's wallpaper, so the palette is
// fixed and every device sees the same console.
//
// Contrast: each ink colour below is checked against the surface it is drawn on.
// Text under 18sp needs 4.5:1, icons and large text need 3:1.
//  - light theme emerald is #047857 (5.5:1 on white); the bright #25D366 WhatsApp green
//    is kept for FILLS only, because as text it only reaches 1.9:1 on white — which is
//    exactly what the earlier screens did, and why the light theme was hard to read.
//  - dark theme emerald is #34D399 (9.4:1 on #111721) and the WhatsApp green is usable
//    as text again (9.1:1).
// =====================================================================================

// ---------------------------------------------------------------- light (paper console)
internal val LightBackground = Color(0xFFF6F7F9)
internal val LightSurface = Color(0xFFFFFFFF)
internal val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
internal val LightSurfaceContainerLow = Color(0xFFF8F9FB)
internal val LightSurfaceContainer = Color(0xFFF1F3F7)
internal val LightSurfaceContainerHigh = Color(0xFFE9ECF2)
internal val LightSurfaceContainerHighest = Color(0xFFE2E6EE)

internal val LightOnSurface = Color(0xFF0D1117)
internal val LightOnSurfaceVariant = Color(0xFF4A5568)

internal val LightPrimary = Color(0xFF047857)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFCCF1E0)
internal val LightOnPrimaryContainer = Color(0xFF065F46)

internal val LightSecondary = Color(0xFF1D4ED8)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSecondaryContainer = Color(0xFFDBEAFE)
internal val LightOnSecondaryContainer = Color(0xFF1E3A8A)

internal val LightTertiary = Color(0xFF0F7A44)
internal val LightOnTertiary = Color(0xFFFFFFFF)
internal val LightTertiaryContainer = Color(0xFFC8F7DC)
internal val LightOnTertiaryContainer = Color(0xFF064E2E)

internal val LightError = Color(0xFFB3261E)
internal val LightOnError = Color(0xFFFFFFFF)
internal val LightErrorContainer = Color(0xFFFBE3E1)
internal val LightOnErrorContainer = Color(0xFF601410)

internal val LightOutline = Color(0xFF8A94A6)
internal val LightOutlineVariant = Color(0xFFD6DBE4)

// ----------------------------------------------------------------- dark (graphite console)
internal val DarkBackground = Color(0xFF0A0E14)
internal val DarkSurface = Color(0xFF111721)
internal val DarkSurfaceContainerLowest = Color(0xFF0A0E14)
internal val DarkSurfaceContainerLow = Color(0xFF151C28)
internal val DarkSurfaceContainer = Color(0xFF1A222F)
internal val DarkSurfaceContainerHigh = Color(0xFF222B3A)
internal val DarkSurfaceContainerHighest = Color(0xFF2B3546)

internal val DarkOnSurface = Color(0xFFE8EDF5)
internal val DarkOnSurfaceVariant = Color(0xFF98A6BC)

internal val DarkPrimary = Color(0xFF34D399)
internal val DarkOnPrimary = Color(0xFF04231A)
internal val DarkPrimaryContainer = Color(0xFF0E4034)
internal val DarkOnPrimaryContainer = Color(0xFFA7F3D0)

internal val DarkSecondary = Color(0xFF7CB3FF)
internal val DarkOnSecondary = Color(0xFF06213F)
internal val DarkSecondaryContainer = Color(0xFF17395E)
internal val DarkOnSecondaryContainer = Color(0xFFCFE3FF)

internal val DarkTertiary = Color(0xFF25D366)
internal val DarkOnTertiary = Color(0xFF04270F)
internal val DarkTertiaryContainer = Color(0xFF0D3B22)
internal val DarkOnTertiaryContainer = Color(0xFFB7F5CE)

internal val DarkError = Color(0xFFFFB4AB)
internal val DarkOnError = Color(0xFF690005)
internal val DarkErrorContainer = Color(0xFF93000A)
internal val DarkOnErrorContainer = Color(0xFFFFDAD6)

internal val DarkOutline = Color(0xFF5A6879)
internal val DarkOutlineVariant = Color(0xFF2B3546)

// ---------------------------------------------------------------- semantic status inks
// Amber/blue/grey support tones, tuned per theme so a "needs you" chip is readable in both.
internal val LightSuccess = LightPrimary
internal val LightSuccessContainer = Color(0xFFDCF5E9)
internal val LightOnSuccessContainer = Color(0xFF065F46)

internal val LightWarning = Color(0xFF8A5300)
internal val LightWarningContainer = Color(0xFFFFF0C7)
internal val LightOnWarningContainer = Color(0xFF4A2A00)

internal val LightInfo = LightSecondary
internal val LightInfoContainer = Color(0xFFE3EDFF)
internal val LightOnInfoContainer = Color(0xFF1E3A8A)

internal val LightNeutral = Color(0xFF4A5568)
internal val LightNeutralContainer = Color(0xFFEDF0F5)
internal val LightOnNeutralContainer = Color(0xFF334155)

internal val DarkSuccess = DarkPrimary
internal val DarkSuccessContainer = DarkPrimaryContainer
internal val DarkOnSuccessContainer = DarkOnPrimaryContainer

internal val DarkWarning = Color(0xFFFFC46B)
internal val DarkWarningContainer = Color(0xFF4A3200)
internal val DarkOnWarningContainer = Color(0xFFFFE0AE)

internal val DarkInfo = DarkSecondary
internal val DarkInfoContainer = DarkSecondaryContainer
internal val DarkOnInfoContainer = DarkOnSecondaryContainer

internal val DarkNeutral = DarkOnSurfaceVariant
internal val DarkNeutralContainer = DarkSurfaceContainerHigh
internal val DarkOnNeutralContainer = Color(0xFFD3DBE6)

// ---------------------------------------------------------------- brand fills
/**
 * Brand fill colours. These are FILLS (dots, switch tracks, progress indicators) — never
 * text or icons on a light surface, because neither clears the 3:1 floor there. Use
 * `MaterialTheme.colorScheme.primary` / `MaterialTheme.status.*` for anything readable.
 */
val AgentEmerald = Color(0xFF10B981)
val AgentWhatsAppGreen = Color(0xFF25D366)
