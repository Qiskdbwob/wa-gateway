package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.agent.loop.AgentState
import com.example.ui.theme.Sizes
import com.example.ui.theme.Spacing
import com.example.ui.theme.StatusTone
import com.example.ui.theme.status
import com.example.ui.theme.telemetry

// =====================================================================================
// Shared UI primitives.
//
// Every screen builds its cards, headers and status chips from this file, so a change to
// the spacing rhythm, the hairline border or a status colour lands everywhere at once
// instead of drifting screen by screen.
//
// Two rules this file exists to enforce:
//   1. a container is only clickable when it actually does something (a Card with an empty
//      onClick lambda is announced by TalkBack as a button that does nothing);
//   2. status is never colour alone — every status chip carries an icon and a label.
// =====================================================================================

/**
 * Status pill: container tint, icon, and a text label. The label and icon are what convey
 * the status; the colour reinforces it and is never the only signal.
 */
@Composable
fun StatusBadge(
  label: String,
  tone: StatusTone,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  mono: Boolean = false,
) {
  Row(
    modifier =
      modifier
        .background(tone.container, CircleShape)
        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
  ) {
    if (icon != null) {
      Icon(imageVector = icon, contentDescription = null, tint = tone.ink, modifier = Modifier.size(14.dp))
    }
    Text(
      text = label,
      style = if (mono) MaterialTheme.telemetry.label else MaterialTheme.typography.labelMedium,
      color = tone.ink,
      maxLines = 1,
    )
  }
}

/**
 * Small coloured dot for dense rows where a full pill would not fit. Always pair it with a
 * text label — the dot alone must never be the only status signal.
 */
@Composable
fun StatusDot(tone: StatusTone, modifier: Modifier = Modifier) {
  Box(modifier = modifier.size(8.dp).background(tone.ink, CircleShape))
}

/** Indonesian label for the agent state machine. */
fun agentStateLabel(state: AgentState): String =
  when (state) {
    AgentState.IDLE -> "Siap"
    AgentState.THINKING -> "Berpikir"
    AgentState.CALLING_TOOL -> "Menjalankan tool"
    AgentState.WAITING_TOOL -> "Menunggu tool"
    AgentState.DELEGATING -> "Delegasi"
    AgentState.WAITING_SUB_AGENT -> "Sub-agent"
    AgentState.REFLECTING -> "Refleksi"
    AgentState.RETRYING -> "Mencoba ulang"
    AgentState.FALLBACK -> "Model cadangan"
    AgentState.WAITING_APPROVAL -> "Butuh konfirmasi"
    AgentState.COMPLETED -> "Selesai"
    AgentState.FAILED -> "Gagal"
  }

/** Theme-aware status tone for the agent state machine. */
@Composable
fun agentStateTone(state: AgentState): StatusTone {
  val status = MaterialTheme.status
  return when (state) {
    AgentState.IDLE, AgentState.COMPLETED -> status.success
    AgentState.THINKING, AgentState.CALLING_TOOL, AgentState.WAITING_TOOL -> status.info
    AgentState.DELEGATING, AgentState.WAITING_SUB_AGENT -> status.info
    AgentState.REFLECTING, AgentState.FALLBACK -> status.neutral
    AgentState.RETRYING, AgentState.WAITING_APPROVAL -> status.warning
    AgentState.FAILED -> status.danger
  }
}

@Composable
fun AgentStatusBadge(state: AgentState, modifier: Modifier = Modifier) {
  val working = state == AgentState.THINKING || state == AgentState.CALLING_TOOL || state == AgentState.WAITING_TOOL
  StatusBadge(
    label = agentStateLabel(state),
    tone = agentStateTone(state),
    modifier = modifier,
    icon = if (working) Icons.Default.HourglassTop else null,
  )
}

/** WhatsApp channel status. Green here is the channel's own colour, so the tertiary roles
 * are the right source — and in the light theme they are the readable green, not #25D366. */
@Composable
fun ConnectionStatusBadge(
  isConnected: Boolean,
  statusText: String,
  modifier: Modifier = Modifier,
) {
  val scheme = MaterialTheme.colorScheme
  if (isConnected) {
    StatusBadge(
      label = "Terhubung",
      tone = StatusTone(scheme.tertiary, scheme.tertiaryContainer, scheme.onTertiaryContainer),
      modifier = modifier,
      icon = Icons.Default.CheckCircle,
    )
  } else {
    StatusBadge(
      label = statusText.ifBlank { "Terputus" },
      tone = MaterialTheme.status.neutral,
      modifier = modifier,
      icon = Icons.Default.CloudOff,
    )
  }
}

/**
 * Screen-level header. Every tab starts with one so the app has a single, predictable
 * anatomy: where you are, what this screen is for, and (optionally) one thing you can do
 * here. Before this, each screen invented its own header and only some had a subtitle.
 */
@Composable
fun ScreenHeader(
  title: String,
  subtitle: String? = null,
  modifier: Modifier = Modifier,
  trailing: @Composable (() -> Unit)? = null,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Top,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(text = title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
      if (subtitle != null) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (trailing != null) {
      Spacer(modifier = Modifier.width(Spacing.sm))
      trailing()
    }
  }
}

/**
 * Section divider inside a screen, with an optional action. The action is a real TextButton
 * (48dp touch target) rather than a shrunken button with a 32dp target.
 */
@Composable
fun SectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  actionLabel: String? = null,
  onActionClick: (() -> Unit)? = null,
) {
  Row(
    modifier = modifier.fillMaxWidth().heightIn(min = Sizes.touchTarget),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
      if (icon != null) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(Spacing.sm))
      }
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }

    if (actionLabel != null && onActionClick != null) {
      TextButton(onClick = onActionClick, contentPadding = PaddingValues(horizontal = Spacing.md)) {
        Text(text = actionLabel, style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}

/**
 * The app's single card primitive. Flat, hairline-bordered and tone-based: no drop shadows,
 * because elevation here is expressed by surface tone. Pass [accent] for a card that is
 * *about* a status (a failed task, an approval request) to tint the whole container.
 */
@Composable
fun ConsoleCard(
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  accent: StatusTone? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val colors = CardDefaults.cardColors(containerColor = accent?.container ?: MaterialTheme.colorScheme.surfaceContainerLow)
  val border =
    BorderStroke(
      width = 1.dp,
      color = accent?.ink?.copy(alpha = 0.35f) ?: MaterialTheme.colorScheme.outlineVariant,
    )
  val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)

  if (onClick != null) {
    Card(
      onClick = onClick,
      modifier = modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.medium,
      colors = colors,
      elevation = elevation,
      border = border,
    ) {
      Column(modifier = Modifier.padding(Spacing.lg), content = content)
    }
  } else {
    Card(
      modifier = modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.medium,
      colors = colors,
      elevation = elevation,
      border = border,
    ) {
      Column(modifier = Modifier.padding(Spacing.lg), content = content)
    }
  }
}

/**
 * One line of machine data: a quiet sans label and a monospace value — the pattern this app
 * uses for model ids, counters, timestamps and connection details.
 */
@Composable
fun TelemetryLine(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  tone: StatusTone? = null,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(end = Spacing.sm),
    )
    Text(
      text = value,
      style = MaterialTheme.telemetry.value,
      color = tone?.ink ?: MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
    )
  }
}

@Composable
fun EmptyStateCard(
  icon: ImageVector,
  title: String,
  subtitle: String,
  modifier: Modifier = Modifier,
  actionLabel: String? = null,
  onActionClick: (() -> Unit)? = null,
) {
  Column(
    modifier = modifier.fillMaxWidth().padding(Spacing.xl),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Box(
      modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(26.dp),
      )
    }
    Spacer(modifier = Modifier.height(Spacing.md))
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(Spacing.xs))
    Text(
      text = subtitle,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    if (actionLabel != null && onActionClick != null) {
      Spacer(modifier = Modifier.height(Spacing.lg))
      Button(onClick = onActionClick, shape = MaterialTheme.shapes.small) { Text(actionLabel) }
    }
  }
}

/**
 * @deprecated Use [ConsoleCard]. Kept only so screens still being migrated compile; this
 * version also fixes the old bug where every card was clickable even with no action.
 */
@Deprecated("Use ConsoleCard", ReplaceWith("ConsoleCard"))
@Composable
fun CompactCard(
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  ConsoleCard(modifier = modifier, onClick = onClick) { content() }
}
