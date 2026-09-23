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
    val enabled: Boolean = true,
    /**
     * Phase 6: when true, the tools registered on the Agent Loop are advertised to the
     * model. Endpoints that reject tool schemas are detected at runtime and the turn is
     * retried without tools (see AgentLoop), so this can stay on by default.
     */
    val toolsEnabled: Boolean = true,
    /** Hard cap on tool-call round trips per model call, so the loop cannot spin forever. */
    val maxToolIterations: Int = 4
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
    val timestamp: Long = System.currentTimeMillis(),
    /** Assistant messages carry the tool calls the model asked for (Phase 6). */
    val toolCalls: List<ToolCall> = emptyList(),
    /** TOOL messages answer exactly one tool call. */
    val toolCallId: String? = null,
    val toolName: String? = null
)

/**
 * Model capabilities (Phase 2).
 *
 * IMPORTANT: these must only be set to true once the capability has actually been
 * verified for that model — either by the provider's catalogue or, like [toolCalling],
 * by observing a successful call at runtime.
 */
data class ModelCapabilities(
    val text: Boolean = true,
    val vision: Boolean = false,
    val audio: Boolean = false,
    val video: Boolean = false,
    val toolCalling: Boolean = false,
    val reasoning: Boolean = false,
    val contextWindow: Int? = null
)

data class Model(
    val id: String,
    val providerId: String,
    val name: String,
    val capabilities: ModelCapabilities = ModelCapabilities()
)

/**
 * Permission class of a tool (Phase 6 declares it, Phase 9 enforces it during execution).
 *
 * Only [SAFE] tools are advertised to the model by default: a tool that needs manual
 * approval must not be offered while there is no approval layer to answer for it.
 */
enum class ToolPermission {
    SAFE,
    CONFIRM
}

/** A tool invocation requested by the model. */
data class ToolCall(
    val id: String,
    val name: String,
    /** Raw JSON string exactly as returned by the model. */
    val arguments: String = "{}"
)

/** What a tool exposes to the model (OpenAI "function" format). */
data class ToolDefinition(
    val name: String,
    val description: String,
    /** JSON Schema for the arguments, i.e. "function.parameters". */
    val parametersJson: String = """{"type":"object","properties":{}}"""
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
    /** JSON Schema describing the input, sent to the model as "function.parameters". */
    val inputSchema: String
    val permission: ToolPermission
    suspend fun execute(input: String): ToolResult
}

/** Convenience conversion used by the Agent Loop when advertising tools. */
fun Tool.toDefinition(): ToolDefinition = ToolDefinition(
    name = name,
    description = description,
    parametersJson = inputSchema
)

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
    val metadata: Map<String, String> = emptyMap(),
    /** Tools offered to the model for this request. Empty means plain text completion. */
    val tools: List<ToolDefinition> = emptyList()
)

data class ModelResponse(
    val content: String,
    val finishReason: String? = null,
    val usage: ModelUsage? = null,
    val model: String = "",
    val provider: String = "",
    val latencyMs: Long = 0L,
    val metadata: Map<String, String> = emptyMap(),
    /** Non-empty when the model asked for tools instead of answering directly. */
    val toolCalls: List<ToolCall> = emptyList()
)

interface AgentChannelAdapter {
    val channelName: String
    suspend fun sendResponse(input: AgentInput, response: AgentResponse): Result<Unit>
}
