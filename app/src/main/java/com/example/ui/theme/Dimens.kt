package com.example.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing rhythm. Every gap in the app is one of these values, so a screen edited in
 * isolation cannot drift away from the others — the previous screens used ad hoc 2/4/6/10/
 * 12/14/16/20/24 dp gaps and eight different corner radii, which is what made them look
 * assembled rather than designed.
 */
object Spacing {
  val xxs = 2.dp
  val xs = 4.dp
  val sm = 8.dp
  val md = 12.dp
  val lg = 16.dp
  val xl = 24.dp
  val xxl = 32.dp
}

object Sizes {
  /** Android's accessibility floor for anything tappable. */
  val touchTarget = 48.dp

  val iconSm = 16.dp
  val iconMd = 20.dp
  val iconLg = 24.dp

  /** Keeps a phone-width reading column on tablet/desktop windows instead of stretching. */
  val contentMaxWidth = 640.dp

  /** Material 3 compact/medium breakpoint: at this width the shell switches to a rail. */
  val railBreakpoint = 600.dp
}

object Motion {
  const val fast = 140
  const val standard = 220
}
