package com.example.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.agent.loop.AgentState
import com.example.ui.components.EmptyStateCard
import com.example.ui.theme.AgentEmerald
import com.example.wagateway.WaGatewayViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TaskFilter(val label: String) {
    ALL("Semua"),
    RUNNING("Running"),
    WAITING("Waiting"),
    SCHEDULED("Scheduled"),
    COMPLETED("Completed"),
    FAILED("Failed")
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
    val error: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    viewModel: WaGatewayViewModel,
    modifier: Modifier = Modifier
) {
    val agentState by viewModel.agentState.collectAsState()
    val agentLastError by viewModel.agentLastError.collectAsState()
    val agentLogs by viewModel.agentLogs.collectAsState()
    val modelId by viewModel.agentModelId.collectAsState()
    val sessions by viewModel.sessions.collectAsState()

    var selectedFilter by remember { mutableStateOf(TaskFilter.ALL) }
    var selectedTask by remember { mutableStateOf<AgentTaskRecord?>(null) }
    val sheetState = rememberModalBottomSheetState()

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // Task list is derived ONLY from state that really exists in the app:
    // the live AgentState, the persisted Room sessions and the last real error.
    // No placeholder/dummy tasks are invented here.
    val tasks = remember(agentState, agentLogs, sessions, agentLastError, modelId) {
        val list = mutableListOf<AgentTaskRecord>()

        // 1. Live execution: the agent is actively working on a message right now.
        when (agentState) {
            AgentState.THINKING -> list.add(
                AgentTaskRecord(
                    id = "active-task",
                    title = "Memproses pesan",
                    prompt = "Memanggil model provider untuk menyusun balasan",
                    modelId = modelId.ifBlank { "gpt-4o-mini" },
                    status = TaskFilter.RUNNING,
                    timeline = timeFormat.format(Date())
                )
            )

            AgentState.RETRYING, AgentState.FALLBACK -> list.add(
                AgentTaskRecord(
                    id = "active-task",
                    title = if (agentState == AgentState.RETRYING) "Mencoba ulang model" else "Beralih ke model fallback",
                    prompt = "Pemulihan otomatis setelah kegagalan provider",
                    modelId = modelId.ifBlank { "gpt-4o-mini" },
                    status = TaskFilter.RUNNING,
                    timeline = timeFormat.format(Date())
                )
            )

            AgentState.WAITING_APPROVAL, AgentState.WAITING_TOOL -> list.add(
                AgentTaskRecord(
                    id = "waiting-task",
                    title = "Menunggu persetujuan",
                    prompt = "Menunggu keputusan pengguna sebelum melanjutkan",
                    modelId = modelId.ifBlank { "gpt-4o-mini" },
                    status = TaskFilter.WAITING,
                    timeline = timeFormat.format(Date())
                )
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
                        status = TaskFilter.COMPLETED,
                        timeline = timeFormat.format(Date(session.updatedAt))
                    )
                )
            }
        }

        // 3. Real failure recorded by the Agent Loop.
        if (agentLastError != null) {
            list.add(
                AgentTaskRecord(
                    id = "last-error-task",
                    title = "Eksekusi gagal",
                    prompt = "Permintaan inferensi ke model provider",
                    modelId = modelId.ifBlank { "gpt-4o-mini" },
                    status = TaskFilter.FAILED,
                    timeline = timeFormat.format(Date()),
                    error = agentLastError
                )
            )
        }

        list
    }

    val filteredTasks = tasks.filter {
        selectedFilter == TaskFilter.ALL || it.status == selectedFilter
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp)
    ) {
        // Header
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "Pekerjaan Agent",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Pantau status eksekusi tugas dan riwayat aktivitas",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Filter Chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(TaskFilter.values()) { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tasks List
        if (filteredTasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                val (emptyTitle, emptySubtitle) = when (selectedFilter) {
                    TaskFilter.ALL ->
                        "Belum ada task aktif." to "Tugas yang sedang berjalan atau riwayat percakapan akan muncul di sini."
                    TaskFilter.RUNNING ->
                        "Tidak ada task berjalan." to "Task muncul saat agent sedang memproses pesan."
                    TaskFilter.WAITING ->
                        "Tidak ada task menunggu." to "Permintaan persetujuan akan muncul di sini."
                    TaskFilter.SCHEDULED ->
                        "Belum ada task terjadwal." to "Penjadwalan task belum tersedia pada versi ini."
                    TaskFilter.COMPLETED ->
                        "Belum ada percakapan selesai." to "Riwayat percakapan akan tampil di sini setelah agent membalas."
                    TaskFilter.FAILED ->
                        "Tidak ada kegagalan." to "Error saat memproses pesan akan tercatat di sini."
                }
                EmptyStateCard(
                    icon = Icons.Outlined.Checklist,
                    title = emptyTitle,
                    subtitle = emptySubtitle
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredTasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onClick = { selectedTask = task }
                    )
                }
            }
        }
    }

    // Detail Bottom Sheet
    if (selectedTask != null) {
        val task = selectedTask!!
        ModalBottomSheet(
            onDismissRequest = { selectedTask = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Detail Task",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { selectedTask = null }) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Tutup")
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                // Status & Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    TaskStatusBadge(status = task.status)
                }

                // Prompt
                Column {
                    Text(
                        text = "Prompt / Instruksi",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Text(
                            text = task.prompt,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                // Metadata: Agent & Model
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Agent",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = task.agentName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Column {
                        Text(
                            text = "Model",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = task.modelId,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Column {
                        Text(
                            text = "Waktu",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = task.timeline,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Tools used
                if (task.tools.isNotEmpty()) {
                    Column {
                        Text(
                            text = "Tools Terlibat",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            task.tools.forEach { toolName ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        text = toolName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Result or Error
                if (task.result != null) {
                    Column {
                        Text(
                            text = "Hasil (Result)",
                            style = MaterialTheme.typography.labelMedium,
                            color = AgentEmerald
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AgentEmerald.copy(alpha = 0.1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        ) {
                            Text(
                                text = task.result,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }

                if (task.error != null) {
                    Column {
                        Text(
                            text = "Pesan Error",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFEF4444)
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFEF4444).copy(alpha = 0.1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                        ) {
                            Text(
                                text = task.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFEF4444),
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun TaskCard(
    task: AgentTaskRecord,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("task_item_${task.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TaskStatusBadge(status = task.status)
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = task.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = task.timeline,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                Text(
                    text = task.modelId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun TaskStatusBadge(status: TaskFilter) {
    val (label, color, bgColor) = when (status) {
        TaskFilter.ALL -> Triple("Semua", MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.surfaceVariant)
        TaskFilter.RUNNING -> Triple("Running", Color(0xFF3B82F6), Color(0xFF3B82F6).copy(alpha = 0.15f))
        TaskFilter.WAITING -> Triple("Waiting", Color(0xFFF59E0B), Color(0xFFF59E0B).copy(alpha = 0.15f))
        TaskFilter.SCHEDULED -> Triple("Scheduled", Color(0xFF8B5CF6), Color(0xFF8B5CF6).copy(alpha = 0.15f))
        TaskFilter.COMPLETED -> Triple("Completed", AgentEmerald, AgentEmerald.copy(alpha = 0.15f))
        TaskFilter.FAILED -> Triple("Failed", Color(0xFFEF4444), Color(0xFFEF4444).copy(alpha = 0.15f))
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}
