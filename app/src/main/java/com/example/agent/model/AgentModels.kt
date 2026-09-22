package com.example.agent.model

import java.util.UUID

enum class AgentRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}

data class Agent(
    val id: String = "default-agent",
    val name: String = "WhatsApp Assistant",
    val systemPrompt: String = "You are a helpful, courteous, and efficient WhatsApp assistant.",
    val providerId: String = "openai-compatible",
    val modelId: String = "gpt-4o-mini",
    val enabled: Boolean = true
)

data class AgentSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val agentId: String = "default-agent",
    val channel: String = "whatsapp",
    val conversationId: String, // e.g. sender phone or JID
    val title: String? = null,
    val lastMessagePreview: String? = null,
    val messageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class AgentMessage(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val role: AgentRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class Model(
    val id: String,
    val providerId: String,
    val name: String
)

data class ToolResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

interface Tool {
    val id: String
    val name: String
    val description: String
    suspend fun execute(input: String): ToolResult
}

data class AgentInput(
    val messageId: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val senderId: String = conversationId,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val channel: String = "whatsapp",
    val metadata: Map<String, String> = emptyMap()
)

data class ModelUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
)

data class AgentResponse(
    val content: String,
    val finishReason: String? = null,
    val usage: ModelUsage? = null,
    val model: String = "",
    val provider: String = "",
    val metadata: Map<String, String> = emptyMap()
)

data class ModelRequest(
    val messages: List<AgentMessage>,
    val systemPrompt: String? = null,
    val modelId: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class ModelResponse(
    val content: String,
    val finishReason: String? = null,
    val usage: ModelUsage? = null,
    val model: String = "",
    val provider: String = "",
    val latencyMs: Long = 0L,
    val metadata: Map<String, String> = emptyMap()
)

interface AgentChannelAdapter {
    val channelName: String
    suspend fun sendResponse(input: AgentInput, response: AgentResponse): Result<Unit>
}
