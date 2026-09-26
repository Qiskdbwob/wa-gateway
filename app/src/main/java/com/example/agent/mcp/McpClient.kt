package com.example.agent.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal MCP client: JSON-RPC over Streamable HTTP (`initialize`, `tools/list`, `tools/call`).
 *
 * Scope is deliberate — no resources, prompts or sampling. Tools are what makes an MCP server
 * immediately useful to this agent (one config unlocks the server's whole tool set), and the
 * three methods above are all a tool-only client needs. Everything else would be more surface to
 * get wrong without a device to test on.
 *
 * Transport notes learned from the spec:
 *  * the server MAY answer as JSON or as SSE → [McpProtocol.extractJsonPayload] handles both;
 *  * a session id may come back in `Mcp-Session-Id` and must then be echoed on every request;
 *  * `initialize` must be followed by the `notifications/initialized` notification (fire-and-forget).
 */
class McpClient(
    private val endpoint: String,
    private val headers: Map<String, String> = emptyMap(),
    private val client: OkHttpClient = defaultClient()
) {

    @Volatile
    var sessionId: String? = null
        private set

    private val requestIds = AtomicInteger(1)

    suspend fun initialize(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val payload = McpProtocol.initializeRequest()
            val body = post(payload) ?: return@withContext Result.failure(IllegalStateException("Server tidak merespons initialize."))
            val json = JSONObject(body)
            val error = json.optJSONObject("error")?.optString("message")
            if (!error.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException("initialize gagal: $error"))
            }
            val result = json.optJSONObject("result")
                ?: return@withContext Result.failure(IllegalStateException("initialize tanpa hasil."))
            val serverInfo = result.optJSONObject("serverInfo")
            val name = serverInfo?.optString("name").orEmpty().ifBlank { endpoint }
            val version = serverInfo?.optString("version").orEmpty()
            val protocol = result.optString("protocolVersion").orEmpty()

            // Best effort: servers that need it will use the session; others ignore it.
            runCatching { post(McpProtocol.initializedNotification()) }

            Result.success(
                buildString {
                    append(name)
                    if (version.isNotBlank()) append(" v").append(version)
                    if (protocol.isNotBlank()) append(" (protocol ").append(protocol).append(')')
                }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listTools(): Result<List<McpToolDescriptor>> = withContext(Dispatchers.IO) {
        try {
            val body = post(McpProtocol.toolsListRequest(requestIds.incrementAndGet()))
                ?: return@withContext Result.failure(IllegalStateException("Server tidak merespons tools/list."))
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }?.let {
                return@withContext Result.failure(IllegalStateException("tools/list gagal: $it"))
            }
            val tools = json.optJSONObject("result")?.optJSONArray("tools")
                ?: return@withContext Result.success(emptyList())

            val descriptors = mutableListOf<McpToolDescriptor>()
            for (index in 0 until tools.length()) {
                val item = tools.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                if (name.isEmpty()) continue
                descriptors += McpToolDescriptor(
                    name = name,
                    description = item.optString("description").trim(),
                    inputSchema = item.optJSONObject("inputSchema")?.toString().orEmpty()
                )
            }
            Result.success(descriptors)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun callTool(toolName: String, argumentsJson: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val body = post(McpProtocol.toolsCallRequest(requestIds.incrementAndGet(), toolName, argumentsJson))
                ?: return@withContext Result.failure(IllegalStateException("Server tidak merespons tools/call."))
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }?.let {
                return@withContext Result.failure(IllegalStateException("$toolName gagal: $it"))
            }
            val result = json.optJSONObject("result")
                ?: return@withContext Result.failure(IllegalStateException("$toolName tanpa hasil."))

            Result.success(renderResult(result))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Flattens the MCP `content` array (text/resources) into something a model can read. */
    private fun renderResult(result: JSONObject): String {
        val isError = result.optBoolean("isError", false)
        val builder = StringBuilder()
        val content = result.optJSONArray("content")
        if (content != null) {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                when (item.optString("type")) {
                    "text" -> builder.append(item.optString("text")).append('\n')
                    "resource" -> builder.append("[resource] ")
                        .append(item.optJSONObject("resource")?.optString("uri").orEmpty())
                        .append('\n')
                    "image" -> builder.append("[gambar ").append(item.optString("mimeType")).append("]\n")
                    else -> builder.append('[').append(item.optString("type")).append("]\n")
                }
            }
        }
        result.optJSONObject("structuredContent")?.let { structured ->
            if (builder.isEmpty()) builder.append(structured.toString())
        }
        val body = builder.toString().trim()
        val text = if (body.isEmpty()) result.toString() else body
        return if (isError) "Server melaporkan error:\n$text" else text.take(McpProtocol.DEFAULT_LIMIT_CHARS)
    }

    /** POSTs one JSON-RPC message and returns the payload (JSON or unwrapped SSE). */
    private fun post(payload: String): String? {
        val requestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder()
            .url(endpoint)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
            .post(requestBody)
        headers.forEach { (key, value) -> builder.addHeader(key, value) }
        sessionId?.let { builder.addHeader(McpProtocol.SESSION_HEADER, it) }

        client.newCall(builder.build()).execute().use { response: Response ->
            response.header(McpProtocol.SESSION_HEADER)?.takeIf { it.isNotBlank() }?.let { sessionId = it }

            val raw = response.body?.string().orEmpty()
            val payloadText = McpProtocol.extractJsonPayload(raw, response.header("Content-Type"))
                ?: return null

            if (!response.isSuccessful && payloadText.isEmpty()) {
                throw IllegalStateException("HTTP ${response.code} dari MCP server")
            }
            return payloadText
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
