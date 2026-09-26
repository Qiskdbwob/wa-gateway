package com.example.agent.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agent_configs")
data class AgentConfigEntity(
    @PrimaryKey
    val id: String = DEFAULT_CONFIG_ID,
    val isAutoReplyEnabled: Boolean = false,
    val useEchoFallback: Boolean = true,
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    /**
     * Extra keys rotated with [apiKey] when a key hits its rate limit/quota ("keys pool").
     * Stored encrypted (SecretCipher) as a newline-separated blob, never plaintext.
     */
    val apiKeys: String = "",
    val modelId: String = "gpt-4o-mini",
    val systemPrompt: String = "You are an intelligent, polite, and helpful AI assistant responding via WhatsApp. Keep responses concise, natural, and formatted nicely for WhatsApp.",
    // --- Priority 1: contact access control ---------------------------------------
    /** MODE_ALLOW = only whitelisted numbers may talk to the agent. MODE_OFF = everyone (except blacklist) may talk. */
    val whitelistMode: Boolean = false,
    // --- Priority 2: long-term memory ---------------------------------------------
    val longTermMemoryEnabled: Boolean = true,
    /** Automatically compact (summarize) a session when its message count passes [maxContextMessages]. */
    val autoCompactEnabled: Boolean = true,
    val maxContextMessages: Int = 30,
    // --- Priority 5: media understanding subagent ---------------------------------
    /** Provider/model used by the media-understanding subagent. Empty = same as primary provider. */
    val visionBaseUrl: String = "https://generativelanguage.googleapis.com/v1beta/openai",
    val visionApiKey: String = "",
    val visionModelId: String = "gemini-2.0-flash",
    // --- General -------------------------------------------------------------------
    val agentName: String = "Personal AI Agent",
    // --- Auto reflection (periodic self-review through the scheduler) ---------------
    /** When true a scheduled task re-reads recent work and stores durable lessons. */
    val autoReflectEnabled: Boolean = false,
    val autoReflectIntervalHours: Int = 6,
    // --- Terminal (built-in shell tool) --------------------------------------------
    /** When false the `run_command`/`terminal_info` tools are not registered at all. */
    val terminalEnabled: Boolean = true,
    // --- Browser automation ---------------------------------------------------------
    /** Off by default: the user opts in to letting the agent drive a real browsing session. */
    val browserEnabled: Boolean = false,
    /** `SiteCredentialStore` blob, encrypted with SecretCipher (never plaintext). */
    val browserSites: String = "",
    val browserUserAgent: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_CONFIG_ID = "default_config"
    }
}
