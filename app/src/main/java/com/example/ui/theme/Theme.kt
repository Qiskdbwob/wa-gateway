package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = AgentEmerald,
    onPrimary = Color.Black,
    primaryContainer = AgentEmeraldDark,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF60A5FA),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1E3A8A),
    onSecondaryContainer = Color(0xFFDBEAFE),
    tertiary = AgentWhatsAppGreen,
    background = AgentDarkBackground,
    onBackground = Color(0xFFF1F5F9),
    surface = AgentDarkSurface,
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = AgentDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = AgentDarkOutline
  )

private val LightColorScheme =
  lightColorScheme(
    primary = AgentEmeraldDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF065F46),
    secondary = Color(0xFF2563EB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDBEAFE),
    onSecondaryContainer = Color(0xFF1E40AF),
    tertiary = AgentWhatsAppGreen,
    background = AgentLightBackground,
    onBackground = Color(0xFF0F172A),
    surface = AgentLightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = AgentLightSurfaceVariant,
    onSurfaceVariant = Color(0xFF475569),
    outline = AgentLightOutline
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is intentionally OFF by default: it would replace the branded
  // emerald / WhatsApp green palette with the user's wallpaper colors, which makes
  // the app look inconsistent across devices.
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
