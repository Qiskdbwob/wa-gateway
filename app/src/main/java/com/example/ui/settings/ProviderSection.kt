package com.example.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.agent.provider.ProviderDescriptor
import com.example.agent.provider.ProviderDirectory
import com.example.agent.provider.ProviderKeyPool
import com.example.ui.components.StatusBadge
import com.example.ui.theme.Spacing
import com.example.ui.theme.status
import com.example.ui.theme.telemetry
import com.example.wagateway.WaGatewayViewModel

/**
 * Multi-provider settings: the list of configured providers, each with its own base URL, model
 * and key pool.
 *
 * Two layers of redundancy, both visible here:
 *  - inside one provider the keys are rotated when a key is rejected or rate-limited;
 *  - across providers the app fails over to the next usable one, in the order shown here, after
 *    the active provider's keys are exhausted.
 *
 * Rows are plain settings rows with dividers rather than cards: the surrounding screen already
 * provides the container, and a card inside a card reads as two competing surfaces.
 */
@Composable
fun ProviderList(viewModel: WaGatewayViewModel, modifier: Modifier = Modifier) {
  val providers by viewModel.providers.collectAsState()
  val activeId by viewModel.activeProviderId.collectAsState()
  val probeResults by viewModel.providerProbeResults.collectAsState()

  var showAddDialog by remember { mutableStateOf(false) }
  var editing by remember { mutableStateOf<ProviderDescriptor?>(null) }
  var deleting by remember { mutableStateOf<ProviderDescriptor?>(null) }

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
    Text(
      text = "Provider model",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
      fontWeight = FontWeight.SemiBold,
    )
    Text(
      text =
        "Tambahkan beberapa provider — satu untuk tiap gateway/langganan. Provider **aktif** yang " +
          "menjawab; kalau semua kuncinya kena limit, agent otomatis pindah ke provider berikutnya " +
          "sesuai urutan di bawah.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (providers.isEmpty()) {
      Text(
        text = "Belum ada provider. Tambahkan satu untuk mulai memakai model sungguhan.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      providers.forEachIndexed { index, provider ->
        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ProviderRow(
          provider = provider,
          order = index + 1,
          isActive = provider.id == activeId,
          probeResult = probeResults[provider.id],
          onActivate = { viewModel.setActiveProvider(provider.id) },
          onProbe = { viewModel.probeProvider(provider.id) },
          onEdit = { editing = provider },
          onDelete = { deleting = provider },
          onToggleEnabled = { viewModel.setProviderEnabled(provider.id, it) },
          onMoveUp = { viewModel.moveProvider(provider.id, -1) },
          onMoveDown = { viewModel.moveProvider(provider.id, 1) },
          canMoveUp = index > 0,
          canMoveDown = index < providers.lastIndex,
        )
      }
    }

    OutlinedButton(
      onClick = { showAddDialog = true },
      shape = MaterialTheme.shapes.small,
      modifier = Modifier.testTag("settings_provider_add"),
    ) {
      Text("Tambah provider")
    }
  }

  if (showAddDialog) {
    ProviderDialog(
      initial = null,
      onDismiss = { showAddDialog = false },
      onSave = { label, baseUrl, modelId, keys ->
        viewModel.addProvider(label, baseUrl, modelId, keys)
        showAddDialog = false
      },
    )
  }

  editing?.let { provider ->
    ProviderDialog(
      initial = provider,
      onDismiss = { editing = null },
      onSave = { label, baseUrl, modelId, keys ->
        viewModel.updateProvider(provider.id, label, baseUrl, modelId, keys)
        editing = null
      },
    )
  }

  deleting?.let { provider ->
    AlertDialog(
      onDismissRequest = { deleting = null },
      title = { Text("Hapus provider?") },
      text = {
        Text(
          "\"${provider.displayLabel}\" beserta ${provider.keys.size} API key-nya akan dihapus " +
            "dari perangkat ini."
        )
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.deleteProvider(provider.id)
            deleting = null
          },
          colors =
            androidx.compose.material3.ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
            ),
        ) {
          Text("Hapus")
        }
      },
      dismissButton = { TextButton(onClick = { deleting = null }) { Text("Batal") } },
    )
  }
}

@Composable
private fun ProviderRow(
  provider: ProviderDescriptor,
  order: Int,
  isActive: Boolean,
  probeResult: String?,
  onActivate: () -> Unit,
  onProbe: () -> Unit,
  onEdit: () -> Unit,
  onDelete: () -> Unit,
  onToggleEnabled: (Boolean) -> Unit,
  onMoveUp: () -> Unit,
  onMoveDown: () -> Unit,
  canMoveUp: Boolean,
  canMoveDown: Boolean,
) {
  Column(
    modifier = Modifier.fillMaxWidth().testTag("settings_provider_${provider.id}"),
    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = provider.displayLabel,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = "urutan $order",
          style = MaterialTheme.telemetry.label,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (isActive) {
        StatusBadge(label = "Aktif", tone = MaterialTheme.status.success)
      } else if (!provider.enabled) {
        StatusBadge(label = "Nonaktif", tone = MaterialTheme.status.neutral)
      } else if (provider.keys.isEmpty()) {
        StatusBadge(label = "Tanpa kunci", tone = MaterialTheme.status.warning)
      }
      Spacer(modifier = Modifier.width(Spacing.sm))
      Switch(
        checked = provider.enabled,
        onCheckedChange = onToggleEnabled,
        colors =
          SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
          ),
      )
    }

    Text(text = provider.baseUrl, style = MaterialTheme.telemetry.value, color = MaterialTheme.colorScheme.onSurface)
    Text(
      text = "${provider.modelId} • ${provider.keys.size} kunci",
      style = MaterialTheme.telemetry.label,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (probeResult != null) {
      Text(
        text = probeResult,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (!isActive) {
        TextButton(onClick = onActivate, enabled = provider.usable) {
          Text("Pakai", style = MaterialTheme.typography.labelMedium)
        }
      }
      TextButton(onClick = onProbe, modifier = Modifier.testTag("settings_provider_probe_${provider.id}")) {
        Text("Uji", style = MaterialTheme.typography.labelMedium)
      }
      TextButton(onClick = onEdit) {
        Text("Edit", style = MaterialTheme.typography.labelMedium)
      }
      Spacer(modifier = Modifier.weight(1f))
      IconButton(onClick = onMoveUp, enabled = canMoveUp) {
        Icon(
          imageVector = Icons.Default.ArrowUpward,
          contentDescription = "Naikkan urutan",
          modifier = Modifier.size(18.dp),
        )
      }
      IconButton(onClick = onMoveDown, enabled = canMoveDown) {
        Icon(
          imageVector = Icons.Default.ArrowDownward,
          contentDescription = "Turunkan urutan",
          modifier = Modifier.size(18.dp),
        )
      }
      IconButton(onClick = onDelete) {
        Icon(
          imageVector = Icons.Default.DeleteOutline,
          contentDescription = "Hapus provider",
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

/** Known OpenAI-compatible gateways, so a new provider is a tap instead of four typed fields. */
private data class GatewayPreset(val label: String, val baseUrl: String, val modelId: String)

private val GATEWAY_PRESETS =
  listOf(
    GatewayPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    GatewayPreset("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
    GatewayPreset("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    GatewayPreset(
      "Gemini",
      "https://generativelanguage.googleapis.com/v1beta/openai",
      "gemini-2.0-flash",
    ),
    GatewayPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
  )

@Composable
private fun ProviderDialog(
  initial: ProviderDescriptor?,
  onDismiss: () -> Unit,
  onSave: (label: String, baseUrl: String, modelId: String, keys: String) -> Unit,
) {
  var label by remember { mutableStateOf(initial?.label.orEmpty()) }
  var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "https://api.openai.com/v1") }
  var modelId by remember { mutableStateOf(initial?.modelId ?: "gpt-4o-mini") }
  var keysText by remember { mutableStateOf(ProviderKeyPool.format(initial?.keys.orEmpty())) }
  var showKeys by remember { mutableStateOf(false) }

  val parsedKeys = ProviderKeyPool.parse(keysText)
  val tooManyKeys = parsedKeys.size > ProviderDirectory.MAX_KEYS_PER_PROVIDER
  val canSave = baseUrl.isNotBlank() && modelId.isNotBlank() && !tooManyKeys

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (initial == null) "Tambah provider" else "Edit provider") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
      ) {
        if (initial == null) {
          Text(
            text = "Isi cepat:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxWidth(),
          ) {
            GATEWAY_PRESETS.take(3).forEach { preset ->
              AssistChip(
                onClick = {
                  label = preset.label
                  baseUrl = preset.baseUrl
                  modelId = preset.modelId
                },
                label = { Text(preset.label, style = MaterialTheme.typography.labelMedium) },
              )
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            GATEWAY_PRESETS.drop(3).forEach { preset ->
              AssistChip(
                onClick = {
                  label = preset.label
                  baseUrl = preset.baseUrl
                  modelId = preset.modelId
                },
                label = { Text(preset.label, style = MaterialTheme.typography.labelMedium) },
              )
            }
          }
        }

        OutlinedTextField(
          value = label,
          onValueChange = { label = it },
          label = { Text("Nama provider") },
          placeholder = { Text("mis. OpenRouter pribadi") },
          singleLine = true,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth().testTag("settings_provider_label"),
        )

        OutlinedTextField(
          value = baseUrl,
          onValueChange = { baseUrl = it },
          label = { Text("Base URL") },
          placeholder = { Text("https://api.openai.com/v1") },
          singleLine = true,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth().testTag("settings_provider_base_url"),
        )

        OutlinedTextField(
          value = modelId,
          onValueChange = { modelId = it },
          label = { Text("Model ID") },
          placeholder = { Text("gpt-4o-mini") },
          singleLine = true,
          shape = MaterialTheme.shapes.small,
          modifier = Modifier.fillMaxWidth().testTag("settings_provider_model_id"),
        )

        OutlinedTextField(
          value = keysText,
          onValueChange = { keysText = it },
          label = { Text("API key (satu per baris)") },
          placeholder = { Text("sk-...\nsk-...") },
          minLines = 3,
          maxLines = 6,
          shape = MaterialTheme.shapes.small,
          modifier =
            Modifier
              .fillMaxWidth()
              .heightIn(max = 180.dp)
              .testTag("settings_provider_keys"),
          visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation(),
          trailingIcon = {
            IconButton(onClick = { showKeys = !showKeys }) {
              Icon(
                imageVector = if (showKeys) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (showKeys) "Sembunyikan kunci" else "Tampilkan kunci",
              )
            }
          },
          supportingText = {
            Text(
              text =
                when {
                  tooManyKeys ->
                    "Terlalu banyak: maksimal ${ProviderDirectory.MAX_KEYS_PER_PROVIDER} kunci per provider."
                  parsedKeys.isEmpty() ->
                    "Tanpa kunci, provider ini belum bisa dipakai."
                  parsedKeys.size == 1 -> "1 kunci tersimpan (terenkripsi)."
                  else ->
                    "${parsedKeys.size} kunci akan dirotasi bergiliran saat satu kunci kena limit."
                },
              style = MaterialTheme.typography.bodySmall,
              color =
                if (tooManyKeys) {
                  MaterialTheme.colorScheme.error
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
          },
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onSave(label, baseUrl, modelId, keysText) },
        enabled = canSave,
        modifier = Modifier.testTag("settings_provider_save"),
      ) {
        Text("Simpan")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
  )
}

