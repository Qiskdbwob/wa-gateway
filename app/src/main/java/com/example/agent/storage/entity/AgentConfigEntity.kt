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
    val modelId: String = "gpt-4o-mini",
    val systemPrompt: String = "You are an intelligent, polite, and helpful AI assistant responding via WhatsApp. Keep responses concise, natural, and formatted nicely for WhatsApp.",
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_CONFIG_ID = "default_config"
    }
}
