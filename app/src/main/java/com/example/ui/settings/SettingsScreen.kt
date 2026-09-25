package com.example.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ui.components.ConnectionStatusBadge
import com.example.ui.components.SectionHeader
import com.example.ui.theme.AgentEmerald
import com.example.ui.theme.AgentWhatsAppGreen
import com.example.wagateway.WaGatewayViewModel

@Composable
fun SettingsScreen(
    viewModel: WaGatewayViewModel,
    onNavigateToGateway: () -> Unit,
    onNavigateToDebug: () -> Unit,
    modifier: Modifier = Modifier
) {
    val baseUrl by viewModel.agentBaseUrl.collectAsState()
    val apiKey by viewModel.agentApiKey.collectAsState()
    val modelId by viewModel.agentModelId.collectAsState()
    val systemPrompt by viewModel.agentSystemPrompt.collectAsState()
    val useEchoFallback by viewModel.useEchoFallback.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val feedback by viewModel.sendFeedback.collectAsState()

    // Priority 1 — access control
    val whitelistMode by viewModel.whitelistMode.collectAsState()
    val whitelistContacts by viewModel.whitelistContacts.collectAsState()
    val blacklistContacts by viewModel.blacklistContacts.collectAsState()
    val contactNumberInput by viewModel.contactNumberInput.collectAsState()
    val contactLabelInput by viewModel.contactLabelInput.collectAsState()

    // Priority 2 — long-term memory
    val longTermMemoryEnabled by viewModel.longTermMemoryEnabled.collectAsState()
    val autoCompactEnabled by viewModel.autoCompactEnabled.collectAsState()
    val maxContextMessages by viewModel.maxContextMessages.collectAsState()

    // Priority 3 — approvals
    val approvalEnabled by viewModel.approvalEnabled.collectAsState()
    val pendingApprovals by viewModel.pendingApprovals.collectAsState()

    // Priority 5 — vision
    val visionBaseUrl by viewModel.visionBaseUrl.collectAsState()
    val visionApiKey by viewModel.visionApiKey.collectAsState()
    val visionModelId by viewModel.visionModelId.collectAsState()
    val visionGeminiNative by viewModel.visionGeminiNative.collectAsState()

    var showVisionKey by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Column {
            Text(
                text = "Pengaturan",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Konfigurasi model AI, persona, saluran, dan preferensi",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Feedback Notice
        if (feedback != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = feedback!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { viewModel.clearFeedback() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Text("×", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 1. Model Provider Configuration
        SectionHeader(title = "Model & Provider", icon = Icons.Default.AutoAwesome)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "OpenAI Compatible API",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )

                // Base URL
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { viewModel.agentBaseUrl.value = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_base_url"),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )

                // API Key with secure toggle
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { viewModel.agentApiKey.value = it },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_api_key"),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showApiKey) "Sembunyikan" else "Tampilkan"
                            )
                        }
                    }
                )

                // Model ID
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { viewModel.agentModelId.value = it },
                    label = { Text("Model ID") },
                    placeholder = { Text("gpt-4o-mini") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_model_id"),
                    shape = RoundedCornerShape(10.dp),
                    singleLine = true
                )

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                // Echo fallback toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Echo Fallback Cerdas",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Gunakan engine lokal jika API Key kosong",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = useEchoFallback,
                        onCheckedChange = { viewModel.setUseEchoFallback(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AgentEmerald
                        )
                    )
                }
            }
        }

        // 2. Persona & System Prompt
        SectionHeader(title = "Agent Persona & Prompt", icon = Icons.Default.Psychology)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { viewModel.agentSystemPrompt.value = it },
                    label = { Text("System Prompt") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_system_prompt"),
                    shape = RoundedCornerShape(10.dp),
                    minLines = 3,
                    maxLines = 6
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = { viewModel.saveAgentSettings() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_save_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Simpan Konfigurasi ke Database")
                }
            }
        }

        // 3. Saluran Terhubung (Channels)
        SectionHeader(title = "Saluran Terhubung", icon = Icons.Default.Phone)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToGateway() }
                .testTag("settings_gateway_tile"),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = null,
                        tint = AgentWhatsAppGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "WhatsApp Gateway",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Autentikasi QR, Pairing Code, & Foreground Service",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    ConnectionStatusBadge(isConnected = isConnected, statusText = connectionStatus)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null)
                }
            }
        }

        // 4. Keamanan & Akses (Priority 1 + 3)
        SectionHeader(title = "Keamanan & Akses", icon = Icons.Default.Security)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_security_card"),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Mode whitelist",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Hanya nomor di whitelist yang boleh berbicara dengan agent",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = whitelistMode,
                        onCheckedChange = { viewModel.setWhitelistMode(it) },
                        modifier = Modifier.testTag("settings_whitelist_switch")
                    )
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Persetujuan tool destruktif",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Tool yang menghapus/mengubah data harus disetujui lewat chat",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = approvalEnabled,
                        onCheckedChange = { viewModel.setApprovalEnabled(it) },
                        modifier = Modifier.testTag("settings_approval_switch")
                    )
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                OutlinedTextField(
                    value = contactNumberInput,
                    onValueChange = { viewModel.contactNumberInput.value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_contact_number"),
                    label = { Text("Nomor (format internasional)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = contactLabelInput,
                    onValueChange = { viewModel.contactLabelInput.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Label (opsional)") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.addWhitelistContact() },
                        modifier = Modifier.testTag("settings_add_whitelist")
                    ) {
                        Text("Izinkan")
                    }
                    Button(
                        onClick = { viewModel.addBlacklistContact() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Blokir")
                    }
                }

                if (pendingApprovals.isNotEmpty()) {
                    Text(
                        text = "Menunggu persetujuan (${pendingApprovals.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    pendingApprovals.forEach { request ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${request.toolName} • ${request.id}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = request.arguments.take(160),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.approveRequest(request.id) },
                                    colors = ButtonDefaults.buttonColors(containerColor = AgentEmerald)
                                ) {
                                    Text("Setujui")
                                }
                                Button(
                                    onClick = { viewModel.rejectRequest(request.id) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Tolak")
                                }
                            }
                        }
                    }
                }

                if (whitelistContacts.isNotEmpty() || blacklistContacts.isNotEmpty()) {
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    whitelistContacts.forEach { rule ->
                        ContactRuleRow(
                            number = rule.contactId,
                            label = rule.label,
                            hint = "Whitelist",
                            onRemove = { viewModel.removeContactRule(rule.contactId) }
                        )
                    }
                    blacklistContacts.forEach { rule ->
                        ContactRuleRow(
                            number = rule.contactId,
                            label = rule.label,
                            hint = "Blacklist",
                            onRemove = { viewModel.removeContactRule(rule.contactId) }
                        )
                    }
                }
            }
        }

        // 5. Memori Jangka Panjang (Priority 2)
        SectionHeader(title = "Memori Jangka Panjang", icon = Icons.Default.Psychology)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Memori jangka panjang",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Ingat fakta & pengalaman relevan ke setiap balasan (RAG lokal)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = longTermMemoryEnabled,
                        onCheckedChange = { viewModel.setLongTermMemoryEnabled(it) }
                    )
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto compact",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Ringkas riwayat lama otomatis agar konteks tetap ringan",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoCompactEnabled,
                        onCheckedChange = { viewModel.setAutoCompactEnabled(it) }
                    )
                }

                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Batas konteks per percakapan",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                viewModel.setMaxContextMessages(maxContextMessages - 10)
                            }
                        ) {
                            Text("−", fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = "$maxContextMessages pesan",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(
                            onClick = {
                                viewModel.setMaxContextMessages(maxContextMessages + 10)
                            }
                        ) {
                            Text("+", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 6. Vision untuk Media (Priority 5)
        SectionHeader(title = "Vision (Analisis Media)", icon = Icons.Default.Visibility)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = visionBaseUrl,
                    onValueChange = { viewModel.visionBaseUrl.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Vision Base URL") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = visionApiKey,
                    onValueChange = { viewModel.visionApiKey.value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_vision_key"),
                    label = { Text("Vision API Key") },
                    singleLine = true,
                    visualTransformation = if (showVisionKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showVisionKey = !showVisionKey }) {
                            Icon(
                                imageVector = if (showVisionKey) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = "Tampilkan API key"
                            )
                        }
                    }
                )
                OutlinedTextField(
                    value = visionModelId,
                    onValueChange = { viewModel.visionModelId.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Vision Model ID") },
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "API Gemini native",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Aktifkan bila memakai endpoint generativelanguage.googleapis.com",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = visionGeminiNative,
                        onCheckedChange = { viewModel.visionGeminiNative.value = it }
                    )
                }
                Button(
                    onClick = { viewModel.saveVisionSettings() },
                    modifier = Modifier.testTag("settings_save_vision")
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Simpan Konfigurasi Vision")
                }
            }
        }

        // 7. Developer & Diagnostik
        SectionHeader(title = "Developer & Diagnostik", icon = Icons.Default.BugReport)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToDebug() }
                .testTag("settings_debug_tile"),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Developer & Debug Tools",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Interactive console, probe model, raw logs & diagnostics",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/** One whitelist/blacklist entry with its remove action. */
@Composable
private fun ContactRuleRow(
    number: String,
    label: String?,
    hint: String,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = number,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "$hint" + (label?.let { " • $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRemove) {
            Text("×", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        }
    }
}
