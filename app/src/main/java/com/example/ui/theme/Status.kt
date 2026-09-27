package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * One semantic status, as three colours: the [ink] for icons and text, the [container] it
 * sits on, and the [onContainer] for text inside that container. Material's [androidx.compose.material3.ColorScheme]
 * has slots for primary/secondary/error but none for "a tool is running" or "the agent
 * needs you", which is why the screens ended up with literal hex values; these tones
 * replace them and stay correct in both themes.
 */
data class StatusTone(val ink: Color, val container: Color, val onContainer: Color)

data class StatusPalette(
  val success: StatusTone,
  val warning: StatusTone,
  val info: StatusTone,
  val neutral: StatusTone,
  val danger: StatusTone,
)

internal val LightStatusPalette =
  StatusPalette(
    success = StatusTone(LightSuccess, LightSuccessContainer, LightOnSuccessContainer),
    warning = StatusTone(LightWarning, LightWarningContainer, LightOnWarningContainer),
    info = StatusTone(LightInfo, LightInfoContainer, LightOnInfoContainer),
    neutral = StatusTone(LightNeutral, LightNeutralContainer, LightOnNeutralContainer),
    danger = StatusTone(LightError, LightErrorContainer, LightOnErrorContainer),
  )

internal val DarkStatusPalette =
  StatusPalette(
    success = StatusTone(DarkSuccess, DarkSuccessContainer, DarkOnSuccessContainer),
    warning = StatusTone(DarkWarning, DarkWarningContainer, DarkOnWarningContainer),
    info = StatusTone(DarkInfo, DarkInfoContainer, DarkOnInfoContainer),
    neutral = StatusTone(DarkNeutral, DarkNeutralContainer, DarkOnNeutralContainer),
    danger = StatusTone(DarkError, DarkErrorContainer, DarkOnErrorContainer),
  )

internal val LocalStatusPalette = staticCompositionLocalOf { LightStatusPalette }

internal val LocalTelemetry = staticCompositionLocalOf { Telemetry }

/** Semantic status colours for the current theme: `MaterialTheme.status.warning.ink`. */
val MaterialTheme.status: StatusPalette
  @Composable
  @ReadOnlyComposable
  get() = LocalStatusPalette.current

/** Monospace styles for machine data: `MaterialTheme.telemetry.value`. */
val MaterialTheme.telemetry: TelemetryTypography
  @Composable
  @ReadOnlyComposable
  get() = LocalTelemetry.current
