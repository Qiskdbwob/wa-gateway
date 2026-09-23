package com.example.agent.provider

import com.example.agent.model.AgentMessage
import com.example.agent.model.AgentRole
import com.example.agent.model.ModelRequest
import com.example.agent.model.ModelResponse
import com.example.agent.model.ModelUsage
import com.example.agent.model.ToolCall
import com.example.agent.model.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

interface ModelProvider {
    val id: String
    val name: String

    suspend fun generate(request: ModelRequest): Result<ModelResponse>

    suspend fun generateChat(messages: List<AgentMessage>, systemPrompt: String?): Result<String> {
        return generate(ModelRequest(messages = messages, systemPrompt = systemPrompt)).map { it.content }
    }
}

data class ProviderConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val modelId: String = "gpt-4o-mini"
)

class OpenAiCompatibleProvider(
    private val configProvider: () -> ProviderConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) : ModelProvider {

    override val id: String = "openai-compatible"
    override val name: String = "OpenAI Compatible"

    override suspend fun generate(request: ModelRequest): Result<ModelResponse> = withContext(Dispatchers.IO) {
        val config = configProvider()
        val apiKey = config.apiKey.trim()
        val baseUrl = config.baseUrl.trim().trimEnd('/')
        val model = request.modelId?.trim()?.ifEmpty { null } ?: config.modelId.trim().ifEmpty { "gpt-4o-mini" }

        if (apiKey.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("API key is not configured. Please set an API key in Agent Settings.")
            )
        }

        val startTime = System.currentTimeMillis()

        try {
            val jsonBody = JSONObject()
            jsonBody.put("model", model)

            val messagesArray = JSONArray()

            // Optional System Prompt
            if (!request.systemPrompt.isNullOrBlank()) {
                val sysObj = JSONObject()
                sysObj.put("role", "system")
                sysObj.put("content", request.systemPrompt)
                messagesArray.put(sysObj)
            }

            // Message history
            for (msg in request.messages) {
                val msgObj = JSONObject()
                msgObj.put("role", roleForApi(msg.role))

                // An assistant turn that requested tools carries no text of its own; sending an
                // empty string there makes strict endpoints reject the history, so the field is
                // omitted entirely. Every other role keeps its content, empty or not.
                val toolCallTurn = msg.role == AgentRole.ASSISTANT && msg.toolCalls.isNotEmpty()
                if (!toolCallTurn || msg.content.isNotBlank()) {
                    msgObj.put("content", msg.content)
                }

                // The follow-up "tool" messages must be able to reference those calls,
                // otherwise providers reject the history.
                if (toolCallTurn) {
                    msgObj.put("tool_calls", toolCallsToJson(msg.toolCalls))
                }
                if (msg.role == AgentRole.TOOL && msg.toolCallId != null) {
                    msgObj.put("tool_call_id", msg.toolCallId)
                }
                messagesArray.put(msgObj)
            }

            jsonBody.put("messages", messagesArray)

            // Phase 6: advertise the tools offered for this request
            if (request.tools.isNotEmpty()) {
                jsonBody.put("tools", toolsToJson(request.tools))
            }

            val endpoint = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonBody.toString().toRequestBody(mediaType)

            val httpRequest = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val httpResponse = client.newCall(httpRequest).execute()
            val latencyMs = System.currentTimeMillis() - startTime
            val responseBody = httpResponse.body?.string() ?: ""

            if (!httpResponse.isSuccessful) {
                val errorDetail = try {
                    val errJson = JSONObject(responseBody)
                    errJson.optJSONObject("error")?.optString("message") ?: responseBody
                } catch (_: Exception) {
                    responseBody.take(200)
                }
                return@withContext Result.failure(
                    IOException("HTTP ${httpResponse.code} error from $name: $errorDetail")
                )
            }

            val resJson = JSONObject(responseBody)
            val choices = resJson.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return@withContext Result.failure(IOException("Empty choices array received from model"))
            }

            val firstChoice = choices.getJSONObject(0)
            val finishReason = firstChoice.optString("finish_reason").ifEmpty { null }
            val messageObj = firstChoice.optJSONObject("message")
            val content = messageObj?.optString("content")?.trim() ?: ""
            val toolCalls = parseToolCalls(messageObj)

            // A tool-call reply legitimately has no text content, so only a reply with
            // neither text nor tool calls counts as the "empty response" failure.
            if (content.isEmpty() && toolCalls.isEmpty()) {
                return@withContext Result.failure(IOException("Received empty response content from model"))
            }

            val usageObj = resJson.optJSONObject("usage")
            val usage = if (usageObj != null) {
                ModelUsage(
                    promptTokens = usageObj.optInt("prompt_tokens"),
                    completionTokens = usageObj.optInt("completion_tokens"),
                    totalTokens = usageObj.optInt("total_tokens")
                )
            } else null

            val returnedModel = resJson.optString("model").ifEmpty { model }

            Result.success(
                ModelResponse(
                    content = content,
                    finishReason = finishReason,
                    usage = usage,
                    model = returnedModel,
                    provider = name,
                    latencyMs = latencyMs,
                    toolCalls = toolCalls
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun roleForApi(role: AgentRole): String = when (role) {
        AgentRole.USER -> "user"
        AgentRole.ASSISTANT -> "assistant"
        AgentRole.SYSTEM -> "system"
        AgentRole.TOOL -> "tool"
    }

    private fun toolsToJson(tools: List<ToolDefinition>): JSONArray {
        val array = JSONArray()
        for (tool in tools) {
            val parameters = try {
                JSONObject(tool.parametersJson)
            } catch (_: Exception) {
                JSONObject().put("type", "object").put("properties", JSONObject())
            }
            val function = JSONObject()
                .put("name", tool.name)
                .put("description", tool.description)
                .put("parameters", parameters)
            array.put(JSONObject().put("type", "function").put("function", function))
        }
        return array
    }

    private fun toolCallsToJson(toolCalls: List<ToolCall>): JSONArray {
        val array = JSONArray()
        for (call in toolCalls) {
            val function = JSONObject()
                .put("name", call.name)
                .put("arguments", call.arguments)
            array.put(
                JSONObject()
                    .put("id", call.id)
                    .put("type", "function")
                    .put("function", function)
            )
        }
        return array
    }

    private fun parseToolCalls(messageObj: JSONObject?): List<ToolCall> {
        val raw = messageObj?.optJSONArray("tool_calls") ?: return emptyList()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: continue
            val function = item.optJSONObject("function") ?: continue
            val toolName = function.optString("name").trim()
            if (toolName.isEmpty()) continue
            calls.add(
                ToolCall(
                    id = item.optString("id").ifEmpty { "call_${i}_$toolName" },
                    name = toolName,
                    arguments = function.optString("arguments").ifEmpty { "{}" }
                )
            )
        }
        return calls
    }
}

class EchoTestProvider : ModelProvider {
    override val id: String = "echo-test"
    override val name: String = "Echo / Test Provider"

    override suspend fun generate(request: ModelRequest): Result<ModelResponse> {
        val lastMsg = request.messages.lastOrNull { it.role == AgentRole.USER }?.content ?: "Empty prompt"
        val reply = "[Echo Agent]: Halo! Saya menerima pesan Anda: \"$lastMsg\". Agent Core loop bekerja normal. Silakan masukkan API Key di Pengaturan Agent untuk menghubungkan ke model AI sesungguhnya."
        return Result.success(
            ModelResponse(
                content = reply,
                finishReason = "stop",
                usage = ModelUsage(promptTokens = 15, completionTokens = 35, totalTokens = 50),
                model = "echo-model-v1",
                provider = name,
                latencyMs = 20L
            )
        )
    }
}
