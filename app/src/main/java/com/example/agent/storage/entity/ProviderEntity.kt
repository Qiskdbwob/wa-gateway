package com.example.agent.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A model provider the user configured: its own base URL, model and key pool.
 *
 * Before this table existed the app held exactly one provider inside `agent_configs`
 * (`baseUrl` + `apiKey` + `apiKeys` + `modelId`). Those columns are still written with the
 * *active* provider's values so a downgrade keeps working, and an install that predates this
 * table is migrated by seeding one row from them (see `WhatsAppAgentBridge`).
 *
 * [keys] is the same encrypted blob format the single provider used: one key per line, wrapped
 * by `SecretCipher`, never plaintext.
 */
@Entity(
  tableName = "providers",
  indices = [Index(value = ["sortOrder"]), Index(value = ["enabled"])]
)
data class ProviderEntity(
  @PrimaryKey val id: String,
  val label: String = "",
  val baseUrl: String = "",
  val modelId: String = "",
  /** Newline-separated API keys, encrypted with SecretCipher. */
  val keys: String = "",
  val enabled: Boolean = true,
  /** Display order; also the failover priority after the active provider. */
  val sortOrder: Int = 0,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis(),
)
