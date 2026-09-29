package com.example.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.agent.loop.AgentState
import com.example.agent.storage.entity.ApprovalRequestEntity
import com.example.ui.components.AgentStatusBadge
import com.example.ui.components.ConnectionStatusBadge
import com.example.ui.components.ConsoleCard
import com.example.ui.components.ScreenHeader
import com.example.ui.components.SectionHeader
import com.example.ui.components.TelemetryLine
import com.example.ui.navigation.SettingsSection
import com.example.ui.theme.Sizes
import com.example.ui.theme.Spacing
import com.example.ui.theme.status
import com.example.ui.theme.telemetry
import com.example.wagateway.WaGatewayViewModel

/**
 * Beranda answers three questions in order, which is the whole navigation model of the app:
 *
 *  1. Does anything need me right now? (an approval the agent is blocked on, a channel that
 *     is not linked, a model that has no key) — shown first, with the action inline.
 *  2. What is the agent doing? — one card with its state and live telemetry.
 *  3. What did it just do, and where do I go to talk to it?
 *
 * Every card states its destination in words ("Ubah model", "Tautkan perangkat") instead of
 * being a mystery tap target, and nothing here is decorative.
 */
@Composable
fun HomeScreen(
  viewModel: WaGatewayViewModel,
  onNavigateToChat: () -> Unit,
  onNavigateToGateway: () -> Unit,
  onNavigateToSettings: (SettingsSection) -> Unit,
  onNavigateToTasks: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val agentState by viewModel.agentState.collectAsState()
  val isAutoReply by viewModel.isAgentAutoReply.collectAsState()
  val isConnected by viewModel.isConnected.collectAsState()
  val connectionStatus by viewModel.connectionStatus.collectAsState()
  val modelId by viewModel.agentModelId.collectAsState()
  val apiKey by viewModel.agentApiKey.collectAsState()
  val useEchoFallback by viewModel.useEchoFallback.collectAsState()
  val agentLogs by viewModel.agentLogs.collectAsState()
  val sessions by viewModel.sessions.collectAsState()
  val whitelistMode by viewModel.whitelistMode.collectAsState()
  val pendingApprovals by viewModel.pendingApprovals.collectAsState()
  val subAgentTasks by viewModel.subAgentTasks.collectAsState()
  val scheduledTasks by viewModel.scheduledTasks.collectAsState()

  val activeSubAgents =
    subAgentTasks.count {
      it.status == com.example.agent.storage.entity.AgentTaskEntity.STATUS_RUNNING ||
        it.status == com.example.agent.storage.entity.AgentTaskEntity.STATUS_QUEUED
    }
  val modelConfigured = apiKey.isNotBlank() || useEchoFallback

  Column(
    modifier =
      modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    verticalArrangement = Arrangement.spacedBy(Spacing.md),
  ) {
    ScreenHeader(
      title = "Beranda",
      subtitle = "Status agent dan aksi cepat",
      trailing = { AgentStatusBadge(state = agentState) },
    )

    // ------------------------------------------------------------------ 1. needs you now
    if (pendingApprovals.isNotEmpty()) {
      ApprovalQueueCard(
        requests = pendingApprovals,
        onApprove = { viewModel.approveRequest(it) },
        onReject = { viewModel.rejectRequest(it) },
        onOpenSettings = { onNavigateToSettings(SettingsSection.ACCESS) },
      )
    }

    if (!isConnected) {
      ConsoleCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
          ChannelGlyph()
          Spacer(modifier = Modifier.width(Spacing.md))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "WhatsApp belum tertaut",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
            )
            Text(
              text = "Agent belum bisa menerima atau membalas pesan WhatsApp.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Spacer(modifier = Modifier.height(Spacing.md))
        Button(
          onClick = onNavigateToGateway,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.testTag("home_link_device_button"),
        ) {
          Text("Tautkan perangkat")
        }
      }
    }

    if (!modelConfigured) {
      ConsoleCard(accent = MaterialTheme.status.warning) {
        Text(
          text = "Model belum dikonfigurasi",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
          text = "Isi API key atau aktifkan Echo fallback supaya agent bisa menjawab.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Button(
          onClick = { onNavigateToSettings(SettingsSection.MODEL) },
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.testTag("home_configure_model_button"),
        ) {
          Text("Atur model")
        }
      }
    }

    // ------------------------------------------------------------------ 2. the agent
    ConsoleCard(modifier = Modifier.testTag("home_agent_status_card")) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier =
            Modifier
              .size(36.dp)
              .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Filled.Psychology,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(Sizes.iconMd),
          )
        }
        Spacer(modifier = Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
          Text(text = "Agent", style = MaterialTheme.typography.titleMedium)
          Text(
            text = when (agentState) {
              AgentState.THINKING, AgentState.CALLING_TOOL, AgentState.WAITING_TOOL ->
                "Sedang mengerjakan permintaan terakhir"
              AgentState.WAITING_APPROVAL -> "Berhenti: menunggu konfirmasi Anda"
              AgentState.FAILED -> "Percobaan terakhir gagal"
              else -> if (isAutoReply) "Siap membalas pesan masuk" else "Siaga — balasan otomatis mati"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Spacer(modifier = Modifier.height(Spacing.md))
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Spacer(modifier = Modifier.height(Spacing.md))

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Balas otomatis pesan WhatsApp",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
          )
          Text(
            text = "Agent menjawab pesan WhatsApp tanpa Anda membuka aplikasi",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = isAutoReply,
          onCheckedChange = { viewModel.toggleAgentAutoReply() },
          colors =
            SwitchDefaults.colors(
              checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
              checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
          modifier = Modifier.testTag("home_auto_reply_switch"),
        )
      }

      Spacer(modifier = Modifier.height(Spacing.md))
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Spacer(modifier = Modifier.height(Spacing.md))

      TelemetryLine(label = "Model", value = modelId.ifBlank { "gpt-4o-mini" })
      Spacer(modifier = Modifier.height(Spacing.sm))
      TelemetryLine(
        label = "Provider",
        value =
          when {
            apiKey.isNotBlank() -> "openai-compatible"
            useEchoFallback -> "echo-fallback"
            else -> "belum diatur"
          },
        tone = if (modelConfigured) null else MaterialTheme.status.warning,
      )
      Spacer(modifier = Modifier.height(Spacing.sm))
      TelemetryLine(label = "Sesi tersimpan", value = sessions.size.toString())
      Spacer(modifier = Modifier.height(Spacing.sm))
      TelemetryLine(label = "Sub-agent aktif", value = activeSubAgents.toString())
      Spacer(modifier = Modifier.height(Spacing.sm))
      TelemetryLine(label = "Task terjadwal", value = scheduledTasks.size.toString())
      Spacer(modifier = Modifier.height(Spacing.sm))
      TelemetryLine(
        label = "Antrean konfirmasi",
        value = pendingApprovals.size.toString(),
        tone = if (pendingApprovals.isEmpty()) null else MaterialTheme.status.warning,
      )
      Spacer(modifier = Modifier.height(Spacing.sm))
      TelemetryLine(
        label = "Whitelist nomor",
        value = if (whitelistMode) "aktif" else "nonaktif",
      )

      Spacer(modifier = Modifier.height(Spacing.sm))
      Row {
        TextButton(onClick = { onNavigateToSettings(SettingsSection.MODEL) }) {
          Text("Ubah model", style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = { onNavigateToSettings(SettingsSection.ACCESS) }) {
          Text("Keamanan & akses", style = MaterialTheme.typography.labelMedium)
        }
      }
    }

    // ------------------------------------------------------------------ 3. the channel
    ConsoleCard(
      onClick = onNavigateToGateway,
      modifier = Modifier.testTag("home_whatsapp_card"),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        ChannelGlyph()
        Spacer(modifier = Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "WhatsApp Gateway",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text =
              if (isConnected) {
                "Akun tertaut — pesan masuk diproses agent"
              } else {
                "Belum tertaut — ketuk untuk menautkan perangkat"
              },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        ConnectionStatusBadge(isConnected = isConnected, statusText = connectionStatus)
        Spacer(modifier = Modifier.width(Spacing.xs))
        Icon(
          imageVector = Icons.Filled.ChevronRight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(Sizes.iconMd),
        )
      }
    }

    // ------------------------------------------------------------------ 4. what just happened
    SectionHeader(
      title = "Aktivitas terakhir",
      icon = Icons.Outlined.History,
      actionLabel = "Buka chat",
      onActionClick = onNavigateToChat,
    )

    val recentLogs = agentLogs.take(3)
    ConsoleCard {
      if (recentLogs.isEmpty()) {
        Text(
          text = "Belum ada aktivitas",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
          text = "Pesan masuk dan balasan agent akan tampil di sini.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        recentLogs.forEachIndexed { index, entry ->
          if (index > 0) Spacer(modifier = Modifier.height(Spacing.sm))
          Text(
            text = entry,
            style = MaterialTheme.telemetry.block,
            color = MaterialTheme.colorScheme.onSurface,
          )
        }
        Spacer(modifier = Modifier.height(Spacing.md))
        TextButton(onClick = onNavigateToTasks) {
          Text("Lihat riwayat lengkap", style = MaterialTheme.typography.labelMedium)
        }
      }
    }

    Spacer(modifier = Modifier.height(Spacing.sm))
  }
}

/**
 * The one card that always sits at the top when it exists: a destructive tool call is parked
 * and the agent cannot continue until a human answers. It used to be a passive counter that
 * dropped the user at the top of the 1200-line settings page.
 */
@Composable
private fun ApprovalQueueCard(
  requests: List<ApprovalRequestEntity>,
  onApprove: (String) -> Unit,
  onReject: (String) -> Unit,
  onOpenSettings: () -> Unit,
) {
  ConsoleCard(
    accent = MaterialTheme.status.warning,
    modifier = Modifier.testTag("home_approval_card"),
  ) {
    Text(
      text = if (requests.size == 1) "1 tool menunggu persetujuan" else "${requests.size} tool menunggu persetujuan",
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(Spacing.xs))
    Text(
      text = "Agent berhenti di langkah ini sampai Anda memutuskan.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    requests.take(3).forEach { request ->
      Spacer(modifier = Modifier.height(Spacing.md))
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Spacer(modifier = Modifier.height(Spacing.md))
      Text(
        text = request.toolName,
        style = MaterialTheme.telemetry.value,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (request.arguments.isNotBlank()) {
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
          text = request.arguments,
          style = MaterialTheme.telemetry.label,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 3,
        )
      }
      Spacer(modifier = Modifier.height(Spacing.sm))
      Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Button(onClick = { onApprove(request.id) }, shape = MaterialTheme.shapes.small) {
          Text("Setujui")
        }
        OutlinedButton(onClick = { onReject(request.id) }, shape = MaterialTheme.shapes.small) {
          Text("Tolak")
        }
      }
    }

    if (requests.size > 3) {
      Spacer(modifier = Modifier.height(Spacing.sm))
      Text(
        text = "…dan ${requests.size - 3} permintaan lain",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    Spacer(modifier = Modifier.height(Spacing.sm))
    TextButton(onClick = onOpenSettings) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Outlined.Shield,
          contentDescription = null,
          modifier = Modifier.size(Sizes.iconSm),
        )
        Spacer(modifier = Modifier.width(Spacing.xs))
        Text("Atur di Keamanan & Akses", style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}

/** The WhatsApp channel glyph, in the channel's own colour from the theme. */
@Composable
private fun ChannelGlyph() {
  Box(
    modifier =
      Modifier
        .size(36.dp)
        .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.Filled.Phone,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onTertiaryContainer,
      modifier = Modifier.size(18.dp),
    )
  }
}

