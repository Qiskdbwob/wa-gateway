package com.example.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.agent.loop.AgentState
import com.example.agent.storage.entity.AgentTaskEntity
import com.example.ui.components.ConsoleCard
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.ScreenHeader
import com.example.ui.components.StatusBadge
import com.example.ui.components.TelemetryLine
import com.example.ui.theme.StatusTone
import com.example.ui.theme.Spacing
import com.example.ui.theme.status
import com.example.ui.theme.telemetry
import com.example.wagateway.WaGatewayViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TaskFilter(val label: String) {
  ALL("Semua"),
  RUNNING("Berjalan"),
  WAITING("Menunggu"),
  SCHEDULED("Terjadwal"),
  DONE("Selesai"),
  FAILED("Gagal"),
}

data class AgentTaskRecord(
  val id: String,
  val title: String,
  val prompt: String,
  val agentName: String = "Personal AI Agent",
  val modelId: String,
  val tools: List<String> = emptyList(),
  val status: TaskFilter,
  val timeline: String,
  val result: String? = null,
  val error: String? = null,
  /** Set when this row is a Room scheduled task, so the detail sheet can control it. */
  val scheduledId: String? = null,
  val scheduledEnabled: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
  viewModel: WaGatewayViewModel,
  modifier: Modifier = Modifier,
) {
  val agentState by viewModel.agentState.collectAsState()
  val agentLastError by viewModel.agentLastError.collectAsState()
  val agentLogs by viewModel.agentLogs.collectAsState()
  val modelId by viewModel.agentModelId.collectAsState()
  val sessions by viewModel.sessions.collectAsState()
  val subAgentTasks by viewModel.subAgentTasks.collectAsState()
  val scheduledTasks by viewModel.scheduledTasks.collectAsState()

  var selectedFilter by remember { mutableStateOf(TaskFilter.ALL) }
  var selectedTask by remember { mutableStateOf<AgentTaskRecord?>(null) }
  val sheetState = rememberModalBottomSheetState()

  val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

  // Task list is derived ONLY from state that really exists in the app: the live AgentState,
  // the persisted Room sessions, the background subagent runs, the scheduled tasks, and the
  // last real error. No placeholder/dummy tasks are invented here.
  val tasks =
    remember(agentState, agentLogs, sessions, agentLastError, modelId, subAgentTasks, scheduledTasks) {
      val list = mutableListOf<AgentTaskRecord>()

      // 1. Live execution: the agent is actively working on a message right now.
      when (agentState) {
        AgentState.THINKING ->
          list.add(
            AgentTaskRecord(
              id = "active-task",
              title = "Memproses pesan",
              prompt = "Memanggil model provider untuk menyusun balasan",
              modelId = modelId.ifBlank { "gpt-4o-mini" },
              status = TaskFilter.RUNNING,
              timeline = timeFormat.format(Date()),
            ),
          )

        AgentState.RETRYING, AgentState.FALLBACK ->
          list.add(
            AgentTaskRecord(
              id = "active-task",
              title = if (agentState == AgentState.RETRYING) "Mencoba ulang model" else "Beralih ke model fallback",
              prompt = "Pemulihan otomatis setelah kegagalan provider",
              modelId = modelId.ifBlank { "gpt-4o-mini" },
              status = TaskFilter.RUNNING,
              timeline = timeFormat.format(Date()),
            ),
          )

        AgentState.WAITING_APPROVAL, AgentState.WAITING_TOOL ->
          list.add(
            AgentTaskRecord(
              id = "waiting-task",
              title = "Menunggu persetujuan",
              prompt = "Menunggu keputusan pengguna sebelum melanjutkan",
              modelId = modelId.ifBlank { "gpt-4o-mini" },
              status = TaskFilter.WAITING,
              timeline = timeFormat.format(Date()),
            ),
          )

        else -> Unit
      }

      // 2. Real history: every persisted conversation with at least one message.
      sessions.forEach { session ->
        if (session.messageCount > 0) {
          list.add(
            AgentTaskRecord(
              id = "sess-${session.sessionId}",
              title = "Percakapan: ${session.conversationId}",
              prompt = session.lastMessagePreview?.ifBlank { "(tanpa pesan)" } ?: "(tanpa pesan)",
              modelId = modelId.ifBlank { "gpt-4o-mini" },
              status = TaskFilter.DONE,
              timeline = timeFormat.format(Date(session.updatedAt)),
            ),
          )
        }
      }

      // 3. Real background subagent runs started by the `delegate_task` tool. The main agent
      // never waits for these: they appear here while they run, then carry their result/error.
      subAgentTasks.forEach { sub ->
        val status =
          when (sub.status) {
            AgentTaskEntity.STATUS_QUEUED, AgentTaskEntity.STATUS_RUNNING -> TaskFilter.RUNNING
            AgentTaskEntity.STATUS_COMPLETED -> TaskFilter.DONE
            AgentTaskEntity.STATUS_FAILED, AgentTaskEntity.STATUS_CANCELLED -> TaskFilter.FAILED
            else -> null
          }
        list.add(
          AgentTaskRecord(
            id = "sub-${sub.id}",
            title = "Sub-agent: ${sub.name}",
            prompt = sub.task,
            agentName = sub.agentName,
            modelId = sub.modelId.ifBlank { "(model utama)" },
            status = status ?: TaskFilter.WAITING,
            timeline =
              timeFormat.format(Date(sub.completedAt ?: sub.startedAt ?: sub.createdAt)),
            result = sub.result,
            error = sub.error,
          ),
        )
      }

      // 4. Real scheduled (cron) tasks from Room, with their last outcome. These carry the
      // scheduled id so the detail sheet can run/enable/delete them — the actions used to be
      // duplicated in a separate control block above this list, so every scheduled task was
      // rendered twice under the Terjadwal filter.
      scheduledTasks.forEach { scheduled ->
        list.add(
          AgentTaskRecord(
            id = "sched-${scheduled.id}",
            title = "Terjadwal: ${scheduled.name}",
            prompt = if (scheduled.enabled) scheduled.prompt else "(nonaktif) ${scheduled.prompt}",
            modelId = modelId.ifBlank { "(model utama)" },
            status = TaskFilter.SCHEDULED,
            timeline =
              scheduled.nextRunAt?.let { "Berikutnya " + timeFormat.format(Date(it)) }
                ?: "Belum dijadwalkan",
            result = scheduled.lastResult,
            error = scheduled.lastError,
            scheduledId = scheduled.id,
            scheduledEnabled = scheduled.enabled,
          ),
        )
      }

      // 5. Real failure recorded by the Agent Loop.
      if (agentLastError != null) {
        list.add(
          AgentTaskRecord(
            id = "last-error-task",
            title = "Eksekusi gagal",
            prompt = "Permintaan inferensi ke model provider",
            modelId = modelId.ifBlank { "gpt-4o-mini" },
            status = TaskFilter.FAILED,
            timeline = timeFormat.format(Date()),
            error = agentLastError,
          ),
        )
      }

      list
    }

  val filteredTasks = tasks.filter { selectedFilter == TaskFilter.ALL || it.status == selectedFilter }
  val counts = remember(tasks) { TaskFilter.entries.associateWith { filter -> tasks.count { it.status == filter } } }

  Column(modifier = modifier.fillMaxSize()) {
    ScreenHeader(
      title = "Tugas",
      subtitle = "Yang sedang berjalan, terjadwal, dan sudah dikerjakan",
      modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
    )

    // Filters carry their own count, so an empty tab is never a surprise tap away.
    LazyRow(
      contentPadding = PaddingValues(horizontal = Spacing.lg),
      horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      items(TaskFilter.entries) { filter ->
        FilterChip(
          selected = selectedFilter == filter,
          onClick = { selectedFilter = filter },
          label = {
            Text(
              text = "${filter.label} (${if (filter == TaskFilter.ALL) tasks.size else counts[filter] ?: 0})",
              style = MaterialTheme.typography.labelMedium,
            )
          },
        )
      }
    }

    Spacer(modifier = Modifier.height(Spacing.sm))

    if (filteredTasks.isEmpty()) {
      Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val (emptyTitle, emptySubtitle) =
          when (selectedFilter) {
            TaskFilter.ALL ->
              "Belum ada tugas" to
                "Tugas yang sedang berjalan, jadwal, dan riwayat percakapan akan muncul di sini."
            TaskFilter.RUNNING ->
              "Tidak ada tugas berjalan" to "Tugas muncul saat agent sedang memproses pesan."
            TaskFilter.WAITING ->
              "Tidak ada tugas menunggu" to "Permintaan persetujuan akan muncul di sini."
            TaskFilter.SCHEDULED ->
              "Belum ada tugas terjadwal" to
                "Buat jadwal lewat chat, misalnya: \"ingatkan saya tiap jam untuk cek email\"."
            TaskFilter.DONE ->
              "Belum ada percakapan selesai" to "Riwayat percakapan tampil di sini setelah agent membalas."
            TaskFilter.FAILED ->
              "Tidak ada kegagalan" to "Error saat memproses pesan akan tercatat di sini."
          }
        EmptyStateCard(
          icon = Icons.Outlined.Checklist,
          title = emptyTitle,
          subtitle = emptySubtitle,
          modifier = Modifier,
        )
      }
    } else {
      LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
      ) {
        items(filteredTasks, key = { it.id }) { task ->
          TaskCard(task = task, onClick = { selectedTask = task })
        }
      }
    }
  }

  val openTask = selectedTask
  if (openTask != null) {
    ModalBottomSheet(
      onDismissRequest = { selectedTask = null },
      sheetState = sheetState,
    ) {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Detail tugas",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
          )
          IconButton(onClick = { selectedTask = null }) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Tutup")
          }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = openTask.title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
          )
          Spacer(modifier = Modifier.width(Spacing.sm))
          TaskStatusBadge(status = openTask.status)
        }

        Column {
          Text(
            text = "Prompt / instruksi",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
          ) {
            Text(
              text = openTask.prompt,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(Spacing.md),
            )
          }
        }

        TelemetryLine(label = "Agent", value = openTask.agentName)
        TelemetryLine(label = "Model", value = openTask.modelId)
        TelemetryLine(label = "Waktu", value = openTask.timeline)

        if (openTask.tools.isNotEmpty()) {
          Column {
            Text(
              text = "Tool terlibat",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
              horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
              modifier = Modifier.padding(top = Spacing.xs),
            ) {
              openTask.tools.forEach { toolName ->
                Surface(
                  shape = MaterialTheme.shapes.extraSmall,
                  color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                  Text(
                    text = toolName,
                    style = MaterialTheme.telemetry.label,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                  )
                }
              }
            }
          }
        }

        if (openTask.result != null) {
          ResultBlock(label = "Hasil", body = openTask.result, tone = MaterialTheme.status.success)
        }

        if (openTask.error != null) {
          ResultBlock(label = "Pesan error", body = openTask.error, tone = MaterialTheme.status.danger)
        }

        // Scheduled tasks are the only rows with actions that change real state.
        val scheduledId = openTask.scheduledId
        if (scheduledId != null) {
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          Text(
            text = "Kontrol jadwal",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            TextButton(onClick = { viewModel.runScheduledTaskNow(scheduledId) }) {
              Text("Jalankan sekarang", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(
              onClick = {
                viewModel.setScheduledTaskEnabled(scheduledId, !openTask.scheduledEnabled)
              },
            ) {
              Text(
                if (openTask.scheduledEnabled) "Nonaktifkan" else "Aktifkan",
                style = MaterialTheme.typography.labelMedium,
              )
            }
          }
          OutlinedButton(
            onClick = {
              viewModel.deleteScheduledTask(scheduledId)
              selectedTask = null
            },
            shape = MaterialTheme.shapes.small,
          ) {
            Text(
              "Hapus jadwal",
              color = MaterialTheme.colorScheme.error,
            )
          }
        }

        Spacer(modifier = Modifier.height(Spacing.lg))
      }
    }
  }
}

@Composable
private fun ResultBlock(label: String, body: String, tone: StatusTone) {
  Column {
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = tone.ink)
    Surface(
      shape = MaterialTheme.shapes.small,
      color = tone.container,
      modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
    ) {
      Text(
        text = body,
        style = MaterialTheme.typography.bodySmall,
        color = tone.onContainer,
        modifier = Modifier.padding(Spacing.md),
      )
    }
  }
}

@Composable
fun TaskCard(
  task: AgentTaskRecord,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Failed and waiting rows are tinted, because those are the two the user may have to act on.
  val accent =
    when (task.status) {
      TaskFilter.FAILED -> MaterialTheme.status.danger
      TaskFilter.WAITING -> MaterialTheme.status.warning
      else -> null
    }

  ConsoleCard(
    onClick = onClick,
    accent = accent,
    modifier = modifier.testTag("task_item_${task.id}"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = task.title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(1f),
      )
      Spacer(modifier = Modifier.width(Spacing.sm))
      TaskStatusBadge(status = task.status)
    }

    Spacer(modifier = Modifier.height(Spacing.xs))

    Text(
      text = task.prompt,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 2,
    )

    Spacer(modifier = Modifier.height(Spacing.sm))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.AccessTime,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(Spacing.xs))
        Text(
          text = task.timeline,
          style = MaterialTheme.telemetry.label,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Text(
        text = task.modelId,
        style = MaterialTheme.telemetry.label,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
      )
    }
  }
}

@Composable
fun taskStatusTone(status: TaskFilter): StatusTone =
  when (status) {
    TaskFilter.ALL -> MaterialTheme.status.neutral
    TaskFilter.RUNNING -> MaterialTheme.status.info
    TaskFilter.WAITING -> MaterialTheme.status.warning
    TaskFilter.SCHEDULED -> MaterialTheme.status.neutral
    TaskFilter.DONE -> MaterialTheme.status.success
    TaskFilter.FAILED -> MaterialTheme.status.danger
  }

@Composable
fun taskStatusIcon(status: TaskFilter): ImageVector =
  when (status) {
    TaskFilter.ALL -> Icons.Outlined.Checklist
    TaskFilter.RUNNING -> Icons.Default.PlayArrow
    TaskFilter.WAITING -> Icons.Default.HourglassTop
    TaskFilter.SCHEDULED -> Icons.Default.Schedule
    TaskFilter.DONE -> Icons.Default.CheckCircle
    TaskFilter.FAILED -> Icons.Default.Error
  }

@Composable
fun TaskStatusBadge(status: TaskFilter) {
  StatusBadge(
    label = status.label,
    tone = taskStatusTone(status),
    icon = taskStatusIcon(status),
  )
}

