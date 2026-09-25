package com.example.ui.memory

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.agent.model.AgentSession
import com.example.agent.storage.entity.MemoryItemEntity
import com.example.ui.components.EmptyStateCard
import com.example.ui.theme.AgentEmerald
import com.example.wagateway.WaGatewayViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One long-term memory row (episodic / knowledge / learning), with optional actions.
 */
@Composable
private fun MemoryItemCard(
    item: MemoryItemEntity,
    onDelete: (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null
) {
    val itemTimeFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("memory_item_${item.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                    text = item.type.lowercase().replaceFirstChar { it.uppercase() } + " • " + item.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    actions?.invoke()
                    if (onDelete != null) {
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Hapus memori",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 6
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = item.source.ifBlank { "manual" } + " • " + itemTimeFormat.format(Date(item.updatedAt)) +
                    if (item.useCount > 0) " • dipakai ${item.useCount}x" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

enum class MemoryCategory(val label: String, val icon: ImageVector) {
    SESSIONS("Sessions", Icons.Default.Chat),
    EPISODIC("Episodic Memory", Icons.Default.History),
    KNOWLEDGE("Knowledge", Icons.Default.Psychology),
    SKILLS("Skills", Icons.Default.AutoAwesome),
    LEARNING("Learning", Icons.Default.Lightbulb)
}

@Composable
fun MemoryScreen(
    viewModel: WaGatewayViewModel,
    onNavigateToChatSession: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sessions by viewModel.sessions.collectAsState()
    val episodicMemories by viewModel.episodicMemories.collectAsState()
    val knowledgeMemories by viewModel.knowledgeMemories.collectAsState()
    val learnings by viewModel.learnings.collectAsState()
    val newMemoryInput by viewModel.newMemoryInput.collectAsState()
    val systemPrompt by viewModel.agentSystemPrompt.collectAsState()
    val modelId by viewModel.agentModelId.collectAsState()
    val isAutoReply by viewModel.isAgentAutoReply.collectAsState()
    val useEchoFallback by viewModel.useEchoFallback.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val apiKey by viewModel.agentApiKey.collectAsState()

    var selectedTab by remember { mutableStateOf(MemoryCategory.SESSIONS) }
    var sessionToDelete by remember { mutableStateOf<AgentSession?>(null) }
    var sessionToClear by remember { mutableStateOf<AgentSession?>(null) }

    val totalMessages = remember(sessions) { sessions.sumOf { it.messageCount } }
    val timeFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 12.dp)
    ) {
        // Header
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "Memory & Persistence",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Manajemen memori persisten di Room SQLite lokal",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Persistence Overview Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(AgentEmerald.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = AgentEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "SQLite Room Database",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Penyimpanan lokal aman tanpa cloud",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${sessions.size} Sesi",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "$totalMessages Pesan",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Categories Scrollable Tab Row
        ScrollableTabRow(
            selectedTabIndex = selectedTab.ordinal,
            edgePadding = 16.dp,
            divider = { Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)) }
        ) {
            MemoryCategory.values().forEach { category ->
                Tab(
                    selected = selectedTab == category,
                    onClick = { selectedTab = category },
                    text = { Text(category.label) },
                    icon = { Icon(category.icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }
        }

        // Tab Content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            when (selectedTab) {
                MemoryCategory.SESSIONS -> {
                    if (sessions.isEmpty()) {
                        EmptyStateCard(
                            icon = Icons.Outlined.Storage,
                            title = "Belum ada percakapan tersimpan",
                            subtitle = "Sesi percakapan WhatsApp dan chat langsung akan otomatis dicatat di sini."
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(sessions, key = { it.sessionId }) { session ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("memory_session_${session.sessionId}"),
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
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(AgentEmerald)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = session.conversationId.ifBlank { "Session ${session.sessionId.take(6)}" },
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1
                                                )
                                            }

                                            Text(
                                                text = "${session.messageCount} pesan",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Text(
                                            text = session.lastMessagePreview?.ifBlank { "(Belum ada pesan)" } ?: "(Belum ada pesan)",
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
                                            Text(
                                                text = "Update: ${timeFormat.format(Date(session.updatedAt))}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )

                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                TextButton(
                                                    onClick = {
                                                        viewModel.selectSession(session.sessionId)
                                                        onNavigateToChatSession(session.sessionId)
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Text("Buka Chat", style = MaterialTheme.typography.labelSmall)
                                                }

                                                IconButton(
                                                    onClick = { sessionToClear = session },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.DeleteSweep,
                                                        contentDescription = "Bersihkan",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }

                                                IconButton(
                                                    onClick = { sessionToDelete = session },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.DeleteOutline,
                                                        contentDescription = "Hapus",
                                                        tint = Color.Red.copy(alpha = 0.8f),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                MemoryCategory.EPISODIC -> {
                    if (episodicMemories.isEmpty()) {
                        EmptyStateCard(
                            icon = Icons.Default.History,
                            title = "Belum ada memori episodik",
                            subtitle = "Ringkasan percakapan panjang (auto-compact) tersimpan di sini sebagai memori episodik."
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(episodicMemories, key = { it.id }) { item ->
                                MemoryItemCard(item = item, onDelete = { viewModel.deleteMemory(item.id) })
                            }
                        }
                    }
                }

                MemoryCategory.KNOWLEDGE -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Tambah fakta ke memori jangka panjang",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = newMemoryInput,
                                    onValueChange = { viewModel.newMemoryInput.value = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("memory_input"),
                                    placeholder = { Text("Contoh: Nama istri saya Sari, ulang tahunnya 12 Maret.") },
                                    minLines = 2
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { viewModel.saveMemory() },
                                    modifier = Modifier.testTag("memory_save_button")
                                ) {
                                    Text("Simpan Memori")
                                }
                            }
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "System Prompt & Persona",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = systemPrompt,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        if (knowledgeMemories.isNotEmpty()) {
                            Text(
                                text = "Fakta tersimpan (${knowledgeMemories.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            knowledgeMemories.forEach { item ->
                                MemoryItemCard(item = item, onDelete = { viewModel.deleteMemory(item.id) })
                            }
                        }
                    }
                }

                MemoryCategory.SKILLS -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        EmptyStateCard(
                            icon = Icons.Default.AutoAwesome,
                            title = "Belum ada skill agent",
                            subtitle = "Sistem skill/tool eksternal belum diimplementasikan. Saat ini agent hanya menangani percakapan teks melalui model provider."
                        )

                        Text(
                            text = "Komponen Runtime Aktif",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        // Status di bawah ini dibaca langsung dari state aplikasi yang nyata.
                        val runtimeStatus = listOf(
                            Triple(
                                "WhatsApp Gateway (whatsmeow)",
                                "Koneksi perangkat tertaut melalui Go bridge",
                                isConnected
                            ),
                            Triple(
                                "Room SQLite Persistence",
                                "Sesi, riwayat chat, dan konfigurasi tersimpan lokal",
                                true
                            ),
                            Triple(
                                "Model Provider (OpenAI compatible)",
                                if (apiKey.isBlank()) "API Key belum diisi" else "API Key tersimpan & terenkripsi",
                                apiKey.isNotBlank()
                            ),
                            Triple(
                                "Echo Fallback",
                                "Menjawab secara lokal saat provider tidak tersedia",
                                useEchoFallback
                            )
                        )

                        runtimeStatus.forEach { (name, desc, active) ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = if (active) AgentEmerald else Color.Gray,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = desc,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                MemoryCategory.LEARNING -> {
                    val candidates = learnings.filter { it.status == MemoryItemEntity.STATUS_CANDIDATE }
                    val active = learnings.filter { it.status == MemoryItemEntity.STATUS_ACTIVE }
                    if (learnings.isEmpty()) {
                        EmptyStateCard(
                            icon = Icons.Default.Info,
                            title = "Belum ada pembelajaran",
                            subtitle = "Agent mencatat pelajaran lewat tool 'reflect'; kandidat muncul di sini untuk disetujui."
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(learnings, key = { it.id }) { item ->
                                MemoryItemCard(
                                    item = item,
                                    onDelete = { viewModel.rejectLearning(item.id) },
                                    actions = {
                                        if (item.status == MemoryItemEntity.STATUS_CANDIDATE) {
                                            TextButton(onClick = { viewModel.promoteLearning(item.id) }) {
                                                Text("Aktifkan", style = MaterialTheme.typography.labelSmall)
                                            }
                                            TextButton(onClick = { viewModel.rejectLearning(item.id) }) {
                                                Text("Tolak", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                )
                            }
                            item {
                                Text(
                                    text = "${active.size} aktif • ${candidates.size} menunggu review",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation Dialogs
    if (sessionToDelete != null) {
        val s = sessionToDelete!!
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Hapus Sesi Percakapan?") },
            text = { Text("Semua pesan dalam sesi \"${s.conversationId}\" akan dihapus permanen dari Room Database.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSession(s.sessionId)
                        sessionToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Hapus")
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("Batal")
                }
            }
        )
    }

    if (sessionToClear != null) {
        val s = sessionToClear!!
        AlertDialog(
            onDismissRequest = { sessionToClear = null },
            title = { Text("Bersihkan Riwayat Pesan?") },
            text = { Text("Semua pesan dalam sesi \"${s.conversationId}\" akan dikosongkan.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearSessionHistory(s.sessionId)
                        sessionToClear = null
                    }
                ) {
                    Text("Bersihkan")
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToClear = null }) {
                    Text("Batal")
                }
            }
        )
    }
}
